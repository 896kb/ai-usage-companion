package dev.kb896.aiusage.data

import dev.kb896.aiusage.Panel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelConfigColoursTest {

    @Test fun `each colour once, opaque`() {
        val colours = PanelConfig().usedColours()
        assertEquals(colours.size, colours.distinct().size)
        assertTrue(colours.all { it ushr 24 == 0xFF })
    }

    @Test fun `the panel's own colours are in it`() {
        val colours = PanelConfig().usedColours()
        assertTrue(Panel.Palette.BG or (0xFF shl 24) in colours)
        assertTrue(Panel.Palette.WORK or (0xFF shl 24) in colours)
        assertTrue(Panel.Palette.PERSONAL or (0xFF shl 24) in colours)
    }

    @Test fun `a colour someone chose joins it`() {
        val pink = 0xFFFF66CC.toInt()
        assertTrue(pink in PanelConfig(clockColor = pink).usedColours())
        assertTrue(pink in PanelConfig(accounts = listOf(AccountStyle(mascotBody = pink))).usedColours())
    }

    @Test fun `the background leads`() {
        assertEquals(0xFF102030.toInt(), PanelConfig(background = 0xFF102030.toInt()).usedColours().first())
    }
}
