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

    @Test fun `twelve apps with the strip all fit on the panel`() {
        PortalSheet.cells(12, withStrip = true).forEach {
            assertEquals(true, it.right <= dev.kb896.aiusage.Panel.WIDTH)
            assertEquals(true, it.bottom <= dev.kb896.aiusage.Panel.HEIGHT)
        }
    }

    @Test fun `more than twelve apps are capped`() {
        assertEquals(12, PortalSheet.cells(15, withStrip = true).size)
    }

    @Test fun `with the strip, a tapped app is still that app`() {
        val c = PortalSheet.cells(7, withStrip = true)[5]
        assertEquals(Hit.App(5), PortalSheet.hit(c.centerX, c.centerY, 7, withStrip = true))
    }

    @Test fun `the gap between cells is outside`() {
        val c = PortalSheet.cells(7, withStrip = false)[0]
        assertEquals(Hit.Outside, PortalSheet.hit(c.right + 6f, c.centerY, 7, withStrip = false))
    }

    @Test fun `without the strip the buttons' area is not a button`() {
        val b = PortalSheet.buttons()[1]
        val h = PortalSheet.hit(b.centerX, b.centerY, 7, withStrip = false)
        assertEquals(false, h is Hit.Button)
    }

    @Test fun `the sheet slides up as it opens, and stands in place when open`() {
        assertEquals(dev.kb896.aiusage.Panel.HEIGHT * 0.4f, PortalSheet.slide(0f), 0.01f)
        assertEquals(0f, PortalSheet.slide(1f), 0.01f)
        assertEquals(true, PortalSheet.slide(0.5f) < PortalSheet.slide(0.25f))
    }

    @Test fun `an empty sheet's words stand in the middle of the full grid`() {
        val row = PortalSheet.cells(4, withStrip = false)
        assertEquals((row.first().left + row.last().right) / 2f, PortalSheet.gridCenterX, 0.01f)
    }
}
