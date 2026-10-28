package dev.kb896.aiusage.rear

/**
 * How a sheet's contents arrive as it is pulled open: the control centre's, shared with
 * the portal sheet so both come in alike. Pure; [progress] is how far through its pull
 * (or its stage of one) the sheet is, 0 to 1.
 *
 * NOTHING UNTIL [FADE_FROM] of the pull, then over the rest an ease-out cubic — quick to
 * show, settling gently — so a pull that is still deciding shows only the blur, and the
 * contents come as it commits; a push back plays the same curve in reverse. On that one
 * curve they fade in (as one layer: glass cannot take a paint's alpha), slide into place
 * from [offset] away, and each piece grows about its own middle from [GROW_FROM] of its
 * size ([scale]) — not the lot from one point.
 */
object Arrival {

    /** The contents fade in from this far into the pull. */
    const val FADE_FROM = 0.5f
    /** They slide this share of the pull's travel, over the part they are shown for. */
    const val SLIDE = 0.5f
    /** The size they grow from into their place. */
    const val GROW_FROM = 0.85f

    /** How far the contents have arrived, 0 to 1, at [progress]. */
    fun of(progress: Float): Float {
        val c = ((progress - FADE_FROM) / (1f - FADE_FROM)).coerceIn(0f, 1f)
        return 1f - (1f - c) * (1f - c) * (1f - c)
    }

    /**
     * How far from their place the contents stand at [progress], for a pull that travels
     * [travel]: [SLIDE] of the half of it they are shown for, to nothing as they arrive.
     * A distance; the caller gives it its direction (the control centre's come down, the
     * portal sheet's up).
     */
    fun offset(progress: Float, travel: Float): Float = (1f - of(progress)) * travel * SLIDE * (1f - FADE_FROM)

    /** A piece's size at [progress], as a share of its own: [GROW_FROM] to 1. */
    fun scale(progress: Float): Float = GROW_FROM + (1f - GROW_FROM) * of(progress)
}
