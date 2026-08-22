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

    // -- the surface ------------------------------------------------------------------

    @Test fun `flat when the panel is level`() {
        assertEquals(0f, ChargeOverlay.tilt(0f, 9.81f), 0.01f)
    }

    @Test fun `it leans the way the panel leans`() {
        assertTrue("did not lean right", ChargeOverlay.tilt(3f, 9f) > 0f)
        assertTrue("did not lean left", ChargeOverlay.tilt(-3f, 9f) < 0f)
    }

    /**
     * A faithful surface stays level however far the glass turns, which in a narrow cell
     * is a diagonal line corner to corner — a broken drawing rather than a liquid.
     */
    @Test fun `the lean is clamped hard`() {
        assertEquals(ChargeOverlay.MAX_TILT, ChargeOverlay.tilt(50f, 1f), 0.01f)
        assertEquals(-ChargeOverlay.MAX_TILT, ChargeOverlay.tilt(-50f, 1f), 0.01f)
        var gx = -60f
        while (gx <= 60f) {
            val a = ChargeOverlay.tilt(gx, 4f)
            assertTrue("out of clamp at $gx: $a", Math.abs(a) <= ChargeOverlay.MAX_TILT + 0.01f)
            gx += 3f
        }
    }

    /** Upside down is still a lean, not a flip: the sign follows gx alone. */
    @Test fun `turning the phone over does not invert the lean`() {
        assertTrue(ChargeOverlay.tilt(3f, -9f) > 0f)
        assertTrue(ChargeOverlay.tilt(-3f, -9f) < 0f)
    }

    @Test fun `no reading is flat rather than an error`() {
        assertEquals(0f, ChargeOverlay.tilt(0f, 0f), 0.001f)
    }
}
