package dev.kb896.aiusage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The cache, tested as the privacy mechanism it primarily is.
 *
 * Its speed is incidental. What it is FOR is making sure a recording is asked about once
 * rather than once per play, and that what it keeps on disk does not itself spell out a
 * listening history.
 */
class LyricsCacheTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun cache(limit: Int = 500) = LyricsCache(tmp.root, limit)

    @Test fun `stores and returns a sheet`() {
        val c = cache()
        c.put("Song", "Band", 225L, LyricsCache.Entry.Sheet("[00:01.00]words"))
        val got = c.get("Song", "Band", 225L)
        assertTrue(got is LyricsCache.Entry.Sheet)
        assertEquals("[00:01.00]words", (got as LyricsCache.Entry.Sheet).lrc)
    }

    /** Never looked up and looked up with no result are different, and must stay so. */
    @Test fun `a miss is remembered, and is not the same as never asked`() {
        val c = cache()
        assertNull("an unasked track claimed to be known", c.get("Song", "Band", 225L))
        c.put("Song", "Band", 225L, LyricsCache.Entry.None)
        assertEquals(
            "a remembered miss was reported as never asked",
            LyricsCache.Entry.None, c.get("Song", "Band", 225L),
        )
    }

    /**
     * The bug this prevents: instrumentals generating the most traffic of anything,
     * because they are the tracks that never produce a stored answer.
     */
    @Test fun `a track with no lyrics is only ever asked about once`() {
        val c = cache()
        var lookups = 0
        repeat(5) {
            if (c.get("Instrumental", "Band", 180L) == null) {
                lookups++
                c.put("Instrumental", "Band", 180L, LyricsCache.Entry.None)
            }
        }
        assertEquals("asked more than once about a track with no lyrics", 1, lookups)
    }

    /** Two recordings of one song are told apart by how long they run. */
    @Test fun `length is part of the identity`() {
        val c = cache()
        c.put("Song", "Band", 225L, LyricsCache.Entry.Sheet("studio"))
        c.put("Song", "Band", 400L, LyricsCache.Entry.Sheet("live"))
        assertEquals("studio", (c.get("Song", "Band", 225L) as LyricsCache.Entry.Sheet).lrc)
        assertEquals("live", (c.get("Song", "Band", 400L) as LyricsCache.Entry.Sheet).lrc)
    }

    @Test fun `case and surrounding space do not make a new entry`() {
        val c = cache()
        c.put("Song", "Band", 225L, LyricsCache.Entry.Sheet("words"))
        assertNotNull(c.get("  song  ", "BAND", 225L))
    }

    /**
     * A directory listing must not read as a listening history. This is the same data the
     * request was careful about — leaving it in the clear on disk would give it all back.
     */
    @Test fun `file names do not spell out what was played`() {
        val c = cache()
        c.put("A Very Distinctive Title", "An Artist", 225L, LyricsCache.Entry.Sheet("x"))
        val names = tmp.root.list()!!.joinToString(" ")
        assertFalse("the title is on disk in the clear", names.contains("Distinctive"))
        assertFalse("the artist is on disk in the clear", names.contains("Artist"))
        assertTrue("the name is not a hash", names.trim().matches(Regex("[0-9a-f]{64}")))
    }

    /** Off means gone: switching the feature off should not leave the data behind. */
    @Test fun `clearing removes everything`() {
        val c = cache()
        c.put("One", "Band", 1L, LyricsCache.Entry.Sheet("a"))
        c.put("Two", "Band", 2L, LyricsCache.Entry.Sheet("b"))
        c.clear()
        assertNull(c.get("One", "Band", 1L))
        assertNull(c.get("Two", "Band", 2L))
        assertEquals(0, tmp.root.list()!!.size)
    }

    @Test fun `the store is bounded`() {
        val c = cache(limit = 3)
        repeat(10) { c.put("Song $it", "Band", it.toLong(), LyricsCache.Entry.Sheet("x")) }
        assertTrue("the cache grew past its limit", tmp.root.list()!!.size <= 3)
    }

    /** A favourite fetched long ago should outlive something skipped through once. */
    @Test fun `eviction drops the least recently played`() {
        val c = cache(limit = 2)
        c.put("Favourite", "Band", 1L, LyricsCache.Entry.Sheet("keep"))
        c.put("Filler", "Band", 2L, LyricsCache.Entry.Sheet("x"))
        // Age everything, then play the favourite — which touches it.
        tmp.root.listFiles()!!.forEach { it.setLastModified(1_000L) }
        assertNotNull(c.get("Favourite", "Band", 1L))

        c.put("New", "Band", 3L, LyricsCache.Entry.Sheet("y"))
        assertNotNull("the played favourite was evicted", c.get("Favourite", "Band", 1L))
        assertNull("the untouched filler survived", c.get("Filler", "Band", 2L))
    }

    @Test fun `a missing directory is not an error`() {
        val c = LyricsCache(java.io.File(tmp.root, "not-created-yet"))
        assertNull(c.get("Song", "Band", 1L))
        c.put("Song", "Band", 1L, LyricsCache.Entry.Sheet("words"))
        assertNotNull(c.get("Song", "Band", 1L))
    }
}
