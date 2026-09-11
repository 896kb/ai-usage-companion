package dev.kb896.aiusage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherReadingTest {
    private fun cond(code: Int, day: Boolean = true) = Condition.of(code, day)

    @Test fun `clear picks the sun by day and the moon by night`() {
        assertEquals(Condition.CLEAR_DAY, cond(0, day = true))
        assertEquals(Condition.CLEAR_NIGHT, cond(0, day = false))
        assertEquals("SUN", Condition.CLEAR_DAY.iconName)
        assertEquals("MOON", Condition.CLEAR_NIGHT.iconName)
        assertEquals("Clear", Condition.CLEAR_NIGHT.word)
    }

    @Test fun `every code in the table lands where the spec says`() {
        assertEquals(Condition.PARTLY, cond(1)); assertEquals(Condition.PARTLY, cond(2))
        assertEquals(Condition.OVERCAST, cond(3))
        assertEquals(Condition.FOG, cond(45)); assertEquals(Condition.FOG, cond(48))
        for (c in 51..57) assertEquals("code $c", Condition.DRIZZLE, cond(c))
        for (c in 61..67) assertEquals("code $c", Condition.RAIN, cond(c))
        for (c in 80..82) assertEquals("code $c", Condition.SHOWERS, cond(c))
        for (c in listOf(71, 72, 73, 75, 77, 85, 86)) assertEquals("code $c", Condition.SNOW, cond(c))
        for (c in 95..99) assertEquals("code $c", Condition.THUNDER, cond(c))
    }

    @Test fun `what auto draws follows the condition`() {
        assertEquals("off", Condition.CLEAR_DAY.glass)
        assertEquals("off", Condition.OVERCAST.glass)
        assertEquals("off", Condition.FOG.glass)
        assertEquals("rain", Condition.DRIZZLE.glass)
        assertEquals("rain", Condition.RAIN.glass)
        assertEquals("rain", Condition.SHOWERS.glass)
        assertEquals("rain", Condition.THUNDER.glass)
        assertEquals("snow", Condition.SNOW.glass)
    }

    @Test fun `a code outside the table is overcast, not a crash`() {
        assertEquals(Condition.OVERCAST, cond(4))
        assertEquals(Condition.OVERCAST, cond(-1))
        assertEquals(Condition.OVERCAST, cond(200))
    }

    @Test fun `a reading is fresh for two hours`() {
        val r = WeatherReading(14.4f, 61, true, "Bucharest, Romania", fetchedAt = 1_000_000L)
        assertTrue(r.isFresh(1_000_000L + 7_199_999L))
        assertFalse(r.isFresh(1_000_000L + 7_200_001L))
        assertEquals(Condition.RAIN, r.condition)
    }
}
