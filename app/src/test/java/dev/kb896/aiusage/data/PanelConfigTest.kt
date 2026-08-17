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
}
