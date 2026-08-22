package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeSurgeTest {

    private fun run(untilMs: Float, depth: Float = 0.7f): Pair<ChargeSurge, MutableList<Float>> {
        val s = ChargeSurge(seed = 5)
        val starts = mutableListOf<Float>()
        var seen = 0
        var t = 0f
        while (t < untilMs) {
            s.update(t, depth)
            if (s.size > seen) s.forEach { f -> if (f.startMs == t) starts.add(t) }
            seen = s.size
            t += 8f
        }
        return s to starts
    }

    @Test fun `an empty cell has nothing to light`() {
        val s = ChargeSurge()
        var t = 0f
        while (t < 3_000f) { s.update(t, 0f); t += 8f }
        assertEquals("lit an empty glass", 0, s.size)
    }

    @Test fun `flashes appear once there is liquid`() {
        val (_, starts) = run(3_000f)
        assertTrue("nothing fired in three seconds", starts.size >= 3)
    }

    /** Anything periodic reads as a loading animation, which is the one thing to avoid. */
    @Test fun `the gaps are irregular`() {
        val (_, starts) = run(6_000f)
        val gaps = starts.zipWithNext { a, b -> b - a }
        assertTrue("too few to judge", gaps.size >= 4)
        val distinct = gaps.map { Math.round(it / 40f) }.toSet()
        assertTrue("the gaps are on a beat: $gaps", distinct.size >= 3)
    }

    @Test fun `they burn out rather than accumulating`() {
        val (s, _) = run(8_000f)
        assertTrue("flashes piled up: ${s.size}", s.size <= ChargeSurge.MAX_FLASHES)
    }

    @Test fun `every flash sits inside the liquid`() {
        val (s, _) = run(4_000f)
        s.forEach {
            assertTrue("outside the body across: ${it.u}", it.u in 0f..1f)
            assertTrue("outside the body in depth: ${it.v}", it.v in 0f..1f)
            assertTrue("sat on the surface: ${it.v}", it.v > 0.15f)
        }
    }

    /**
     * A discharge strobes — the channel re-ionises several times in a fraction of a
     * second — and that stutter is most of what separates a flash from a lamp.
     */
    @Test fun `the envelope flickers on the way down`() {
        val f = ChargeSurge.Flash(0.5f, 0.5f, 0f, 200f, 1f)
        val s = ChargeSurge()
        var rises = 0
        var last = s.brightness(f, 0f)
        var t = 4f
        while (t <= 200f) {
            val b = s.brightness(f, t)
            if (b > last + 0.01f) rises++
            last = b
            t += 4f
        }
        assertTrue("it faded smoothly instead of strobing", rises >= 2)
    }

    @Test fun `it is over when its life is`() {
        val f = ChargeSurge.Flash(0.5f, 0.5f, 0f, 200f, 1f)
        val s = ChargeSurge()
        assertEquals(0f, s.brightness(f, 200f), 0.001f)
        assertEquals(0f, s.brightness(f, 9_999f), 0.001f)
        assertTrue("it never got bright", s.brightness(f, 20f) > 0.3f)
    }
}
