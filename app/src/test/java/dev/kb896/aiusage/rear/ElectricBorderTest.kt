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
        val clean = ElectricBorder.outline(lens)
        val bolt = ElectricBorder.bolt(lens, time = 1.7f)
        var strayed = 0f
        for (i in clean.indices) {
            val d = kotlin.math.abs(bolt[i] - clean[i])
            assertTrue(d <= ElectricBorder.REACH + 0.001f)
            strayed = maxOf(strayed, d)
        }
        assertTrue("never moved", strayed > 1f)
    }

    @Test fun `the same time draws the same bolt, a later one another`() {
        val a = ElectricBorder.bolt(edge, 2f)
        assertTrue(a.contentEquals(ElectricBorder.bolt(edge, 2f)))
        assertTrue(!a.contentEquals(ElectricBorder.bolt(edge, 2.1f)))
    }
}
