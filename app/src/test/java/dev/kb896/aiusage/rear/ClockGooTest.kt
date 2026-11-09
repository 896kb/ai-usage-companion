package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockGooTest {

    @Test fun `a face all there is sharp, one going blurs away`() {
        assertEquals(0f, ClockGoo.blurOf(1f), 0.001f)
        assertEquals(ClockGoo.BLUR_K, ClockGoo.blurOf(0.5f), 0.001f)
        assertEquals(ClockGoo.BLUR_CAP, ClockGoo.blurOf(0f), 0.001f)
        assertTrue(ClockGoo.blurOf(0.2f) > ClockGoo.blurOf(0.5f))
    }

    @Test fun `halfway both faces are still mostly there, so the goo holds together`() {
        // A plain cross-fade has each at a half there, which the cut throws away.
        val half = ClockGoo.opacityOf(0.5f)
        assertTrue(half > 0.7f)
        assertEquals(1f, ClockGoo.opacityOf(1f), 0.001f)
        assertEquals(0f, ClockGoo.opacityOf(0f), 0.001f)
    }

    @Test fun `the blur comes in steps, so its effects can be kept`() {
        val seen = (0..1000).map { ClockGoo.stepOf(ClockGoo.blurOf(it / 1000f)) }.toSet()
        assertTrue(seen.size <= ClockGoo.STEPS + 1)
        assertEquals(0, ClockGoo.stepOf(0f))
        assertEquals(ClockGoo.STEPS, ClockGoo.stepOf(ClockGoo.BLUR_CAP))
    }

    @Test fun `the cutoff makes the edge hard at the threshold`() {
        val m = ClockGoo.thresholdRow()
        // alpha' = k * a + offset: under the threshold nothing, over it all.
        fun out(a: Float) = (m[0] * a + m[1]).coerceIn(0f, 255f)
        assertEquals(0f, out(255f * (ClockGoo.THRESHOLD - 0.05f)), 0.001f)
        assertEquals(255f, out(255f * (ClockGoo.THRESHOLD + 0.05f)), 0.001f)
    }

    @Test fun `the glass faces hand over to the goo at the ends and it carries the rest`() {
        assertEquals(1f, ClockGoo.digitalShown(0f), 0.001f)
        assertEquals(0f, ClockGoo.digitalShown(0.3f), 0.001f)
        assertEquals(0f, ClockGoo.analogShown(0.7f), 0.001f)
        assertEquals(1f, ClockGoo.analogShown(1f), 0.001f)
        assertEquals(0f, ClockGoo.gooShown(0f), 0.001f)
        for (t in listOf(0.3f, 0.5f, 0.7f)) assertEquals(1f, ClockGoo.gooShown(t), 0.001f)
        assertEquals(0f, ClockGoo.gooShown(1f), 0.001f)
    }
}
