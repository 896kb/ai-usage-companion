package dev.kb896.aiusage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatePayloadWeatherTest {
    private val reading = WeatherReading(14.4f, 61, false, "Bucharest, Romania", 123_456L)

    @Test fun `a reading rides the payload and comes back whole`() {
        val json = StatePayload.encode(DashboardState.EMPTY, PanelConfig.DEFAULT, reading)
        val back = StatePayload.decode(json).weather!!
        assertEquals(reading, back)
    }

    @Test fun `no reading is no weather, and the old payload shape still decodes`() {
        assertNull(StatePayload.decode(StatePayload.encode(DashboardState.EMPTY)).weather)
        assertNull(StatePayload.decode("""{"fetchedAt":0,"accounts":[]}""").weather)
        assertNull(StatePayload.decode("""{"fetchedAt":0,"accounts":[],"weather":{"code":"x"}}""").weather)
    }
}
