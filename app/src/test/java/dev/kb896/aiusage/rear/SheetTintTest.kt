package dev.kb896.aiusage.rear

import dev.kb896.aiusage.data.GlassReactivity
import dev.kb896.aiusage.data.GlassStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetTintTest {
    private val n = GlassLuminance.SIDE * GlassLuminance.SIDE
    private val black = SheetTint.cells(IntArray(n) { 0xFF000000.toInt() })
    private val white = SheetTint.cells(IntArray(n) { 0xFFFFFFFF.toInt() })

    @Test fun `cells are each pixel's luminance`() {
        assertEquals(0f, black[0], 1e-6f)
        assertEquals(1f, white[n - 1], 1e-6f)
        val green = SheetTint.cells(IntArray(n) { 0xFF00FF00.toInt() })
        assertEquals(GlassLuminance.of(0xFF00FF00.toInt()), green[17], 1e-6f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a grid of the wrong size is refused`() {
        SheetTint.cells(IntArray(25))
    }

    @Test fun `clear glass is lightened over black and darkened over white, as the panel's`() {
        val overBlack = SheetTint.of(black, 10f, 10f, 100f, 60f, glass = true, style = null)
        val overWhite = SheetTint.of(white, 10f, 10f, 100f, 60f, glass = true, style = null)
        assertEquals(GlassLuminance.clearTint(0f), overBlack)
        assertEquals(GlassLuminance.clearTint(1f), overWhite)
        assertEquals(0xFFFFFF, overBlack and 0xFFFFFF)
        assertEquals(0x000000, overWhite and 0xFFFFFF)
        assertTrue(overBlack ushr 24 > 0)
    }

    @Test fun `it reads what is behind the pane, not the whole picture`() {
        // Left half black, right half white.
        val half = SheetTint.cells(IntArray(n) { if (it % GlassLuminance.SIDE < GlassLuminance.SIDE / 2) 0xFF000000.toInt() else -1 })
        assertEquals(GlassLuminance.clearTint(0f), SheetTint.of(half, 0f, 0f, 200f, 100f, true, null))
        assertEquals(GlassLuminance.clearTint(1f), SheetTint.of(half, 700f, 0f, 900f, 100f, true, null))
    }

    @Test fun `before a backdrop, clear glass stays clear`() {
        assertEquals(0, SheetTint.of(null, 0f, 0f, 100f, 100f, glass = true, style = null) ushr 24)
    }

    @Test fun `original has the built-in tint`() {
        assertEquals(Glass.DISC_TINT, SheetTint.of(white, 0f, 0f, 100f, 100f, glass = false, style = null))
    }

    @Test fun `a chosen style wins, reacting as Glass settings say`() {
        val shard = GlassStyle(0xFFFF0000.toInt(), 0.6f, GlassReactivity.SHARD)
        assertEquals(GlassTint.of(shard, 0f, 0f), SheetTint.of(black, 0f, 0f, 100f, 100f, true, shard))
        assertNotEquals(SheetTint.of(black, 0f, 0f, 100f, 100f, true, shard), SheetTint.of(white, 0f, 0f, 100f, 100f, true, shard))
        val none = GlassStyle(0xFFFF0000.toInt(), 0.6f, GlassReactivity.NONE)
        assertEquals(none.tint(), SheetTint.of(white, 0f, 0f, 100f, 100f, true, none))
        assertEquals(none.tint(), SheetTint.of(null, 0f, 0f, 100f, 100f, true, none))
    }
}
