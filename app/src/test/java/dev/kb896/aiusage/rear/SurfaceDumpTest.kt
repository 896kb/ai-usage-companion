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

    private val release = "dev.kb896.aiusage"
    private val dev = "dev.kb896.aiusage.dev"

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
        assertEquals(Where.REAR, SurfaceDump.where(dump, release) { it == maxRear })
    }

    @Test fun `a panel on a 17 Pro's rear display is on the rear`() {
        val dump = section(proMain, true, launcher) + section(proRear, true, panel)
        assertEquals(Where.REAR, SurfaceDump.where(dump, release) { it == proRear })
    }

    @Test fun `a panel composited on the front display is on the main`() {
        val dump = section(maxMain, true, launcher, panel) + section(maxRear, true)
        assertEquals(Where.MAIN, SurfaceDump.where(dump, release) { it == maxRear })
    }

    @Test fun `listed under both displays, the main wins`() {
        val dump = section(maxMain, true, panel) + section(maxRear, true, panel)
        assertEquals(Where.MAIN, SurfaceDump.where(dump, release) { it == maxRear })
    }

    @Test fun `someone else on a lit rear display has displaced the panel`() {
        val dump = section(maxMain, true, launcher) + section(maxRear, true, clock)
        assertEquals(Where.DISPLACED, SurfaceDump.where(dump, release) { it == maxRear })
    }

    @Test fun `someone else on a dark rear display is the panel asleep`() {
        val dump = section(maxMain, true, launcher) + section(maxRear, false, clock)
        assertEquals(Where.NOWHERE, SurfaceDump.where(dump, release) { it == maxRear })
    }

    @Test fun `an empty rear display is the panel asleep`() {
        val dump = section(maxMain, true, launcher) + section(maxRear, true)
        assertEquals(Where.NOWHERE, SurfaceDump.where(dump, release) { it == maxRear })
    }

    // Android 17 (HyperOS 4) heads a section "Display <id> HWC layers (top to bottom):"
    // and no longer says whether the display is active; an unlit display's section is
    // empty. Layer names gained a "VRI-" prefix. Shapes from the 17 Pro on OS4.0.4.0.
    private fun section17(id: String, vararg layers: String) = buildString {
        appendLine("Display $id HWC layers (top to bottom):")
        appendLine(rule)
        appendLine(" Layer name")
        appendLine("           Z |  Window Type |  Layer Class | Comp Type |  Transform |   Disp Frame (LTRB)")
        appendLine(rule)
        for (layer in layers) {
            appendLine(" $layer")
            appendLine("          17 |            1 |            0 |     DEVICE |          0 |    0    0  904  572")
            appendLine(rule)
        }
        appendLine()
    }

    private val panel17 = "VRI-dev.kb896.aiusage/dev.kb896.aiusage.rear.RearActivity#3105"
    private val gestures17 = arrayOf("VRI-SubScreenGestureHome#525", "VRI-SubScreenGestureBack#524")
    private val settings17 = "VRI-com.android.settings/com.android.settings.MainSettings#3097"

    @Test fun `android 17 - the panel on the rear display is on the rear`() {
        val dump = section17(proMain, settings17) + section17(proRear, *gestures17, panel17)
        assertEquals(Where.REAR, SurfaceDump.where(dump, release) { it == proRear })
    }

    @Test fun `android 17 - the panel on the front display is on the main`() {
        val dump = section17(proMain, panel17, settings17) + section17(proRear)
        assertEquals(Where.MAIN, SurfaceDump.where(dump, release) { it == proRear })
    }

    @Test fun `android 17 - a lit rear display without the panel has it displaced`() {
        val dump = section17(proMain, settings17) + section17(proRear, *gestures17)
        assertEquals(Where.DISPLACED, SurfaceDump.where(dump, release) { it == proRear })
    }

    @Test fun `android 17 - an unlit rear display, listing nothing, is the panel asleep`() {
        val dump = section17(proMain, settings17) + section17(proRear)
        assertEquals(Where.NOWHERE, SurfaceDump.where(dump, release) { it == proRear })
    }

    @Test fun `a dump with no HWC sections is nowhere`() {
        assertEquals(Where.NOWHERE, SurfaceDump.where("", release) { it == maxRear })
        assertEquals(Where.NOWHERE, SurfaceDump.where("SurfaceFlinger is busy", release) { it == maxRear })
    }

    // -- the release and the dev build, both installed -----------------------------------

    // As SurfaceFlinger shortens it on Android 16: the package and its slash survive.
    private val devPanel = "dev.kb896.aiusage.dev/de[...]rear.RearActivity#1190"
    private val devPanel17 = "VRI-dev.kb896.aiusage.dev/dev.kb896.aiusage.rear.RearActivity#3110"

    @Test fun `the dev build's panel on the rear is the dev build's, and displaces the release`() {
        val dump = section(proMain, true, launcher) + section(proRear, true, devPanel)
        assertEquals(Where.REAR, SurfaceDump.where(dump, dev) { it == proRear })
        assertEquals(Where.DISPLACED, SurfaceDump.where(dump, release) { it == proRear })
    }

    @Test fun `the release's panel on the rear displaces the dev build`() {
        val dump = section(proMain, true, launcher) + section(proRear, true, panel)
        assertEquals(Where.REAR, SurfaceDump.where(dump, release) { it == proRear })
        assertEquals(Where.DISPLACED, SurfaceDump.where(dump, dev) { it == proRear })
    }

    @Test fun `the other build's panel on the front is not ours on the main`() {
        val dump = section(proMain, true, launcher, devPanel) + section(proRear, true, panel)
        assertEquals(Where.REAR, SurfaceDump.where(dump, release) { it == proRear })
    }

    @Test fun `android 17 - each build reads only its own panel`() {
        val dump = section17(proMain, settings17) + section17(proRear, *gestures17, devPanel17, panel17)
        assertEquals(Where.REAR, SurfaceDump.where(dump, dev) { it == proRear })
        assertEquals(Where.REAR, SurfaceDump.where(dump, release) { it == proRear })
        val devOnly = section17(proMain, settings17) + section17(proRear, *gestures17, devPanel17)
        assertEquals(Where.DISPLACED, SurfaceDump.where(devOnly, release) { it == proRear })
    }
}
