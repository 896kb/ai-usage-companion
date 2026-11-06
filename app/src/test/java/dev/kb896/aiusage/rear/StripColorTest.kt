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
    @Test fun `samples come quickly until the colour has held a few times, then slowly`() {
        val p = StripColor.Pace()
        assertEquals(StripColor.FAST_MS, p.next(changed = true))
        repeat(StripColor.STABLE - 1) { assertEquals(StripColor.FAST_MS, p.next(changed = false)) }
        assertEquals(StripColor.SLOW_MS, p.next(changed = false))
        assertEquals(StripColor.SLOW_MS, p.next(changed = false))
        // A new colour: quick again.
        assertEquals(StripColor.FAST_MS, p.next(changed = true))
    }

    @Test fun `a touch or a new guest makes it quick again`() {
        val p = StripColor.Pace()
        repeat(StripColor.STABLE + 2) { p.next(changed = false) }
        p.poke()
        assertEquals(StripColor.FAST_MS, p.next(changed = false))
    }

    @Test fun `the sample is the first columns of the app's layer, the display's whole height`() {
        assertEquals(GuestShape.Bounds(0, 0, 6, 572), StripColor.sample(RearGeometry.PRO17))
        assertEquals(GuestShape.Bounds(0, 0, 6, 596), StripColor.sample(RearGeometry(976, 596, 296, 110, 450)))
    }

    @Test fun `the strip itself is the lens column, the display's whole height`() {
        assertEquals(listOf(GuestShape.Bounds(0, 0, 296, 572)), StripColor.around(RearGeometry.PRO17))
        assertEquals(listOf(GuestShape.Bounds(0, 0, 296, 596)), StripColor.around(RearGeometry(976, 596, 296, 110, 450)))
    }

    @Test fun `a narrower app has the colour either side of it`() {
        assertEquals(
            listOf(GuestShape.Bounds(0, 0, 296, 572), GuestShape.Bounds(296, 0, 366, 572), GuestShape.Bounds(835, 0, 904, 572)),
            StripColor.around(RearGeometry.PRO17, GuestShape.Bounds(366, 0, 835, 572)),
        )
    }

    @Test fun `a keyboard stops the bars above it, not the lens column`() {
        assertEquals(
            listOf(GuestShape.Bounds(0, 0, 296, 572), GuestShape.Bounds(296, 0, 366, 330), GuestShape.Bounds(835, 0, 904, 330)),
            StripColor.around(RearGeometry.PRO17, GuestShape.Bounds(366, 0, 835, 572), keyboardTop = 330),
        )
    }

    @Test fun `a keyboard over a shorter app's bottom bar takes it`() {
        assertEquals(
            listOf(GuestShape.Bounds(0, 0, 296, 572), GuestShape.Bounds(296, 0, 904, 40)),
            StripColor.around(RearGeometry.PRO17, GuestShape.Bounds(296, 40, 904, 532), keyboardTop = 300),
        )
    }

    @Test fun `the keyboard is looked for in the middle of the wider bar, in the display's pixels`() {
        val area = GuestShape.appArea(RearGeometry.PRO17)
        // Bolt: x 366-835 of the rear, bars 70 left and 69 right.
        assertEquals(GuestShape.Bounds(34, 0, 36, 572), StripColor.keyboardProbe(RearGeometry.PRO17, GuestShape.Bounds(366, 0, 835, 572), area))
        assertEquals(GuestShape.Bounds(575, 0, 577, 572), StripColor.keyboardProbe(RearGeometry.PRO17, GuestShape.Bounds(300, 0, 840, 572), area))
        assertEquals(null, StripColor.keyboardProbe(RearGeometry.PRO17, area, area))
    }

    @Test fun `a keyboard starts at the first row that is not the bar's black`() {
        val black = 0xFF000000.toInt()
        val keys = 0xFF202124.toInt()
        assertEquals(330, StripColor.keyboardTop(IntArray(572) { if (it >= 330) keys else black }))
        assertEquals(330, StripColor.keyboardTop(IntArray(286) { if (it >= 165) keys else black }, scale = 0.5f))
        assertEquals(null, StripColor.keyboardTop(IntArray(572) { black }))
        assertEquals("a sliver is not a keyboard", null, StripColor.keyboardTop(IntArray(572) { if (it >= 560) keys else black }))
        // As read on the phone: a black band under the keys, and black between rows of them.
        assertEquals(330, StripColor.keyboardTop(IntArray(572) { if (it in 330..540 && it % 50 != 0) keys else black }))
    }

    @Test fun `a shorter app has it above and below too`() {
        assertEquals(
            listOf(GuestShape.Bounds(0, 0, 296, 572), GuestShape.Bounds(296, 0, 904, 40), GuestShape.Bounds(296, 532, 904, 572)),
            StripColor.around(RearGeometry.PRO17, GuestShape.Bounds(296, 40, 904, 532)),
        )
    }

    @Test fun `a letterboxed app's colour is read off its own left edge, not the black bar`() {
        assertEquals(GuestShape.Bounds(70, 0, 76, 572), StripColor.sample(RearGeometry.PRO17, GuestShape.Bounds(366, 0, 835, 572)))
    }
}
