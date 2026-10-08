package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Carrying on from a finger: the time the rest takes, and the curve that starts at its speed. */
class GlideTest {
    @Test fun `the rest takes twice the distance over the speed, within bounds`() {
        assertEquals(200f, Glide.ms(100f, 1f, 80f, 400f), 0f)
        assertEquals(400f, Glide.ms(100f, 0f, 80f, 400f), 0f)
        assertEquals(80f, Glide.ms(4f, 2f, 80f, 400f), 0f)
    }

    @Test fun `carried it leaves at full speed and otherwise it eases in`() {
        // Slope at the start: about 2 for the carried curve, about 0 for the other.
        assertTrue(Glide.curve(0.01f, carried = true) > 0.015f)
        assertTrue(Glide.curve(0.01f, carried = false) < 0.002f)
        assertEquals(1f, Glide.curve(1f, carried = true), 0f)
        assertEquals(1f, Glide.curve(1f, carried = false), 0.0001f)
    }
}
