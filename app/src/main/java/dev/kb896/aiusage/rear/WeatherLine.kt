package dev.kb896.aiusage.rear

import dev.kb896.aiusage.data.WeatherReading

/**
 * The words and the glyph of the weather line, kept out of the view so they can be
 * tested without a canvas. Layout numbers live here too, beside the clock's, because
 * the line sits under the date and is measured against it.
 */
object WeatherLine {
    /**
     * Under the date's 420, and stopping short of the unlock slider.
     *
     * The slider's band starts at 476 — see markRegion — and the line's card runs from
     * the baseline less DATE_SIZE and the card's padding to the baseline plus it. At 466
     * the card is [434..472]: four clear of the slider, eight below the date's own card.
     * At the 486 this was first written as, the two overlapped by sixteen units across
     * the slider's full width, on every locked panel showing this page.
     *
     * That clearance is the READING's alone. Once the place moved onto its own line the
     * card grew down into the slider's band anyway — see PLACE_BASELINE, and the fade in
     * drawWeatherLine that settles it.
     */
    const val BASELINE = 405f
    const val ICON_GAP = 8f
    /**
     * The place is a caption under the reading, not a suffix beside it: at 11 px beside
     * the line it could not be read from arm's length. 16 is the notification body size,
     * which is what a caption on this page already is.
     */
    const val PLACE_SIZE = 16f
    /**
     * Under the reading's baseline (466) by one caption line. The glass card then ends at
     * 496, inside the unlock slider's band (top 476) — which is why the block leaves with
     * the slider instead of the clock and date moving up to make room: see drawWeatherLine.
     */
    const val PLACE_BASELINE = 428f

    fun temperature(reading: WeatherReading): String = "${Math.round(reading.temperatureC)}°"

    /** The separator and the word, apart from the figure: the line draws them in two inks. */
    fun words(reading: WeatherReading): String = " · ${reading.condition.word}"

    fun text(reading: WeatherReading): String = temperature(reading) + words(reading)

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
