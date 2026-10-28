package dev.kb896.aiusage.rear

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
}
