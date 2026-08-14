package dev.kb896.aiusage.rear

import dev.kb896.aiusage.Panel
import org.junit.Assert.assertEquals
import org.junit.Test

class BarColorTest {

    private val accent = Panel.Palette.PERSONAL

    @Test fun `below 75 percent uses the account accent`() {
        assertEquals(accent, RearDialView.barColor(0.00f, accent))
        assertEquals(accent, RearDialView.barColor(0.74f, accent))
    }

    @Test fun `at or above 75 percent goes amber`() {
        assertEquals(Panel.Palette.WARN, RearDialView.barColor(0.75f, accent))
        assertEquals(Panel.Palette.WARN, RearDialView.barColor(0.89f, accent))
    }

    @Test fun `at or above 90 percent goes red`() {
        assertEquals(Panel.Palette.CRIT, RearDialView.barColor(0.90f, accent))
        assertEquals(Panel.Palette.CRIT, RearDialView.barColor(1.00f, accent))
    }
}
