package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestShapeTest {

    private val pro = RearGeometry.PRO17
    // The Pro Max's size; its lens column a stand-in (unmeasured).
    private val proMax = RearGeometry(976, 596, 296, 110, 450)

    @Test fun `the area right of the cameras, and the whole display`() {
        assertEquals(GuestShape.Bounds(296, 0, 904, 572), GuestShape.appArea(pro))
        assertEquals(608, GuestShape.appArea(pro).width)
        assertEquals(572, GuestShape.appArea(pro).height)
        assertEquals(GuestShape.Bounds(0, 0, 904, 572), GuestShape.full(pro))
    }

    @Test fun `on a Pro Max both reach its own edges`() {
        assertEquals(GuestShape.Bounds(296, 0, 976, 596), GuestShape.appArea(proMax))
        assertEquals(680, GuestShape.appArea(proMax).width)
        assertEquals(GuestShape.Bounds(0, 0, 976, 596), GuestShape.full(proMax))
    }

    @Test fun `by package - the whole rear unless the app was asked to keep right of the cameras`() {
        assertFalse(GuestShape.onPrivateDisplay("a.b", emptySet()))
        assertFalse(GuestShape.onPrivateDisplay("a.b", setOf("c.d")))
        assertTrue(GuestShape.onPrivateDisplay("a.b", setOf("a.b")))
    }

    @Test fun `drifted only when the task's bounds are read and are not the display's`() {
        val s = GuestShape.full(pro)
        assertFalse(GuestShape.drifted(null, s))
        assertFalse(GuestShape.drifted(GuestShape.Bounds(0, 0, 904, 572), s))
        assertTrue(GuestShape.drifted(GuestShape.Bounds(296, 0, 904, 572), s))
    }

    @Test fun `a fullscreen guest on a Pro Max, at the display's own size, has not drifted`() {
        // Its task reads the whole 976x596 every keep-alive tick: that is its shape, not a drift.
        val s = GuestShape.full(proMax)
        assertFalse(GuestShape.drifted(GuestShape.Bounds(0, 0, 976, 596), s))
        assertTrue(GuestShape.drifted(GuestShape.Bounds(0, 0, 904, 572), s))
    }

    @Test fun `put right a few times, then left alone until the key changes`() {
        val g = GuestShape.GiveUp<String>(tries = 3)
        assertTrue(g.attempt("a"))
        assertTrue(g.attempt("a"))
        assertTrue(g.attempt("a"))
        // Three goes and still not right: no more.
        assertFalse(g.attempt("a"))
        assertTrue(g.givenUp("a"))
        assertFalse(g.attempt("a"))
        // Another task or density: counted afresh.
        assertTrue(g.attempt("b"))
        assertFalse(g.givenUp("b"))
    }

    @Test fun `once right, its count starts again`() {
        val g = GuestShape.GiveUp<String>(tries = 2)
        g.attempt("a"); g.attempt("a")
        g.placed("a")
        assertTrue(g.attempt("a"))
        assertTrue(g.attempt("a"))
        assertFalse(g.attempt("a"))
        // Being found right later counts too: it may be tried again when it next goes wrong.
        g.placed("a")
        assertFalse(g.givenUp("a"))
        // Another key's right says nothing of this one.
        g.attempt("a"); g.attempt("a"); g.placed("b")
        assertTrue(g.givenUp("a"))
    }

    @Test fun `giving up is said once per key`() {
        val g = GuestShape.GiveUp<String>(tries = 1)
        g.attempt("a")
        assertTrue(g.announce("a"))
        assertFalse(g.announce("a"))
        g.attempt("b")
        assertFalse("not given up yet", g.announce("c"))
        assertTrue(g.announce("b"))
    }

    @Test fun `an app on a private display, moved right of the lenses`() {
        assertEquals(GuestShape.Bounds(366, 0, 835, 572), GuestShape.appOnRear(pro, GuestShape.Bounds(70, 0, 539, 572)))
        assertEquals(GuestShape.appArea(pro), GuestShape.appOnRear(pro, GuestShape.Bounds(0, 0, 608, 572)))
    }

    @Test fun `unread, empty or out of the area, the whole area`() {
        assertEquals(GuestShape.appArea(pro), GuestShape.appOnRear(pro, null))
        assertEquals(GuestShape.appArea(pro), GuestShape.appOnRear(pro, GuestShape.Bounds(0, 0, 0, 0)))
        assertEquals(GuestShape.Bounds(296, 0, 904, 572), GuestShape.appOnRear(pro, GuestShape.Bounds(-20, -5, 900, 900)))
    }
}
