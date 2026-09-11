package dev.kb896.aiusage.data

/**
 * One current-conditions reading from Open-Meteo, as the panel needs it.
 *
 * The condition is derived, not stored: the WMO code is the fact and everything else —
 * the word, the icon, what Auto draws on the glass — is a reading of it that can change
 * without the payload changing. The icon is carried by NAME because this package must
 * not know the panel's icon objects; the panel maps the name.
 */
data class WeatherReading(
    val temperatureC: Float,
    val code: Int,
    val isDay: Boolean,
    val place: String,
    val fetchedAt: Long,
) {
    val condition: Condition get() = Condition.of(code, isDay)

    /** Wall-clock, because fetchedAt crosses a process boundary and uptime does not. */
    fun isFresh(nowMs: Long): Boolean = nowMs - fetchedAt <= MAX_AGE_MS

    companion object {
        /** Two hours: a reading older than that is a guess dressed as a fact. */
        const val MAX_AGE_MS = 7_200_000L
    }
}

/** WMO weather code → the word the line shows, the icon it draws, and what Auto paints. */
enum class Condition(val word: String, val iconName: String, val glass: String) {
    CLEAR_DAY("Clear", "SUN", "off"),
    CLEAR_NIGHT("Clear", "MOON", "off"),
    PARTLY("Partly cloudy", "PARTLY", "off"),
    OVERCAST("Overcast", "CLOUD", "off"),
    FOG("Fog", "FOG", "off"),
    DRIZZLE("Drizzle", "RAIN", "rain"),
    RAIN("Rain", "RAIN", "rain"),
    SHOWERS("Showers", "RAIN", "rain"),
    SNOW("Snow", "SNOW", "snow"),
    // A storm is rain on the glass; the icon is the only thing that says thunder.
    THUNDER("Thunderstorm", "THUNDER", "rain");

    companion object {
        fun of(code: Int, isDay: Boolean): Condition = when (code) {
            0 -> if (isDay) CLEAR_DAY else CLEAR_NIGHT
            1, 2 -> PARTLY
            3 -> OVERCAST
            45, 48 -> FOG
            in 51..57 -> DRIZZLE
            in 61..67 -> RAIN
            in 80..82 -> SHOWERS
            in 71..77, 85, 86 -> SNOW
            in 95..99 -> THUNDER
            // Open-Meteo documents nothing outside these; overcast is the least wrong
            // thing to say about a sky we cannot read.
            else -> OVERCAST
        }
    }
}
