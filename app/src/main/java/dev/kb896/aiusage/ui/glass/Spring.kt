package dev.kb896.aiusage.ui.glass

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One damped spring, integrated by hand.
 *
 * The catalog animates with Compose's spring(dampingRatio, stiffness). This is the same
 * physics for unit mass — damping coefficient 2 * ratio * sqrt(stiffness) — stepped with
 * semi-implicit Euler in substeps small enough to be stable at any stiffness used here.
 * No dependency, no Choreographer: something else calls [step] with elapsed time.
 */
class Spring(
    val stiffness: Float,
    val dampingRatio: Float,
    val threshold: Float,
    initial: Float,
) {
    var value = initial; private set
    var velocity = 0f; private set
    var target = initial

    val settled: Boolean
        get() = abs(value - target) < threshold && abs(velocity) < threshold * 10f

    fun snap(v: Float) { value = v; target = v; velocity = 0f }

    /** Advance by [dt] seconds. True while still moving. */
    fun step(dt: Float): Boolean {
        var remaining = min(dt, MAX_STEP)
        val damping = 2f * dampingRatio * sqrt(stiffness)
        while (remaining > 0f) {
            val h = min(remaining, SUBSTEP)
            val accel = -stiffness * (value - target) - damping * velocity
            velocity += accel * h
            value += velocity * h
            remaining -= h
        }
        if (settled) { value = target; velocity = 0f; return false }
        return true
    }

    private companion object {
        /** A frame longer than this is a hitch, not motion; treat it as one long frame. */
        const val MAX_STEP = 1f / 30f
        const val SUBSTEP = 1f / 240f
    }
}
