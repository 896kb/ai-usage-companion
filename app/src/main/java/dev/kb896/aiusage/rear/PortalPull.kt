package dev.kb896.aiusage.rear

import dev.kb896.aiusage.Panel

/**
 * The App Portal's pull: the control centre's, mirrored to the bottom edge. A finger that
 * lands within [EDGE] of the bottom, right of the lens column, pulls the portal up over
 * [TRAVEL]; past halfway it opens. Open, a drag down closes it the same way.
 *
 * Shared by the panel's own touches and by the ones the shell service relays from the
 * rear touchscreen while a guest app has the display (see EdgeGestures), so both feel
 * the same. Up is negative dy, as MotionEvent has it.
 */
object PortalPull {

    /** The control centre's edge width, for the same reason: a finger on the back lands short. */
    const val EDGE = ControlCenter.EDGE
    const val TRAVEL = ControlCenter.TRAVEL

    private val PULL_LEFT = Panel.CUTOUT_RIGHT.toFloat()

    fun startsPull(x: Float, y: Float): Boolean = y >= Panel.HEIGHT - EDGE && x >= PULL_LEFT

    /** How far open, 0 to 1, under a finger that has moved [dy] (down positive). */
    fun progress(open: Boolean, dy: Float): Float =
        ((if (open) 1f else 0f) - dy / TRAVEL).coerceIn(0f, 1f)

    /** Open once let go: a flick (panel units a ms, down positive) wins, else past halfway. */
    fun release(open: Boolean, dy: Float, velocity: Float = 0f): Boolean = when {
        velocity < -ControlCenter.FLING -> true
        velocity > ControlCenter.FLING -> false
        else -> progress(open, dy) > 0.5f
    }

    /** How far up the portal is when its own ground over a guest is fully in. */
    const val GUEST_FADE = 0.35f

    /**
     * Over a guest, how much of the portal's ground (and dim) shows at [progress]: none
     * at rest, so the live guest under the translucent window is all there is, and all of
     * it by [GUEST_FADE] — eased both ends, so it neither cuts in nor stops dead.
     */
    fun guestGround(progress: Float): Float {
        val t = (progress / GUEST_FADE).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
