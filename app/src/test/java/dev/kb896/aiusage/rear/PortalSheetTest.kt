package dev.kb896.aiusage.rear

import dev.kb896.aiusage.rear.PortalSheet.Hit
import org.junit.Assert.assertEquals
import org.junit.Test

class PortalSheetTest {

    private val apps = listOf("a", "b", "c", "d", "e", "f", "g")

    @Test fun `the grid fills rows of four right of the lenses`() {
        val cells = PortalSheet.cells(apps.size, withStrip = false)
        assertEquals(7, cells.size)
        assertEquals(cells[0].top, cells[3].top, 0.01f)
        assertEquals(true, cells[4].top > cells[0].top)
        cells.forEach { assertEquals(true, it.left >= dev.kb896.aiusage.Panel.CUTOUT_RIGHT) }
    }

    @Test fun `a tap on a cell is that app`() {
        val c = PortalSheet.cells(apps.size, withStrip = false)[5]
        assertEquals(Hit.App(5), PortalSheet.hit(c.centerX, c.centerY, apps.size, withStrip = false))
    }

    @Test fun `with a guest, the strip's three buttons sit above the grid`() {
        val b = PortalSheet.buttons()
        assertEquals(3, b.size)
        val cellTop = PortalSheet.cells(apps.size, withStrip = true)[0].top
        b.forEach { assertEquals(true, it.bottom <= cellTop) }
        assertEquals(Hit.Button(2), PortalSheet.hit(b[2].centerX, b[2].centerY, apps.size, withStrip = true))
    }

    @Test fun `anywhere else is outside`() {
        assertEquals(Hit.Outside, PortalSheet.hit(100f, 300f, apps.size, withStrip = true))
    }
}
