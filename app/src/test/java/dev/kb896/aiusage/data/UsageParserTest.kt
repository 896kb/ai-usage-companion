package dev.kb896.aiusage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageParserTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()

    private val personal by lazy { UsageParser.parse(fixture("usage-personal.json"), fetchedAt = 1_000L) }
    private val work by lazy { UsageParser.parse(fixture("usage-work.json"), fetchedAt = 2_000L) }

    @Test fun `reads all three windows in order`() {
        assertEquals(
            listOf(WindowKind.SESSION, WindowKind.WEEKLY_ALL, WindowKind.WEEKLY_SCOPED),
            personal.windows.map { it.kind },
        )
    }

    @Test fun `converts percent 0-100 to a 0-1 fraction`() {
        // fixture reports percent: 33 for the session window
        assertEquals(0.33f, personal.windows.first { it.kind == WindowKind.SESSION }.utilization, 0.0001f)
        assertEquals(0.19f, personal.windows.first { it.kind == WindowKind.WEEKLY_ALL }.utilization, 0.0001f)
    }

    @Test fun `parses ISO-8601 resets_at into epoch millis`() {
        val session = personal.windows.first { it.kind == WindowKind.SESSION }
        // 2026-08-14T10:49:59.932026+00:00 — verified against the fixture
        assertEquals(1786704599932L, session.resetsAt)
    }

    @Test fun `takes the scoped label from scope model display_name`() {
        val scoped = personal.windows.first { it.kind == WindowKind.WEEKLY_SCOPED }
        assertEquals("FABLE", scoped.label)
    }

    @Test fun `session and weekly labels are fixed`() {
        assertEquals("5H", personal.windows.first { it.kind == WindowKind.SESSION }.label)
        assertEquals("7D", personal.windows.first { it.kind == WindowKind.WEEKLY_ALL }.label)
    }

    @Test fun `tolerates null resets_at on an unused window`() {
        // the work fixture sits at percent 0 with every resets_at null
        assertTrue(work.windows.isNotEmpty())
        work.windows.forEach { assertNull(it.resetsAt) }
        work.windows.forEach { assertEquals(0f, it.utilization, 0.0001f) }
    }

    @Test fun `ignores unknown limit kinds`() {
        val json = """{"limits":[
            {"kind":"session","percent":50,"resets_at":null,"scope":null},
            {"kind":"tangelo_experimental","percent":90,"resets_at":null,"scope":null}
        ]}"""
        val s = UsageParser.parse(json, 0L)
        assertEquals(1, s.windows.size)
        assertEquals(WindowKind.SESSION, s.windows[0].kind)
    }

    @Test fun `omits the scoped window when absent rather than showing zero`() {
        val json = """{"limits":[{"kind":"weekly_all","percent":12,"resets_at":null,"scope":null}]}"""
        val s = UsageParser.parse(json, 0L)
        assertEquals(1, s.windows.size)
        assertEquals(WindowKind.WEEKLY_ALL, s.windows[0].kind)
    }

    @Test fun `falls back to a generic scoped label when display_name is missing`() {
        val json = """{"limits":[{"kind":"weekly_scoped","percent":5,"resets_at":null,"scope":{"model":{}}}]}"""
        assertEquals("SCOPED", UsageParser.parse(json, 0L).windows[0].label)
    }

    @Test fun `empty limits yields an empty snapshot rather than throwing`() {
        assertEquals(0, UsageParser.parse("""{"limits":[]}""", 0L).windows.size)
        assertEquals(0, UsageParser.parse("""{}""", 0L).windows.size)
    }

    @Test fun `not-JSON-at-all yields an empty snapshot rather than throwing`() {
        assertEquals(0, UsageParser.parse("<html>503</html>", 0L).windows.size)
    }

    @Test fun `empty string yields an empty snapshot rather than throwing`() {
        assertEquals(0, UsageParser.parse("", 0L).windows.size)
    }

    @Test fun `truncated JSON yields an empty snapshot rather than throwing`() {
        assertEquals(0, UsageParser.parse("""{"limits":[{"kind":"session",""", 0L).windows.size)
    }
}
