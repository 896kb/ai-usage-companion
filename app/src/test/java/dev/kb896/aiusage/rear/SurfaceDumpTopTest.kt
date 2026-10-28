package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SurfaceDumpTopTest {

    private val rear = "4630946949513469332"
    private val main = "4630946949513469331"
    private val rule = "-".repeat(80)

    private fun section(id: String, vararg layers: String) = buildString {
        appendLine("Display $id HWC layers (top to bottom):")
        appendLine(rule)
        appendLine(" Layer name")
        appendLine("           Z |  Window Type |  Layer Class | Comp Type |  Transform |   Disp Frame (LTRB)")
        appendLine(rule)
        for (layer in layers) {
            appendLine(" $layer")
            appendLine("          53 |         2027 |            0 |     DEVICE |          0 |    0    0   93  904")
        }
    }

    private fun isRear(id: String) = id == rear

    @Test fun `the first app layer on the rear, past Xiaomi's gesture strips and our frame`() {
        val dump = section(main, "VRI-com.miui.home/com.miui.home.launcher.Launcher#2") +
            section(rear, "portal-frame#99", "VRI-SubScreenGestureHome#867", "VRI-SubScreenGestureBack#866",
                "VRI-org.mozilla.firefox/org.mozilla.firefox.App#17725",
                "VRI-dev.kb896.aiusage/dev.kb896.aiusage.rear.RearActivity#16936")
        assertEquals("org.mozilla.firefox", SurfaceDump.topPackage(dump, ::isRear))
    }

    @Test fun `our panel on top reads as ours`() {
        val dump = section(rear, "VRI-SubScreenGestureHome#867", "VRI-dev.kb896.aiusage/dev.kb896.aiusage.rear.RearActivity#17891")
        assertEquals("dev.kb896.aiusage", SurfaceDump.topPackage(dump, ::isRear))
    }

    @Test fun `a dark rear has nobody on top`() {
        assertNull(SurfaceDump.topPackage(section(main, "VRI-a/b#1"), ::isRear))
    }
}
