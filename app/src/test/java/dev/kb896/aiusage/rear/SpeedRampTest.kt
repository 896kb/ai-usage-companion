package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedRampTest {
    @Test fun `it starts at full speed`() {
        assertEquals(1f, SpeedRamp().at(0), 0f)
    }

    @Test fun `asked to stop, it slows over the ramp, never faster at a later moment`() {
        val r = SpeedRamp()
        r.toward(0f, now = 1_000)
        var last = 1f
        for (t in 1_000L..(1_000 + SpeedRamp.RAMP_MS) step 50) {
            val s = r.at(t)
            assertTrue("at $t: $s after $last", s <= last + 1e-6f)
            last = s
        }
        assertEquals(0f, r.at(1_000 + SpeedRamp.RAMP_MS), 0f)
    }

    @Test fun `asked to go again part-way through stopping, it turns from where it had got to`() {
        val r = SpeedRamp()
        r.toward(0f, now = 0)
        val half = r.at(SpeedRamp.RAMP_MS / 2)
        r.toward(1f, now = SpeedRamp.RAMP_MS / 2)
        assertEquals(half, r.at(SpeedRamp.RAMP_MS / 2), 1e-6f)    // no jump
        assertEquals(1f, r.at(SpeedRamp.RAMP_MS / 2 + SpeedRamp.RAMP_MS), 0f)
    }

    @Test fun `stopped at once, it is stopped, and starts from there`() {
        val r = SpeedRamp()
        r.stopNow()
        assertEquals(0f, r.at(5), 0f)
        r.toward(1f, now = 10)
        assertEquals(0f, r.at(10), 0f)
        assertTrue(r.at(10 + SpeedRamp.RAMP_MS / 2) in 0.1f..0.9f)
    }
}
