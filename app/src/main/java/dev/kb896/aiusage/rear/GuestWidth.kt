package dev.kb896.aiusage.rear

import kotlin.math.roundToInt

/**
 * How wide an app kept right of the cameras is (PanelConfig.portalWidth): pure, so the rule is
 * tested away from the shell.
 *
 * WHY A WIDTH AND NOT A DPI. Some apps size themselves by the window's width whatever the
 * density: TikTok draws its 360-wide design across all of it (ByteDance's screen adaptation),
 * so on the 608 px right of the lenses its bottom bar took a quarter of the height at any DPI
 * (the owner, 2026-11-08). Only the window's shape changes that: narrower, and the same design
 * is drawn smaller against the same height.
 *
 * In the rear's own pixels, from [min] to the whole width right of the lenses ([full]), in
 * steps of [STEP]. No width (null) is the whole of it, and so is any width that reaches it.
 * The app's box is centred in the area, the rest beside it coloured as an app narrower than
 * its area already is (CameraStrip).
 */
object GuestWidth {
    const val STEP = 8

    /**
     * The narrowest: a 9:16 portrait at [rear]'s height, in steps (322 → 320 on the 17 Pro's 572) —
     * narrower than any phone app is built for. From the rear's own height, for every model.
     */
    fun min(rear: RearGeometry): Int = maxOf(STEP, Math.round(rear.heightPx * 9f / 16f / STEP) * STEP).coerceAtMost(full(rear))

    /** The whole width right of [rear]'s lenses. */
    fun full(rear: RearGeometry): Int = rear.widthPx - rear.cutoutPx

    /** [w] in steps, within [min] and [full]; null for the whole width (or [w] null). */
    fun clamp(rear: RearGeometry, w: Int?): Int? {
        if (w == null) return null
        val f = full(rear)
        val stepped = (Math.round(w / STEP.toFloat()) * STEP).coerceIn(min(rear), f)
        return stepped.takeIf { it < f }
    }

    /** The width an app is drawn at: its own, or the whole. */
    fun of(rear: RearGeometry, w: Int?): Int = clamp(rear, w) ?: full(rear)

    /** The box an app [w] wide stands in: right of the lenses, centred in what is there. */
    fun area(rear: RearGeometry, w: Int?): GuestShape.Bounds {
        val f = full(rear)
        val width = of(rear, w)
        val left = rear.cutoutPx + (f - width) / 2
        return GuestShape.Bounds(left, 0, left + width, rear.heightPx)
    }

    /** What the sheet's value says. */
    fun label(rear: RearGeometry, w: Int?): String = clamp(rear, w)?.let { "$it px" } ?: "Full"

    /** Where on a slider [w] stands, 0 at [min] to 1 at the whole width. */
    fun fraction(rear: RearGeometry, w: Int?): Float {
        val f = full(rear)
        val lo = min(rear)
        if (f <= lo) return 1f
        return ((of(rear, w) - lo) / (f - lo).toFloat()).coerceIn(0f, 1f)
    }

    /** The width a slider at [t] (0 to 1) stands for: null at the whole width. */
    fun at(rear: RearGeometry, t: Float): Int? =
        clamp(rear, (min(rear) + t.coerceIn(0f, 1f) * (full(rear) - min(rear))).roundToInt())
}
