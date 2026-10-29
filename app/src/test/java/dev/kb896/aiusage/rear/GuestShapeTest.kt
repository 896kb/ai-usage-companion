package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestShapeTest {

    private val pro = RearGeometry.PRO17
    // The Pro Max's size; its lens column a stand-in (unmeasured).
    private val proMax = RearGeometry(976, 596, 296, 110, 450)

    @Test fun `a guest keeps right of the cameras, in multi-window`() {
        val s = GuestShape.of(fullscreen = false, pro)
        assertEquals(GuestShape.MULTI_WINDOW, s.mode)
        assertEquals(GuestShape.Bounds(296, 0, 904, 572), s.bounds)
        assertEquals(608, s.bounds.width)
        assertEquals(572, s.bounds.height)
    }

    @Test fun `one allowed the camera area has the whole display, fullscreen`() {
        val s = GuestShape.of(fullscreen = true, pro)
        assertEquals(GuestShape.FULLSCREEN, s.mode)
        assertEquals(GuestShape.Bounds(0, 0, 904, 572), s.bounds)
    }

    @Test fun `on a Pro Max both shapes reach its own edges`() {
        assertEquals(GuestShape.Bounds(296, 0, 976, 596), GuestShape.of(false, proMax).bounds)
        assertEquals(GuestShape.Bounds(0, 0, 976, 596), GuestShape.of(true, proMax).bounds)
        assertEquals(680, GuestShape.of(false, proMax).bounds.width)
        assertEquals(GuestShape.Bounds(0, 0, 976, 596), GuestShape.full(proMax))
    }

    @Test fun `by package - fullscreen unless the app was asked to keep right of the cameras`() {
        // Confining is opt-in for now: on the 17 Pro a confined guest is composed at x 0, not 296.
        assertEquals(GuestShape.of(true, pro), GuestShape.forApp("a.b", emptySet(), pro))
        assertEquals(GuestShape.of(true, pro), GuestShape.forApp("a.b", setOf("c.d"), pro))
        assertEquals(GuestShape.of(false, pro), GuestShape.forApp("a.b", setOf("a.b"), pro))
        assertEquals(GuestShape.of(true, proMax), GuestShape.forApp("a.b", emptySet(), proMax))
    }

    @Test fun `drifted only when the task's bounds are read and are not the shape's`() {
        val s = GuestShape.of(false, pro)
        assertFalse(GuestShape.drifted(null, s))
        assertFalse(GuestShape.drifted(GuestShape.Bounds(296, 0, 904, 572), s))
        assertTrue(GuestShape.drifted(GuestShape.Bounds(0, 0, 904, 572), s))
        assertTrue(GuestShape.drifted(GuestShape.Bounds(0, 0, 608, 572), s))
    }

    @Test fun `a fullscreen guest on a Pro Max, at the display's own size, has not drifted`() {
        // Its task reads the whole 976x596 every keep-alive tick: that is its shape, not a drift.
        val s = GuestShape.of(true, proMax)
        assertFalse(GuestShape.drifted(GuestShape.Bounds(0, 0, 976, 596), s))
        assertTrue(GuestShape.drifted(GuestShape.Bounds(0, 0, 904, 572), s))
        assertFalse(GuestShape.drifted(GuestShape.Bounds(296, 0, 976, 596), GuestShape.of(false, proMax)))
    }

    @Test fun `the launch asks for multi-window only for a confined guest`() {
        assertEquals("--windowingMode 6 ", GuestShape.launchFlags(GuestShape.of(false, pro)))
        assertEquals("", GuestShape.launchFlags(GuestShape.of(true, pro)))
    }
}
