package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuestWidthTest {

    private val pro = RearGeometry.PRO17

    @Test fun `the whole width right of the lenses is the most, and none set is that`() {
        assertEquals(608, GuestWidth.full(pro))
        assertNull(GuestWidth.clamp(pro, null))
        assertNull(GuestWidth.clamp(pro, 608))
        assertNull(GuestWidth.clamp(pro, 900))
        assertEquals(608, GuestWidth.of(pro, null))
        assertEquals("Full", GuestWidth.label(pro, null))
    }

    @Test fun `widths go in steps and no narrower than the least`() {
        assertEquals(520, GuestWidth.clamp(pro, 522))
        assertEquals(528, GuestWidth.clamp(pro, 525))
        assertEquals(GuestWidth.min(pro), GuestWidth.clamp(pro, 100))
        assertEquals("520 px", GuestWidth.label(pro, 520))
    }

    @Test fun `a narrower app stands centred right of the lenses`() {
        assertEquals(GuestShape.Bounds(296, 0, 904, 572), GuestWidth.area(pro, null))
        // 608 - 480 = 128 to share: 64 each side.
        assertEquals(GuestShape.Bounds(360, 0, 840, 572), GuestWidth.area(pro, 480))
    }

    @Test fun `the slider runs from the least to the whole width`() {
        assertEquals(0f, GuestWidth.fraction(pro, GuestWidth.min(pro)), 0.001f)
        assertEquals(1f, GuestWidth.fraction(pro, null), 0.001f)
        assertEquals(GuestWidth.min(pro), GuestWidth.at(pro, 0f))
        assertNull(GuestWidth.at(pro, 1f))
        assertEquals(464, GuestWidth.at(pro, 0.5f))
        for (w in GuestWidth.min(pro) until 608 step GuestWidth.STEP) {
            assertEquals(w, GuestWidth.at(pro, GuestWidth.fraction(pro, w)))
        }
    }
}

class GuestWidthOtherRearsTest {
    @Test fun `the narrowest follows the rear's own height`() {
        org.junit.Assert.assertEquals(320, GuestWidth.min(RearGeometry.PRO17))
        // A taller rear: its own 9:16, in steps.
        org.junit.Assert.assertEquals(336, GuestWidth.min(RearGeometry(976, 596, 296, 110, 450)))
    }

    @Test fun `round keys stay round where panel units are stretched unevenly`() {
        val proMax = RearGeometry(976, 596, 296, 110, 450)
        val aspect = proMax.fitY / proMax.fitX
        val k = PortalSheet.keypad(proMax.cutoutUnits, aspect)
        val key = k.keys[0]
        // On the glass: width × fitX equals height × fitY.
        org.junit.Assert.assertEquals(key.height * proMax.fitY, key.width * proMax.fitX, 0.01f)
    }
}
