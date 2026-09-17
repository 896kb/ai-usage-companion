package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrtScreenTest {
    private val w = 904f
    private val h = 572f
    private val rings = listOf(CrtScreen.Still(150f, 144f, 140f), CrtScreen.Still(150f, 420f, 140f))

    private fun at(x: Float, y: Float) = CrtScreen.sourcePoint(x, y, w, h, rings)

    @Test
    fun `the centre, the margin band and the corners do not move`() {
        assertEquals(452f to 286f, at(452f, 286f))
        assertEquals(10f to 286f, at(10f, 286f))
        assertEquals(600f to 12f, at(600f, 12f))
        assertEquals(0f to 0f, at(0f, 0f))
        assertEquals(w to h, at(w, h))
    }

    @Test
    fun `inside a lens ring nothing moves`() {
        assertEquals(150f to 144f, at(150f, 144f))
        assertEquals(250f to 200f, at(250f, 200f))
        assertEquals(150f to 300f, at(150f, 300f))
    }

    @Test
    fun `further in, a touch is pushed outwards from the centre`() {
        val (x, y) = at(650f, 180f)
        assertTrue("x pushed right: $x", x > 650f)
        assertTrue("y pushed up: $y", y < 180f)
        val (lx, ly) = at(400f, 420f)
        assertTrue("x pushed left: $lx", lx < 400f)
        assertTrue("y pushed down: $ly", ly > 420f)
    }
}
