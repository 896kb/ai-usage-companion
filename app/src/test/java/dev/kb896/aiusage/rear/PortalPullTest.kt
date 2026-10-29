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

    @Test fun `on a Pro Max the band starts where its lenses end, in panel units`() {
        // 296 lens pixels of 976 are 274 panel units: a finger at 280 is clear of them there.
        val left = RearGeometry(976, 596, 296, 110, 450).cutoutUnits
        assertTrue(PortalPull.startsPull(280f, 560f, left))
        assertFalse(PortalPull.startsPull(270f, 560f, left))
        assertFalse("the panel's own band is the 17 Pro's", PortalPull.startsPull(280f, 560f))
    }
}
