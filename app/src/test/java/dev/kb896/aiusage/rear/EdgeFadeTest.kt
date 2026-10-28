package dev.kb896.aiusage.rear

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeFadeTest {

    private fun alpha(c: Int) = c ushr 24

    @Test fun `sharp inside, nothing at the edge, blurred on the way`() {
        assertEquals(0f, EdgeFade.sharp(0f), 0f)
        assertEquals(1f, EdgeFade.sharp(1f), 0f)
        assertEquals(0f, EdgeFade.blurred(0f), 0f)
        assertEquals(0f, EdgeFade.blurred(1f), 0f)
        assertEquals(0.25f, EdgeFade.blurred(0.5f), 1e-6f)
        // Never more of the two together than the content itself.
        for (i in 0..20) { val t = i / 20f; assertTrue(EdgeFade.sharp(t) + EdgeFade.blurred(t) <= 1f + 1e-6f) }
    }

    @Test fun `the masks run from the edge in, as colours with only an alpha`() {
        val sharp = EdgeFade.sharpMask()
        assertEquals(EdgeFade.STOPS, sharp.size)
        assertEquals(0, alpha(sharp.first()))
        assertEquals(255, alpha(sharp.last()))
        sharp.forEach { assertEquals(0, it and 0xFFFFFF) }
        val blur = EdgeFade.blurMask()
        assertEquals(0, alpha(blur.first()))
        assertEquals(0, alpha(blur.last()))
        assertTrue(blur.any { alpha(it) > 50 })
        assertEquals(EdgeFade.STOPS, EdgeFade.positions().size)
        assertEquals(1f, EdgeFade.positions().last(), 0f)
    }

    @Test fun `a fade that is off leaves the content whole and draws no blur`() {
        assertArrayEquals(IntArray(EdgeFade.STOPS) { 0xFF shl 24 }, EdgeFade.sharpMask(0f))
        assertArrayEquals(IntArray(EdgeFade.STOPS), EdgeFade.blurMask(0f))
        // Half on: the edge half gone.
        assertEquals(128, alpha(EdgeFade.sharpMask(0.5f).first()))
    }
}
