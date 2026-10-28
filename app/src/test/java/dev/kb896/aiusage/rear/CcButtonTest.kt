package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Test

class CcButtonTest {

    private val crit = dev.kb896.aiusage.Panel.Palette.CRIT and 0x00FFFFFF

    @Test fun `over glass, off has no fill and a faint mark`() {
        assertEquals(0x00FFFFFF, CcButton.glassFill(0x00FFFFFF, 0f))
        assertEquals(0x59FFFFFF, CcButton.glassMark(0f))
    }

    @Test fun `over glass, on fills to the liquid's alpha in the tint and the mark is white`() {
        assertEquals(crit or (0x66 shl 24), CcButton.glassFill(crit, 1f))
        assertEquals(0xFFFFFFFF.toInt(), CcButton.glassMark(1f))
    }

    @Test fun `flat, the fill comes in with on and the rim is always opaque`() {
        assertEquals(0x00FFFFFF, CcButton.flatFill(0x00FFFFFF, 0f))
        assertEquals(0xFFFFFFFF.toInt(), CcButton.flatFill(0x00FFFFFF, 1f))
        assertEquals(crit or (0xFF shl 24), CcButton.flatRim(crit))
        // an alpha handed in with the tint is ignored
        assertEquals(crit or (0xFF shl 24), CcButton.flatRim(crit or (0x12 shl 24)))
    }

    @Test fun `flat, the mark turns from white to the background`() {
        val bg = 0xFF1D1F21.toInt()
        assertEquals(0xFFFFFFFF.toInt(), CcButton.flatMark(bg, 0f))
        assertEquals(bg, CcButton.flatMark(bg, 1f))
    }
}
