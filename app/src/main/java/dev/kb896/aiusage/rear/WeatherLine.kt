package dev.kb896.aiusage.rear

import dev.kb896.aiusage.data.WeatherReading

/**
 * The words and the glyph of the weather line, kept out of the view so they can be
 * tested without a canvas. Layout numbers live here too, beside the clock's, because
 * the line sits under the date and is measured against it.
 */
object WeatherLine {
    /** Under the date's 420, above the free band's end; the same rhythm as clock→date. */
    const val BASELINE = 486f
    const val ICON_GAP = 8f
    const val PLACE_GAP = 18f
    const val PLACE_SIZE = 11f

    fun temperature(reading: WeatherReading): String = "${Math.round(reading.temperatureC)}°"

    fun text(reading: WeatherReading): String = "${temperature(reading)} · ${reading.condition.word}"

    /** The reading names its icon; the panel owns the objects. Unknown names get a cloud. */
    fun icon(name: String): PanelIcons.Icon = when (name) {
        "SUN" -> PanelIcons.SUN
        "MOON" -> PanelIcons.MOON
        "PARTLY" -> PanelIcons.PARTLY
        "CLOUD" -> PanelIcons.CLOUD
        "FOG" -> PanelIcons.FOG
        "RAIN" -> PanelIcons.RAIN
        "SNOW" -> PanelIcons.SNOW
        "THUNDER" -> PanelIcons.THUNDER
        else -> PanelIcons.CLOUD
    }
}
