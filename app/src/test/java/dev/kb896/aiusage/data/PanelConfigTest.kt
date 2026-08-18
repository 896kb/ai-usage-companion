package dev.kb896.aiusage.data

import dev.kb896.aiusage.Panel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PanelConfigTest {

    /** The whole safety argument for putting this in a live payload. */
    @Test fun `defaults reproduce what was previously compiled in`() {
        val d = PanelConfig.DEFAULT
        assertEquals("CLAUDE USAGE", d.title)
        assertEquals(Panel.Palette.BG, d.background)
        assertEquals(Panel.RAMP_STOPS.map { it.second }, d.rampColors)
        assertEquals(RingWindow.SESSION, d.rings[0].window)
        assertEquals(RingMetric.TIME_TO_RESET, d.rings[0].metric)
        // the measured ring geometry, previously constants in RingGeometry
        assertEquals(RingLayout(cx = 150, cyFirst = 144, cySecond = 420, radius = 128, stroke = 25),
            d.ringLayout)
    }

    @Test fun `round trips`() {
        val c = PanelConfig.DEFAULT.copy(
            title = "MY USAGE",
            background = 0xFF102030.toInt(),
            ringLayout = RingLayout(cx = 160, cyFirst = 150, cySecond = 430, radius = 120, stroke = 20),
            showClock = true,
        )
        assertEquals(c, PanelConfig.decode(PanelConfig.encode(c)))
    }

    /** A config written by an older build has fewer keys; it must not lose the rest. */
    @Test fun `missing fields take defaults`() {
        val c = PanelConfig.decode("""{"title":"ONLY TITLE"}""")
        assertEquals("ONLY TITLE", c.title)
        assertEquals(PanelConfig.DEFAULT.background, c.background)
        assertEquals(PanelConfig.DEFAULT.ringLayout, c.ringLayout)
    }

    /**
     * A payload from a NEWER build reaches an older :rear process during an install.
     * Throwing there would blank the panel until the next respawn.
     */
    @Test fun `unknown fields are ignored`() {
        val c = PanelConfig.decode("""{"title":"T","somethingFromTheFuture":42}""")
        assertEquals("T", c.title)
    }

    @Test fun `garbage decodes to defaults rather than throwing`() {
        assertEquals(PanelConfig.DEFAULT, PanelConfig.decode("not json"))
        assertEquals(PanelConfig.DEFAULT, PanelConfig.decode(null))
        assertEquals(PanelConfig.DEFAULT, PanelConfig.decode(""))
    }

    @Test fun `a changed field really is different`() {
        assertNotEquals(PanelConfig.DEFAULT, PanelConfig.DEFAULT.copy(title = "X"))
        assertNotEquals(PanelConfig.DEFAULT,
            PanelConfig.DEFAULT.copy(ringLayout = RingLayout(cx = 151)))
    }

    /** Rings are per account and there is no primary/secondary anywhere in the schema. */
    @Test fun `rings are addressed by account index`() {
        val d = PanelConfig.DEFAULT
        assertEquals(0, d.rings[0].account)
        assertEquals(1, d.rings[1].account)
    }

    // ---- per-account appearance ------------------------------------------------------

    /**
     * null means "use the built-in", never "blank". Storing the resolved value at first
     * write would freeze today's defaults into every config ever saved, and a later
     * change to a default would reach nobody.
     */
    @Test fun `an unset style leaves every field null`() {
        val d = PanelConfig.DEFAULT.styleFor(0)
        assertEquals(null, d.label)
        assertEquals(null, d.accent)
        assertEquals(null, d.mascotBody)
        assertEquals(null, d.mascotAccent)
    }

    /** A short or absent list must behave exactly like an unset entry, not throw. */
    @Test fun `a missing entry is the same as an unset one`() {
        val c = PanelConfig.DEFAULT.copy(accounts = emptyList())
        assertEquals(AccountStyle(), c.styleFor(0))
        assertEquals(AccountStyle(), c.styleFor(7))
    }

    @Test fun `styles round trip`() {
        val c = PanelConfig.DEFAULT.copy(accounts = listOf(
            AccountStyle(label = "HOME", accent = 0xFF00FF00.toInt()),
            AccountStyle(mascotBody = 0xFF123456.toInt(), mascotAccent = 0xFF654321.toInt()),
        ))
        val back = PanelConfig.decode(PanelConfig.encode(c))
        assertEquals("HOME", back.styleFor(0).label)
        assertEquals(0xFF123456.toInt(), back.styleFor(1).mascotBody)
        assertEquals(null, back.styleFor(0).mascotBody)
    }

    // ---- bar order -------------------------------------------------------------------

    /** The order is a WHITELIST: a kind left out is hidden, not sorted last. */
    @Test fun `bar order defaults to all three, in reading order`() {
        assertEquals(listOf("session", "weekly_all", "weekly_scoped"), PanelConfig.DEFAULT.barOrder)
    }

    // ---- editing short lists ---------------------------------------------------------

    /**
     * A stored config can be shorter than the number of accounts — written when there
     * were fewer, or by hand. Editing past the end must GROW the list; a plain map would
     * silently do nothing and the setting would appear not to work.
     */
    @Test fun `editing a style past the end grows the list`() {
        val c = PanelConfig.DEFAULT.copy(accounts = listOf(AccountStyle()))
        val grown = c.withStyle(1) { it.copy(label = "SECOND") }
        assertEquals(2, grown.size)
        assertEquals("SECOND", grown[1].label)
        assertEquals(null, grown[0].label)
    }

    @Test fun `editing a ring past the end grows the list`() {
        val c = PanelConfig.DEFAULT.copy(rings = listOf(RingSpec(account = 0)))
        val grown = c.withRing(1) { it.copy(metric = RingMetric.UTILIZATION) }
        assertEquals(2, grown.size)
        assertEquals(RingMetric.UTILIZATION, grown[1].metric)
        // a grown ring defaults to its own index rather than to account 0, so two rings
        // do not silently report on the same account
        assertEquals(1, grown[1].account)
    }

    @Test fun `editing an existing entry leaves its neighbours alone`() {
        val c = PanelConfig.DEFAULT.copy(accounts = listOf(
            AccountStyle(label = "A"), AccountStyle(label = "B")))
        val edited = c.withStyle(0) { it.copy(accent = 1) }
        assertEquals("B", edited[1].label)
        assertEquals(1, edited[0].accent)
        assertEquals("A", edited[0].label)
    }
}
