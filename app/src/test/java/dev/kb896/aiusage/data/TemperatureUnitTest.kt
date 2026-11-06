package dev.kb896.aiusage.data

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class TemperatureUnitTest {
    @Test fun `a chosen unit stays`() {
        assertEquals("c", TemperatureUnit.resolve("c", Locale.US))
        assertEquals("f", TemperatureUnit.resolve("f", Locale.forLanguageTag("ro-RO")))
    }

    @Test fun `auto follows the region`() {
        assertEquals("f", TemperatureUnit.resolve("auto", Locale.US))
        assertEquals("c", TemperatureUnit.resolve("auto", Locale.UK))
        assertEquals("c", TemperatureUnit.resolve("auto", Locale.forLanguageTag("ro-RO")))
    }

    @Test fun `auto follows the regional preference over the region`() {
        assertEquals("c", TemperatureUnit.resolve("auto", Locale.forLanguageTag("en-US-u-mu-celsius")))
        assertEquals("f", TemperatureUnit.resolve("auto", Locale.forLanguageTag("ro-RO-u-mu-fahrenhe")))
    }

    @Test fun `auto never crosses to the back`() {
        val json = StatePayload.encode(DashboardState.EMPTY, PanelConfig.DEFAULT)
        val unit = StatePayload.decode(json).config.temperatureUnit
        assertEquals(true, unit == "c" || unit == "f")
    }
}
