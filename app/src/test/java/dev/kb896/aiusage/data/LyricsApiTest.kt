package dev.kb896.aiusage.data

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The one part of the lyrics feature that leaves the phone.
 *
 * Tested with as much attention to WHAT IS SENT as to what comes back, because the
 * request is the part with a cost attached — see the note on LyricsApi.
 */
class LyricsApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: LyricsApi

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        api = LyricsApi(OkHttpClient(), server.url("/"))
    }

    @After fun tearDown() = server.shutdown()

    private val sheet = "[00:12.00]a line\n[00:15.00]another"

    private fun json(synced: String?, instrumental: Boolean = false) =
        """{"instrumental":$instrumental,"syncedLyrics":${
            if (synced == null) "null" else "\"" + synced.replace("\n", "\\n") + "\""
        }}"""

    @Test fun `returns a synced sheet`() {
        server.enqueue(MockResponse().setBody(json(sheet)))
        assertEquals(sheet, api.fetch("Song", "Band", "Album", 225L))
    }

    /** Only what identifies the recording, and nothing that identifies the listener. */
    @Test fun `sends the recording and nothing else`() {
        server.enqueue(MockResponse().setBody(json(sheet)))
        api.fetch("Song", "Band", "Album", 225L)
        val req = server.takeRequest()
        val url = req.requestUrl!!

        assertEquals("Song", url.queryParameter("track_name"))
        assertEquals("Band", url.queryParameter("artist_name"))
        assertEquals("Album", url.queryParameter("album_name"))
        assertEquals("225", url.queryParameter("duration"))
        assertEquals(
            "the request carries something other than the recording",
            setOf("track_name", "artist_name", "album_name", "duration"),
            url.queryParameterNames,
        )
        assertNull("a cookie went with the request", req.getHeader("Cookie"))
        assertNull("an auth header went with the request", req.getHeader("Authorization"))
        // The version is READ FROM THE BUILD, never written out. Four headers once went on
        // introducing themselves as 0.1 through every release after it — a request quietly
        // misreporting who was making it. This is what stops that coming back.
        val agent = req.getHeader("User-Agent")!!
        assertTrue(
            "the agent string should name the project: $agent",
            agent.startsWith("claude-status/"),
        )
        assertTrue(
            "the agent string does not carry the build's version: $agent",
            agent.contains(dev.kb896.aiusage.BuildConfig.VERSION_NAME),
        )
        assertFalse("the version is hardcoded, not derived", agent.contains("0.1 "))
    }

    /** An untimed sheet cannot say which line is being sung, so it is not lyrics here. */
    @Test fun `plain lyrics are not accepted`() {
        server.enqueue(
            MockResponse().setBody("""{"plainLyrics":"words words","syncedLyrics":null}"""),
        )
        assertNull(api.fetch("Song", "Band", null, 0L))
    }

    @Test fun `an instrumental has nothing to show`() {
        server.enqueue(MockResponse().setBody(json(sheet, instrumental = true)))
        server.enqueue(MockResponse().setBody("[]"))
        assertNull(api.fetch("Song", "Band", null, 0L))
    }

    /** Every ordinary failure means the same thing to the panel: no lyrics. */
    @Test fun `failures are silent`() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(api.fetch("Song", "Band", null, 0L))

        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        assertNull(api.fetch("Song", "Band", null, 0L))

        server.enqueue(MockResponse().setBody("this is not json"))
        server.enqueue(MockResponse().setBody("nor is this"))
        assertNull(api.fetch("Song", "Band", null, 0L))
    }

    /** Nothing to ask about: no request is built at all. */
    @Test fun `a track with no name is not looked up`() {
        assertNull(api.fetch("", "Band", null, 100L))
        assertNull(api.fetch("Song", "", null, 100L))
        assertEquals("a request was sent for a track with no name", 0, server.requestCount)
    }

    /** The exact endpoint is strict, so a miss falls back to the loose one. */
    @Test fun `falls back to search when the exact lookup misses`() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(
            MockResponse().setBody("""[{"duration":225,"syncedLyrics":"$sheetOneLine"}]"""),
        )
        assertEquals(sheetOneLine, api.fetch("Song", "Band", "Album", 225L))
        server.takeRequest()
        assertTrue(
            "the fallback did not use the search endpoint",
            server.takeRequest().path!!.startsWith("/api/search"),
        )
    }

    /**
     * A sheet timed to a different edit is WORSE than no sheet: it starts out looking
     * right and drifts, which reads as the feature being broken rather than absent.
     */
    @Test fun `search picks the closest length and rejects a different recording`() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(
            MockResponse().setBody(
                """[{"duration":400,"syncedLyrics":"[00:01.00]live"},
                    {"duration":226,"syncedLyrics":"[00:01.00]studio"}]""",
            ),
        )
        assertEquals("[00:01.00]studio", api.fetch("Song", "Band", null, 225L))

        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(
            MockResponse().setBody("""[{"duration":400,"syncedLyrics":"[00:01.00]live"}]"""),
        )
        assertNull("took a sheet from a different recording", api.fetch("Song", "Band", null, 225L))
    }

    private companion object {
        const val sheetOneLine = "[00:01.00]only line"
    }
}
