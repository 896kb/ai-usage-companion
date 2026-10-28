package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class ElectricBorderTest {

    private val edge = ElectricBorder.Shape.Rounded(10f, 10f, 894f, 562f, 87f)
    private val lens = ElectricBorder.Shape.Circle(150f, 144f, 146f)

    @Test fun `the rounded rectangle's points lie on its outline`() {
        val pts = ElectricBorder.outline(edge)
        for (i in pts.indices step 2) {
            val x = pts[i]; val y = pts[i + 1]
            val onSide = x in 9.9f..10.1f || x in 893.9f..894.1f || y in 9.9f..10.1f || y in 561.9f..562.1f
            val inCorner = (x < 97f || x > 807f) && (y < 97f || y > 475f)
            assertTrue("($x, $y) off the outline", onSide || inCorner)
        }
    }

    @Test fun `the circle's points are at its radius, a point every four pixels or so`() {
        val pts = ElectricBorder.outline(lens)
        for (i in pts.indices step 2) assertEquals(146f, hypot(pts[i] - 150f, pts[i + 1] - 144f), 0.01f)
        assertEquals((2 * Math.PI * 146 / ElectricBorder.STEP).toInt() + 1, pts.size / 2)
    }

    @Test fun `the bolt strays from the outline, but never further than its reach`() {
        for (shape in listOf(edge, lens)) for (time in listOf(0f, 1.7f, 13.3f, -4.2f)) {
            val clean = ElectricBorder.outline(shape)
            val bolt = ElectricBorder.bolt(shape, time)
            var strayed = 0f
            for (i in clean.indices) {
                val d = kotlin.math.abs(bolt[i] - clean[i])
                assertTrue(d <= ElectricBorder.REACH + 0.001f)
                strayed = maxOf(strayed, d)
            }
            assertTrue("never moved ($shape, $time)", strayed > 1f)
        }
    }

    @Test fun `the rounded outline starts at the top edge, runs clockwise and closes`() {
        val p = ElectricBorder.outline(edge)
        assertEquals(97f, p[0], 0.01f); assertEquals(10f, p[1], 0.01f)
        assertEquals(p[0], p[p.size - 2], 0.01f); assertEquals(p[1], p[p.size - 1], 0.01f)
        assertTrue(p[2] > p[0])
    }

    @Test fun `the rounded outline has no gaps`() {
        val p = ElectricBorder.outline(edge)
        for (i in 2 until p.size step 2)
            assertTrue("gap at $i", hypot(p[i] - p[i - 2], p[i + 1] - p[i - 1]) <= ElectricBorder.STEP * 1.01f)
    }

    @Test fun `the rounded corners are arcs about their own centres`() {
        val p = ElectricBorder.outline(edge)
        for (i in p.indices step 2) {
            val x = p[i]; val y = p[i + 1]
            val cx = if (x < 97f) 97f else if (x > 807f) 807f else continue
            val cy = if (y < 97f) 97f else if (y > 475f) 475f else continue
            assertEquals("($x, $y)", 87f, hypot(x - cx, y - cy), 0.01f)
        }
    }

    @Test fun `the circle starts at the top and runs clockwise`() {
        val p = ElectricBorder.outline(lens)
        assertEquals(150f, p[0], 0.01f); assertEquals(144f - 146f, p[1], 0.01f)
        assertTrue(p[2] > 150f)
    }

    @Test fun `the same time draws the same bolt, a later one another`() {
        val a = ElectricBorder.bolt(edge, 2f)
        assertTrue(a.contentEquals(ElectricBorder.bolt(edge, 2f)))
        assertTrue(!a.contentEquals(ElectricBorder.bolt(edge, 2.1f)))
    }
}
