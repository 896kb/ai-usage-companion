package dev.kb896.aiusage.rear

import dev.kb896.aiusage.Panel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where the control centre's pieces stand — the brightness cell, the automatic button under
 * it, the four tiles — as the panel has always laid them out, now shared with the shell.
 */
// RectF is one of the android.graphics types the plain JVM stub throws "not mocked" for.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ControlCenterLayoutTest {

    /** The 17 Pro's lens column, in panel units. */
    private val PRO = Panel.CUTOUT_RIGHT.toFloat()


    private val m = ChargeOverlay.MARGIN
    private val w = ChargeOverlay.width(Panel.WIDTH.toFloat())
    private val left = Panel.CUTOUT_RIGHT + m

    @Test fun `open, the cell is the charging cell's width at the left of the usable area, short of the button`() {
        val r = ControlCenterLayout.cellRect(1f, PRO)
        assertEquals(left, r.left, 0.001f)
        assertEquals(m, r.top, 0.001f)
        assertEquals(left + w, r.right, 0.001f)
        assertEquals(Panel.HEIGHT - m - w - ControlCenterLayout.BUTTON_GAP, r.bottom, 0.001f)
    }

    @Test fun `open, the automatic button is a disc as wide as the cell, at the bottom`() {
        val r = ControlCenterLayout.autoRect(1f, PRO)
        assertEquals(left, r.left, 0.001f)
        assertEquals(w, r.width(), 0.001f)
        assertEquals(w, r.height(), 0.001f)
        assertEquals(Panel.HEIGHT - m, r.bottom, 0.001f)
    }

    @Test fun `open, the tiles are two by two right of the cell, one gap apart and from every edge`() {
        val t = (0 until 4).map { ControlCenterLayout.tileRect(it, 1f, PRO) }
        assertEquals(left + w + m, t[0].left, 0.001f)
        assertEquals(m, t[0].top, 0.001f)
        assertEquals(Panel.WIDTH - m, t[1].right, 0.001f)
        assertEquals(Panel.HEIGHT - m, t[3].bottom, 0.001f)
        assertEquals(m, t[1].left - t[0].right, 0.001f)
        assertEquals(m, t[2].top - t[0].bottom, 0.001f)
        assertEquals(t[0].width(), t[3].width(), 0.001f)
        assertEquals(t[0].height(), t[3].height(), 0.001f)
    }

    @Test fun `arriving, each piece is higher by the arrival's offset and grown about its own middle`() {
        val open = ControlCenterLayout.tileRect(2, 1f, PRO)
        val half = ControlCenterLayout.tileRect(2, 0.5f, PRO)
        val drop = Arrival.offset(0.5f, Panel.HEIGHT.toFloat())
        assertTrue(drop > 0f)
        assertEquals(open.centerX(), half.centerX(), 0.001f)
        assertEquals(open.centerY() - drop, half.centerY(), 0.001f)
        assertEquals(open.width() * Arrival.GROW_FROM, half.width(), 0.001f)
        assertEquals(open.height() * Arrival.GROW_FROM, half.height(), 0.001f)
    }

    @Test fun `a finger lands on the cell, the button, a tile, or nothing`() {
        val cell = ControlCenterLayout.cellRect(1f, PRO)
        val auto = ControlCenterLayout.autoRect(1f, PRO)
        assertEquals(ControlCenterLayout.CELL, ControlCenterLayout.hit(cell.centerX(), cell.centerY(), PRO))
        assertEquals(ControlCenterLayout.AUTO, ControlCenterLayout.hit(auto.centerX(), auto.centerY(), PRO))
        for (i in 0 until 4) {
            val t = ControlCenterLayout.tileRect(i, 1f, PRO)
            assertEquals(i, ControlCenterLayout.hit(t.centerX(), t.centerY(), PRO))
        }
        // Between the tiles, and over the cameras.
        val t0 = ControlCenterLayout.tileRect(0, 1f, PRO)
        assertEquals(ControlCenterLayout.NONE, ControlCenterLayout.hit(t0.right + m / 2f, t0.centerY(), PRO))
        assertEquals(ControlCenterLayout.NONE, ControlCenterLayout.hit(100f, 100f, PRO))
    }

    @Test fun `right of the lenses it is given - a Pro Max's end further left, and everything with them`() {
        val proMax = RearGeometry(976, 596, 296, 110, 450).cutoutUnits
        val d = PRO - proMax
        assertEquals(ControlCenterLayout.cellRect(1f, PRO).left - d, ControlCenterLayout.cellRect(1f, proMax).left, 0.001f)
        assertEquals(ControlCenterLayout.autoRect(1f, PRO).left - d, ControlCenterLayout.autoRect(1f, proMax).left, 0.001f)
        assertEquals(ControlCenterLayout.tileRect(0, 1f, PRO).left - d, ControlCenterLayout.tileRect(0, 1f, proMax).left, 0.001f)
        // The tiles still end a margin short of the panel's right edge, wider by as much.
        assertEquals(ControlCenterLayout.tileRect(1, 1f, PRO).right, ControlCenterLayout.tileRect(1, 1f, proMax).right, 0.001f)
        val c = ControlCenterLayout.cellRect(1f, proMax)
        assertEquals(ControlCenterLayout.CELL, ControlCenterLayout.hit(c.centerX(), c.centerY(), proMax))
    }
}
