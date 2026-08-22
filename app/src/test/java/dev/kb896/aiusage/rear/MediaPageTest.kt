package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The media page's layout, and specifically what it does when something takes the top of
 * the panel away from it.
 *
 * The rule this exists to hold: the transport buttons never move. They are the bottom of
 * the page and the only thing on it meant to be pressed, and a notification that pushed
 * them off the panel was the bug — you cannot press what has left the screen.
 */
class MediaPageTest {

    private val panelH = 572f
    private val panelW = 904f

    /** A notification strip: a quarter of the panel, and again half as much with a
     *  cascaded group card under it. */
    private val notice = panelH / 4f
    private val noticeWithGroup = notice + 76f

    @Test fun `at rest the record sits where the page was drawn for`() {
        val (cy, r) = MediaPage.disc(0f)
        assertEquals(216f, cy, 0.01f)
        assertEquals(166f, r, 0.01f)
    }

    /** The whole point. */
    @Test fun `a notification shrinks the record and moves nothing else`() {
        val (restCy, restR) = MediaPage.disc(0f)
        val (cy, r) = MediaPage.disc(notice)
        assertTrue("the record did not give up any room", r < restR)
        assertTrue("the record grew", cy > restCy || r < restR)
        // The stack below is untouched by construction — assert the record stays clear
        // of it, which is the relationship that actually matters.
        assertTrue("the record ran into the title",
            cy + r <= MediaPage.STACK_TOP - MediaPage.DISC_GAP + 0.01f)
    }

    @Test fun `a group card shrinks it further and still clears the stack`() {
        val (small, smallR) = MediaPage.disc(noticeWithGroup)
        val (big, bigR) = MediaPage.disc(notice)
        assertTrue("a taller strip did not take more room", smallR <= bigR)
        assertTrue("the record ran into the title",
            small + smallR <= MediaPage.STACK_TOP - MediaPage.DISC_GAP + 0.01f)
    }

    @Test fun `the record clears whatever is above it`() {
        for (top in listOf(0f, notice, noticeWithGroup)) {
            val (cy, r) = MediaPage.disc(top)
            assertTrue("the record ran under a strip $top tall", cy - r >= top)
        }
    }

    /** It stops shrinking rather than inverting, however tall the thing above it gets. */
    @Test fun `the record never collapses`() {
        val (_, r) = MediaPage.disc(panelH)
        assertTrue("the record inverted", r >= MediaPage.DISC_MIN_RADIUS)
    }

    /**
     * The volume is the whole area above the transport row, not a strip inside it — so
     * the one thing that matters is that it stops before the buttons start. An area that
     * overlapped them would make every press a volume change.
     */
    @Test fun `the volume area stops clear of the buttons`() {
        val bottom = MediaPage.volumeAreaBottom()
        val buttonsTop = MediaPage.TRANSPORT_CY - MediaPage.TRANSPORT_H / 2f
        assertTrue("the volume area runs into the buttons", bottom < buttonsTop)
        assertTrue("the volume area is too small to use", bottom > 200f)
    }

    /** It has to leave the pager somewhere to work from, or there is no way back. */
    @Test fun `there is room below the buttons to page from`() {
        val buttonsBottom = MediaPage.TRANSPORT_CY + MediaPage.TRANSPORT_H / 2f
        assertTrue("nothing left below the buttons", panelH - buttonsBottom > 40f)
    }

    /**
     * The one that matters about the labels, and it is not a matter of taste: a ring is
     * drawn around a camera lens, and there is no display inside it. Anything placed
     * there renders into the framebuffer and onto a piece of glass — perfectly, and
     * invisibly.
     */
    @Test fun `ring labels clear the ring on both sides`() {
        val radius = 128f
        val stroke = 25f
        val outerEdge = radius + stroke / 2f
        val outward = MediaPage.labelRadius(radius, stroke, outward = true)
        assertTrue("an outward label starts on the ring", outward > outerEdge)

        // Inward-facing glyphs grow BACK toward the ring from their baseline, so the
        // baseline has to start a whole line-height further out than an outward one.
        val inward = MediaPage.labelRadius(radius, stroke, outward = false)
        assertTrue("an inward label grows back over the ring",
            inward - MediaPage.LABEL_SIZE > outerEdge)
    }

    /**
     * The speaker orbits outside its ring, and at five o'clock that is the corner of the
     * panel. It fits only because the glyph is small; a larger one lands half off the
     * bottom edge, which is what sent an earlier version inside the ring and onto glass.
     */
    @Test fun `the speaker stays on the panel at full volume`() {
        val ringCy = 420f
        val orbit = 128f + 25f / 2f + MediaPage.MUTE_GAP + MediaPage.MUTE_H / 2f
        val limit = MediaPage.speakerLimit(ringCy, orbit, panelH)
        val bottom = ringCy + orbit * Math.sin(Math.toRadians(limit.toDouble())).toFloat() +
            MediaPage.MUTE_H / 2f
        assertTrue("the speaker runs off the bottom at full volume: $bottom", bottom <= panelH)
    }

    /** The limit is a CAP, never an extension — it must not push the glyph past the end
     *  of the arc it is supposed to be marking. The arc fills upward now, so its lowest
     *  point is where it STARTS. */
    @Test fun `the speaker limit never exceeds the arc`() {
        val orbit = 128f + 25f / 2f + MediaPage.MUTE_GAP + MediaPage.MUTE_H / 2f
        val limit = MediaPage.speakerLimit(420f, orbit, panelH)
        assertTrue("the speaker overshoots the arc", limit <= MediaPage.VOLUME_ARC_START + 0.01f)
    }

    /** With room to spare it rides the whole arc, rather than stopping short for nothing. */
    @Test fun `a taller panel lets the speaker reach the end`() {
        val orbit = 128f + 25f / 2f + MediaPage.MUTE_GAP + MediaPage.MUTE_H / 2f
        val limit = MediaPage.speakerLimit(420f, orbit, 2_000f)
        assertEquals(MediaPage.VOLUME_ARC_START, limit, 0.01f)
    }

    /**
     * Silent at the bottom of the segment, loudest at the top — and the speaker rides the
     * head, so this is also the direction the glyph travels.
     */
    @Test fun `the volume arc fills upward`() {
        val silent = MediaPage.VOLUME_ARC_START
        val loudest = MediaPage.VOLUME_ARC_START + MediaPage.VOLUME_ARC_SWEEP
        // Android's angles run clockwise from three o'clock, so a smaller angle is higher.
        assertTrue("the arc fills downward", loudest < silent)
        assertEquals(MediaPage.VOLUME_ARC_SPAN, silent - loudest, 0.01f)
    }

    /** The drag agrees with the arc: up is louder in both. */
    @Test fun `dragging up raises the volume, from where the finger landed`() {
        assertTrue("up did not raise it", MediaPage.volumeTravel(100f, 300f, panelH) > 0f)
        assertTrue("down did not lower it", MediaPage.volumeTravel(500f, 300f, panelH) < 0f)
        assertEquals(0f, MediaPage.volumeTravel(300f, 300f, panelH), 0.0001f)
        // Relative: the same movement gives the same travel wherever it happened.
        assertEquals(
            MediaPage.volumeTravel(100f, 200f, panelH),
            MediaPage.volumeTravel(400f, 500f, panelH),
            0.0001f,
        )
    }

    @Test fun `a full sweep of the panel covers the whole range`() {
        assertEquals(
            MediaPage.VOLUME_TRAVEL,
            MediaPage.volumeTravel(0f, panelH, panelH),
            0.0001f,
        )
    }

    @Test fun `clocks read as clocks`() {
        assertEquals("0:00", MediaPage.clock(0L))
        assertEquals("3:45", MediaPage.clock(225_000L))
        assertEquals("1:03:45", MediaPage.clock(3_825_000L))
        assertEquals("0:00", MediaPage.clock(-5_000L))
    }

    @Test fun `the bar leaves room for both clocks`() {
        val chars = MediaPage.clockWidth(225_000L)
        val clockW = chars * 11f
        val left = MediaPage.barLeft(296f, clockW)
        val right = MediaPage.barRight(panelW, clockW)
        assertTrue("the bar has no room left", right > left)
        assertTrue("the bar runs under the left clock", left > 296f + MediaPage.MARGIN)
    }

    /**
     * Silence and mute are different states, and the icon has to say which.
     *
     * Zero waves means the stream is live and set to nothing; mute keeps a level it will
     * return to, and shows a slash and a ghost arc instead. Collapsing them would make
     * the one recoverable state and the one deliberate state look identical.
     */
    @Test fun `the speaker shows nothing at silence`() {
        assertEquals(0, MediaPage.volumeWaves(0f))
        assertEquals(0, MediaPage.volumeWaves(-0.1f))
    }

    /** Even thirds, where it used to be one threshold at a half. */
    @Test fun `the waves change on thirds`() {
        assertEquals(1, MediaPage.volumeWaves(0.01f))
        assertEquals(1, MediaPage.volumeWaves(0.32f))
        assertEquals(2, MediaPage.volumeWaves(1f / 3f))
        assertEquals(2, MediaPage.volumeWaves(0.65f))
        assertEquals(3, MediaPage.volumeWaves(2f / 3f))
        assertEquals(3, MediaPage.volumeWaves(1f))
    }

    /** Never more than the icon can draw, whatever it is handed. */
    @Test fun `the count stays in range`() {
        for (i in -20..120) {
            val n = MediaPage.volumeWaves(i / 100f)
            assertTrue("out of range at $i: $n", n in 0..3)
        }
    }

    // -- the two drag controls -------------------------------------------------------

    /**
     * The strip is full height, so it crosses the transport row's band — it stays out of
     * the buttons' way on the X axis alone. The record is centred on the usable half, and
     * the buttons under it are narrower than the record, so the right sixth clears both.
     */
    /**
     * The strip and the record DO overlap now, and that is deliberate — see the note on
     * disc(). What still has to hold is that the strip is a real edge region: a sixth of
     * the panel, clear of the cutout, and not so deep into the record that the record's
     * centre falls inside it.
     */
    @Test fun `the strip is the right sixth and clear of the cutout`() {
        val left = MediaPage.volumeStripLeft(panelW)
        assertEquals(panelW / 6f, panelW - left, 0.01f)
        assertTrue("the strip reaches into the camera cutout", left > 296f)

        val discCx = (296f + panelW) / 2f
        assertTrue("the strip swallows the middle of the record", left > discCx)
        val overlap = discCx + MediaPage.DISC_RADIUS - left
        assertTrue("the record barely reaches the strip: $overlap", overlap > 0f)
        assertTrue("the record eats too much of the strip: $overlap", overlap < 40f)
    }

    @Test fun `scrubbing maps the width to the track, and clamps`() {
        assertEquals(0f, MediaPage.scrubFraction(300f, 300f, 700f), 0.001f)
        assertEquals(0.5f, MediaPage.scrubFraction(500f, 300f, 700f), 0.001f)
        assertEquals(1f, MediaPage.scrubFraction(700f, 300f, 700f), 0.001f)
        assertEquals(0f, MediaPage.scrubFraction(-10f, 300f, 700f), 0.001f)
        assertEquals(1f, MediaPage.scrubFraction(9_999f, 300f, 700f), 0.001f)
    }

    /** A zero-width area answers rather than dividing by zero. */
    @Test fun `a collapsed scrub area is harmless`() {
        assertEquals(0f, MediaPage.scrubFraction(500f, 400f, 400f), 0.001f)
    }

    @Test fun `the drag area stops clear of the buttons`() {
        assertTrue(
            "the drag area runs over the transport row",
            MediaPage.dragAreaBottom() < MediaPage.TRANSPORT_CY - MediaPage.TRANSPORT_H / 2f,
        )
    }
}
