package dev.kb896.aiusage.data

import java.util.Locale

/** Celsius or Fahrenheit for the weather line, and what "auto" means on this phone. */
object TemperatureUnit {
    const val AUTO = "auto"
    const val CELSIUS = "c"
    const val FAHRENHEIT = "f"
    val ALL = listOf(AUTO, CELSIUS, FAHRENHEIT)

    /** Where Fahrenheit is what people read: the US and its territories, and a few others. */
    private val FAHRENHEIT_REGIONS = setOf(
        "US", "PR", "GU", "VI", "AS", "MP", "UM", "BS", "BZ", "KY", "PW", "LR", "FM", "MH",
    )

    /**
     * [unit] with "auto" settled for [locale]: Android 14's Regional preferences put the user's
     * temperature choice in the locale as the "mu" extension, and that wins; otherwise the region.
     */
    fun resolve(unit: String, locale: Locale = Locale.getDefault(Locale.Category.FORMAT)): String = when (unit) {
        CELSIUS, FAHRENHEIT -> unit
        else -> when (locale.getUnicodeLocaleType("mu")) {
            "fahrenhe" -> FAHRENHEIT
            "celsius", "kelvin" -> CELSIUS
            else -> if (locale.country.uppercase() in FAHRENHEIT_REGIONS) FAHRENHEIT else CELSIUS
        }
    }
}
