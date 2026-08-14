package dev.rgarjoaba.claudestatus.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StalenessTest {

    private val min = 60_000L

    @Test fun `fresh data has no stale marker`() {
        assertNull(RearDialView.staleLabel(fetchedAt = 0L, now = 30_000L))
    }

    @Test fun `data older than two minutes is marked in minutes`() {
        assertEquals("⟳ 12m", RearDialView.staleLabel(fetchedAt = 0L, now = 12 * min))
    }

    @Test fun `data older than an hour is marked in hours`() {
        assertEquals("⟳ 3h", RearDialView.staleLabel(fetchedAt = 0L, now = 190 * min))
    }

    @Test fun `drift stays within a few pixels and repeats`() {
        for (t in 0..600_000 step 7_000) {
            val (dx, dy) = RearDialView.driftOffset(t.toLong())
            assert(dx in -4f..4f && dy in -4f..4f) { "drift escaped bounds at t=$t" }
        }
        assertEquals(RearDialView.driftOffset(0L), RearDialView.driftOffset(600_000L))
    }
}
