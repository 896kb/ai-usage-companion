package dev.kb896.aiusage.rear

import dev.kb896.aiusage.data.WeatherReading
import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherEffectiveTest {
    private fun reading(code: Int, at: Long = 0L) = WeatherReading(10f, code, true, "x", at)

    @Test fun `a fixed setting is itself, reading or not`() {
        assertEquals(Weather.RAIN, Weather.effective(Weather.RAIN, null, 0L))
        assertEquals(Weather.SNOW, Weather.effective(Weather.SNOW, reading(0), 0L))
        assertEquals(Weather.STORM, Weather.effective(Weather.STORM, reading(0), 0L))
        assertEquals(Weather.OFF, Weather.effective(Weather.OFF, reading(61), 0L))
    }

    @Test fun `auto follows the reading`() {
        assertEquals(Weather.RAIN, Weather.effective(Weather.AUTO, reading(61), 0L))
        // A thunderstorm is a storm on the glass: rain, with lightning.
        assertEquals(Weather.STORM, Weather.effective(Weather.AUTO, reading(95), 0L))
        assertEquals(Weather.SNOW, Weather.effective(Weather.AUTO, reading(71), 0L))
        assertEquals(Weather.OFF, Weather.effective(Weather.AUTO, reading(3), 0L))
    }

    @Test fun `auto with no reading, or a stale one, is off`() {
        assertEquals(Weather.OFF, Weather.effective(Weather.AUTO, null, 0L))
        assertEquals(Weather.OFF, Weather.effective(Weather.AUTO, reading(61, at = 0L), WeatherReading.MAX_AGE_MS + 1))
    }

    @Test fun `an unknown setting string is off, not a crash`() {
        assertEquals(Weather.OFF, Weather.effective("hail", reading(61), 0L))
    }
}
