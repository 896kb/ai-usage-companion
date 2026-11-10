package dev.kb896.aiusage.rear

import kotlin.math.abs
import kotlin.math.exp

/**
 * Where the portal's dome has been moved to (PortalDome.Pose), and where it is going: a finger
 * drags it any way, and let go it glides on at the finger's speed and slows to a stop, as the
 * grid's apps do sideways (SheetPager). No end to it either way. Pure; the service drives it
 * under the sheet's lock.
 */
class DomeMotion {

    private var xTo = 0f
    private var yTo = 0f
    private var xFrom = 0f
    private var yFrom = 0f
    private var startMs = 0L
    private var held = false
    private var downX = 0f
    private var downY = 0f
    private var dx = 0f
    private var dy = 0f
    private val speedX = FingerSpeed()
    private val speedY = FingerSpeed()

    /** Gliding: [at] moves it on. */
    var settling = false
        private set

    /** The tiles go with the finger: the sheet of them is moved back by as much. */
    fun pose(nowMs: Long): PortalDome.Pose = when {
        held -> PortalDome.Pose(xFrom - dx, yFrom - dy)
        settling -> {
            val k = exp(-(nowMs - startMs).coerceAtLeast(0L) / SheetPager.GLIDE_MS)
            PortalDome.Pose(xTo + (xFrom - xTo) * k, yTo + (yFrom - yTo) * k)
        }
        else -> PortalDome.Pose(xTo, yTo)
    }

    /** [pose], and a glide that has arrived comes to rest. */
    fun at(nowMs: Long): PortalDome.Pose {
        val p = pose(nowMs)
        if (settling && abs(p.x - xTo) + abs(p.y - yTo) < SheetPager.REST_PX) {
            settling = false
            return PortalDome.Pose(xTo, yTo)
        }
        return p
    }

    /** A finger lands at [x], [y] to move it: it stops where it is. */
    fun grab(x: Float, y: Float, atMs: Long) {
        val p = pose(atMs)
        xFrom = p.x; yFrom = p.y
        settling = false
        held = true
        downX = x; downY = y; dx = 0f; dy = 0f
        speedX.start(x, atMs); speedY.start(y, atMs)
    }

    fun drag(x: Float, y: Float, atMs: Long): PortalDome.Pose {
        if (!held) return pose(atMs)
        speedX.move(x, atMs); speedY.move(y, atMs)
        dx = x - downX; dy = y - downY
        return pose(atMs)
    }

    /** Let go: on at the finger's speed, slowing to a stop. */
    fun release(x: Float, y: Float, atMs: Long) {
        if (!held) return
        dx = x - downX; dy = y - downY
        val p = pose(atMs)
        held = false
        xFrom = p.x; yFrom = p.y
        xTo = p.x - speedX.at(atMs) * SheetPager.GLIDE_MS
        yTo = p.y - speedY.at(atMs) * SheetPager.GLIDE_MS
        startMs = atMs
        settling = abs(xTo - xFrom) + abs(yTo - yFrom) >= SheetPager.REST_PX
        if (!settling) rest(p)
    }

    /** Stopped where it stands (a tap on a glide). */
    fun stop(nowMs: Long): PortalDome.Pose {
        val p = pose(nowMs)
        held = false
        rest(p)
        return p
    }

    private fun rest(p: PortalDome.Pose) {
        settling = false
        xTo = p.x; yTo = p.y
        xFrom = p.x; yFrom = p.y
    }
}
