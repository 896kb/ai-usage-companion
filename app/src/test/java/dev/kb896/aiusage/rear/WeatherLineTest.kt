package dev.kb896.aiusage.rear

import dev.kb896.aiusage.data.Condition
import dev.kb896.aiusage.data.WeatherReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherLineTest {
    private fun r(t: Float, code: Int = 61) = WeatherReading(t, code, true, "Bucharest, Romania", 0L)

    @Test fun `the temperature is a whole degree`() {
        assertEquals("14°", WeatherLine.temperature(r(14.4f)))
        assertEquals("15°", WeatherLine.temperature(r(14.5f)))
        assertEquals("-4°", WeatherLine.temperature(r(-3.6f)))
        assertEquals("0°", WeatherLine.temperature(r(-0.4f)))
    }

    @Test fun `the words are a middle dot and the condition`() {
        assertEquals(" · Rain", WeatherLine.words(r(14.4f, 61)))
        assertEquals(" · Snow", WeatherLine.words(r(1.6f, 73)))
        assertEquals(" · Thunderstorm", WeatherLine.words(r(19f, 95)))
    }

    @Test fun `the line is temperature, a middle dot, the word`() {
        assertEquals("14° · Rain", WeatherLine.text(r(14.4f, 61)))
        assertEquals("2° · Snow", WeatherLine.text(r(1.6f, 73)))
    }

    @Test fun `every condition names an icon that exists`() {
        // The table, spelled out rather than asked of the thing under test: a name that
        // silently fell through to the cloud would still have matched itself.
        val named = mapOf(
            "SUN" to PanelIcons.SUN,
            "MOON" to PanelIcons.MOON,
            "PARTLY" to PanelIcons.PARTLY,
            "CLOUD" to PanelIcons.CLOUD,
            "FOG" to PanelIcons.FOG,
            "RAIN" to PanelIcons.RAIN,
            "SNOW" to PanelIcons.SNOW,
            "THUNDER" to PanelIcons.THUNDER,
        )
        for ((name, icon) in named) assertSame(name, icon, WeatherLine.icon(name))
        for (c in Condition.entries) {
            assertTrue("${c.name} → ${c.iconName}", named.containsKey(c.iconName))
        }
        assertSame(PanelIcons.CLOUD, WeatherLine.icon("something else"))
    }
}
