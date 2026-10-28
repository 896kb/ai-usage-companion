package dev.kb896.aiusage.rear

/**
 * Which density the rear display (display 1) should have: pure, so the rule is tested away
 * from the shell. An app can carry its own density (PanelConfig.portalDpi); it applies for
 * as long as that app is the live guest — on top or behind the panel — and any other time
 * the display keeps its own.
 *
 * Values are kept between [MIN] and [MAX] dpi, in steps of [STEP]: below 240 everything is
 * huge and above 600 nothing is legible on the small rear panel, and a coarse step keeps the
 * sliders (the front's, the back's) from producing a hundred nearly identical values.
 */
object RearDensity {
    const val MIN = 240
    const val MAX = 600
    const val STEP = 10

    /** The density display 1 should have: a live guest's own, else null (the display's own). */
    fun target(guest: String?, guestLive: Boolean, dpi: Map<String, Int>): Int? =
        if (guest != null && guestLive) dpi[guest]?.let { clamp(it) } else null

    /** [v] rounded to the nearest [STEP] and held within [MIN]..[MAX]. */
    fun clamp(v: Int): Int = (Math.round(v / STEP.toFloat()) * STEP).coerceIn(MIN, MAX)
}
