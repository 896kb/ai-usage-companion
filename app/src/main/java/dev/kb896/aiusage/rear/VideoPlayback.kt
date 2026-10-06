package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * A video background's frames, as hardware bitmaps the panel draws like a picture.
 *
 * The clip decodes into an ImageReader rather than a SurfaceView: the frame has to be IN
 * the panel's drawing, where the glass panes refract it, not a layer under it. Each new
 * frame is wrapped (no copy) and [onFrame] asks for a redraw; the frames before it stay
 * open a little longer, since the render thread may still be reading them.
 *
 * The reader is the clip's own size, read from the file: the decoder writes at that size,
 * and the panel scales the frame into place when it draws it.
 *
 * STOPPING NEVER BLANKS IT. A frozen or sleeping video keeps [still], a copy of the frame
 * it stopped on, and starting over cross-fades from that copy into the new run over
 * [FADE_MS] — the panel draws [fadeFrom] over the live frame at [fadeLeft].
 *
 * NOTHING HERE MAY THROW INTO THE PANEL. A player in its error state (mediaserver gone,
 * the decoder taken back) throws on every call; that is caught, the player let go, and
 * [onLost] asks the panel to open a new one. The reader is closed on its own thread, the
 * one its frames arrive on, so a frame being taken can never meet a reader being closed.
 */
class VideoPlayback(private val onFrame: () -> Unit, private val onLost: () -> Unit) {
    @Volatile var frame: Bitmap? = null; private set

    /** The frame the video stopped on, kept through sleep: a copy, not the reader's buffer. */
    var still: Bitmap? = null; private set

    /** The frame a restart fades out of, and when its fade began (0: not yet, the run has no frame). */
    var fadeFrom: Bitmap? = null; private set
    @Volatile private var fadeStart = 0L
    @Volatile private var awaitingRun = false
    @Volatile private var pauseOnFrame = false
    /** Bumped on every close: frames from a closed reader are dropped. */
    @Volatile private var generation = 0

    private var player: MediaPlayer? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var path: String? = null
    private val held = ArrayDeque<Image>()
    private val main = Handler(Looper.getMainLooper())
    private var easeIn = false
    private var easeOut = false
    private var easing = false
    private var lastSpeed = 1f

    /** Opens [path]; true when that made a new player, which has drawn nothing yet. */
    fun open(path: String): Boolean {
        if (path == this.path && player != null) return false
        // Another clip: the old one's frame is not this one's to fade from.
        if (this.path != null && path != this.path) drop() else close()
        val (w, h) = sizeOf(path) ?: return false
        this.path = path
        val t = HandlerThread("video-backdrop").also { it.start() }
        thread = t
        val gen = generation
        val r = ImageReader.newInstance(w, h, ImageFormat.PRIVATE, 5, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        r.setOnImageAvailableListener({ took(it, gen) }, Handler(t.looper))
        reader = r
        player = try {
            MediaPlayer().apply {
                setDataSource(path)
                setSurface(r.surface)
                setVolume(0f, 0f)
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "player error $what/$extra")
                    main.post { lost() }
                    true
                }
                prepare()
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not open $path", e)
            close()
            return false
        }
        return true
    }

    private fun sizeOf(path: String): Pair<Int, Int>? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
            if (w > 0 && h > 0) w to h else null
        } catch (_: Exception) {
            null
        } finally {
            r.release()
        }
    }

    /** On the reader's thread. Never throws: a frame that cannot be had is skipped. */
    private fun took(r: ImageReader, gen: Int) {
        if (gen != generation) return
        val image = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return
        val bmp = try {
            image.hardwareBuffer?.use { Bitmap.wrapHardwareBuffer(it, ColorSpace.get(ColorSpace.Named.SRGB)) }
        } catch (_: Exception) {
            null
        }
        if (bmp == null || gen != generation) {
            if (bmp == null) Log.w(TAG, "a frame would not wrap")
            image.close()
            return
        }
        synchronized(held) {
            held.addLast(image)
            while (held.size > 3) held.removeFirst().close()
        }
        frame = bmp
        if (awaitingRun) { awaitingRun = false; fadeStart = SystemClock.uptimeMillis() }
        if (pauseOnFrame) { pauseOnFrame = false; main.post { guard { if (it.isPlaying) it.pause() } } }
        main.post(onFrame)
    }

    /** What to show: the live frame, or the still while there is none. */
    val shown: Bitmap? get() = frame ?: still

    /** How much of [fadeFrom] still shows, 1 to 0 over [FADE_MS] from the run's first frame. */
    fun fadeLeft(now: Long): Float {
        if (fadeFrom == null) return 0f
        val start = fadeStart
        if (start == 0L) return 1f
        val left = 1f - (now - start).toFloat() / FADE_MS
        if (left <= 0f) { fadeFrom = null; return 0f }
        return left
    }

    /** A copy of what is on show, safe from the reader reusing its buffer. */
    private fun snapshot(): Bitmap? =
        frame?.let { runCatching { it.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() } ?: still

    /** Runs [block] on the player; a player that has failed is let go and reopened, never thrown from. */
    private inline fun guard(block: (MediaPlayer) -> Unit) {
        val p = player ?: return
        try {
            block(p)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "player refused a call", e)
            lost()
        }
    }

    /** The player failed: keep what was on show, let go of it, and ask for a new one. */
    private fun lost() {
        if (player == null) return
        still = snapshot()
        close()
        // A clip that fails every time it opens must not be reopened forever: three losses
        // in half a minute and it stays on its still until the panel next changes state.
        val now = SystemClock.uptimeMillis()
        losses.removeAll { now - it > LOSS_WINDOW_MS }
        losses.addLast(now)
        if (losses.size <= MAX_LOSSES) onLost()
    }
    private val losses = ArrayDeque<Long>()

    /** From the start, fading out of whatever was on show. */
    private fun startOver(p: MediaPlayer) {
        fadeFrom = snapshot()
        fadeStart = 0L
        awaitingRun = fadeFrom != null
        p.seekTo(0, MediaPlayer.SEEK_CLOSEST)
    }

    /** Plays round and round; [restart] starts it over with the fade, else it carries on. */
    fun loop(restart: Boolean) = guard { p ->
        stopEasing()
        pauseOnFrame = false
        p.isLooping = true
        if (restart) startOver(p)
        if (lastSpeed != 1f || !p.isPlaying) {
            lastSpeed = 1f
            // A speed set on a paused player starts it: this is the start.
            p.playbackParams = p.playbackParams.setSpeed(1f)
        }
        if (!p.isPlaying) p.start()
    }

    /**
     * Holds the frame where it is. A player that has drawn nothing yet — just opened, after
     * a sleep with no still — runs to its first frame and stops there, since a paused
     * player shows nothing until it has played.
     */
    fun freeze() = guard { p ->
        stopEasing()
        if (p.isPlaying) {
            p.pause()
            still = snapshot()
        } else if (frame == null && still == null) {
            pauseOnFrame = true
            p.seekTo(0, MediaPlayer.SEEK_CLOSEST)
            p.start()
        }
    }

    /** One run from the start, fading in from the frame it stopped on, eased if asked. */
    fun playOnce(easeIn: Boolean, easeOut: Boolean) = guard { p ->
        stopEasing()
        pauseOnFrame = false
        p.isLooping = false
        startOver(p)
        this.easeIn = easeIn
        this.easeOut = easeOut
        lastSpeed = EaseCurve.speed(0, p.duration.toLong(), easeIn, easeOut)
        p.playbackParams = p.playbackParams.setSpeed(lastSpeed)
        if (!p.isPlaying) p.start()
        if (easeIn || easeOut) { easing = true; main.post(ease) }
    }

    /** The ramp, followed while it runs; stops itself with the clip. */
    private val ease = object : Runnable {
        override fun run() {
            if (!easing) return
            guard { p ->
                if (!p.isPlaying) { easing = false; return@guard }
                val s = EaseCurve.speed(p.currentPosition.toLong(), p.duration.toLong(), easeIn, easeOut)
                if (kotlin.math.abs(s - lastSpeed) > 0.01f) {
                    lastSpeed = s
                    p.playbackParams = p.playbackParams.setSpeed(s)
                }
                main.postDelayed(this, EASE_STEP_MS)
            }
        }
    }

    private fun stopEasing() { easing = false; main.removeCallbacks(ease) }

    /** Lets go of the decoder, keeping the frame it was on to show until it is back. */
    fun sleep() {
        if (player == null) return
        still = snapshot()
        close()
    }

    /** Lets go of everything, the last frame too: there is no video any more. */
    fun drop() {
        close()
        still = null
        fadeFrom = null
    }

    private fun close() {
        stopEasing()
        pauseOnFrame = false
        awaitingRun = false
        generation++
        runCatching { player?.release() }
        player = null
        // The reader, and the images it lent, are closed on the thread its frames arrive on.
        val r = reader
        val t = thread
        reader = null
        thread = null
        if (t != null) Handler(t.looper).post {
            synchronized(held) { held.forEach { it.close() }; held.clear() }
            r?.close()
            t.quitSafely()
        }
        frame = null
        path = null
    }

    private companion object {
        const val TAG = "AiUsageVideo"
        /** How often the ramp's speed is updated; frames still arrive at the clip's own rate. */
        const val EASE_STEP_MS = 16L
        /** The cross-fade from the frame it stopped on into a new run. */
        const val FADE_MS = 450L
        const val MAX_LOSSES = 3
        const val LOSS_WINDOW_MS = 30_000L
    }
}
