package dev.kb896.aiusage.rear

/**
 * The colour of the camera strip (x < Panel.CUTOUT_RIGHT) beside a guest on a private display
 * (PrivateDisplay), and of whatever else of the rear the app leaves bare (a portrait app's bars):
 * the app's own background, read off its leftmost columns, so the strip reads as part of it — or
 * black where there is no one colour to take. Pure; CameraStrip shows it.
 *
 * [pick]: per channel, the median of the sampled pixels; the edge is uniform enough when
 * every channel's median absolute deviation is within [MAD_LIMIT] — text or an icon
 * touching the edge is outvoted, a photo or a gradient is not, and gives black.
 */
object StripColor {

    /** Over this, per channel (0–255), the edge is busy and the strip goes black. */
    const val MAD_LIMIT = 8
    /** A new colour eases in over this long. */
    const val EASE_MS = 200L

    private const val BLACK = 0xFF000000.toInt()

    /** The guest's columns read for the colour, from its left edge. */
    const val SAMPLE_W = 6

    /**
     * Where on the app's layer the colour is read: the first [SAMPLE_W] columns of the app itself,
     * [app] as it stands on [rear] (GuestShape.appOnRear), its whole height. Not the layer's: a
     * letterboxed app has a black bar there, and the strip would read black beside any app.
     */
    fun sample(rear: RearGeometry, app: GuestShape.Bounds = GuestShape.appArea(rear)): GuestShape.Bounds {
        val x = app.left - rear.cutoutPx
        return GuestShape.Bounds(x, app.top, x + SAMPLE_W, app.bottom)
    }

    /**
     * What the colour covers on [rear], in its pixels, with [app] standing where it does: all that
     * is not the app, the lens column first. Only the lens column for an app filling its area.
     * [keyboardTop]: where a keyboard on the app's display starts ([keyboardTop]'s reading), null
     * with none: it is the display's whole width, so the bars stop above it; the lens column, not
     * the display's, does not.
     */
    fun around(rear: RearGeometry, app: GuestShape.Bounds = GuestShape.appArea(rear), keyboardTop: Int? = null): List<GuestShape.Bounds> {
        val lens = minOf(rear.cutoutPx, app.left)
        val bottom = keyboardTop?.coerceIn(0, rear.heightPx) ?: rear.heightPx
        return listOf(
            GuestShape.Bounds(0, 0, lens, rear.heightPx),
            GuestShape.Bounds(lens, 0, app.left, bottom),
            GuestShape.Bounds(app.right, 0, rear.widthPx, bottom),
            GuestShape.Bounds(app.left, 0, app.right, minOf(app.top, bottom)),
            GuestShape.Bounds(app.left, app.bottom, app.right, bottom),
        ).filter { it.width > 0 && it.height > 0 }
    }

    /** A bar narrower than this is not read for a keyboard. */
    const val PROBE_MIN_BAR = 6
    const val PROBE_W = 2

    /**
     * Where on the app's layer (its display's pixels) a keyboard is looked for: a thin column in
     * the middle of the wider of the bars beside [app], its whole height; null with no bar to look
     * in (an app the display's width: the keyboard is over the app, nothing of ours over it).
     * Letterbox bars are black in the picture, and only the keyboard, the whole display wide,
     * draws there.
     */
    fun keyboardProbe(rear: RearGeometry, app: GuestShape.Bounds, area: GuestShape.Bounds): GuestShape.Bounds? {
        val left = app.left - area.left
        val right = area.right - app.right
        val (from, width) = if (left >= right) area.left to left else app.right to right
        if (width < PROBE_MIN_BAR) return null
        val x = from - area.left + width / 2 - PROBE_W / 2
        return GuestShape.Bounds(x, 0, x + PROBE_W, area.height)
    }

    /** Less than this of not-black in a bar is not a keyboard. */
    const val KEYBOARD_MIN_PX = 40
    /** Over this, in any channel, a pixel is not the bar's black. */
    const val NOT_BLACK = 12

    /**
     * From one column of a bar, top to bottom (ARGB, [scale] rows per display pixel): where a
     * keyboard starts, in display pixels — the first row that is not the bar's black — or null
     * where there is too little of that to be one. Not "the run that reaches the bottom": Gboard
     * leaves a black band under its keys, and its theme may have black between them (read on the
     * phone 2026-11-06: black, keys, a light row, keys, black at the bottom).
     */
    fun keyboardTop(column: IntArray, scale: Float = 1f): Int? {
        val first = column.indexOfFirst(::notBlack)
        if (first < 0) return null
        if (column.count(::notBlack) / scale < KEYBOARD_MIN_PX) return null
        return (first / scale).toInt()
    }

    private fun notBlack(c: Int) =
        ((c shr 16) and 0xFF) > NOT_BLACK || ((c shr 8) and 0xFF) > NOT_BLACK || (c and 0xFF) > NOT_BLACK

    /** The strip's colour for [pixels] (ARGB, alpha ignored): opaque, or black. */
    fun pick(pixels: IntArray): Int {
        if (pixels.isEmpty()) return BLACK
        var out = BLACK
        for (shift in intArrayOf(16, 8, 0)) {
            val ch = IntArray(pixels.size) { (pixels[it] shr shift) and 0xFF }
            val med = median(ch)
            val mad = median(IntArray(ch.size) { kotlin.math.abs(ch[it] - med) })
            if (mad > MAD_LIMIT) return BLACK
            out = out or (med shl shift)
        }
        return out
    }

    private fun median(v: IntArray): Int { v.sort(); return v[v.size / 2] }

    /** Between samples while the colour is new or the rear is being used. */
    const val FAST_MS = 500L
    /** Between samples once it has held [STABLE] samples running. */
    const val SLOW_MS = 2_000L
    const val STABLE = 3

    /**
     * How long until the next sample: [FAST_MS] after a change, a touch or a new guest
     * ([poke]); [SLOW_MS] once [STABLE] samples running have changed nothing. A sample is a
     * capture of its own (8–37 ms measured on the phone), so a page left still is not read
     * four times a second.
     */
    class Pace {
        private var unchanged = 0
        fun next(changed: Boolean): Long {
            if (changed) unchanged = 0 else unchanged++
            return if (unchanged >= STABLE) SLOW_MS else FAST_MS
        }
        fun poke() { unchanged = 0 }
    }

    /** The strip's colour on its way to the latest pick: eased over [EASE_MS], the first taken as it is. */
    class Ease {
        private var from = 0
        private var to = 0
        private var startMs = 0L
        private var seeded = false

        fun at(target: Int, nowMs: Long): Int {
            if (!seeded) { seeded = true; from = target; to = target; startMs = nowMs - EASE_MS; return target }
            val now = value(nowMs)
            if (target != to) { from = now; to = target; startMs = nowMs }
            return value(nowMs)
        }

        fun settled(nowMs: Long): Boolean = nowMs - startMs >= EASE_MS

        private fun value(nowMs: Long): Int {
            val t = ((nowMs - startMs).toFloat() / EASE_MS).coerceIn(0f, 1f)
            return if (t >= 1f) to else RampColor.lerp(from, to, Tween.easeInOut(t))
        }
    }
}
