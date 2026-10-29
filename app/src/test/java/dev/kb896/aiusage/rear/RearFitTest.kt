package dev.kb896.aiusage.rear

import dev.kb896.aiusage.data.PortalGrid
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shell's two spaces meet where a finger does: the sheet is laid out in panel units and
 * drawn stretched by the rear's fit (ShellOverlay), the touchscreen is read straight into
 * panel units (RearTouches). So a finger on what is drawn hits what is laid out there — on
 * the 17 Pro, and on a Pro Max, whose 976x596 stretches the panel unevenly.
 */
// The control centre's layout is RectFs, which the plain JVM stub throws "not mocked" for.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RearFitTest {

    private val rears = listOf(RearGeometry.PRO17, RearGeometry(976, 596, 296, 110, 450))

    private fun hex(v: Int) = "%08x".format(v)

    /**
     * A finger at real pixel ([x], [y]) on [rear]'s touchscreen — the 17 Pro's measures its
     * pixels times 100, and a Pro Max's is taken to do the same — as the shell reads it.
     */
    private fun finger(rear: RearGeometry, x: Float, y: Float): RearTouches.Event {
        val t = RearTouches(rear.widthPx * 100 - 1, rear.heightPx * 100 - 1)
        return listOf(
            "0003 0039 00000001", "0003 0035 ${hex((x * 100).toInt())}", "0003 0036 ${hex((y * 100).toInt())}", "0000 0000 00000000",
        ).flatMap { t.feed(it, 0L) }.single()
    }

    @Test fun `a finger on a drawn cell or button hits it, on both rears`() {
        for (rear in rears) {
            val l = PortalSheet.layout(PortalGrid.R3C4, 12, guestOnTop = true)
            val drawn = l.slots.mapIndexed { i, c -> c to PortalSheet.Hit.App(i) } +
                l.buttons.mapIndexed { i, b -> b to PortalSheet.Hit.Button(i) }
            for ((box, want) in drawn) {
                // Drawn at the box's centre in panel units, so at that times the fit on the glass.
                val e = finger(rear, box.centerX * rear.fitX, box.centerY * rear.fitY)
                assertEquals("$rear: $want", want, PortalSheet.hit(l, e.x, e.y, scroll = 0f, page = 0))
                // And near its right edge, where an unscaled drawing would have been furthest off.
                val r = finger(rear, (box.right - 4f) * rear.fitX, box.centerY * rear.fitY)
                assertEquals("$rear: $want at its right edge", want, PortalSheet.hit(l, r.x, r.y, scroll = 0f, page = 0))
            }
        }
    }

    @Test fun `the control centre's pieces too`() {
        for (rear in rears) {
            val pieces = (0 until 4).map { ControlCenterLayout.tileRect(it, 1f) to it } +
                listOf(ControlCenterLayout.cellRect(1f) to ControlCenterLayout.CELL, ControlCenterLayout.autoRect(1f) to ControlCenterLayout.AUTO)
            for ((rect, want) in pieces) {
                val e = finger(rear, rect.centerX() * rear.fitX, rect.centerY() * rear.fitY)
                assertEquals("$rear: $want", want, ControlCenterLayout.hit(e.x, e.y))
            }
        }
    }

    @Test fun `the display's far corner is the panel's`() {
        for (rear in rears) {
            val e = finger(rear, rear.widthPx - 0.5f, rear.heightPx - 0.5f)
            assertEquals(904f, e.x, 1f)
            assertEquals(572f, e.y, 1f)
        }
    }
}
