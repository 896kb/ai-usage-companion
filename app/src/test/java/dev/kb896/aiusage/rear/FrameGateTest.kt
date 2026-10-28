package dev.kb896.aiusage.rear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameGateTest {

    @Test fun `allowed by default, the frame runs while it is wanted`() {
        val g = FrameGate()
        assertFalse(g.runs)
        assertTrue(g.want(true))
        assertFalse(g.want(false))
    }

    @Test fun `switched off, it goes at once and a show does nothing`() {
        val g = FrameGate()
        g.want(true)
        assertFalse(g.allow(false))
        assertFalse(g.want(true))
    }

    @Test fun `switched back on while still wanted, it is up again without another show`() {
        val g = FrameGate()
        g.want(true); g.allow(false)
        assertTrue(g.allow(true))
    }

    @Test fun `switched back on with no guest on top, it stays down`() {
        val g = FrameGate()
        g.allow(false); g.want(true); g.want(false)
        assertFalse(g.allow(true))
    }
}
