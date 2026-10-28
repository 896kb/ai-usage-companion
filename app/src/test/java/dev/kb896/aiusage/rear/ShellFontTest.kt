package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellFontTest {

    private val never: () -> String = { error("pm path asked for no reason") }
    private val pm = "package:/data/app/~~a/dev.kb896.aiusage.dev-1/split_config.arm64_v8a.apk\npackage:/data/app/~~a/dev.kb896.aiusage.dev-1/base.apk\n"

    @Test fun `the context's code path first, and pm path not asked until it is needed`() {
        assertEquals("/data/app/x/base.apk", ShellFont.apkCandidates("/data/app/x/base.apk", never).first())
    }

    @Test fun `then pm path, the base apk before a split`() {
        assertEquals(
            listOf("/data/app/x/base.apk", "/data/app/~~a/dev.kb896.aiusage.dev-1/base.apk", "/data/app/~~a/dev.kb896.aiusage.dev-1/split_config.arm64_v8a.apk"),
            ShellFont.apkCandidates("/data/app/x/base.apk", { pm }).toList(),
        )
    }

    @Test fun `no code path - pm path alone, and the code path is not listed twice`() {
        assertEquals("/data/app/~~a/dev.kb896.aiusage.dev-1/base.apk", ShellFont.apkCandidates(null, { pm }).first())
        assertEquals(2, ShellFont.apkCandidates("/data/app/~~a/dev.kb896.aiusage.dev-1/base.apk", { pm }).count())
    }

    @Test fun `nothing anywhere is no candidate`() {
        assertEquals(emptyList<String>(), ShellFont.apkCandidates(null, { "" }).toList())
    }
}
