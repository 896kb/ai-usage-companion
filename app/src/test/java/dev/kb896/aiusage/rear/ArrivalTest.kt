package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalTest {

    @Test fun `nothing until halfway, then an ease-out cubic to all of it`() {
        assertEquals(0f, Arrival.of(0f), 0f)
        assertEquals(0f, Arrival.of(0.5f), 0f)
        // A quarter of the way through the rest: 1 - (3/4)^3... at c = 0.5, 1 - 1/8.
        assertEquals(0.875f, Arrival.of(0.75f), 1e-5f)
        assertEquals(1f, Arrival.of(1f), 0f)
        assertEquals(1f, Arrival.of(1.5f), 0f)
        assertEquals(0f, Arrival.of(-1f), 0f)
    }

    @Test fun `it rises the whole way, never back`() {
        val xs = (0..100).map { Arrival.of(it / 100f) }
        assertTrue(xs.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun `the offset is a quarter of the travel until they show, nothing once arrived`() {
        assertEquals(143f, Arrival.offset(0f, 572f), 1e-3f)
        assertEquals(143f, Arrival.offset(0.5f, 572f), 1e-3f)
        assertEquals(143f * 0.125f, Arrival.offset(0.75f, 572f), 1e-3f)
        assertEquals(0f, Arrival.offset(1f, 572f), 0f)
    }

    @Test fun `each piece grows from GROW_FROM of its size to all of it`() {
        assertEquals(Arrival.GROW_FROM, Arrival.scale(0.3f), 0f)
        assertEquals(1f, Arrival.scale(1f), 0f)
        assertEquals(Arrival.GROW_FROM + (1f - Arrival.GROW_FROM) * 0.875f, Arrival.scale(0.75f), 1e-5f)
    }
}
