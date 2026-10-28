package dev.kb896.aiusage.rear

/**
 * Which density the rear display (display 1) should have: pure, so the rule is tested away
 * from the shell. An app can carry its own density (PanelConfig.portalDpi); it applies only
 * while that app is the guest on top, and any other time the display keeps its default.
 *
 * Values are kept between [MIN] and [MAX] dpi, in steps of [STEP]: below 240 everything is
 * huge and above 600 nothing is legible on the small rear panel, and a coarse step keeps the
 * back's nudge buttons from producing a hundred nearly identical values.
 */
object RearDensity {
    const val MIN = 240
    const val MAX = 600
    const val STEP = 10

    /** The density display 1 should have: the guest's own while it is on top, else null (the display's default). */
    fun target(guest: String?, guestOnTop: Boolean, dpi: Map<String, Int>): Int? =
        if (guest != null && guestOnTop) dpi[guest]?.let { clamp(it) } else null

    /** [v] rounded to the nearest [STEP] and held within [MIN]..[MAX]. */
    fun clamp(v: Int): Int = (Math.round(v / STEP.toFloat()) * STEP).coerceIn(MIN, MAX)

    /** One nudge from the back's - / +: ±20, from [current] or the default when unset. */
    fun nudge(current: Int?, default: Int, up: Boolean): Int = clamp((current ?: default) + if (up) 20 else -20)
}
