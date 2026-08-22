package dev.kb896.aiusage.rear

/**
 * The charging cell: a tall glass on the right of the panel that fills with liquid.
 *
 * It ARRIVES OVER the panel rather than pushing it aside, which is the opposite of what
 * a notification does and deliberately so. A notification is a message and earns the
 * room it takes; this is an acknowledgement — the charger went in, here is what you have
 * — and it is gone in five seconds. Moving the whole readout for something that
 * transient would be five seconds of everything else being in the wrong place.
 *
 * It also passes touches straight through. There is no target here and no hit region is
 * registered for it, so the volume strip it happens to sit over goes on working while it
 * is up. That is not an oversight to be tidied later: a thing that appears unbidden for
 * five seconds must not take control of the surface underneath it.
 */
object ChargeOverlay {

    /** How much of the panel's width the cell occupies. */
    const val WIDTH_FRACTION = 1f / 7f

    /** How far in from the panel's edges the glass sits. */
    const val MARGIN = 16f

    /** The glass's corner radius, as a fraction of its own width. */
    const val CORNER = 0.28f

    /** How long the cell takes to slide in, and to leave. */
    const val SLIDE_MS = 320f

    /**
     * How long the liquid takes to reach its level.
     *
     * Three seconds of a five-second appearance. The filling IS the animation — a cell
     * that arrives already full has nothing to say that a number would not say faster —
     * so it gets the majority of the time on screen, and what is left is the pause needed
     * to read the figure it settled at.
     */
    const val FILL_MS = 3_000f

    /** How long the whole thing lasts, arrival to departure. */
    const val TOTAL_MS = 5_000f

    fun width(panelWidth: Float): Float = panelWidth * WIDTH_FRACTION

    /**
     * How far the cell is off the right-hand edge, in units, for a cell of this width.
     *
     * Zero once it has arrived. It leaves the way it came rather than fading: it entered
     * as an object and an object does not become transparent to go away.
     */
    fun offset(elapsedMs: Float, width: Float): Float {
        if (elapsedMs <= 0f) return width
        val exitAt = TOTAL_MS - SLIDE_MS
        return when {
            elapsedMs < SLIDE_MS -> width * (1f - Tween.easeInOut(elapsedMs / SLIDE_MS))
            elapsedMs < exitAt -> 0f
            elapsedMs < TOTAL_MS ->
                width * Tween.easeInOut((elapsedMs - exitAt) / SLIDE_MS)
            else -> width
        }
    }

    /** Whether the whole appearance is over and nothing need be drawn. */
    fun done(elapsedMs: Float): Boolean = elapsedMs >= TOTAL_MS

    /**
     * How full the glass is, 0..1, on the way to [level].
     *
     * Filling starts as the cell arrives rather than after it has landed. Waiting for the
     * slide to finish reads as two events — a box appears, then something happens in it —
     * where the liquid already rising as it comes in reads as one.
     */
    fun fill(elapsedMs: Float, level: Float): Float {
        if (elapsedMs <= 0f) return 0f
        val t = (elapsedMs / FILL_MS).coerceIn(0f, 1f)
        return level.coerceIn(0f, 1f) * Tween.easeInOut(t)
    }

    /**
     * The angle the liquid's surface sits at, in degrees, from a gravity reading.
     *
     * CLAMPED, and hard. A real surface stays level however far the glass is turned, and
     * a faithful version of that on a panel held at arm's length is a diagonal line
     * across a narrow cell — which reads as a broken drawing rather than as a liquid. The
     * clamp keeps it as a lean: enough to say the thing is loose in there, never enough
     * to leave the glass.
     *
     * [gx] and [gy] are the gravity vector's components in the PANEL's own axes, which is
     * the caller's job to arrange — the rear display is landscape on the back of a
     * portrait phone, so the sensor's axes and this one's are not the same.
     */
    fun tilt(gx: Float, gy: Float): Float {
        if (gx == 0f && gy == 0f) return 0f
        val deg = Math.toDegrees(Math.atan2(gx.toDouble(), Math.abs(gy.toDouble()))).toFloat()
        return deg.coerceIn(-MAX_TILT, MAX_TILT)
    }

    /** The steepest lean the surface is allowed. */
    const val MAX_TILT = 18f

    /**
     * How far the surface rides up and down as the phone moves, in units.
     *
     * Slosh, not waves. The liquid is answering a hand, and a hand moves slowly compared
     * to anything that would look like a ripple.
     */
    const val SLOSH = 3.5f

    /** How much of the cell's height the percentage occupies. */
    const val TEXT_SIZE = 0.13f
}
