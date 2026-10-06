package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopPaceTest {
    private val r = EaseCurve.RAMP_MS

    private fun steadyFrom(base: Long): LoopPace = LoopPace(RunClock().apply { start(base) })

    @Test fun `steady, a frame is due its own time after the start`() {
        val p = steadyFrom(1_000)
        assertEquals(1_500L, p.due(500))
    }

    @Test fun `easing out, the frames come further and further apart, then stop`() {
        val p = steadyFrom(0)
        p.easeOut(now = 10_000, at = 2_000)
        val gaps = (2_033L..(2_000 + r) step 33).map { p.due(it)!! }.zipWithNext { a, b -> b - a }
        assertTrue("gaps $gaps", gaps.zipWithNext().all { (a, b) -> b >= a - 1 })
        assertTrue("first ${gaps.first()} last ${gaps.last()}", gaps.last() > gaps.first() * 2)
        assertNull(p.due(2_000 + r + 33))   // past the stop: held there
    }

    @Test fun `easing in, it picks up speed and then runs at its own pace, with no jump`() {
        val p = steadyFrom(0)
        p.easeIn(now = 50_000, at = 3_000)
        val first = p.due(3_033)!! - 50_000
        assertTrue("first frame after $first ms", first > 33)
        val atEnd = p.due(3_000 + r)!!
        // Beyond the ramp: steady, a frame every 33 ms.
        assertEquals(atEnd + 33, p.due(3_000 + r + 33))
        assertEquals(atEnd + 330, p.due(3_000 + r + 330))
    }

    @Test fun `resumed without ease, it carries on at once from the frame it stopped on`() {
        val p = steadyFrom(0)
        p.easeOut(now = 10_000, at = 2_000)
        p.resume(now = 20_000, at = 2_500)
        assertEquals(20_033L, p.due(2_533))
    }

    @Test fun `a loop going round in a ramp keeps the ramp's clock`() {
        val p = steadyFrom(0)
        p.easeOut(now = 10_000, at = 3_900)       // a 4 s clip, stopping 1 s on: into the next pass
        val before = p.due(3_966)!!
        p.wrapped(passMs = 4_000)
        val after = p.due(0)!!
        assertTrue("before $before after $after", after > before)
    }
}
