package dev.kb896.aiusage.rear

/**
 * The Info page's notifications put away to the right and brought back from it: how far
 * the stack is pushed right under a finger ([offset], 0 home, the panel's width gone), and
 * where it goes when the finger lets go ([release]) — past a quarter of the way it goes,
 * short of that it springs back, and a swipe the wrong way changes nothing.
 */
object NoticeStash {
    fun offset(stashed: Boolean, dx: Float, width: Float): Float =
        ((if (stashed) width else 0f) + dx).coerceIn(0f, width)

    /** Whether it is put away after a drag of [dx]. */
    fun release(stashed: Boolean, dx: Float, width: Float): Boolean = when {
        !stashed -> dx > width * GO
        else -> dx > -width * GO
    }

    private const val GO = 0.25f
}
