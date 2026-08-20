package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
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
     * Wider than the artwork slot the mark uses (442..720). The row took over the corner
     * the close button vacated, because thirteen characters of track title was not a
     * track title.
     */
    private val left = 322f
    private val right = 720f
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
        assertEquals(row.prev - MediaLayout.TEXT_GAP, left + row.textWidth, 0.01f)
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

    @Test fun `the row fits the slot's height`() {
        val block = MediaLayout.LINE * 2f
        assertTrue("text is taller than the slot", block <= 70f)
        assertTrue("glyphs are taller than the slot", MediaLayout.GLYPH_H <= 70f)
    }
}
