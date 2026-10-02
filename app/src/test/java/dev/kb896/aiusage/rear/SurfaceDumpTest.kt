package dev.kb896.aiusage.rear

import dev.kb896.aiusage.rear.SurfaceDump.Where
import org.junit.Assert.assertEquals
import org.junit.Test

class SurfaceDumpTest {

    // Physical display ids as SurfaceFlinger prints them. The 17 Pro's rear id is the one
    // measured on the phone; the Pro Max's two are invented, and differ from the Pro's
    // where it matters — the rear one does not end in 332.
    private val proMain = "4619827259835644672"
    private val proRear = "4630946949513469332"
    private val maxMain = "4619827260271452928"
    private val maxRear = "4630947037102180609"

    private val rule = "-".repeat(80)

    private fun section(id: String, active: Boolean, vararg layers: String) = buildString {
        appendLine("Display $id (${if (active) "active" else "inactive"}) HWC layers:")
        appendLine(rule)
        appendLine(" Layer name")
        appendLine("           Z |  Window Type |  Comp Type |  Transform |   Disp Frame (LTRB)")
        appendLine(rule)
        for (layer in layers) {
            appendLine(" $layer")
            appendLine("  rel      0 |            1 |     DEVICE |          0 |    0    0  976  596")
            appendLine(rule)
        }
    }

    private val panel = "dev.kb896.aiusage/dev.kb[...]rear.RearActivity#1187"
    private val launcher = "com.miui.home/com.miui.home.launcher.Launcher#1043"
    private val clock = "com.xiaomi.subscreencenter/[...]SubScreenLauncher#902"

    @Test fun `a panel on a Pro Max's rear display is on the rear`() {
        val dump = section(maxMain, true, launcher) + section(maxRear, true, panel)
        assertEquals(Where.REAR, SurfaceDump.where(dump) { it == maxRear })
    }

    @Test fun `a panel on a 17 Pro's rear display is on the rear`() {
        val dump = section(proMain, true, launcher) + section(proRear, true, panel)
        assertEquals(Where.REAR, SurfaceDump.where(dump) { it == proRear })
    }

    @Test fun `a panel composited on the front display is on the main`() {
        val dump = section(maxMain, true, launcher, panel) + section(maxRear, true)
        assertEquals(Where.MAIN, SurfaceDump.where(dump) { it == maxRear })
    }

    @Test fun `listed under both displays, the main wins`() {
        val dump = section(maxMain, true, panel) + section(maxRear, true, panel)
        assertEquals(Where.MAIN, SurfaceDump.where(dump) { it == maxRear })
    }

    @Test fun `someone else on a lit rear display has displaced the panel`() {
        val dump = section(maxMain, true, launcher) + section(maxRear, true, clock)
        assertEquals(Where.DISPLACED, SurfaceDump.where(dump) { it == maxRear })
    }

    @Test fun `someone else on a dark rear display is the panel asleep`() {
        val dump = section(maxMain, true, launcher) + section(maxRear, false, clock)
        assertEquals(Where.NOWHERE, SurfaceDump.where(dump) { it == maxRear })
    }

    @Test fun `an empty rear display is the panel asleep`() {
        val dump = section(maxMain, true, launcher) + section(maxRear, true)
        assertEquals(Where.NOWHERE, SurfaceDump.where(dump) { it == maxRear })
    }

    @Test fun `a dump with no HWC sections is nowhere`() {
        assertEquals(Where.NOWHERE, SurfaceDump.where("") { it == maxRear })
        assertEquals(Where.NOWHERE, SurfaceDump.where("SurfaceFlinger is busy") { it == maxRear })
    }
}
