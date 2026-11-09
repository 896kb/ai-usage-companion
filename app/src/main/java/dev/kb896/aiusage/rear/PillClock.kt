package dev.kb896.aiusage.rear

/**
 * The analog clock face of glass pills (PanelConfig.clockFace): where its dial stands and where
 * its hands point. Pure, in panel units, so it is tested away from the view; PillClockPainter
 * draws it.
 *
 * After the capsules of the Xiaomi 18 Pro's wallpaper, the motif only: hands and nothing else,
 * no markers, no numerals (the owner, 2026-11-09).
 */
object PillClock {

    /** The dial: its centre and radius. */
    class Dial(val cx: Float, val cy: Float, val r: Float) {
        val left get() = cx - r
        val top get() = cy - r
        val right get() = cx + r
        val bottom get() = cy + r
    }

    /** One hand, in fractions of the dial's radius: out from the centre, across, and behind it. */
    class Hand(val length: Float, val thickness: Float, val tail: Float)

    // Bigger than first drawn (the owner, 2026-11-09): pills you read at a glance, not needles.
    val HOUR = Hand(length = 0.62f, thickness = 0.26f, tail = 0.16f)
    val MINUTE = Hand(length = 0.92f, thickness = 0.18f, tail = 0.16f)
    val SECOND = Hand(length = 0.96f, thickness = 0.07f, tail = 0.16f)

    /** Degrees clockwise from twelve. */
    class Angles(val hour: Float, val minute: Float, val second: Float)

    /** A hand's box, pointing straight up from the centre: rotated by its angle about the centre to stand. */
    class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    fun angles(hour: Int, minute: Int, second: Int, millis: Int): Angles {
        val s = second + millis / 1000f
        val m = minute + s / 60f
        val h = hour % 12 + m / 60f
        return Angles(hour = 30f * h, minute = 6f * m, second = 6f * s)
    }

    /**
     * The biggest dial in the band from [top] to [bottom] (between the date's row over it and
     * the slider's band under it), between [left] (the lenses' edge) and [right], less
     * [MARGIN]; centred on [cx] across, moved in where that would take it past either side, and
     * in the middle of the band down.
     */
    fun dial(cx: Float, left: Float, right: Float, top: Float, bottom: Float): Dial {
        val r = (minOf(right - left, bottom - top) / 2f - MARGIN).coerceAtLeast(1f)
        val x = cx.coerceIn(left + r, right - r)
        return Dial(x, (top + bottom) / 2f, r)
    }

    fun box(dial: Dial, hand: Hand): Box {
        val half = hand.thickness * dial.r / 2f
        return Box(dial.cx - half, dial.cy - hand.length * dial.r, dial.cx + half, dial.cy + hand.tail * dial.r)
    }

    /**
     * The second hand part-way out of the minute hand, at [p] from 0 (inside it, as Seconds is
     * switched on) to 1 (its own place, length and width). It peels off sideways along the
     * shortest way round to [second] from [minute], and grows as it goes. [web] is the sticky
     * glass still joining the two: whole at first, stretched thin, gone by [WEB_SNAP] (the owner,
     * 2026-11-09: "a sticky cohesive shape-to-shape animation"). Switched off, the same run back.
     */
    class Split(val angle: Float, val length: Float, val thickness: Float, val web: Float)

    fun split(minute: Float, second: Float, p: Float): Split {
        val t = p.coerceIn(0f, 1f)
        // Eased in and out, cubic: a soft start, a quick middle, a soft landing — and nothing
        // past its place (the owner, 2026-11-09: no overshoot). Switched off, the same backwards.
        val e = if (t < 0.5f) 4f * t * t * t else 1f - (2f - 2f * t).let { it * it * it } / 2f
        val delta = ((second - minute) % 360f + 540f) % 360f - 180f
        val angle = ((minute + delta * e) % 360f + 360f) % 360f
        val length = MINUTE.length * BUD_LENGTH + (SECOND.length - MINUTE.length * BUD_LENGTH) * e
        // The whole bottle, body and neck, at [BUD_WIDTH] of its width as it leaves: small enough
        // to lie inside the minute hand.
        val thickness = SECOND.thickness * (BUD_WIDTH + (1f - BUD_WIDTH) * e)
        val w = ((t - WEB_HOLD) / (WEB_SNAP - WEB_HOLD)).coerceIn(0f, 1f)
        return Split(angle, length, thickness, web = 1f - w * w * (3f - 2f * w))
    }

    /**
     * How far out from the centre the web between the minute hand (at [minute]) and the second
     * hand (at [second], [secondLength] long and [secondThickness] across, in the dial's radius
     * [r]) still reaches, at [web] of its whole: along the shorter hand while they lie together,
     * and only as far as the gap between their facing edges is still under [NECK_GAP] of the
     * minute hand's width — a neck where they are close, never a fan between hands far apart.
     * Panel units.
     */
    fun webReach(r: Float, minute: Float, second: Float, secondLength: Float, secondThickness: Float, web: Float): Float {
        val most = minOf(MINUTE.length, secondLength) * r * WEB_REACH
        val apart = Math.abs((((second - minute) % 360f) + 540f) % 360f - 180f)
        val sinHalf = Math.sin(Math.toRadians(apart / 2.0)).toFloat()
        val wm = MINUTE.thickness * r
        val ws = secondThickness * r
        val neck = if (sinHalf < 1e-4f) most else (NECK_GAP * wm + (wm + ws) / 2f) / (2f * sinHalf)
        return web.coerceIn(0f, 1f) * minOf(most, neck)
    }

    /** At its whole, the web reaches this much of the shorter hand. */
    const val WEB_REACH = 0.9f
    /** The widest gap between the hands' facing edges the web still bridges, in the minute hand's width. */
    private const val NECK_GAP = 0.6f

    /** The second hand as it leaves the minute hand, in that hand's length and width. */
    private const val BUD_LENGTH = 0.85f
    /** The second hand's width as it leaves the minute hand, in its own: its body then fits inside. */
    const val BUD_WIDTH = 0.8f

    /**
     * THE SECOND HAND IS A BOTTLE (the owner, 2026-11-09): a wider body round the centre, a
     * shoulder, and the long thin neck that points. In its neck's width ([Hand.thickness] of
     * [SECOND]): the body is [BOTTLE_BODY] times as wide, reaches [BOTTLE_BODY_TOP] of the dial's
     * radius out past the centre (and back down the tail), and the shoulder narrows it to the
     * neck over [BOTTLE_SHOULDER].
     */
    const val BOTTLE_BODY = 2.2f
    const val BOTTLE_BODY_TOP = 0.12f
    const val BOTTLE_SHOULDER = 0.10f
    /** The web holds whole until here, then thins, and is gone at [WEB_SNAP]. */
    private const val WEB_HOLD = 0.12f
    const val WEB_SNAP = 0.62f

    /** Clear of the band's edges, panel units. */
    const val MARGIN = 6f
}
