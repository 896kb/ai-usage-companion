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

    /**
     * Extra room between the cell and the right edge of the panel.
     *
     * On top of [MARGIN], and shifting the whole cell rather than narrowing it — a cell
     * made thinner to create a gap is a thinner cell, and the liquid in it is the reading.
     * The display's corner curls away exactly here, which is why sixteen units of margin
     * measured flush to the eye: the glass is already gone before the gap begins.
     */
    const val RIGHT_INSET = 15f

    /** How far the figure sits from the cell it is labelling. */
    const val FIGURE_GAP = 22f

    /** The least curve a glass may have before it reads as a box. */
    const val MIN_CORNER = 14f

    /** How hard the arriving liquid knocks the surface as the level climbs. */
    const val SPLASH = 22f

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
    /**
     * Gravity for the liquid, from the panel-plane components of a real reading.
     *
     * THE PANEL ALWAYS FEELS A FULL G, and whatever the tilt does not account for is
     * treated as pointing down the panel. That is a deliberate lie, and it is the
     * difference between a readout and a curiosity.
     *
     * Honestly projected, a rear screen lying flat on a desk — which is exactly how one
     * gets looked at — has NO gravity in its own plane: measured at (-0.17, +0.51) with
     * the phone face down, against 9.8 straight through the screen. The drops then sit
     * wherever they were poured, and a battery at 70% shows as a full glass stuck to the
     * ceiling. Correct physics, useless picture.
     *
     * So the missing component is added downward. Upright, the tilt accounts for all of
     * it and nothing is added; flat, all of it is added and the liquid simply falls to
     * the bottom of the cell; in between it leans by exactly as much as the phone does.
     * Turn the phone in its own plane and the pool goes round with it, because the
     * measured part still dominates.
     */
    fun gravity(gx: Float, gy: Float): Pair<Float, Float> {
        val inPlane = Math.hypot(gx.toDouble(), gy.toDouble()).toFloat()
        // How much of a real tilt there is to go on, 0 when flat and 1 when upright.
        val trust = (inPlane / FULL_G).coerceIn(0f, 1f)
        // The measured direction, or straight down when there is nothing to measure.
        val ux = if (inPlane > 0.001f) gx / inPlane else 0f
        val uy = if (inPlane > 0.001f) gy / inPlane else 1f
        // BLENDED AS DIRECTIONS, then given a fixed length. Adding a downward component
        // to the reading was the first attempt and it cancels itself: a phone tilted so
        // that the panel's "up" is slightly favoured has a small upward reading, and
        // adding a large downward one to it leaves almost nothing — the liquid feels a
        // third of a g and drifts. Blending which WAY is down, and then always pulling
        // with the same force, has no such hole.
        val dx = ux * trust
        val dy = uy * trust + (1f - trust)
        val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (len < 0.0001f) return 0f to FULL_G
        return (dx / len * FULL_G) to (dy / len * FULL_G)
    }

    /** One gravity, in the units the sensor reports. */
    const val FULL_G = 9.81f

    /**
     * Turn an angle toward another, the short way round, at a limited rate.
     *
     * THE SURFACE FOLLOWS THIS RATHER THAN THE SENSOR DIRECTLY, and without it a rotation
     * is a teleport. The liquid's level is worked out from where down is, so when the
     * phone is turned over the answer changes discontinuously and the water is simply on
     * the other side on the next frame — no swing, no slosh, just somewhere else.
     *
     * The short way matters at the seam. Angles wrap at pi, so easing the raw numbers
     * sends a surface at 179 degrees the long way round to -179 rather than the two
     * degrees it actually moved, and the liquid spins a full turn to get somewhere it
     * was almost already at.
     *
     * Rate-limited rather than eased proportionally: a proportional ease crawls the last
     * few degrees forever, and a liquid that never quite finishes turning reads as drag
     * in the rendering rather than as weight in the liquid.
     */
    fun turnToward(from: Float, to: Float, dt: Float, rate: Float): Float {
        var delta = to - from
        val twoPi = (Math.PI * 2).toFloat()
        while (delta > Math.PI) delta -= twoPi
        while (delta < -Math.PI) delta += twoPi
        val step = rate * dt
        if (Math.abs(delta) <= step) return to
        return from + Math.signum(delta) * step
    }

    /** How fast the surface may swing to a new downhill, in radians per second. */
    const val TURN_RATE = 4.2f

    /**
     * How far the surface rides up and down as the phone moves, in units.
     *
     * Slosh, not waves. The liquid is answering a hand, and a hand moves slowly compared
     * to anything that would look like a ripple.
     */
    const val SLOSH = 3.5f

    /**
     * The figure's size, against the cell's width.
     *
     * Measured off the width rather than the height, which was the original mistake and
     * still holds: a thirteenth of a 540-tall glass is seventy units, and three characters
     * at seventy are wider than the cell they were then inside.
     *
     * MUCH larger now that it is no longer inside it. The old third-of-the-width was the
     * biggest a figure could be and still sit within the glass; standing beside the cell
     * it has the whole panel to be big in, and it should be — it is the one number the
     * charging animation exists to say, and at thirty units it was smaller than the row of
     * readings it interrupts.
     */
    const val TEXT_SIZE = 0.88f
}
