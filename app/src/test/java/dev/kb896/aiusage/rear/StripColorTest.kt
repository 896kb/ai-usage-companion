package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StripColorTest {

    private val black = 0xFF000000.toInt()

    @Test fun `a plain edge gives its colour`() {
        val px = IntArray(600) { 0xFF1E2A3B.toInt() }
        assertEquals(0xFF1E2A3B.toInt(), StripColor.pick(px))
    }

    @Test fun `text or an icon touching the edge does not change it`() {
        // 600 white rows, 120 of them crossed by black text: the median is still white.
        val px = IntArray(600) { if (it % 5 == 0) black else 0xFFFFFFFF.toInt() }
        assertEquals(0xFFFFFFFF.toInt(), StripColor.pick(px))
    }

    @Test fun `slight noise is still one colour`() {
        val px = IntArray(600) { i -> val d = i % 5 - 2; (0xFF shl 24) or ((200 + d) shl 16) or ((100 + d) shl 8) or (50 + d) }
        assertEquals((0xFF shl 24) or (200 shl 16) or (100 shl 8) or 50, StripColor.pick(px))
    }

    @Test fun `a busy edge - a photo, a gradient - is black`() {
        val gradient = IntArray(572) { i -> val v = i * 255 / 571; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        assertEquals(black, StripColor.pick(gradient))
        val halves = IntArray(600) { if (it < 300) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() }
        assertEquals(black, StripColor.pick(halves))
    }

    @Test fun `nothing read is black, and the colour is always opaque`() {
        assertEquals(black, StripColor.pick(IntArray(0)))
        assertEquals(0xFF102030.toInt(), StripColor.pick(IntArray(10) { 0x00102030 }))
    }

    @Test fun `a change eases over EASE_MS, the first colour taken as it is`() {
        val e = StripColor.Ease()
        assertEquals(0xFFFFFFFF.toInt(), e.at(0xFFFFFFFF.toInt(), 0))
        assertTrue(e.settled(0))
        val mid = e.at(black, 1_000)
        assertEquals(0xFFFFFFFF.toInt(), mid)
        val half = e.at(black, 1_000 + StripColor.EASE_MS / 2)
        assertTrue(half != black && half != 0xFFFFFFFF.toInt())
        assertEquals(black, e.at(black, 1_000 + StripColor.EASE_MS))
        assertTrue(e.settled(1_000 + StripColor.EASE_MS))
    }
}
