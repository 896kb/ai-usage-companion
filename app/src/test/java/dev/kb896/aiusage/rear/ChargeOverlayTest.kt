package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeOverlayTest {

    private val panelW = 904f
    private val w = ChargeOverlay.width(panelW)

    @Test fun `the cell is a seventh of the panel`() {
        assertEquals(panelW / 7f, w, 0.01f)
    }

    /** Off-screen before and after, flush during. */
    @Test fun `it arrives, stays, and leaves the way it came`() {
        assertEquals("not off-screen before it starts", w, ChargeOverlay.offset(0f, w), 0.01f)
        assertEquals("did not land", 0f, ChargeOverlay.offset(ChargeOverlay.SLIDE_MS, w), 0.01f)
        assertEquals("did not hold", 0f, ChargeOverlay.offset(2_000f, w), 0.01f)
        assertEquals("did not leave", w, ChargeOverlay.offset(ChargeOverlay.TOTAL_MS, w), 0.01f)
        assertEquals("came back", w, ChargeOverlay.offset(9_999f, w), 0.01f)
    }

    @Test fun `the slide is monotonic in each direction`() {
        var last = Float.MAX_VALUE
        var t = 0f
        while (t <= ChargeOverlay.SLIDE_MS) {
            val o = ChargeOverlay.offset(t, w)
            assertTrue("the arrival went backwards at $t", o <= last + 0.01f)
            last = o
            t += 20f
        }
        last = -1f
        t = ChargeOverlay.TOTAL_MS - ChargeOverlay.SLIDE_MS
        while (t <= ChargeOverlay.TOTAL_MS) {
            val o = ChargeOverlay.offset(t, w)
            assertTrue("the exit went backwards at $t", o >= last - 0.01f)
            last = o
            t += 20f
        }
    }

    @Test fun `it is done only after the whole appearance`() {
        assertTrue(!ChargeOverlay.done(0f))
        assertTrue(!ChargeOverlay.done(ChargeOverlay.TOTAL_MS - 1f))
        assertTrue(ChargeOverlay.done(ChargeOverlay.TOTAL_MS))
    }

    /**
     * The filling IS the animation, so it starts with the arrival rather than after it —
     * otherwise a box appears and then, separately, something happens inside it.
     */
    @Test fun `the liquid rises to the level over the fill time`() {
        assertEquals(0f, ChargeOverlay.fill(0f, 0.8f), 0.001f)
        assertTrue("nothing happened during the slide",
            ChargeOverlay.fill(ChargeOverlay.SLIDE_MS, 0.8f) > 0f)
        assertEquals("did not reach the level", 0.8f,
            ChargeOverlay.fill(ChargeOverlay.FILL_MS, 0.8f), 0.001f)
        assertEquals("did not stay at the level", 0.8f,
            ChargeOverlay.fill(ChargeOverlay.TOTAL_MS, 0.8f), 0.001f)
    }

    @Test fun `the liquid never passes the level, at any moment`() {
        var t = 0f
        while (t <= ChargeOverlay.TOTAL_MS) {
            val f = ChargeOverlay.fill(t, 0.42f)
            assertTrue("overshot at $t: $f", f <= 0.42f + 0.0001f)
            assertTrue("went negative at $t: $f", f >= 0f)
            t += 25f
        }
    }

    @Test fun `an absurd level is still a level`() {
        assertEquals(1f, ChargeOverlay.fill(ChargeOverlay.FILL_MS, 5f), 0.001f)
        assertEquals(0f, ChargeOverlay.fill(ChargeOverlay.FILL_MS, -2f), 0.001f)
    }

    // -- gravity ----------------------------------------------------------------------

    /**
     * The case that made this necessary: a phone lying flat, which is how a rear screen
     * gets looked at. Honestly projected there is no gravity in the panel's plane at all.
     */
    @Test fun `a flat panel still has a down`() {
        val (gx, gy) = ChargeOverlay.gravity(-0.17f, 0.51f)
        assertTrue("no downward pull when flat: $gy", gy > 9f)
        assertTrue("invented a sideways pull: $gx", Math.abs(gx) < 0.5f)
    }

    @Test fun `upright is left alone`() {
        // The direction is kept; only the length is standardised.
        val (gx, gy) = ChargeOverlay.gravity(-0.31f, 9.77f)
        assertEquals(-0.31f, gx, 0.05f)
        assertEquals(9.81f, gy, 0.05f)
    }

    /** However the phone is held, the liquid feels about one g. Never none, never two. */
    @Test fun `the total is always about one g`() {
        var a = 0.0
        while (a < 2 * Math.PI) {
            for (scale in listOf(0f, 0.3f, 0.7f, 1f)) {
                val gx = (Math.cos(a) * ChargeOverlay.FULL_G * scale).toFloat()
                val gy = (Math.sin(a) * ChargeOverlay.FULL_G * scale).toFloat()
                val (ox, oy) = ChargeOverlay.gravity(gx, gy)
                val mag = Math.hypot(ox.toDouble(), oy.toDouble()).toFloat()
                assertTrue("felt %.2f g at angle %.2f scale %.1f".format(mag / 9.81f, a, scale),
                    mag > 8f && mag < 12f)
            }
            a += Math.PI / 12
        }
    }

    /** Tilt it and the pull leans, rather than staying pinned to the panel's own down. */
    @Test fun `a lean is preserved`() {
        val (gx, _) = ChargeOverlay.gravity(6f, 6f)
        assertTrue("the lean was flattened out: $gx", gx > 4f)
    }

    /** Upside down, the liquid belongs at the top. Nothing here should fight that. */
    @Test fun `a fully inverted phone pulls upward`() {
        val (_, gy) = ChargeOverlay.gravity(0f, -9.8f)
        assertTrue("did not pull upward when inverted: $gy", gy < -8f)
    }
}
