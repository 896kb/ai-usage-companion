package dev.kb896.aiusage.rear

/**
 * How bright it is behind a pane, and what that should do to the pane.
 *
 * REAL GLASS TAKES ITS COLOUR FROM WHAT IS BEHIND IT. A fixed tint does not: pick one
 * that reads over a dark panel and it turns into a bright smear the moment somebody sets
 * a photograph as the background, and pick one for the photograph and it disappears over
 * black. Both happened here — the notification card and the charging cell were tuned
 * against a black panel, and the backdrop image arrived afterwards.
 *
 * THE REFERENCE'S OWN METHOD, which is worth stating because it is simpler than it
 * sounds: scale the backdrop down to twenty-five pixels, average their luminance, and
 * drive the glass from that. Twenty-five is not an approximation of a better answer — a
 * pane covers a region and what matters is the region's average, so throwing away the
 * detail IS the measurement.
 *
 * WHERE THIS DIFFERS, and why. The reference reads the composited backdrop back from a
 * layer every frame, on a phone that is showing one demo. This panel is always on, and a
 * per-frame pixel readback is the most expensive thing it could possibly do — it stalls
 * the render thread waiting for the GPU. It does not need to: what is behind these panes
 * is a background image and a colour, and neither changes between frames. So the grid is
 * built once when the image is, and the reading is a handful of array lookups.
 */
object GlassLuminance {

    /**
     * Rec.709 luminance of one colour, 0 to 1.
     *
     * The same weights the reference uses, and they are not arbitrary: the eye is roughly
     * seven times more sensitive to green than to blue, so an unweighted mean calls a
     * saturated blue as bright as a green that is plainly much brighter.
     */
    fun of(argb: Int): Float {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    /** The grid's side. Twenty-five cells, as the reference samples. */
    const val SIDE = 5

    /**
     * The average luminance of the cells a pane covers.
     *
     * [cells] is [SIDE] by [SIDE] in row-major order, over the whole panel. A pane smaller
     * than one cell still gets an answer — the cell it lands in — and a pane covering the
     * panel gets the mean of all of them.
     */
    fun average(
        cells: FloatArray,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        panelWidth: Float,
        panelHeight: Float,
    ): Float {
        if (cells.size < SIDE * SIDE || panelWidth <= 0f || panelHeight <= 0f) return 0f
        val c0 = cell(left, panelWidth)
        val c1 = cell(right, panelWidth)
        val r0 = cell(top, panelHeight)
        val r1 = cell(bottom, panelHeight)
        var sum = 0f
        var n = 0
        for (r in minOf(r0, r1)..maxOf(r0, r1)) {
            for (c in minOf(c0, c1)..maxOf(c0, c1)) {
                sum += cells[r * SIDE + c]
                n++
            }
        }
        return if (n == 0) 0f else sum / n
    }

    private fun cell(v: Float, extent: Float): Int =
        ((v / extent) * SIDE).toInt().coerceIn(0, SIDE - 1)

    /**
     * The reference's response curve: signed, and squared about the middle.
     *
     * Zero at half brightness, running to -1 at black and +1 at white. Squaring keeps the
     * middle of the range quiet — a backdrop that is merely a bit light should barely move
     * the glass, and only a genuinely bright one should push it the whole way. A linear
     * response makes every background feel like it is fighting the panel.
     */
    fun response(luminance: Float): Float {
        val t = (luminance.coerceIn(0f, 1f) * 2f) - 1f
        return if (t < 0f) -(t * t) else t * t
    }

    /**
     * [base] adjusted for a backdrop of this luminance.
     *
     * AGAINST THE BACKDROP, NOT WITH IT. This used to go the other way — lighter over a
     * bright panel, darker over a dark one — on the reference's reasoning that frosted
     * glass held up to daylight glows. That is true of frosting and false of this: the
     * panes here are read AGAINST what is behind them, and a pane that lightens over
     * white and darkens over black is a pane that disappears at both ends of the range,
     * which is precisely where it is most needed. Over white it now goes darker, over
     * black lighter, and either way there is a surface to see.
     *
     * The alpha follows the same logic and had to change with it. It used to thicken over
     * bright backdrops and THIN over dark ones, which under the old direction merely
     * softened a tint that was already too quiet; under this one it would cancel the fix,
     * weakening the pale tint exactly when the backdrop is blackest. It now thickens
     * toward both ends and leaves the middle alone, which is where clear glass works.
     *
     * The alpha is bounded rather than scaled freely. A card is a surface to read type
     * against and it has to stay one at both ends.
     */
    fun reactive(base: Int, luminance: Float): Int {
        val r = response(luminance)
        val away = Math.abs(r)
        val a = (base ushr 24) and 0xFF
        val toward = if (r > 0f) 0x00 else 0xFF
        val mix = lerp(LIFT_MIN, LIFT_MAX, away)
        val alpha = (a * lerp(1f, ALPHA_MAX, away)).toInt().coerceIn(0, 255)
        return (alpha shl 24) or
            (blend((base shr 16) and 0xFF, toward, mix) shl 16) or
            (blend((base shr 8) and 0xFF, toward, mix) shl 8) or
            blend(base and 0xFF, toward, mix)
    }

    /**
     * A tint for a pane that has NONE of its own, taken purely from its backdrop.
     *
     * The buttons are clear glass on purpose — no tint at all, so what you see through
     * them is the panel and nothing else. That is right over most of this panel and wrong
     * at the extremes: over black there is nothing to see through them and the disc
     * vanishes, over a bright photograph they are a hole with a rim.
     *
     * So instead of giving them a tint, this gives them one only where they need it. The
     * squared response is exactly right here, unlike everywhere it has been wrong today:
     * it returns nothing at all over a middling backdrop, which keeps the glass clear in
     * the case that matters, and comes on only as the panel runs to one end or the other.
     *
     * And it comes on OPPOSED, for the reason [reactive] gives at length: a dark wash over
     * a black panel and a white one over a white panel are both invisible, which makes the
     * whole measurement pointless in the two cases it exists to serve.
     */
    fun clearTint(luminance: Float, strength: Float = CLEAR_STRENGTH): Int {
        val r = response(luminance)
        val toward = if (r > 0f) 0x00 else 0xFF
        val a = (Math.abs(r) * strength * 255f).toInt().coerceIn(0, 255)
        return (a shl 24) or (toward shl 16) or (toward shl 8) or toward
    }

    /** How far a clear pane is ever tinted, at the very ends of the range. */
    private const val CLEAR_STRENGTH = 0.34f

    private fun blend(from: Int, to: Int, t: Float): Int =
        (from + (to - from) * t.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

    /** How far the tint moves at a middling backdrop — the reference's 0.1 brightness. */
    private const val LIFT_MIN = 0.1f

    /** And at either extreme of it. The reference's 0.5. */
    private const val LIFT_MAX = 0.5f

    /** How much heavier a pane gets at the ends of the range, where it has work to do. */
    private const val ALPHA_MAX = 1.25f
}
