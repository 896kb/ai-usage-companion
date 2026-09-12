package dev.kb896.aiusage.rear

import dev.kb896.aiusage.data.Condition
import dev.kb896.aiusage.data.WeatherReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class WeatherLineTest {
    private fun r(t: Float, code: Int = 61) = WeatherReading(t, code, true, "Bucharest, Romania", 0L)

    @Test fun `the temperature is a whole degree`() {
        assertEquals("14°", WeatherLine.temperature(r(14.4f)))
        assertEquals("15°", WeatherLine.temperature(r(14.5f)))
        assertEquals("-4°", WeatherLine.temperature(r(-3.6f)))
        assertEquals("0°", WeatherLine.temperature(r(-0.4f)))
    }

    @Test fun `the line is temperature, a middle dot, the word`() {
        assertEquals("14° · Rain", WeatherLine.text(r(14.4f, 61)))
        assertEquals("2° · Snow", WeatherLine.text(r(1.6f, 73)))
    }

    @Test fun `every condition names an icon that exists`() {
        for (c in Condition.entries) {
            val icon = WeatherLine.icon(c.iconName)
            assertSame("${c.name} → ${c.iconName}", icon, WeatherLine.icon(c.iconName))
        }
        assertSame(PanelIcons.SUN, WeatherLine.icon("SUN"))
        assertSame(PanelIcons.MOON, WeatherLine.icon("MOON"))
        assertSame(PanelIcons.THUNDER, WeatherLine.icon("THUNDER"))
        assertSame(PanelIcons.CLOUD, WeatherLine.icon("something else"))
    }
}
