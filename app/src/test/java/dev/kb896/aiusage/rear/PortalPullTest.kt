package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalPullTest {

    @Test fun `a finger in the bottom band right of the lenses starts a pull`() {
        assertTrue(PortalPull.startsPull(600f, 560f))
        assertTrue(PortalPull.startsPull(600f, 572f - PortalPull.EDGE))
    }

    @Test fun `higher up, or over the lens column, it does not`() {
        assertFalse(PortalPull.startsPull(600f, 400f))
        assertFalse(PortalPull.startsPull(200f, 560f))
    }

    @Test fun `upward travel opens it, a full travel all the way`() {
        assertEquals(0f, PortalPull.progress(open = false, dy = 0f), 0.001f)
        assertEquals(0.5f, PortalPull.progress(open = false, dy = -PortalPull.TRAVEL / 2), 0.001f)
        assertEquals(1f, PortalPull.progress(open = false, dy = -PortalPull.TRAVEL * 2), 0.001f)
    }

    @Test fun `open, a drag down closes it`() {
        assertEquals(0.5f, PortalPull.progress(open = true, dy = PortalPull.TRAVEL / 2), 0.001f)
    }

    @Test fun `let go past halfway it opens, short of it it springs back`() {
        assertTrue(PortalPull.release(open = false, dy = -PortalPull.TRAVEL * 0.6f))
        assertFalse(PortalPull.release(open = false, dy = -PortalPull.TRAVEL * 0.4f))
    }

    @Test fun `a flick goes the way it was flicked, whatever the distance`() {
        assertTrue(PortalPull.release(open = false, dy = -10f, velocity = -1f))
        assertFalse(PortalPull.release(open = true, dy = -10f, velocity = 1f))
    }
}
