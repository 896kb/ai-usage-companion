package dev.kb896.aiusage.rear

import dev.kb896.aiusage.Panel

/**
 * The App Portal's pull: the control centre's, mirrored to the bottom edge. A finger that
 * lands within [EDGE] of the bottom, right of the lens column, pulls the portal's sheet up
 * over [TRAVEL] (see EdgeGestures, which reads it off the rear touchscreen, and
 * SheetMotion, which follows it from there). Up is negative dy, as MotionEvent has it.
 */
object PortalPull {

    /** The control centre's edge width, for the same reason: a finger on the back lands short. */
    const val EDGE = ControlCenter.EDGE
    /** And its travel: a pull this long opens the sheet all the way. */
    const val TRAVEL = ControlCenter.TRAVEL

    /**
     * A finger at [x], [y] (panel units) starts a pull: in the bottom band, right of [left] —
     * the lens column's edge in panel units, given, never assumed (RearGeometry.cutoutUnits:
     * left of 296 on a Pro Max, whose lenses keep their pixels).
     */
    fun startsPull(x: Float, y: Float, left: Float): Boolean = y >= Panel.HEIGHT - EDGE && x >= left
}
