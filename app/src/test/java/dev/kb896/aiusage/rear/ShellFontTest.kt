package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShellFontTest {

    private val never: () -> String = { error("pm path asked for no reason") }

    @Test fun `the context's code path first`() {
        assertEquals("/data/app/x/base.apk", ShellFont.apkPath("/data/app/x/base.apk", "/other.apk", never))
    }

    @Test fun `else the first apk on the class path`() {
        assertEquals("/data/app/y/base.apk", ShellFont.apkPath(null, "/system/framework/a.jar:/data/app/y/base.apk:/z.apk", never))
    }

    @Test fun `else pm path, the base apk over a split`() {
        val pm = "package:/data/app/~~a/dev.kb896.aiusage.dev-1/split_config.arm64_v8a.apk\npackage:/data/app/~~a/dev.kb896.aiusage.dev-1/base.apk\n"
        assertEquals("/data/app/~~a/dev.kb896.aiusage.dev-1/base.apk", ShellFont.apkPath(null, "", { pm }))
    }

    @Test fun `nothing anywhere is null`() {
        assertNull(ShellFont.apkPath(null, null, { "" }))
    }
}
