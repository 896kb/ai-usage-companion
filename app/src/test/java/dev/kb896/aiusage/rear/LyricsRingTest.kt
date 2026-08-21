package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsRingTest {

    /** Departure Mono, measured: every glyph advances 38.188 at size 60. */
    private val advance: (Float) -> Float = { size -> size * (38.188f / 60f) }

    private val discR = 123f

    /** What the view passes: the arc the text is bent along, not the record's own edge. */
    private val arcR = LyricsRing.currentRadius(123f)

    private fun room(radius: Float) =
        radius * Math.toRadians(LyricsRing.MAX_SWEEP.toDouble()).toFloat()

    private fun fits(f: LyricsRing.Fitted, radius: Float) =
        advance(f.size) * f.text.length <= room(radius) + 0.01f

    @Test fun `a short line is left alone`() {
        val f = LyricsRing.fit("I've been trying", arcR, advance)
        assertEquals("I've been trying", f.text)
        assertEquals(LyricsRing.SIZE, f.size, 0.001f)
    }

    /** The words are the point: shrink before cutting any of them. */
    @Test fun `a long line shrinks before it is cut`() {
        val line = "and every time I close my eyes I see the same thing over"
        val f = LyricsRing.fit(line, arcR, advance)
        assertEquals("the line was cut when it could have shrunk", line, f.text)
        assertTrue("it did not shrink", f.size < LyricsRing.SIZE)
        assertTrue("it shrank past the floor", f.size >= LyricsRing.MIN_SIZE)
        assertTrue("it still does not fit", fits(f, arcR))
    }

    @Test fun `a line too long even at the floor is cut at a word`() {
        val line = "and every time I close my eyes I see the very same thing " +
            "happening over and over again until the morning comes around"
        val f = LyricsRing.fit(line, arcR, advance)
        assertEquals("it did not fall back to the floor size", LyricsRing.MIN_SIZE, f.size, 0.001f)
        assertTrue("a cut line should say so", f.text.endsWith("…"))
        assertTrue("it still does not fit", fits(f, arcR))
        assertFalse("it was cut mid-word", f.text.dropLast(1).endsWith(" "))
        // What is left should be a phrase, not a fragment of one word.
        assertTrue("the cut threw away too much", f.text.length > line.length / 3)
    }

    /** No boundary to cut at: one enormous word still has to be dealt with. */
    @Test fun `a single unbroken word is cut where it lies`() {
        val f = LyricsRing.fit("A".repeat(400), arcR, advance)
        assertTrue(f.text.endsWith("…"))
        assertTrue("it still does not fit", fits(f, arcR))
    }

    @Test fun `an empty line is not an error`() {
        val f = LyricsRing.fit("", arcR, advance)
        assertEquals("", f.text)
    }

    @Test fun `a zero advance does not divide by zero`() {
        val f = LyricsRing.fit("words", arcR) { 0f }
        assertEquals("words", f.text)
    }

    // -- layout -----------------------------------------------------------------------

    @Test fun `the next line waits outside the one being sung`() {
        assertTrue(LyricsRing.nextRadius(discR) > LyricsRing.currentRadius(discR))
        assertTrue("the sung line overlaps the record", LyricsRing.currentRadius(discR) > discR)
    }

    /**
     * The band the record gives up has to be the band the text actually occupies, or the
     * outer line is clipped by the top of the panel.
     */
    @Test fun `the claimed stack covers both lines`() {
        val outermost = LyricsRing.nextRadius(discR) + LyricsRing.SIZE - discR
        assertTrue(
            "the stack ($outermost needed, ${LyricsRing.STACK} claimed) does not cover the text",
            LyricsRing.STACK >= outermost,
        )
    }

    /**
     * With lyrics on, the record shrinks and the outermost word still clears the panel's
     * top edge by the ordinary margin — this is the whole reason STACK is handed to
     * MediaPage.disc as though it were a notification.
     */
    @Test fun `on a real panel the outer line clears the top edge`() {
        val (cy, r) = MediaPage.disc(LyricsRing.STACK)
        val top = cy - (LyricsRing.nextRadius(r) + LyricsRing.SIZE)
        assertTrue("the outer lyric line runs off the top: $top", top >= 0f)
        assertTrue("the record shrank more than it needed to", r > 100f)
    }

    /**
     * Text is drawn over panel, never over the camera cutout — there is no display behind
     * that glass, so a word placed there is a word nobody can read.
     *
     * The far ends of the sweep DO pass under the volume strip, and that is allowed: the
     * strip is a touch region with nothing drawn in it, so the words are perfectly visible
     * and the only consequence is that a volume drag may begin on top of one. Pulling the
     * sweep in far enough to clear it costs about a quarter of the line length, which is a
     * real loss to avoid an imaginary one.
     */
    @Test fun `the arc stays on the panel and clear of the cutout`() {
        val (_, r) = MediaPage.disc(LyricsRing.STACK)
        val cx = (296f + 904f) / 2f
        // The widest the text reaches is at the ends of its sweep, not at three o'clock.
        val half = Math.toRadians((LyricsRing.MAX_SWEEP / 2f).toDouble())
        val reach = (LyricsRing.nextRadius(r) + LyricsRing.SIZE) * Math.sin(half).toFloat()
        assertTrue("the lyrics reach the camera cutout", cx - reach > 296f)
        assertTrue("the lyrics run off the right edge", cx + reach < 904f)
    }

    // -- making room ------------------------------------------------------------------

    /** The rule the view applies: room for the words is decided by the record they wrap. */
    private fun fits(notice: Float) =
        MediaPage.disc(notice + LyricsRing.STACK).second >= LyricsRing.MIN_DISC

    @Test fun `with nothing above it there is room for words`() {
        assertTrue("no room for lyrics on an empty page", fits(0f))
    }

    /**
     * A notification with a group card under it takes enough of the page that the record
     * would collapse to its floor, and a sentence bent around a token is a ring of
     * separate letters. The notification wins; the words come back when it goes.
     */
    @Test fun `a group notification leaves no room`() {
        val group = 572f / 4f + 76f
        assertFalse("lyrics stayed up under a group card", fits(group))
        // And the record really would have bottomed out, which is the reason.
        assertEquals(
            MediaPage.DISC_MIN_RADIUS,
            MediaPage.disc(group + LyricsRing.STACK).second,
            0.01f,
        )
    }

    /** The record itself still fits under that card — it is the ARCS that do not. */
    @Test fun `the record alone survives what the words cannot`() {
        val group = 572f / 4f + 76f
        assertTrue(
            "the record bottomed out with no lyrics asking for room",
            MediaPage.disc(group).second > MediaPage.DISC_MIN_RADIUS,
        )
    }

    // -- promotion --------------------------------------------------------------------

    @Test fun `a line promotes over its own duration`() {
        assertEquals(0f, LyricsRing.promotion(3, 4, 0f), 0.001f)
        assertEquals(0.5f, LyricsRing.promotion(3, 4, LyricsRing.PROMOTE_MS / 2f), 0.001f)
        assertEquals(1f, LyricsRing.promotion(3, 4, LyricsRing.PROMOTE_MS), 0.001f)
        assertEquals(1f, LyricsRing.promotion(3, 4, 99_999f), 0.001f)
    }

    /** A seek is not a song moving on, and must not be animated as four of them. */
    @Test fun `a jump of more than one line lands without animation`() {
        assertEquals(1f, LyricsRing.promotion(3, 9, 0f), 0.001f)
        assertEquals(1f, LyricsRing.promotion(9, 3, 0f), 0.001f)
        assertEquals(1f, LyricsRing.promotion(-1, 12, 0f), 0.001f)
    }

    /** The one case that IS a song moving on from its intro. */
    @Test fun `the first line still promotes`() {
        assertEquals(0f, LyricsRing.promotion(-1, 0, 0f), 0.001f)
    }
}
