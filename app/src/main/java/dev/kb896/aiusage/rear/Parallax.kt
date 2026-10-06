package dev.kb896.aiusage.rear

import kotlin.math.exp

/**
 * How far a background moves for depth: shifts from -1 to 1 on each axis, one being the
 * whole of the [MARGIN] there is past the visible frame on that side. The background is
 * drawn [MARGIN] bigger on every side for this — from real pixels, not by enlarging, where
 * the picture or the clip has them.
 */
object Parallax {
    /** The margin past the frame on each side, as a share of the panel's size. */
    const val MARGIN = 0.06f

    /** Scroll: the first page at one end of the margin, the last at the other, however many there are. */
    fun scroll(offset: Float, span: Float, pages: Int): Float {
        if (pages <= 1 || span <= 0f) return 0f
        return ((offset / ((pages - 1) * span)) * 2f - 1f).coerceIn(-1f, 1f)
    }

    /** Tilt and scroll together, never past the margin. */
    fun combine(tiltX: Float, tiltY: Float, scrollY: Float): Pair<Float, Float> =
        tiltX.coerceIn(-1f, 1f) to (tiltY + scrollY).coerceIn(-1f, 1f)

    /** A shift in pixels along a side of [size]. */
    fun px(shift: Float, size: Float): Float = shift * MARGIN * size
}

/**
 * Tilt, from the phone's orientation: how far it is tipped from where it has been resting,
 * as -1 to 1 on each axis ([MAX_TILT] reaching the edge). The resting angle follows the
 * phone slowly ([RECENTRE_MS]), so it is centred however the phone is held, and a tilt is
 * felt as a movement rather than a position. The output is smoothed a little
 * ([SMOOTH_MS]) against the sensor's jitter.
 */
class TiltTracker {
    var x = 0f; private set
    var y = 0f; private set
    private var restRoll = 0f
    private var restPitch = 0f
    private var started = false

    fun update(roll: Float, pitch: Float, dtMs: Long) {
        if (!started) {
            started = true
            restRoll = roll
            restPitch = pitch
            return
        }
        val follow = 1f - exp(-dtMs / RECENTRE_MS)
        restRoll += (roll - restRoll) * follow
        restPitch += (pitch - restPitch) * follow
        val smooth = 1f - exp(-dtMs / SMOOTH_MS)
        x += (((roll - restRoll) / MAX_TILT).coerceIn(-1f, 1f) - x) * smooth
        y += (((pitch - restPitch) / MAX_TILT).coerceIn(-1f, 1f) - y) * smooth
    }

    /** Forget where it rested: the next reading is the new rest. */
    fun reset() {
        started = false
        x = 0f
        y = 0f
    }

    companion object {
        /** About 15°, in radians: a tip this far reaches the edge of the margin. */
        const val MAX_TILT = 0.2618f
        const val RECENTRE_MS = 3000f
        const val SMOOTH_MS = 60f
    }
}
