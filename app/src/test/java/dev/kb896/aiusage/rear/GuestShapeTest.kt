package dev.kb896.aiusage.rear

import dev.kb896.aiusage.Panel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestShapeTest {

    @Test fun `a guest keeps right of the cameras, in multi-window`() {
        val s = GuestShape.of(fullscreen = false)
        assertEquals(GuestShape.MULTI_WINDOW, s.mode)
        assertEquals(GuestShape.Bounds(Panel.CUTOUT_RIGHT, 0, Panel.WIDTH, Panel.HEIGHT), s.bounds)
        assertEquals(608, s.bounds.width)
        assertEquals(572, s.bounds.height)
    }

    @Test fun `one allowed the camera area has the whole display, fullscreen`() {
        val s = GuestShape.of(fullscreen = true)
        assertEquals(GuestShape.FULLSCREEN, s.mode)
        assertEquals(GuestShape.Bounds(0, 0, Panel.WIDTH, Panel.HEIGHT), s.bounds)
    }

    @Test fun `by package, from the config's set`() {
        assertEquals(GuestShape.of(false), GuestShape.forApp("a.b", setOf("c.d")))
        assertEquals(GuestShape.of(true), GuestShape.forApp("a.b", setOf("a.b")))
    }

    @Test fun `drifted only when the task's bounds are read and are not the shape's`() {
        val s = GuestShape.of(false)
        assertFalse(GuestShape.drifted(null, s))
        assertFalse(GuestShape.drifted(GuestShape.Bounds(296, 0, 904, 572), s))
        assertTrue(GuestShape.drifted(GuestShape.Bounds(0, 0, 904, 572), s))
        assertTrue(GuestShape.drifted(GuestShape.Bounds(0, 0, 608, 572), s))
    }

    @Test fun `the launch asks for multi-window only for a confined guest`() {
        assertEquals("--windowingMode 6 ", GuestShape.launchFlags(GuestShape.of(false)))
        assertEquals("", GuestShape.launchFlags(GuestShape.of(true)))
    }
}
