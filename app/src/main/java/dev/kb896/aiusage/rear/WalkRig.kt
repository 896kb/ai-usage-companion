package dev.kb896.aiusage.rear

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.PI
import kotlin.math.sin

/**
 * The walking mascot, animated from the site's GSAP timeline rather than baked frames.
 *
 * Every other clip is a sequence of SVG frames the artist drew, so baking them into rect
 * lists loses nothing. This one has no frames at all — the site builds it in JavaScript
 * from a nine-rect rig and a timeline of tweens, and the whole 11.5s performance is
 * transforms: a lean, a look, a hop, a walk across the screen, a second look, and a jump.
 *
 * Baking it was tried and is what the user saw as wrong. Sampling only the 0.2s leg cycle
 * produced a mascot marching on the spot at a third of the real speed, missing the lean,
 * the look, the hop and the jump — five sixths of the timeline. Sampling the whole 11.5s
 * instead would need ~290 frames of 9 rects, and still could not express the rotations,
 * because [MascotFrames.Rect] is axis-aligned by construction.
 *
 * So this reproduces the timeline directly. The transform hierarchy is the SVG's:
 *
 *     root  (translate: the hop, the jump, the travel across the row)
 *       legs x4   (rotate + scaleY about a pivot that MOVES — see legPivotY)
 *       body      (rotate about 53,65 + translate: the lean)
 *         hands x2  (translate: the jump's arm swing)
 *         eyes  x2  (translate: the look)
 *
 * Timings are the parsed timeline verbatim, in seconds, so they can be checked against it
 * line by line. Values are in the source viewBox's units (107x86) and scaled at draw time.
 */
object WalkRig {

    private const val VB_W = 107f
    private const val VB_H = 86f

    /** Same aspect the baked `stand` clip reports, because it is the same rig. */
    const val ASPECT = VB_W / VB_H

    /**
     * One full loop. The timeline's last tween ends at 10.51s, but its final callback —
     * the one that snaps the mascot back to the start — is scheduled at "+=1", so GSAP's
     * total duration, and the point at which `repeat: -1` restarts, is a second later.
     * That second is the pause at the far end of the walk, and dropping it would cut the
     * loop short and teleport the mascot back mid-pose.
     */
    const val DURATION_MS = 11_510L

    /** How far above its resting top the jump takes it, as a multiple of its height. */
    const val HEADROOM = 90f / VB_H

    private const val BODY_COLOR = 0xFFB5BD68.toInt()
    private const val EYE_COLOR = 0xFF1D1F21.toInt()

    // --- easings -------------------------------------------------------------------
    //
    // GSAP's power names are off by one from the exponent everyone assumes: Power1 is
    // quadratic, Power2 CUBIC, Power3 quartic. Reading power2.out as a quadratic makes
    // every ease in this timeline visibly lazier than the site's.

    private fun linear(t: Float) = t
    private fun cubicOut(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)
    private fun cubicIn(t: Float) = t * t * t
    private fun quartIn(t: Float) = t * t * t * t
    private fun quadInOut(t: Float) =
        if (t < 0.5f) 2f * t * t else 1f - 2f * (1f - t) * (1f - t)
    private fun sineOut(t: Float) = sin(t * (PI.toFloat() / 2f))

    // --- a minimal tween track -----------------------------------------------------

    private class Seg(val start: Float, val end: Float, val to: Float, val ease: (Float) -> Float)

    /**
     * One animated property over the whole timeline.
     *
     * A GSAP `.to()` tween runs from whatever the property happens to be when it starts,
     * so each segment's start value is the previous segment's target. That chaining is
     * resolved once, at construction: no two segments of the same property overlap in
     * this timeline, which is what makes the shortcut exact rather than approximate.
     */
    private class Track(private val initial: Float, segs: List<Seg>) {
        private val segs = segs.sortedBy { it.start }
        private val from = FloatArray(segs.size).also {
            var v = initial
            for (i in this.segs.indices) { it[i] = v; v = this.segs[i].to }
        }

        fun at(t: Float): Float {
            var value = initial
            for (i in segs.indices) {
                val s = segs[i]
                if (t < s.start) return value
                value = if (t >= s.end) s.to
                else from[i] + (s.to - from[i]) * s.ease((t - s.start) / (s.end - s.start))
            }
            return value
        }
    }

    private fun track(initial: Float, vararg segs: Seg) = Track(initial, segs.toList())

    // --- the timeline --------------------------------------------------------------

    private const val WALK_AT = 4.03f
    private const val JUMP_AT = 9.53f

    /** Leg geometry: x of each 11-wide leg, all y=60 h=26. */
    private val LEG_X = floatArrayOf(11f, 32f, 64f, 85f)
    private const val LEG_Y = 60f
    private const val LEG_W = 11f
    private const val LEG_H = 26f

    private val LEAN_L_ROT = floatArrayOf(-7f, -8f, -8f, -9f)
    private val LEAN_L_SCALE = floatArrayOf(1.35f, 1.3f, 1.2f, 1.15f)
    private val LEAN_R_ROT = floatArrayOf(9f, 8f, 8f, 7f)
    private val LEAN_R_SCALE = floatArrayOf(1.15f, 1.2f, 1.3f, 1.35f)

    private val eyesX = track(
        0f,
        Seg(0f, 0.4f, -3f, ::cubicOut),
        Seg(1.9f, 2.3f, 4f, ::cubicOut),
        Seg(3.5f, 3.7f, 0f, ::cubicOut),
        Seg(WALK_AT, WALK_AT + 0.2f, 4f, ::cubicOut),
        Seg(8.83f, 9.23f, 4f, ::cubicOut),
    )

    private val eyesY = track(
        0f,
        Seg(1.9f, 2.3f, 12f, ::cubicOut),
        Seg(3.5f, 3.7f, 23f, ::cubicOut),
        Seg(WALK_AT, WALK_AT + 0.2f, 0f, ::cubicOut),
        Seg(6.53f, 6.93f, 12f, ::cubicOut),
        Seg(7.53f, 7.83f, 23f, ::cubicOut),
        Seg(8.83f, 9.23f, 0f, ::cubicOut),
    )

    private val bodyRot = track(
        0f,
        Seg(0f, 0.4f, -3f, ::cubicOut),
        Seg(1.9f, 2.3f, 3f, ::cubicOut),
        Seg(3.5f, 3.7f, 0f, ::cubicOut),
        Seg(6.53f, 6.93f, 3f, ::cubicOut),
        Seg(8.83f, 9.23f, 0f, ::cubicOut),
    )

    private val bodyX = track(
        0f,
        Seg(0f, 0.4f, -3f, ::cubicOut),
        Seg(1.9f, 2.3f, 3f, ::cubicOut),
        Seg(3.5f, 3.7f, 0f, ::cubicOut),
        Seg(6.53f, 6.93f, 3f, ::cubicOut),
        Seg(8.83f, 9.23f, 0f, ::cubicOut),
    )

    private val bodyY = track(
        0f,
        Seg(0f, 0.4f, -5f, ::cubicOut),
        Seg(1.9f, 2.3f, -5f, ::cubicOut),
        Seg(3.5f, 3.7f, 0f, ::cubicOut),
        Seg(6.53f, 6.93f, -5f, ::cubicOut),
        Seg(8.83f, 9.23f, 0f, ::cubicOut),
        Seg(9.43f, JUMP_AT, 8f, ::quartIn),
        Seg(JUMP_AT, 9.95f, 0f, ::sineOut),
    )

    private val handY = track(
        0f,
        Seg(9.43f, JUMP_AT, 10f, ::quartIn),
        Seg(JUMP_AT, 9.95f, -12f, ::sineOut),
        Seg(10.13f, 10.33f, 0f, ::quartIn),
        Seg(10.38f, 10.43f, 6f, ::cubicIn),
        Seg(10.43f, 10.51f, 0f, ::cubicOut),
    )

    private val rootY = track(
        0f,
        Seg(3.7f, 3.88f, -18f, ::cubicOut),   // the hop up
        Seg(3.88f, WALK_AT, 0f, ::quartIn),   // and down, straight into the walk
        Seg(JUMP_AT, 9.95f, -90f, ::sineOut), // the jump, well outside the rig's box
        Seg(10.13f, 10.33f, 0f, ::quartIn),   // after a beat of hang time
    )

    /**
     * Travel, as a fraction of the span it is given.
     *
     * The site splits it 55/45 between the walk and the jump (`j = .55*w`, then the jump
     * moves `+= w - j`), so the mascot walks most of the way and clears the rest in one
     * leap. Expressed as a fraction because our span is a row width, not the site's.
     */
    private val rootXFraction = track(
        0f,
        Seg(WALK_AT, 6.23f, 0.55f, ::linear),
        Seg(JUMP_AT, 10.38f, 1f, ::quadInOut),
    )

    private val legRot = Array(4) { i ->
        track(
            0f,
            Seg(0f, 0.4f, LEAN_L_ROT[i], ::cubicOut),
            Seg(1.9f, 2.3f, LEAN_R_ROT[i], ::cubicOut),
            Seg(3.5f, 3.7f, 0f, ::cubicOut),
            Seg(6.53f, 6.93f, LEAN_R_ROT[i], ::cubicOut),
            Seg(8.83f, 9.23f, 0f, ::cubicOut),
        )
    }

    /**
     * The gait: each diagonal pair squashes to 45% and back on a 0.1s beat, the pairs
     * half a beat apart, for the 2.2s of the walk. Ten cycles, generated rather than
     * listed, because the timeline lists all forty tweens by hand.
     */
    private val legScaleY = Array(4) { i ->
        val leadingPair = i == 0 || i == 2          // leg1 and leg3 step first
        val offset = if (leadingPair) 0f else 0.1f
        val gait = buildList {
            for (k in 0 until 10) {
                val down = WALK_AT + offset + 0.2f * k
                add(Seg(down, down + 0.1f, 0.45f, ::cubicOut))
                add(Seg(down + 0.1f, down + 0.2f, 1f, ::cubicIn))
            }
        }
        Track(
            1f,
            listOf(
                Seg(0f, 0.4f, LEAN_L_SCALE[i], ::cubicOut),
                Seg(1.9f, 2.3f, LEAN_R_SCALE[i], ::cubicOut),
                Seg(3.5f, 3.7f, 1f, ::cubicOut),
            ) + gait + listOf(
                Seg(6.13f, 6.21f, 1f, ::cubicIn),
                Seg(6.53f, 6.93f, LEAN_R_SCALE[i], ::cubicOut),
                Seg(8.83f, 9.23f, 1f, ::cubicOut),
            ),
        )
    }

    /**
     * The legs' pivot moves, and it is the difference between leaning and walking.
     *
     * Standing, the pivot is the FOOT (y=86): a leg scaling past 1 stretches upward and
     * lifts the body, which is what makes the lean read as leaning rather than sinking.
     * Walking, the timeline resets it to the HIP (y=60), so scaling to 45% raises the
     * foot into a step instead of driving it through the floor.
     */
    private fun legPivotY(t: Float) = if (t >= WALK_AT && t < 6.23f) LEG_Y else LEG_Y + LEG_H

    /**
     * @param left left edge of the travel span
     * @param top the rig's top edge at rest
     * @param h the rig's height, excluding anything it leaves the box for
     * @param span horizontal distance to cross; the rig's own width must already be
     *   subtracted, or it walks off the end
     * @param rise how far above [top] it may go, in px. Defaults to the timeline's own
     *   reach, [HEADROOM] x [h]. Anything smaller scales the vertical motion rather than
     *   clipping it, so the jump stays a jump and only gets less air — a clamp would
     *   read as the mascot hitting a ceiling.
     */
    fun draw(
        canvas: Canvas,
        nowMs: Long,
        left: Float,
        top: Float,
        h: Float,
        span: Float,
        paint: Paint,
        rise: Float = HEADROOM * h,
    ) {
        val t = (nowMs % DURATION_MS) / 1000f
        val u = h / VB_H
        val lift = (rise / (HEADROOM * h)).coerceIn(0f, 1f)
        val ox = left + rootXFraction.at(t) * span
        val oy = top + rootY.at(t) * u * lift

        fun rect(x: Float, y: Float, w: Float, hh: Float, color: Int) {
            paint.color = color
            canvas.drawRect(ox + x * u, oy + y * u, ox + (x + w) * u, oy + (y + hh) * u, paint)
        }

        // Legs, behind the body, each on its own pivot.
        //
        // The source clips these to the ground line; we do not. The only thing that
        // crosses it is the bottom corner of a leaning leg, by half its width times
        // sin(9°) — under one source unit, which is a fraction of a pixel at any size
        // this is drawn.
        val pivotY = legPivotY(t)
        for (i in 0 until 4) {
            val px = ox + (LEG_X[i] + LEG_W / 2f) * u
            val py = oy + pivotY * u
            canvas.save()
            canvas.rotate(legRot[i].at(t), px, py)
            canvas.scale(1f, legScaleY[i].at(t), px, py)
            rect(LEG_X[i], LEG_Y, LEG_W, LEG_H, BODY_COLOR)
            canvas.restore()
        }

        canvas.save()
        canvas.translate(bodyX.at(t) * u, bodyY.at(t) * u)
        canvas.rotate(bodyRot.at(t), ox + 53f * u, oy + 65f * u)

        rect(11f, 0f, 85f, 65f, BODY_COLOR)

        val hy = handY.at(t)
        rect(85f, 21f + hy, 22f, 23f, BODY_COLOR)
        rect(0f, 21f + hy, 22f, 23f, BODY_COLOR)

        val ex = eyesX.at(t)
        val ey = eyesY.at(t)
        rect(75f + ex, 11f + ey, 11f, 11f, EYE_COLOR)
        rect(21f + ex, 11f + ey, 11f, 11f, EYE_COLOR)

        canvas.restore()
    }
}
