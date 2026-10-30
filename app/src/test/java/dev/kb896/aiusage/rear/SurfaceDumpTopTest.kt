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

    private val wallet = "com.google.android.apps.walletnfcrel"

    @Test fun `a name truncated before its slash is resolved against the known packages`() {
        val dump = section(rear, "VRI-com.google.android.apps.wal[...]WalletActivity#12", "VRI-org.mozilla.firefox/x.App#3")
        assertEquals(wallet, SurfaceDump.topPackage(dump, ::isRear, known = listOf("a.b", wallet)))
    }

    @Test fun `an unknown truncated name stays a prefix, not the next app down`() {
        val dump = section(rear, "VRI-com.google.android.apps.wal[...]WalletActivity#12", "VRI-org.mozilla.firefox/x.App#3")
        assertEquals("com.google.android.apps.wal[...]", SurfaceDump.topPackage(dump, ::isRear))
    }

    @Test fun `a truncated name that kept its slash still gives the package`() {
        val dump = section(rear, "VRI-dev.kb896.aiusage/dev.kb[...]rear.RearActivity#1187")
        assertEquals("dev.kb896.aiusage", SurfaceDump.topPackage(dump, ::isRear))
    }

    @Test fun `the real OS 4_0_5 dump has our panel on top of the rear`() {
        val dump = javaClass.getResource("/surfaceflinger-rear-panel-os4.0.5.txt")!!.readText()
        assertEquals("dev.kb896.aiusage", SurfaceDump.topPackage(dump, ::isRear))
    }

    @Test fun `windows listed after the rear table are not the rear's`() {
        val dump = section(rear, "VRI-SubScreenGestureHome#867") + "\n\nWindow Infos:\n VRI-x.y/z#1\n"
        assertNull(SurfaceDump.topPackage(dump, ::isRear))
    }

    @Test fun `CRLF line endings still bound the table`() {
        val dump = section(rear, "VRI-SubScreenGestureHome#867").replace("\n", "\r\n") +
            "\r\n\r\nWindow Infos:\r\n VRI-x.y/z#1\r\n"
        assertNull(SurfaceDump.topPackage(dump, ::isRear))
    }

    @Test fun `the older active header format works too`() {
        val dump = "Display $rear (active) HWC layers:\n$rule\n VRI-org.mozilla.firefox/x.App#3\n$rule\n"
        assertEquals("org.mozilla.firefox", SurfaceDump.topPackage(dump, ::isRear))
    }

    @Test fun `the dev build's panel on top reads as the dev build, not the release`() {
        val dump = section(rear, "VRI-SubScreenGestureHome#867",
            "VRI-dev.kb896.aiusage.dev/dev.kb896.aiusage.rear.RearActivity#17900",
            "VRI-dev.kb896.aiusage/dev.kb896.aiusage.rear.RearActivity#17891")
        assertEquals("dev.kb896.aiusage.dev", SurfaceDump.topPackage(dump, ::isRear))
    }

    @Test fun `a truncated dev build name keeps its package`() {
        val dump = section(rear, "VRI-dev.kb896.aiusage.dev/dev.k[...]rear.RearActivity#1190")
        assertEquals("dev.kb896.aiusage.dev", SurfaceDump.topPackage(dump, ::isRear, known = listOf("dev.kb896.aiusage")))
    }
}
