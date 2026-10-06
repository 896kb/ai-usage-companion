package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EaseCurveTest {
    @Test fun `no ease is full speed throughout`() {
        for (p in longArrayOf(0, 500, 4000, 8000)) assertEquals(1f, EaseCurve.speed(p, 8000, false, false), 0f)
    }

    @Test fun `ease in starts slowest and reaches full speed after the ramp`() {
        assertEquals(EaseCurve.SLOWEST, EaseCurve.speed(0, 8000, true, false), 0.001f)
        assertEquals(1f, EaseCurve.speed(EaseCurve.RAMP_MS, 8000, true, false), 0.001f)
        assertEquals(1f, EaseCurve.speed(7999, 8000, true, false), 0.001f)
    }

    @Test fun `ease out slows into the last frame`() {
        assertEquals(1f, EaseCurve.speed(8000 - EaseCurve.RAMP_MS, 8000, false, true), 0.001f)
        assertEquals(EaseCurve.SLOWEST, EaseCurve.speed(8000, 8000, false, true), 0.001f)
    }

    @Test fun `the ramp rises without dipping`() {
        var last = 0f
        for (p in 0..1000 step 50) {
            val s = EaseCurve.speed(p.toLong(), 8000, true, false)
            assertTrue("at $p: $s < $last", s >= last - 1e-4f)
            last = s
        }
    }

    @Test fun `a short clip shares itself between the two ramps`() {
        // 800 ms: each ramp is 400 ms, so the middle is the only full-speed instant.
        assertEquals(1f, EaseCurve.speed(400, 800, true, true), 0.001f)
        for (p in 0..800 step 20) {
            val s = EaseCurve.speed(p.toLong(), 800, true, true)
            assertTrue("at $p: $s", s in EaseCurve.SLOWEST..1f)
        }
    }

    @Test fun `positions past either end are clamped`() {
        assertEquals(EaseCurve.SLOWEST, EaseCurve.speed(-50, 8000, true, false), 0.001f)
        assertEquals(EaseCurve.SLOWEST, EaseCurve.speed(9000, 8000, false, true), 0.001f)
    }
}
