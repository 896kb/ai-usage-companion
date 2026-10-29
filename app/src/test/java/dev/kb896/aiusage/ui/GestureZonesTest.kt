package dev.kb896.aiusage.ui

import dev.kb896.aiusage.ui.GestureZones.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureZonesTest {

    private val screen = GestureZones.Box(0f, 0f, GestureZones.WIDTH, GestureZones.HEIGHT)
    private fun zone(kind: Kind) = GestureZones.zones.single { it.kind == kind }

    @Test fun `the sketch is the rear's 904 by 572, the cameras its left 296`() {
        assertEquals(904f, GestureZones.WIDTH)
        assertEquals(572f, GestureZones.HEIGHT)
        assertEquals(GestureZones.Box(0f, 0f, 296f, 572f), GestureZones.cameras)
    }

    @Test fun `one zone each, all on the screen and right of the cameras`() {
        assertEquals(Kind.entries.toSet(), GestureZones.zones.map { it.kind }.toSet())
        assertEquals(Kind.entries.size, GestureZones.zones.size)
        for (z in GestureZones.zones) {
            assertTrue("${z.kind} on the screen", screen.holds(z.box))
            assertTrue("${z.kind} right of the cameras", z.box.left >= GestureZones.CAMERAS)
            assertTrue("${z.kind} has a label", z.label.isNotBlank())
        }
    }

    @Test fun `the portal is the bottom band, the full usable width`() {
        val b = zone(Kind.PORTAL).box
        assertEquals(GestureZones.CAMERAS, b.left)
        assertEquals(GestureZones.WIDTH, b.right)
        assertEquals(GestureZones.HEIGHT, b.bottom)
        assertTrue(b.height < GestureZones.HEIGHT / 6)
        assertEquals(GestureZones.Arrow.UP, zone(Kind.PORTAL).arrow)
    }

    @Test fun `back is the right edge, above the portal's band, where the pull wins`() {
        val back = zone(Kind.BACK).box
        val portal = zone(Kind.PORTAL).box
        assertEquals(GestureZones.WIDTH, back.right)
        assertEquals(0f, back.top)
        assertFalse(back.overlaps(portal))
        assertEquals(portal.top, back.bottom)
        assertTrue(back.width < (GestureZones.WIDTH - GestureZones.CAMERAS) / 6)
        assertEquals(GestureZones.Arrow.LEFT, zone(Kind.BACK).arrow)
    }

    @Test fun `the control centre is the top edge over the usable area's left half`() {
        val cc = zone(Kind.CONTROL_CENTRE).box
        assertEquals(0f, cc.top)
        assertEquals(GestureZones.CAMERAS, cc.left)
        assertEquals(GestureZones.CAMERAS + (GestureZones.WIDTH - GestureZones.CAMERAS) / 2f, cc.right)
        assertEquals(GestureZones.Arrow.DOWN, zone(Kind.CONTROL_CENTRE).arrow)
    }

    @Test fun `the double tap sits in the open middle, clear of every edge zone`() {
        val tap = zone(Kind.DOUBLE_TAP)
        for (z in GestureZones.zones) if (z !== tap) assertFalse("${z.kind}", tap.box.overlaps(z.box))
        assertEquals(GestureZones.Arrow.NONE, tap.arrow)
    }

    @Test fun `the edge zones never overlap each other`() {
        val edges = GestureZones.zones.filter { it.kind != Kind.DOUBLE_TAP }
        for (a in edges) for (b in edges) if (a !== b) assertFalse("${a.kind}/${b.kind}", a.box.overlaps(b.box))
    }

    @Test fun `the lenses sit inside the camera band, one above the other, apart`() {
        val lenses = GestureZones.lenses
        assertTrue(lenses.size >= 2)
        for (l in lenses) assertTrue(GestureZones.cameras.holds(GestureZones.Box(l.x - l.r, l.y - l.r, l.x + l.r, l.y + l.r)))
        for ((a, b) in lenses.zipWithNext()) assertTrue(b.y - a.y > a.r + b.r)
    }

    @Test fun `fitted to a view, it keeps its proportions and centres in the room left over`() {
        val half = GestureZones.fit(452f, 286f)
        assertEquals(0.5f, half.scale, 1e-6f)
        assertEquals(0f, half.dx, 1e-6f); assertEquals(0f, half.dy, 1e-6f)
        // Wider than the sketch: as tall as the view, centred across.
        val wide = GestureZones.fit(1000f, 286f)
        assertEquals(0.5f, wide.scale, 1e-6f)
        assertEquals((1000f - 452f) / 2f, wide.dx, 1e-4f)
        assertEquals(0f, wide.dy, 1e-6f)
        val b = wide.box(GestureZones.Box(0f, 0f, 904f, 572f))
        assertEquals(GestureZones.Box(wide.dx, 0f, wide.dx + 452f, 286f), b)
    }

    @Test fun `the height for a width is the rear's proportion`() {
        assertEquals(572, GestureZones.heightFor(904))
        assertEquals(286, GestureZones.heightFor(452))
        assertEquals(0, GestureZones.heightFor(0))
    }
}
