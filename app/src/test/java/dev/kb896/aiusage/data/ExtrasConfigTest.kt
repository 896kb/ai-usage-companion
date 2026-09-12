package dev.kb896.aiusage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The per-extra colours, and the fallback that keeps older configs looking the same.
 *
 * These were one shared `extrasColor` until the panel had three extras on it, at which
 * point the name stopped describing anything. The overrides are nullable specifically so
 * a config saved before they existed keeps whatever colour it was given rather than
 * silently reverting to the default green — which is the kind of regression nobody
 * reports, they just quietly retune it.
 */
class ExtrasConfigTest {

    @Test fun `an unset extra falls back to the shared colour`() {
        val c = PanelConfig(extrasColor = 0xFF112233.toInt())
        assertEquals(0xFF112233.toInt(), c.clockTint)
        assertEquals(0xFF112233.toInt(), c.batteryTint)
        assertEquals(0xFF112233.toInt(), c.dateTint)
    }

    @Test fun `a set extra overrides it, and only for itself`() {
        val c = PanelConfig(extrasColor = 0xFF112233.toInt(), clockColor = 0xFFAABBCC.toInt())
        assertEquals(0xFFAABBCC.toInt(), c.clockTint)
        assertEquals(0xFF112233.toInt(), c.batteryTint)
        assertEquals(0xFF112233.toInt(), c.dateTint)
    }

    /** The whole reason they are nullable: a stored config predates all three fields. */
    @Test fun `a config saved before these existed keeps its extras colour`() {
        val old = """{"title":"T","extrasColor":-8879617,"showClock":true}"""
        val c = PanelConfig.decode(old)
        assertEquals(-8879617, c.extrasColor)
        assertEquals(-8879617, c.clockTint)
        assertEquals(-8879617, c.batteryTint)
    }

    @Test fun `the new fields round trip`() {
        val c = PanelConfig(
            titleColor = 0xFF010203.toInt(),
            clockColor = 0xFF040506.toInt(),
            dateColor = 0xFF070809.toInt(),
            showDate = true,
        )
        val back = PanelConfig.decode(PanelConfig.encode(c))
        assertEquals(0xFF010203.toInt(), back.titleColor)
        assertEquals(0xFF040506.toInt(), back.clockColor)
        assertEquals(0xFF070809.toInt(), back.dateColor)
        assertEquals(true, back.showDate)
    }

    @Test fun `the weather line follows the date until given its own colour`() {
        val base = PanelConfig.DEFAULT.copy(dateColor = 0xFF112233.toInt())
        assertEquals(0xFF112233.toInt(), base.weatherTint)
        val own = base.copy(weatherColor = 0xFF445566.toInt())
        assertEquals(0xFF445566.toInt(), own.weatherTint)
        assertEquals(0xFF445566.toInt(), PanelConfig.decode(PanelConfig.encode(own)).weatherColor)
        assertNull(PanelConfig.DEFAULT.weatherColor)
    }
}
