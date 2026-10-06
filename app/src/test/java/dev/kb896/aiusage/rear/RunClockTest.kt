package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Test

class RunClockTest {
    @Test fun `a clock never started starts when it is first resumed`() {
        // The preview's first ask is "carry on looping", never "restart": its frames were all
        // due at the epoch and the clip ran as fast as it could be decoded.
        val c = RunClock()
        c.resume(now = 500_000)
        assertEquals(500_000L, c.due(0))
        assertEquals(500_033L, c.due(33))
    }

    @Test fun `a pause moves every later frame on by the time paused`() {
        val c = RunClock()
        c.start(now = 1_000)
        c.pause(now = 2_000)
        c.resume(now = 5_000)
        assertEquals(4_000L + 1_500, c.due(1_500))
    }

    @Test fun `starting over is due from now`() {
        val c = RunClock()
        c.start(now = 1_000)
        c.pause(now = 2_000)
        c.start(now = 9_000)
        assertEquals(9_000L, c.due(0))
        c.resume(now = 9_500)    // not paused since the start: nothing to move
        assertEquals(9_000L, c.due(0))
    }

    @Test fun `a second pause keeps the first one's moment`() {
        val c = RunClock()
        c.start(now = 0)
        c.pause(now = 100)
        c.pause(now = 300)
        c.resume(now = 400)
        assertEquals(300L, c.due(0))
    }

    @Test fun `a run resumed part-way is due from now at the frame it resumes on`() {
        val c = RunClock()
        c.startFrom(now = 10_000, offsetMs = 1_234)
        assertEquals(10_000L, c.due(1_234))
        assertEquals(10_033L, c.due(1_267))
    }

    @Test fun `a loop goes round from the base it is given`() {
        val c = RunClock()
        c.start(now = 0)
        c.restartAt(base = 3_555)
        assertEquals(3_555L, c.due(0))
    }
}
