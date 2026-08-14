package dev.rgarjoaba.claudestatus.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RampColorTest {

    private fun hex(c: Int) = String.format("#%08x", c)
    private fun r(c: Int) = (c ushr 16) and 0xFF
    private fun g(c: Int) = (c ushr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF

    /**
     * Interpolated points are asserted with a per-channel tolerance of 1.
     *
     * This is deliberate, not laziness. Several channels in this ramp land
     * exactly on a .5 rounding boundary in exact arithmetic — t=0.15 blue
     * (108.5), t=0.45 green (189.5) and blue (112.5), t=0.72 green (142.5).
     * Which way each rounds is decided by Float representation noise: in
     * float32, t=0.72's green actually computes to 142.4999847 and rounds
     * DOWN. Pinning those exactly would assert the behaviour of IEEE-754,
     * not the behaviour of the ramp. The stops themselves are exact by
     * construction and ARE asserted exactly, below.
     */
    private fun assertCloseTo(expected: Int, actual: Int, t: Float) {
        val msg = "at t=$t expected ~${hex(expected)} got ${hex(actual)}"
        assertTrue(msg, abs(r(expected) - r(actual)) <= 1)
        assertTrue(msg, abs(g(expected) - g(actual)) <= 1)
        assertTrue(msg, abs(b(expected) - b(actual)) <= 1)
    }

    @Test fun `hits each stop exactly`() {
        assertEquals(hex(0xFFB5BD68.toInt()), hex(RampColor.at(0.00f)))
        assertEquals(hex(0xFFF0C674.toInt()), hex(RampColor.at(0.40f)))
        assertEquals(hex(0xFFDE935F.toInt()), hex(RampColor.at(0.70f)))
        assertEquals(hex(0xFFCC6666.toInt()), hex(RampColor.at(0.90f)))
        assertEquals(hex(0xFFBD2C40.toInt()), hex(RampColor.at(1.00f)))
    }

    // Reference values computed with half-up rounding per channel. Asserted
    // within ±1 per channel — see assertCloseTo above for why.
    @Test fun `interpolates between stops`() {
        // 0.15 sits 37.5% from #b5bd68 toward #f0c674
        assertCloseTo(0xFFCBC06D.toInt(), RampColor.at(0.15f), 0.15f)
        // 0.45 sits 16.67% from #f0c674 toward #de935f
        assertCloseTo(0xFFEDBE71.toInt(), RampColor.at(0.45f), 0.45f)
        // 0.72 sits 10% from #de935f toward #cc6666
        assertCloseTo(0xFFDC8F60.toInt(), RampColor.at(0.72f), 0.72f)
        // 0.94 sits 40% from #cc6666 toward #bd2c40
        assertCloseTo(0xFFC64F57.toInt(), RampColor.at(0.94f), 0.94f)
    }

    /**
     * Guards the real risk this function has: a bug at a segment boundary,
     * which would show as a visible jump in colour as usage ticks over.
     * The steepest segment (0.90→1.00, green falling 102→44) moves ~2.9 per
     * 0.005 step, so 5 leaves headroom for rounding while still catching a
     * genuine discontinuity, which would jump by tens.
     */
    @Test fun `is continuous across segment boundaries`() {
        var t = 0f
        var prev = RampColor.at(0f)
        while (t <= 1f) {
            val c = RampColor.at(t)
            val jump = maxOf(abs(r(c) - r(prev)), abs(g(c) - g(prev)), abs(b(c) - b(prev)))
            assertTrue("discontinuity at t=$t: ${hex(prev)} -> ${hex(c)}", jump <= 5)
            prev = c
            t += 0.005f
        }
    }

    @Test fun `clamps outside the unit interval`() {
        assertEquals(hex(RampColor.at(0f)), hex(RampColor.at(-1f)))
        assertEquals(hex(RampColor.at(1f)), hex(RampColor.at(4f)))
        assertEquals(hex(RampColor.at(0f)), hex(RampColor.at(Float.NaN)))
    }

    @Test fun `alpha is always fully opaque`() {
        for (i in 0..100) {
            val a = (RampColor.at(i / 100f) ushr 24) and 0xFF
            assertEquals("alpha at t=${i / 100f}", 0xFF, a)
        }
    }
}
