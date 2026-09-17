package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrtScreenTest {
    private val w = 904f
    private val h = 572f

    @Test
    fun `the centre and the middles of the edges do not move`() {
        assertEquals(452f to 286f, CrtScreen.sourcePoint(452f, 286f, w, h))
        val (lx, ly) = CrtScreen.sourcePoint(0f, 286f, w, h)
        assertEquals(0f, lx, 0.01f); assertEquals(286f, ly, 0.01f)
        val (tx, ty) = CrtScreen.sourcePoint(452f, 0f, w, h)
        assertEquals(452f, tx, 0.01f); assertEquals(0f, ty, 0.01f)
    }

    @Test
    fun `a touch near a corner is pushed outwards, and held at the edge`() {
        val (x, y) = CrtScreen.sourcePoint(100f, 60f, w, h)
        assertTrue("x pushed left: $x", x < 100f)
        assertTrue("y pushed up: $y", y < 60f)
        val (cx, cy) = CrtScreen.sourcePoint(0f, 0f, w, h)
        assertEquals(0f to 0f, cx to cy)
        val (fx, fy) = CrtScreen.sourcePoint(w, h, w, h)
        assertEquals(w to h, fx to fy)
    }
}
