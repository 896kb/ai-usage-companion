package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EaseCurveTest {
    private val r = 1000L   // a ramp of a second of clip

    @Test fun `no ease is full speed throughout`() {
        for (p in longArrayOf(0, 500, 4000, 8000)) assertEquals(1f, EaseCurve.speed(p, 8000, 0, 0), 0f)
    }

    @Test fun `ease in starts slowest and reaches full speed after its ramp`() {
        assertEquals(EaseCurve.SLOWEST, EaseCurve.speed(0, 8000, r, 0), 0.001f)
        assertEquals(1f, EaseCurve.speed(r, 8000, r, 0), 0.001f)
        assertEquals(1f, EaseCurve.speed(7999, 8000, r, 0), 0.001f)
    }

    @Test fun `ease out slows into the last frame`() {
        assertEquals(1f, EaseCurve.speed(8000 - r, 8000, 0, r), 0.001f)
        assertEquals(EaseCurve.SLOWEST, EaseCurve.speed(8000, 8000, 0, r), 0.001f)
    }

    @Test fun `each end has a ramp of its own length`() {
        assertEquals(1f, EaseCurve.speed(300, 8000, 300, 2000), 0.001f)       // the in-ramp is over at 300
        assertTrue(EaseCurve.speed(6500, 8000, 300, 2000) < 1f)               // the out-ramp began at 6000
    }

    @Test fun `the ramp rises without dipping`() {
        var last = 0f
        for (p in 0..1000 step 50) {
            val s = EaseCurve.speed(p.toLong(), 8000, r, 0)
            assertTrue("at $p: $s < $last", s >= last - 1e-4f)
            last = s
        }
    }

    @Test fun `ramps longer than the clip share it in proportion`() {
        // 800 ms with 1 s each: each ramp is 400 ms, so the middle is the only full-speed instant.
        assertEquals(1f, EaseCurve.speed(400, 800, r, r), 0.001f)
        for (p in 0..800 step 20) {
            val s = EaseCurve.speed(p.toLong(), 800, r, r)
            assertTrue("at $p: $s", s in EaseCurve.SLOWEST..1f)
        }
    }

    @Test fun `positions past either end are clamped`() {
        assertEquals(EaseCurve.SLOWEST, EaseCurve.speed(-50, 8000, r, 0), 0.001f)
        assertEquals(EaseCurve.SLOWEST, EaseCurve.speed(9000, 8000, 0, r), 0.001f)
    }

    @Test fun `without ease the clip's time is the wall's`() {
        for (p in longArrayOf(0, 250, 4000, 8000)) assertEquals(p.toFloat(), EaseCurve.wallMs(p, 8000, 0, 0), 0.01f)
    }

    @Test fun `easing in takes longer over the ramp, and runs at full speed after it`() {
        val ramp = EaseCurve.wallMs(r, 8000, r, 0)
        assertTrue("ramp took $ramp", ramp > r * 1.5f && ramp < r / EaseCurve.SLOWEST)
        assertEquals(3000f, EaseCurve.wallMs(r + 3000, 8000, r, 0) - ramp, 1f)
    }

    @Test fun `easing out adds what easing in adds, at the other end`() {
        val inExtra = EaseCurve.wallMs(8000, 8000, r, 0) - 8000
        val outExtra = EaseCurve.wallMs(8000, 8000, 0, r) - 8000
        assertEquals(inExtra, outExtra, 2f)
        assertEquals(7000f, EaseCurve.wallMs(8000 - r, 8000, 0, r), 1f)   // untouched until its ramp
    }

    @Test fun `wall time only ever moves forward`() {
        var last = -1f
        for (p in 0..800 step 10) {
            val w = EaseCurve.wallMs(p.toLong(), 800, r, r)
            assertTrue("at $p: $w after $last", w > last)
            last = w
        }
    }

    @Test fun `seconds on screen become the stretch of clip whose slow-down lasts them`() {
        val clip = EaseCurve.clipRampFor(wallMs = 2000)
        assertEquals(2000f, EaseCurve.wallMs(clip, 60_000, clip, 0), 25f)
        assertEquals(0L, EaseCurve.clipRampFor(wallMs = 0))
    }
}
