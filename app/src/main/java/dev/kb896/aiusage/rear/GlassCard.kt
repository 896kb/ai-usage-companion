package dev.kb896.aiusage.rear

/**
 * Where a floating card has to sit so the display's own rounding does not cut its corner.
 *
 * THE SCREEN IS A ROUNDED RECTANGLE AND ITS CORNERS ARE NOT DRAWABLE. ORIGINAL's
 * notification strip never had to care: it runs off the top and right edges and lets the
 * hardware's curve finish it, so there is no corner of its own to lose. A glass card is
 * an object inset from those edges, and an object placed by a margin chosen for how it
 * looks will have its corner quietly eaten — measured on this panel at eleven units past
 * the edge, which reads as a card that has been sliced rather than one that is round.
 *
 * The margin is therefore a consequence of two radii rather than a number somebody liked.
 */
object GlassCard {

    /**
     * The least margin at which a card of radius [card] keeps its corner inside a screen
     * corner of radius [screen].
     *
     * Both corners are circles. The card's corner arc has its centre a margin plus a
     * radius in from each edge; the screen's has its centre one screen-radius in from
     * each. Along the diagonal between them, the furthest the card reaches is the
     * distance between the centres plus the card's own radius, and that has to stay
     * within the screen's radius:
     *
     *     sqrt(2) * (screen - margin - card) + card  <=  screen
     *
     * Solved for the margin, which is what this returns — plus [CLEARANCE], because
     * exactly tangent is a corner that touches the cut and shows every rounding error on
     * the way there.
     *
     * A screen no rounder than the card needs no margin for this reason at all, so the
     * floor is whatever the design asks for.
     */
    fun margin(screen: Float, card: Float, floor: Float = MIN): Float {
        if (screen <= card) return floor
        val needed = (screen - card) * (1f - 1f / Math.sqrt(2.0).toFloat())
        return maxOf(floor, needed + CLEARANCE)
    }

    /**
     * The margin a card would have if the screen were square.
     *
     * Still not zero: a card flush against the edge is a strip, and the whole point of a
     * pane is that you can see it end.
     */
    const val MIN = 14f

    /** Air between the card's corner and the screen's cut. */
    const val CLEARANCE = 3f
}
