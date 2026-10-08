package dev.kb896.aiusage.rear

/**
 * The Info page's notifications: hidden above the page until a swipe down pulls them in,
 * pushed back up by a swipe up — one more step of the page rather than a page of its own.
 * [offset] is how far down they are under a finger (0 hidden, [height] in view), [release]
 * where they go when it lets go: past a quarter of the way they go, short of that they
 * spring back, and a swipe the wrong way changes nothing.
 *
 * And one card swiped right, away: [dismissOffset] under the finger, [dismissed] on letting go.
 */
object NoticeReveal {
    fun offset(shown: Boolean, dy: Float, height: Float): Float =
        ((if (shown) height else 0f) + dy).coerceIn(0f, height)

    /**
     * Whether they are in view after a drag of [dy]; a flick ([velocity], units a
     * millisecond, down positive) goes the way it was flicked whatever the distance.
     */
    fun release(shown: Boolean, dy: Float, height: Float, velocity: Float = 0f): Boolean = when {
        velocity > FLING -> true
        velocity < -FLING -> false
        !shown -> dy > height * GO
        else -> dy > -height * GO
    }

    /** A flick, in units a millisecond. */
    const val FLING = 0.6f

    fun dismissOffset(dx: Float, width: Float): Float = dx.coerceIn(0f, width)

    fun dismissed(dx: Float, width: Float): Boolean = dx > width * GO

    private const val GO = 0.25f
}
