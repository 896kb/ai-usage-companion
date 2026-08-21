package dev.kb896.aiusage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lyric sheet parser and its lookup.
 *
 * Worth testing properly because every failure here is silent: a sheet parsed half a
 * second out, or a blank entry dropped, produces lyrics that are present and confidently
 * wrong, which reads as the feature working badly rather than as a bug.
 */
class LrcTest {

    @Test fun `reads a plain sheet`() {
        val lrc = Lrc.parse(
            """
            [00:12.00]The first line
            [00:17.20]The second
            """.trimIndent(),
        )
        assertEquals(2, lrc.lines.size)
        assertEquals(12_000L, lrc.lines[0].atMs)
        assertEquals("The first line", lrc.lines[0].text)
        assertEquals(17_200L, lrc.lines[1].atMs)
    }

    /** The classic way to put a sheet 450ms out: reading `.5` as centiseconds. */
    @Test fun `a fraction is read by its length`() {
        val lrc = Lrc.parse("[00:01.5]a\n[00:02.05]b\n[00:03.005]c\n[00:04]d")
        assertEquals(1_500L, lrc.lines[0].atMs)
        assertEquals(2_050L, lrc.lines[1].atMs)
        assertEquals(3_005L, lrc.lines[2].atMs)
        assertEquals(4_000L, lrc.lines[3].atMs)
    }

    @Test fun `metadata tags are not timestamps`() {
        val lrc = Lrc.parse("[ti:Song]\n[ar:Band]\n[by:someone]\n[00:05.00]words")
        assertEquals(1, lrc.lines.size)
        assertEquals("words", lrc.lines[0].text)
    }

    /** A refrain is one lyric at several moments, and arrives out of order by construction. */
    @Test fun `a line with several stamps becomes several entries, sorted`() {
        val lrc = Lrc.parse("[00:21.10][01:03.50]a refrain\n[00:40.00]a verse")
        assertEquals(3, lrc.lines.size)
        assertEquals(listOf(21_100L, 40_000L, 63_500L), lrc.lines.map { it.atMs })
        assertEquals("a refrain", lrc.lines[0].text)
        assertEquals("a verse", lrc.lines[1].text)
        assertEquals("a refrain", lrc.lines[2].text)
    }

    /**
     * The tidy-up that breaks instrumental breaks. An empty entry is where the previous
     * line STOPS; drop it and the last line before a solo sits there for the whole solo.
     */
    @Test fun `an empty entry is kept`() {
        val lrc = Lrc.parse("[00:10.00]last words\n[00:14.00]\n[00:50.00]back again")
        assertEquals(3, lrc.lines.size)
        assertEquals("", lrc.lines[1].text)
        assertEquals("", lrc.lineAt(lrc.indexAt(30_000L)))
    }

    /** A bracket in the middle of a lyric is part of the lyric. */
    @Test fun `only leading brackets are tags`() {
        val lrc = Lrc.parse("[00:10.00]shouting [twice] over it")
        assertEquals("shouting [twice] over it", lrc.lines[0].text)
    }

    @Test fun `minutes past sixty survive`() {
        val lrc = Lrc.parse("[90:30.00]deep in a mix")
        assertEquals((90L * 60L + 30L) * 1000L, lrc.lines[0].atMs)
    }

    @Test fun `junk costs its own line, not the song`() {
        val lrc = Lrc.parse("[00:01.00]fine\nnot a lyric line at all\n[00:02.00]also fine")
        assertEquals(2, lrc.lines.size)
    }

    @Test fun `finds the line being sung`() {
        val lrc = Lrc.parse("[00:10.00]a\n[00:20.00]b\n[00:30.00]c")
        assertEquals("a", lrc.lineAt(lrc.indexAt(10_000L)))
        assertEquals("a", lrc.lineAt(lrc.indexAt(19_999L)))
        assertEquals("b", lrc.lineAt(lrc.indexAt(20_000L)))
        assertEquals("c", lrc.lineAt(lrc.indexAt(9_999_999L)))
    }

    /**
     * -1 is an answer, not a failure: during an intro nothing is being sung, and saying so
     * leaves the panel free to show what is coming instead.
     */
    @Test fun `before the first line there is no line`() {
        val lrc = Lrc.parse("[00:10.00]a")
        assertEquals(-1, lrc.indexAt(0L))
        assertEquals(-1, lrc.indexAt(9_999L))
        assertNull(lrc.lineAt(-1))
    }

    @Test fun `an empty sheet answers rather than throwing`() {
        assertTrue(Lrc.parse("").isEmpty)
        assertEquals(-1, Lrc.parse("").indexAt(1_000L))
        assertTrue(Lrc.NONE.isEmpty)
    }

    /** The binary search has to agree with the obvious linear one, at every boundary. */
    @Test fun `lookup agrees with a linear scan`() {
        val lrc = Lrc.parse((0 until 50).joinToString("\n") { "[%02d:%02d.00]line %d".format(it / 60, it % 60, it) })
        for (ms in 0L until 52_000L step 137L) {
            val expected = lrc.lines.indexOfLast { it.atMs <= ms }
            assertEquals("at " + ms, expected, lrc.indexAt(ms))
        }
    }
}
