package dev.kb896.aiusage.ui.glass

import dev.kb896.aiusage.rear.GlassLuminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SilkCellsTest {
    @Test fun `the silk's brightness has a cell per square, never brighter than its colour`() {
        val cells = Silk.cells(GlassLuminance.SIDE)
        assertEquals(GlassLuminance.SIDE * GlassLuminance.SIDE, cells.size)
        val brightest = GlassLuminance.of(Silk.COLOR)
        assertTrue(cells.all { it in 0f..brightest })
    }

    @Test fun `the folds show in it — the cells are not all one value`() {
        val cells = Silk.cells(GlassLuminance.SIDE)
        assertTrue((cells.maxOrNull()!! - cells.minOrNull()!!) > 0.05f)
    }
}
