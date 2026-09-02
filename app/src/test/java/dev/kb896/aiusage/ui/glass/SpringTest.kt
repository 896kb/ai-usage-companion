package dev.kb896.aiusage.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpringTest {
    private fun run(spring: Spring, seconds: Float): Float {
        var peak = spring.value
        var t = 0f
        while (t < seconds) { spring.step(1f / 120f); t += 1f / 120f; if (spring.value > peak) peak = spring.value }
        return peak
    }

    @Test fun `a critically damped spring settles on its target without overshoot`() {
        val s = Spring(stiffness = 1000f, dampingRatio = 1f, threshold = 0.001f, initial = 0f)
        s.target = 1f
        val peak = run(s, 1f)
        assertTrue(s.settled)
        assertEquals(1f, s.value, 0.001f)
        assertTrue("peak $peak", peak <= 1.001f)
    }

    @Test fun `an underdamped spring overshoots and then settles`() {
        val s = Spring(stiffness = 300f, dampingRatio = 0.5f, threshold = 0.001f, initial = 0f)
        s.target = 1f
        val peak = run(s, 2f)
        assertTrue("peak $peak", peak > 1.05f)
        assertTrue(s.settled)
    }

    @Test fun `step reports motion until settled`() {
        val s = Spring(1000f, 1f, 0.001f, 0f)
        s.target = 1f
        assertTrue(s.step(1f / 60f))
        run(s, 1f)
        assertFalse(s.step(1f / 60f))
    }

    @Test fun `snap moves value and target together and kills velocity`() {
        val s = Spring(1000f, 1f, 0.001f, 0f)
        s.target = 1f
        s.step(1f / 60f)
        s.snap(0.25f)
        assertEquals(0.25f, s.value, 0f)
        assertEquals(0.25f, s.target, 0f)
        assertEquals(0f, s.velocity, 0f)
        assertTrue(s.settled)
    }
}
