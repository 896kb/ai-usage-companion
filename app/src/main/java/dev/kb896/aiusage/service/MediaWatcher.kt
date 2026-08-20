package dev.kb896.aiusage.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.rear.RearActivity

/**
 * Watches whatever is playing and tells the panel about it.
 *
 * Costs no new permission. MediaSessionManager will hand an app the active sessions only
 * if it holds notification-listener access — which this app already asks for, to mirror
 * notifications — and the same NotificationRelay component is what authorises it. Without
 * that grant this quietly does nothing, which is the correct behaviour rather than a
 * degraded one: the media row is a bonus on top of a permission granted for something
 * else.
 *
 * The controller is held in the MAIN process because that is where the listener lives.
 * The panel runs in `:rear` and only ever receives text — it has no session, no
 * controller, and no way to send a transport command itself, which is why taps come back
 * the other way as a broadcast.
 */
class MediaWatcher(private val context: Context) {

    private var manager: MediaSessionManager? = null
    private var controller: MediaController? = null

    private val listener = MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
        adopt(sessions?.firstOrNull())
    }

    /**
     * Metadata and playback state arrive separately and either can change alone — a
     * track advancing sends metadata, a pause sends state — so both push the same
     * update rather than each maintaining half of one.
     */
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() = adopt(null)
    }

    fun start() {
        val mgr = context.getSystemService(MediaSessionManager::class.java) ?: return
        val relay = ComponentName(context, NotificationRelay::class.java)
        // Throws SecurityException when notification access has not been granted, which
        // is an ordinary state rather than a fault — the feature simply is not available
        // yet, and saying so once is more use than a crash.
        val sessions = try {
            mgr.addOnActiveSessionsChangedListener(listener, relay)
            mgr.getActiveSessions(relay)
        } catch (e: SecurityException) {
            Log.i(TAG, "no notification access, so no media controls: ${e.message}")
            return
        }
        manager = mgr
        adopt(sessions.firstOrNull())
    }

    fun stop() {
        io.shutdown()
        manager?.removeOnActiveSessionsChangedListener(listener)
        controller?.unregisterCallback(callback)
        controller = null
        manager = null
    }

    /** Send a transport command to whatever is playing, or answer a sync request. */
    fun command(action: String) {
        // Answered even with no controller, and BEFORE the transport is resolved: a
        // panel asking what is playing when nothing is deserves the answer "nothing",
        // which is what tells it to keep the artwork.
        if (action == RearActivity.MEDIA_SYNC) {
            // Re-read rather than trusting what we hold. The listener can be rebound by
            // the system at any time, and a sync is exactly when it is worth paying for
            // a fresh look.
            runCatching {
                val mgr = manager ?: return@runCatching
                adopt(mgr.getActiveSessions(
                    ComponentName(context, NotificationRelay::class.java)).firstOrNull())
            }
            publish()
            return
        }
        val transport = controller?.transportControls ?: return
        when (action) {
            RearActivity.MEDIA_PLAY_PAUSE ->
                if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    transport.pause()
                } else {
                    transport.play()
                }
            RearActivity.MEDIA_NEXT -> transport.skipToNext()
            RearActivity.MEDIA_PREVIOUS -> transport.skipToPrevious()
        }
    }

    /**
     * The FIRST session, which the system orders by which is most recently active.
     *
     * Not a merge of all of them. Several apps can hold a session at once — a browser
     * tab, a podcast paused an hour ago — and a row that showed whichever answered first
     * would swap around under the user's hand. Whatever the system considers foremost is
     * the one the buttons should drive.
     */
    private fun adopt(next: MediaController?) {
        if (next?.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(callback)
        controller = next
        next?.registerCallback(callback)
        publish()
    }

    private fun publish() {
        if (!ConfigStore(context).load().showMedia) return
        val c = controller
        val metadata = c?.metadata
        val title = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM)
        val playback = c?.playbackState
        val state = playback?.state
        // A session with no title is a session with nothing to say — a browser tab that
        // has loaded a video element, a game that opened an audio focus. Showing an
        // empty row for those would replace the artwork with a blank.
        val active = c != null && !title.isNullOrBlank()

        // The cover, which is the one thing here that is not text.
        //
        // Read on this thread — a Bundle lookup — but WRITTEN on another: an album cover
        // arrives at whatever size the app decoded it, often 1000px square, and scaling
        // and re-encoding one is tens of milliseconds. Once per track that would not
        // matter to this service, which has no UI, except that MainActivity shares the
        // process and its panel preview is on this thread too.
        val art = if (active) cover(metadata) else null
        // KEYED ON THE ARTWORK, not on the track.
        //
        // Keying on title and artist put the cover exactly one track behind, every time.
        // Players do not deliver a track in one piece: the title and artist arrive first
        // and the artwork follows a moment later, as two separate metadata callbacks. So
        // the first callback for a new track carries the NEW title and the OLD picture —
        // which got written under the new track's key — and the second callback, the one
        // actually carrying the new picture, was then dismissed as "same track, nothing
        // to do". The panel ended up holding the previous sleeve until the track after
        // that.
        //
        // Identifying the artwork itself has no such staging problem: a different picture
        // is a different key whenever it turns up.
        val key = if (active) title + "\u0000" + artist + "\u0000" + artId(metadata, art)
            else null

        val send = { path: String? ->
            context.sendBroadcast(
                Intent(RearActivity.ACTION_MEDIA)
                    .setPackage(context.packageName)
                    .putExtra(RearActivity.EXTRA_MEDIA_ACTIVE, active)
                    .putExtra(RearActivity.EXTRA_MEDIA_TITLE, title.orEmpty())
                    .putExtra(RearActivity.EXTRA_MEDIA_ARTIST, artist.orEmpty())
                    .putExtra(RearActivity.EXTRA_MEDIA_ART, path.orEmpty())
                    .putExtra(
                        RearActivity.EXTRA_MEDIA_DURATION,
                        metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)
                            ?: 0L,
                    )
                    .putExtra(RearActivity.EXTRA_MEDIA_POSITION, playback?.position ?: 0L)
                    // The clock that reading was taken against, so the panel can carry it
                    // forward. elapsedRealtime, which both processes share.
                    .putExtra(
                        RearActivity.EXTRA_MEDIA_POSITION_AT,
                        playback?.lastPositionUpdateTime ?: 0L,
                    )
                    .putExtra(RearActivity.EXTRA_MEDIA_SPEED, playback?.playbackSpeed ?: 1f)
                    .putExtra(
                        RearActivity.EXTRA_MEDIA_PLAYING,
                        state == PlaybackState.STATE_PLAYING,
                    ),
            )
        }

        // Same track: answer immediately with what is already on disk. A pause must not
        // wait on file I/O to move the glyph, and re-encoding an identical cover on every
        // state change would be the panel's one piece of real work done for nothing
        // several times a minute.
        if (key != null && key == artKey) {
            send(if (artFile().exists()) artFile().path else null)
            return
        }

        // A DIFFERENT track with no cover shows NO cover. This is the bug that made the
        // label stop changing: the old branch fell back to whatever was already on disk
        // whenever the new track had no art, so one track without a cover pinned the
        // previous track's sleeve on the record for the rest of the session. Keeping a
        // picture that belongs to something else is worse than showing none.
        artKey = key
        if (key == null || art == null) {
            send(null)
            return
        }
        io.execute {
            val path = runCatching { writeArt(art) }
                .onFailure { Log.w(TAG, "cover write failed: " + it) }
                .getOrNull()
            send(path)
        }
    }

    /**
     * The cover, from whichever of the six places a player might have put it.
     *
     * Bitmaps first, then URIs. Plenty of players — Tidal among them — carry no bitmap at
     * all and hand out a content:// URI instead, which is cheaper for them and meant the
     * art here was simply absent. Reading only the bitmap keys is the difference between
     * a cover that changes with the track and one that never appears.
     *
     * Network URIs are not fetched. A player that points at https:// is asking this app
     * to make a request on its behalf, on a metadata callback, for a picture 140 pixels
     * wide — the blank label is the better answer.
     */
    private fun cover(metadata: android.media.MediaMetadata?): android.graphics.Bitmap? {
        val m = metadata ?: return null
        val bitmap = m.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: m.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART)
            ?: m.getBitmap(android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (bitmap != null) return bitmap

        for (key in ART_URI_KEYS) {
            val uri = m.getString(key)?.takeIf { it.isNotBlank() } ?: continue
            val parsed = runCatching { android.net.Uri.parse(uri) }.getOrNull() ?: continue
            if (parsed.scheme !in LOCAL_SCHEMES) continue
            val loaded = runCatching {
                context.contentResolver.openInputStream(parsed).use {
                    android.graphics.BitmapFactory.decodeStream(it)
                }
            }.getOrNull()
            if (loaded != null) return loaded
        }
        return null
    }

    /**
     * The cover, scaled down and written where `:rear` can read it.
     *
     * A FILE, not a broadcast extra. A bitmap crossing a process boundary is copied
     * through the binder, which has a hard limit around a megabyte and is shared with
     * every other transaction in flight — the notification path was careful to send a
     * package name rather than an icon for exactly this reason. `:rear` is the same app
     * and reads the same filesDir, so a path costs nothing and the panel's own custom
     * artwork already works this way.
     *
     * Written to a temporary file and renamed, because the panel may be decoding the old
     * one at the moment this replaces it. A rename is atomic; writing in place would let
     * it read half a PNG.
     */
    private fun writeArt(art: android.graphics.Bitmap): String {
        val scaled = android.graphics.Bitmap.createScaledBitmap(art, ART_PX, ART_PX, true)
        val tmp = java.io.File(context.filesDir, "$ART_NAME.tmp")
        tmp.outputStream().use {
            scaled.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        if (scaled !== art) scaled.recycle()
        tmp.renameTo(artFile())
        return artFile().path
    }

    /**
     * What identifies this cover, cheaply.
     *
     * The URI when the player names one, because that is stable and free to compare. A
     * bitmap's generation id otherwise — distinct per bitmap instance, which is what is
     * being asked here — and "none" when there is no cover at all, so a track without one
     * still differs from the track before it.
     */
    private fun artId(metadata: android.media.MediaMetadata?, art: android.graphics.Bitmap?): String {
        val uri = ART_URI_KEYS.firstNotNullOfOrNull { key ->
            metadata?.getString(key)?.takeIf { it.isNotBlank() }
        }
        if (uri != null) return uri
        return art?.generationId?.toString() ?: "none"
    }

    private fun artFile() = java.io.File(context.filesDir, ART_NAME)

    /** The track whose cover is on disk, so an unchanged one is not re-encoded. */
    private var artKey: String? = null

    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()

    private companion object {
        const val TAG = "AiUsageMedia"

        const val ART_NAME = "media-art.png"

        /**
         * How big the cover is stored, against a 70-unit square on the panel.
         *
         * Twice the drawn size. The panel is 904x572 and the view is drawn 1:1 into it,
         * so 140 is genuine oversampling rather than the usual density guess — it gives
         * the scaler something to work with when the burn-in drift puts the square on a
         * half-pixel, and it keeps the file to a few kilobytes.
         */
        const val ART_PX = 140

        /** Where a player may name its cover instead of handing one over. */
        val ART_URI_KEYS = listOf(
            android.media.MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            android.media.MediaMetadata.METADATA_KEY_ART_URI,
            android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        )

        /** Schemes this will open. Deliberately not http — see cover(). */
        val LOCAL_SCHEMES = setOf("content", "file", "android.resource")
    }
}
