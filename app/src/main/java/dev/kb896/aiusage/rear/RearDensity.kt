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

    /**
     * A guest on a private display (PrivateDisplay) without a DPI of its own: two thirds of the
     * rear's real density, the display's own being no use here. At the rear's 450 that is 300,
     * the spike's, which the owner tried (2026-10-30): the 608 px right of the lenses read as a
     * 324 dp wide window, about what apps call a small phone's. At the full 450 it is 216 dp, and
     * apps start dropping things. Scaled by the panel's density, so a Pro Max's agrees.
     */
    fun privateDefault(rear: RearGeometry): Int = clamp(rear.dpi * 2 / 3)

    /** [pkg]'s private display's density: its own, else [privateDefault]. Display 1's stays out of this. */
    fun ofPrivate(pkg: String, dpi: Map<String, Int>, rear: RearGeometry): Int =
        dpi[pkg]?.let { clamp(it) } ?: privateDefault(rear)

    /** [v] rounded to the nearest [STEP] and held within [MIN]..[MAX]. */
    fun clamp(v: Int): Int = (Math.round(v / STEP.toFloat()) * STEP).coerceIn(MIN, MAX)
}
