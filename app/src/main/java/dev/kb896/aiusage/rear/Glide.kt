package dev.kb896.aiusage.rear

/**
 * Carrying on from a finger, rather than starting over.
 *
 * A settle eased in and out begins at a standstill, so a release mid-swipe stopped dead and
 * then set off again, slower — the hitch between the finger leaving and the thing moving on.
 * Eased OUT instead, a settle leaves at twice its average speed; give it twice the distance
 * over the finger's speed and it leaves at exactly the finger's, slowing into place.
 * Without speed toward the target, it is the old ease in and out over the most time.
 */
object Glide {
    /** How long [distance] takes from a finger at [speed] toward it (both in the same units, per ms). */
    fun ms(distance: Float, speed: Float, min: Float, max: Float): Float =
        if (speed <= 0f) max else (2f * distance / speed).coerceIn(min, max)

    /** How far along, 0 to 1, at [t] through the settle. */
    fun curve(t: Float, carried: Boolean): Float {
        val u = t.coerceIn(0f, 1f)
        return if (carried) 1f - (1f - u) * (1f - u) else Tween.easeInOut(u)
    }
}
