package dev.kb896.aiusage.data

import dev.kb896.aiusage.rear.Weather
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PanelConfigWeatherTest {
    @Test fun `the place fields round-trip`() {
        val c = PanelConfig.DEFAULT.copy(
            weather = Weather.AUTO, weatherPlace = "bucharest",
            weatherPlaceLabel = "Bucharest, Romania", weatherLat = 44.43225, weatherLon = 26.10626,
            weatherUseDevice = true,
        )
        assertEquals(c, PanelConfig.decode(PanelConfig.encode(c)))
    }

    @Test fun `defaults are nothing typed, nothing resolved, phone location off`() {
        val d = PanelConfig.DEFAULT
        assertEquals("", d.weatherPlace); assertEquals("", d.weatherPlaceLabel)
        assertNull(d.weatherLat); assertNull(d.weatherLon); assertFalse(d.weatherUseDevice)
    }

    @Test fun `a config saved before the feature still decodes`() {
        val c = PanelConfig.decode("""{"weather":"rain"}""")
        assertEquals("rain", c.weather); assertNull(c.weatherLat); assertEquals("", c.weatherPlace)
    }
}
