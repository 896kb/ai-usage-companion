package dev.kb896.aiusage.rear

/**
 * How the portal sheet's contents go at an edge — a page of apps sliding out at the side,
 * the contents scrolled past the top: as the front's settings go under its preview
 * (ui.glass.TopBlurStrip), from sharp, through blurred, to nothing. Pure: the weights at
 * each point across the band, for the painter's gradient masks.
 *
 * Across the band, t runs from 0 at the outer edge to 1 where it meets the content. The
 * front lays its band over the rows — its mask opaque at the edge, gone at the band's
 * end — and inside the band its blurred rows come in toward the end; so a row is seen
 * sharp in proportion to t, and blurred, over that, by t(1 − t): whole and sharp inside,
 * softening on the way, nothing at the edge. [sharp] and [blurred] are those two.
 *
 * The front's band is 56 dp of a tall screen; here it is [BAND] — what fits between the
 * grid's right side and the panel's edge, the same each side — and the blur [BLUR] px,
 * the front's 14 dp scaled to what is seen across the band.
 */
object EdgeFade {

    /** The band's width, in panel pixels. */
    const val BAND = 68f

    /** The blurred copy's radius, in panel pixels. */
    const val BLUR = 12f

    /** How many points each mask's gradient is drawn through. */
    const val STOPS = 6

    /** How much of the sharp content shows at [t] across the band. */
    fun sharp(t: Float): Float = t.coerceIn(0f, 1f)

    /** How much of the blurred copy shows over it at [t]. */
    fun blurred(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * (1f - c)
    }

    /** The gradient's positions across the band, 0 to 1, [STOPS] of them evenly. */
    fun positions(): FloatArray = FloatArray(STOPS) { it / (STOPS - 1f) }

    /**
     * The sharp copy's mask at each of [positions], as colours (black at the weight's
     * alpha), for a fade on at [strength] (the top edge's comes in with the scroll): at 0
     * opaque throughout, at 1 [sharp].
     */
    fun sharpMask(strength: Float = 1f): IntArray {
        val s = strength.coerceIn(0f, 1f)
        return colours { t -> 1f - s + s * sharp(t) }
    }

    /** The blurred copy's mask likewise: at [strength] 0 none of it, at 1 [blurred]. */
    fun blurMask(strength: Float = 1f): IntArray {
        val s = strength.coerceIn(0f, 1f)
        return colours { t -> s * blurred(t) }
    }

    private fun colours(alpha: (Float) -> Float): IntArray = IntArray(STOPS) { i ->
        Math.round(alpha(i / (STOPS - 1f)).coerceIn(0f, 1f) * 255) shl 24
    }
}
