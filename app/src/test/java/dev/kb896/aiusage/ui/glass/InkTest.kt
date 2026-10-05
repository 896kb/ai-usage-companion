package dev.kb896.aiusage.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Test

class InkTest {
    /** Text is white whatever is behind it — it no longer flips to black over light cells. */
    @Test fun `ink is white over a light cell and a dark one`() {
        assertEquals(0xFFFFFFFF.toInt(), Ink.primary(0.8f))
        assertEquals(0xFFFFFFFF.toInt(), Ink.primary(0.2f))
        assertEquals(0xFFFFFFFF.toInt(), Ink.primary(0.5f))
    }

    @Test fun `secondary ink is white at sixty percent, light cell or dark`() {
        assertEquals(0x99FFFFFF.toInt(), Ink.secondary(0.9f))
        assertEquals(0x99FFFFFF.toInt(), Ink.secondary(0.1f))
    }

    @Test fun `the catalog tokens follow the cell`() {
        assertEquals(0xFF34C759.toInt(), Ink.on(0.7f))
        assertEquals(0xFF30D158.toInt(), Ink.on(0.3f))
        assertEquals(0xFF0088FF.toInt(), Ink.accent(0.7f))
        assertEquals(0xFF0091FF.toInt(), Ink.accent(0.3f))
        assertEquals(0x33787878, Ink.track(0.7f))
        assertEquals(0x5C787880, Ink.track(0.3f))
        assertEquals(0.23f, Ink.scrimAlpha(0.7f), 0.001f)
        assertEquals(0.56f, Ink.scrimAlpha(0.3f), 0.001f)
    }

    @Test fun `mix moves each channel toward the target by t`() {
        // 0x1D1F21 toward white by 0.08: 0x1D=29 -> 29 + (255-29)*0.08 = 47.08 -> 47; B: 33 + 222*0.08 = 50.76 -> 51
        assertEquals(0xFF2F3133.toInt(), Ink.mix(0xFF1D1F21.toInt(), 0xFFFFFFFF.toInt(), 0.08f))
        // toward black by 0.12: 29 * 0.88 = 25.52 -> 26 (rounded)
        assertEquals(0xFF1A1B1D.toInt(), Ink.mix(0xFF1D1F21.toInt(), 0xFF000000.toInt(), 0.12f))
    }
}
