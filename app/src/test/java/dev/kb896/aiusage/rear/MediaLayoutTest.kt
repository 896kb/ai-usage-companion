package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The media row's arithmetic, against the real artwork slot.
 *
 * Pure Kotlin — no Robolectric — because MediaLayout deliberately holds no Android
 * types. What it protects is a region 278 units wide holding two lines of text and
 * three buttons: the failure it is here to catch is the text column being squeezed to
 * nothing, which on the back of a phone looks like a track with no name.
 */
class MediaLayoutTest {

    /**
     * The panel's real media band: x 322..720, y 476..546.
     *
     * Wider than the artwork slot the mark uses (442..720) at both ends: it took the
     * corner the close button vacated on the left, and on the right it now runs to where
     * the camera button starts rather than stopping where the bars do. Thirteen
     * characters of track title was not a track title.
     */
    private val left = 322f
    private val right = 804f
    private val centreY = 511f

    /** With a cover, the text starts after it — a 70-unit square plus its gap. */
    private val coveredLeft = left + 70f + MediaLayout.COVER_GAP

    private val row = MediaLayout.of(left, right, centreY)

    @Test fun `the buttons stay inside the slot`() {
        assertTrue("prev at ${row.prev}", row.prev >= left)
        val nextRight = row.next + MediaLayout.GLYPH_H * PanelIcons.NEXT.aspect
        assertEquals("next ends on the slot's right edge", right, nextRight, 0.01f)
    }

    @Test fun `the buttons do not overlap each other`() {
        val prevRight = row.prev + MediaLayout.GLYPH_H * PanelIcons.PREV.aspect
        val playRight = row.play + MediaLayout.GLYPH_H * PanelIcons.PAUSE.aspect
        assertTrue("prev runs into play", prevRight <= row.play)
        assertTrue("play runs into next", playRight <= row.next)
    }

    /**
     * The one that matters. A track name needs enough room to say something, and the
     * number that decides it is the sum of three glyph widths and two gaps — every one
     * of which is a constant somebody might reasonably nudge.
     */
    @Test fun `the text column holds a readable number of characters`() {
        // Departure Mono's advance is 0.6364 of its size — the same ratio TitleLimits
        // measures off the real font. Hardcoded here because this test has no Context.
        val chars = (row.textWidth / (MediaLayout.TEXT_SIZE * 0.6364f)).toInt()
        assertTrue("only $chars characters fit", chars >= 12)
    }

    /**
     * A cover eats 88 units of the band, and the title still has to say something. This
     * is the tighter of the two cases and the one that decides whether the cover is
     * affordable at all.
     */
    @Test fun `the text column survives a cover`() {
        val covered = MediaLayout.of(coveredLeft, right, centreY)
        val chars = (covered.textWidth / (MediaLayout.TEXT_SIZE * 0.6364f)).toInt()
        assertTrue("only $chars characters fit beside a cover", chars >= 12)
    }

    /** A cover must not push the text into the buttons. */
    @Test fun `a cover leaves the buttons where they were`() {
        val covered = MediaLayout.of(coveredLeft, right, centreY)
        assertEquals(row.prev, covered.prev, 0.01f)
        assertEquals(row.play, covered.play, 0.01f)
        assertEquals(row.next, covered.next, 0.01f)
    }

    @Test fun `the text column never runs under the buttons`() {
        assertEquals(row.prev - MediaLayout.GAP, left + row.textWidth, 0.01f)
    }

    @Test fun `two lines are centred as a pair`() {
        val first = MediaLayout.firstBaseline(centreY, hasArtist = true)
        val second = first + MediaLayout.LINE
        // The pair's own centre — halfway between the top of the first line and the
        // bottom of the second — lands on the slot's centre.
        val top = first - MediaLayout.TEXT_SIZE
        assertEquals(centreY, (top + second) / 2f, 0.01f)
    }

    @Test fun `one line is centred on its own`() {
        val only = MediaLayout.firstBaseline(centreY, hasArtist = false)
        assertEquals(centreY, (only - MediaLayout.TEXT_SIZE + only) / 2f, 0.01f)
    }

    /**
     * The regression. On the device, pressing pause restarted the track — the hit test
     * held three slop-padded rectangles on 35-unit centres, they overlapped almost
     * completely, and the first in the list won every press. So every button was PREV,
     * and skipToPrevious() on a track a few seconds in restarts it.
     *
     * Stated as the rule that makes it impossible: a press on a glyph resolves to that
     * glyph. Nearest-centre has no ordering and no padding to get wrong.
     */
    @Test fun `a press on each glyph resolves to that glyph`() {
        val centres = row.centres
        val hit = { x: Float -> centres.indices.minByOrNull { kotlin.math.abs(centres[it] - x) }!! }
        for ((i, c) in centres.withIndex()) {
            assertEquals("dead centre of glyph $i", i, hit(c))
            // And anywhere on the glyph itself, not only its exact middle.
            val halfGlyph = MediaLayout.GLYPH_H * 0.5f / 2f
            assertEquals("left edge of glyph $i", i, hit(c - halfGlyph))
            assertEquals("right edge of glyph $i", i, hit(c + halfGlyph))
        }
    }

    @Test fun `the centres are far enough apart to be told apart`() {
        val centres = row.centres
        for (i in 0 until centres.size - 1) {
            // Half the spacing is how far a press may drift and still land right. At
            // 400dpi a unit is about 1/16 mm, so this is the number that decides whether
            // this is usable without looking.
            assertTrue("centres $i and ${i + 1} are ${centres[i + 1] - centres[i]} apart",
                centres[i + 1] - centres[i] >= 40f)
        }
    }

    @Test fun `the centres are in drawing order`() {
        val centres = row.centres
        assertEquals(centres.sorted(), centres)
    }

    /**
     * The scrolling line runs two copies of itself a fixed distance apart, and that gap
     * must be NARROWER than the column — otherwise there is a stretch of every lap with
     * neither copy on screen and the title appears to vanish. Seen on the device at a gap
     * of 340 against a 155-unit column: four and a half seconds of blank line.
     *
     * Checked against the TIGHTER column, the one with a cover in it, because that is the
     * one that fails first.
     */
    @Test fun `the marquee never leaves the line empty`() {
        val covered = MediaLayout.of(coveredLeft, right, centreY)
        assertTrue("gap ${MediaLayout.MARQUEE_GAP} vs column ${covered.textWidth}",
            MediaLayout.MARQUEE_GAP < covered.textWidth)
    }

    /** One gap, between the glyphs and between the text and the first of them. */
    @Test fun `the buttons are evenly spaced from each other and from the title`() {
        val prevRight = row.prev + MediaLayout.GLYPH_H * PanelIcons.PREV.aspect
        val playRight = row.play + MediaLayout.GLYPH_H * PanelIcons.PAUSE.aspect
        val textRight = left + row.textWidth
        assertEquals("text to prev", MediaLayout.GAP, row.prev - textRight, 0.01f)
        assertEquals("prev to play", MediaLayout.GAP, row.play - prevRight, 0.01f)
        assertEquals("play to next", MediaLayout.GAP, row.next - playRight, 0.01f)
    }

    @Test fun `the buttons are big enough to press`() {
        // The narrowest of the three, in units. At 400dpi a unit is about 1/16 mm, so
        // this is the number that decides whether this is usable without looking.
        val narrowest = MediaLayout.GLYPH_H * PanelIcons.PAUSE.aspect
        assertTrue("narrowest glyph is $narrowest units", narrowest >= 28f)
    }

    @Test fun `the row fits the slot's height`() {
        val block = MediaLayout.LINE * 2f
        assertTrue("text is taller than the slot", block <= 70f)
        assertTrue("glyphs are taller than the slot", MediaLayout.GLYPH_H <= 70f)
    }

    // ---- the progress bar's timeline --------------------------------------

    @Test fun `a playing track carries its position forward`() {
        // 30s in, reported at clock 1000, asked at clock 6000: five seconds later.
        val at = MediaLayout.elapsed(
            positionMs = 30_000L, positionAtMs = 1_000L, speed = 1f,
            playing = true, durationMs = 240_000L, nowMs = 6_000L,
        )
        assertEquals(35_000L, at)
    }

    @Test fun `a paused track does not`() {
        val at = MediaLayout.elapsed(
            positionMs = 30_000L, positionAtMs = 1_000L, speed = 1f,
            playing = false, durationMs = 240_000L, nowMs = 600_000L,
        )
        assertEquals("a paused track moved", 30_000L, at)
    }

    @Test fun `playback speed is respected`() {
        val at = MediaLayout.elapsed(
            positionMs = 0L, positionAtMs = 0L, speed = 1.5f,
            playing = true, durationMs = 240_000L, nowMs = 10_000L,
        )
        assertEquals(15_000L, at)
    }

    /**
     * A track that ends while nobody reports it. The player goes quiet at the end of a
     * track and the panel keeps carrying the count forward, so without a ceiling the bar
     * would run past the end of its own groove.
     */
    @Test fun `carrying forward stops at the end of the track`() {
        val at = MediaLayout.elapsed(
            positionMs = 230_000L, positionAtMs = 0L, speed = 1f,
            playing = true, durationMs = 240_000L, nowMs = 999_000L,
        )
        assertEquals(240_000L, at)
        assertEquals(1f, MediaLayout.progress(at, 240_000L)!!, 0.001f)
    }

    @Test fun `no duration means no bar`() {
        assertNull("a live stream got a progress bar", MediaLayout.progress(5_000L, 0L))
        assertNull(MediaLayout.progress(5_000L, -1L))
    }

    @Test fun `progress is a fraction of the track`() {
        assertEquals(0.5f, MediaLayout.progress(60_000L, 120_000L)!!, 0.001f)
        assertEquals(0f, MediaLayout.progress(0L, 120_000L)!!, 0.001f)
    }

    // ---- the record -------------------------------------------------------

    private val disc = MediaLayout.vinyl(904f, 572f, 296f)

    @Test fun `the record is centred on the usable half of the panel`() {
        // NOT 452, the panel's true midpoint: the left 296 units are camera glass.
        assertEquals(600f, disc.cx, 0.01f)
    }

    @Test fun `the record is as tall as the panel`() {
        assertEquals(572f, disc.radius * 2f, 0.01f)
    }

    /** Dropped far enough to run off the bottom, so it reads as an object rather than a
     *  circle placed on a screen — but not so far that the label leaves the panel. */
    @Test fun `the record hangs off the bottom but keeps its label`() {
        assertTrue("the record does not reach the bottom edge", disc.cy + disc.radius > 572f)
        val labelBottom = disc.cy + disc.radius * MediaLayout.VINYL_LABEL
        assertTrue("the label fell off the panel", labelBottom < 572f)
        assertTrue("the label rose above the panel",
            disc.cy - disc.radius * MediaLayout.VINYL_LABEL > 0f)
    }

    /**
     * The grooves are part of the record's image now, so there is no groove geometry left
     * to check — but the spindle and the label are still drawn, and still have to agree.
     */
    @Test fun `the spindle sits inside the label`() {
        assertTrue("the spindle is bigger than the label",
            MediaLayout.VINYL_SPINDLE < MediaLayout.VINYL_LABEL)
    }
}
