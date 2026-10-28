package dev.kb896.aiusage.rear

import dev.kb896.aiusage.Panel

/**
 * The App Portal's pull: the control centre's, mirrored to the bottom edge. A finger that
 * lands within [EDGE] of the bottom, right of the lens column, pulls the portal's sheet up
 * (see EdgeGestures, which reads it off the rear touchscreen, and SheetMotion, which the
 * sheet's top edge follows from there). Up is negative dy, as MotionEvent has it.
 */
object PortalPull {

    /** The control centre's edge width, for the same reason: a finger on the back lands short. */
    const val EDGE = ControlCenter.EDGE

    private val PULL_LEFT = Panel.CUTOUT_RIGHT.toFloat()

    fun startsPull(x: Float, y: Float): Boolean = y >= Panel.HEIGHT - EDGE && x >= PULL_LEFT
}
