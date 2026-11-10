package dev.kb896.aiusage.rear

import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.data.PortalGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class PortalDomeTest {

    private fun layout(count: Int = 12, guest: Boolean? = null) =
        PortalSheet.layout(PortalGrid.R3C4, count, guest, Panel.CUTOUT_RIGHT.toFloat())

    private val still = PortalDome.Pose(0f, 0f)

    @Test fun `the dome is drawn over all the sheet, and a drag moves it but on a strip at the top and one at the bottom`() {
        for (guest in listOf(null, true, false)) {
            val l = layout(guest = guest)
            val a = PortalDome.area(l)
            assertEquals(0f, a.top, 0f)
            assertEquals(PortalSheet.FULL, a.bottom, 0f)
            val t = PortalDome.turns(l)
            assertTrue(t.top >= PortalDome.TOP_STRIP)
            l.buttons.forEach { assertTrue(it.bottom <= t.top) }
            // The bottom strip only with an app on the back: nothing to pull up without one.
            assertEquals(PortalSheet.FULL - if (guest != null) PortalDome.BOTTOM_STRIP else 0f, t.bottom, 0.01f)
            assertEquals(a.left, t.left, 0f)
            assertEquals(a.right, t.right, 0f)
            assertTrue(t.height > 200f)
        }
    }

    @Test fun `the middle tile is whole and square, tiles towards the rim are turned away and fainter`() {
        val l = layout(20)
        val a = PortalDome.area(l)
        val tiles = PortalDome.tiles(a, l.count, still)
        assertTrue(tiles.size >= 7)
        val mid = tiles.last()
        assertEquals(a.centerX, mid.cx, 0.5f)
        assertEquals(a.centerY, mid.cy, 0.5f)
        assertEquals(1f, mid.along, 1e-3f)
        assertEquals(1f, mid.across, 1e-3f)
        assertEquals(1f, mid.alpha, 1e-3f)
        val far = tiles.first()
        assertTrue(far.along < mid.along)
        assertTrue(far.along < far.across)
        assertTrue(far.alpha <= 1f)
        tiles.forEach { assertTrue(it.app in 0 until 20) }
    }

    /** A tile's four corners and its middle as it is drawn, drawn in a little so a shared edge is not a hit. */
    private fun points(t: PortalDome.Tile): List<Pair<Float, Float>> {
        val c = cos(t.turn); val s = sin(t.turn)
        return listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f, 0f to 0f).map { (u, v) ->
            val x = u * t.half * 0.97f; val y = v * t.half * 0.97f
            // Into the way out and across it, each narrowed, and back.
            val a = (x * c + y * s) * t.along
            val b = (-x * s + y * c) * t.across
            (t.cx + a * c - b * s) to (t.cy + a * s + b * c)
        }
    }

    @Test fun `no tile stands on another, wherever the dome has been moved to`() {
        val l = layout(20)
        val a = PortalDome.area(l)
        for (pose in listOf(still, PortalDome.Pose(37f, -51f), PortalDome.Pose(-4000f, 9000f), PortalDome.Pose(63.5f, 64.5f))) {
            val tiles = PortalDome.tiles(a, l.count, pose)
            for (t in tiles) {
                points(t).forEach { (x, y) -> assertTrue(PortalDome.covers(t, x, y)) }
                for (o in tiles) if (t !== o) points(o).forEach { (x, y) -> assertFalse("$pose", PortalDome.covers(t, x, y)) }
            }
        }
    }

    @Test fun `it goes on for ever every way, and every app comes round`() {
        for (n in listOf(1, 2, 3, 12, 24, 48)) {
            val a = PortalDome.area(layout(n))
            val step = PortalDome.step(a)
            val seen = HashSet<Int>()
            for (i in -30..30) {
                val t = PortalDome.tiles(a, n, PortalDome.Pose(i * step * 2f, i * step * 3f))
                assertTrue(t.isNotEmpty())
                t.forEach { seen += it.app }
            }
            assertEquals("$n apps", n, seen.size)
        }
    }

    @Test fun `no apps, no tiles`() {
        assertTrue(PortalDome.tiles(PortalDome.area(layout(0)), 0, still).isEmpty())
    }

    @Test fun `a tap on a tile is its app, off it nothing`() {
        val l = layout(20)
        val a = PortalDome.area(l)
        val tiles = PortalDome.tiles(a, l.count, still)
        val mid = tiles.last()
        assertEquals(mid.app, PortalDome.hit(tiles, mid.cx, mid.cy))
        assertEquals(mid.app, PortalDome.hit(tiles, mid.cx + mid.half - 1f, mid.cy))
        assertNull(PortalDome.hit(tiles, 0f, a.centerY))
        assertNull(PortalDome.hit(tiles, mid.cx + mid.half + 2f, mid.cy))
    }

    @Test fun `the middle tile goes with the finger`() {
        val a = PortalDome.area(layout(20))
        val before = PortalDome.tiles(a, 20, still).last()
        // A finger 30 px right moves the sheet of tiles back by 30.
        val after = PortalDome.tiles(a, 20, PortalDome.Pose(-30f, 0f)).maxBy { it.depth }
        assertEquals(before.app, after.app)
        assertEquals(before.cx + 30f, after.cx, 1.5f)
    }

    @Test fun `the dome follows the finger any way, and glides on let go, with no stop either way`() {
        val m = DomeMotion()
        m.grab(500f, 300f, 0)
        val p = m.drag(450f, 320f, 100)
        assertEquals(50f, p.x, 1e-3f)
        assertEquals(-20f, p.y, 1e-3f)
        m.drag(400f, 240f, 110)
        m.drag(350f, 160f, 120)
        m.release(350f, 160f, 125)
        assertTrue(m.settling)
        val let = m.pose(125)
        assertTrue(m.pose(400).x > let.x)
        assertTrue(m.pose(400).y > let.y)
        m.at(20_000)
        assertFalse(m.settling)
        // No tip to stop at: a long pull goes as far as the finger.
        val t = DomeMotion()
        t.grab(500f, 100f, 0)
        assertEquals(-4900f, t.drag(500f, 5000f, 100).y, 1e-3f)
    }

    @Test fun `a slow drag let go stays where it was left, and a tap stops a glide`() {
        val m = DomeMotion()
        m.grab(500f, 300f, 0)
        m.drag(460f, 300f, 300)
        m.release(460f, 300f, 300 + SheetMotion.STILL_MS + 50)
        assertFalse(m.settling)
        val g = DomeMotion()
        g.grab(500f, 300f, 0); g.drag(450f, 300f, 10); g.drag(400f, 300f, 20); g.release(400f, 300f, 25)
        assertTrue(g.settling)
        val at = g.stop(100)
        assertFalse(g.settling)
        assertEquals(at.x, g.pose(5_000).x, 1e-3f)
    }
}
