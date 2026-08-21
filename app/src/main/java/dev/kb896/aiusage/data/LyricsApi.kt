package dev.kb896.aiusage.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException

/**
 * Synced lyrics from LRCLIB, which is the only part of this feature that leaves the phone.
 *
 * THE PRIVACY COST IS THE WHOLE DESIGN CONSTRAINT, so it is written down here rather than
 * left implied. Asking for a track's lyrics means telling a third party what is being
 * listened to, when. Three things follow from that and none of them is optional:
 *
 *  - Nothing is requested unless the lyrics toggle is on. Off is the default, and off
 *    means no request is ever built, not a request that is discarded.
 *  - Every answer is cached, INCLUDING "there are none" — see [LyricsCache]. A track
 *    already asked about is never asked about again, so a song on repeat leaks once.
 *  - Only what identifies the recording is sent: title, artist, album, length. No
 *    identifier for the phone, the user or the session goes with it, and LRCLIB wants no
 *    account and issues no key, so there is nothing here to tie two requests together
 *    beyond what any server sees.
 *
 * Ordinary failure is silence. A missing sheet, a refusal, a dead network and a
 * mangled response all mean the same thing to the panel — no lyrics for this track — and
 * none of them is worth telling the user about on a display they are not looking at.
 */
class LyricsApi(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl = "https://lrclib.net/".toHttpUrl(),
) {

    /**
     * The sheet for a track, or null when there is none to be had.
     *
     * [durationSec] is part of the QUERY, not a filter applied afterwards: LRCLIB matches
     * on it to tell a single from an album edit from a live version, which are different
     * recordings with the same title and different timings. Sending the wrong length gets
     * a sheet that drifts further out the longer it plays.
     */
    fun fetch(title: String, artist: String, album: String?, durationSec: Long): String? {
        if (title.isBlank() || artist.isBlank()) return null
        val url = baseUrl.newBuilder()
            .addPathSegments("api/get")
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .apply { if (!album.isNullOrBlank()) addQueryParameter("album_name", album) }
            .apply { if (durationSec > 0L) addQueryParameter("duration", durationSec.toString()) }
            .build()
        val direct = get(url)?.let { synced(it) }
        if (direct != null) return direct

        // LRCLIB's exact endpoint is strict — a duration a couple of seconds out, or an
        // album the player spells differently, is a miss rather than a near match. The
        // search endpoint is the loose one, so a failed exact lookup falls back to it
        // rather than giving up on a sheet that is sitting right there.
        val searchUrl = baseUrl.newBuilder()
            .addPathSegments("api/search")
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .build()
        return get(searchUrl)?.let { best(it, durationSec) }
    }

    private fun get(url: HttpUrl): String? {
        val req = Request.Builder().url(url)
            // LRCLIB asks projects to identify themselves. Honoured, and it carries no
            // more than the name and version — nothing about this phone or its owner.
            .header("User-Agent", "claude-status/0.1 (android; rear panel lyrics)")
            .get().build()
        return try {
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) null else res.body?.string()
            }
        } catch (e: IOException) {
            null
        } catch (e: IllegalStateException) {
            null
        }
    }

    /** The synced sheet out of one record, if it has one worth having.
     *
     *  kotlinx.serialization rather than org.json, and not by preference: org.json ships
     *  as an unimplemented stub in the JVM unit-test classpath, so anything built on it
     *  can only be tested on a device. This is the one class here whose failure modes are
     *  all about malformed input, which is exactly what wants testing without one. */
    private fun synced(body: String): String? {
        return try {
            val o = Json.parseToJsonElement(body) as? JsonObject ?: return null
            sheet(o)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * Plain lyrics are deliberately ignored, everywhere.
     *
     * An untimed sheet cannot say which line is being sung, and the panel has room for
     * one line. Showing the first few words of a song for its whole duration would be a
     * decoration that looks like a reading, which is the one thing this panel does not do.
     */
    private fun sheet(o: JsonObject): String? {
        if (o["instrumental"]?.jsonPrimitive?.booleanOrNull == true) return null
        val synced = o["syncedLyrics"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return if (synced.isBlank()) null else synced
    }

    /**
     * The closest match by length out of a search.
     *
     * Length is the only thing here worth ranking on. Titles and artists all matched
     * loosely enough to be returned, so what separates a single from a live cut from a
     * remix is how long it runs — and a sheet timed to a different edit is worse than no
     * sheet, because it starts out right and drifts.
     */
    private fun best(body: String, durationSec: Long): String? = try {
        val arr = Json.parseToJsonElement(body) as? JsonArray
        var bestSheet: String? = null
        var bestGap = Long.MAX_VALUE
        for (element in arr.orEmpty()) {
            val o = element as? JsonObject ?: continue
            val s = sheet(o) ?: continue
            val len = o["duration"]?.jsonPrimitive?.longOrNull ?: 0L
            val gap = if (durationSec <= 0L) 0L else Math.abs(len - durationSec)
            if (gap < bestGap) {
                bestGap = gap
                bestSheet = s
            }
        }
        // Past a couple of seconds it is a different recording, not a rounding difference.
        if (bestSheet != null && durationSec > 0L && bestGap > DURATION_SLOP_SEC) null
        else bestSheet
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private companion object {
        /** How far a search result's length may be off and still be the same recording. */
        const val DURATION_SLOP_SEC = 4L
    }
}
