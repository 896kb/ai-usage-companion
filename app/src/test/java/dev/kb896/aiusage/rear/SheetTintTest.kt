package dev.kb896.aiusage.rear

import dev.kb896.aiusage.data.GlassReactivity
import dev.kb896.aiusage.data.GlassStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class SheetTintTest {

    @Test fun `clear glass stays clear over the middling ground`() {
        assertEquals(GlassLuminance.clearTint(SheetTint.MIDDLING), SheetTint.of(glass = true, style = null))
        assertEquals(0, SheetTint.of(glass = true, style = null) ushr 24)
    }

    @Test fun `original has the built-in tint`() {
        assertEquals(Glass.DISC_TINT, SheetTint.of(glass = false, style = null))
    }

    @Test fun `a chosen style wins, as Glass settings tint it over the middling ground`() {
        val shard = GlassStyle(0xFFFF0000.toInt(), 0.6f, GlassReactivity.SHARD)
        assertEquals(GlassTint.of(shard, SheetTint.MIDDLING, SheetTint.MIDDLING), SheetTint.of(true, shard))
        val none = GlassStyle(0xFFFF0000.toInt(), 0.6f, GlassReactivity.NONE)
        assertEquals(none.tint(), SheetTint.of(true, none))
    }
}
