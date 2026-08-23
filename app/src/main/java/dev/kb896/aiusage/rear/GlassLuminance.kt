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
     * The reference expresses this as brightness and contrast on the backdrop itself. This
     * panel has no colour controls in its shader, so it says the same thing with the tint,
     * which is the one knob a pane here actually has: over a bright backdrop the glass
     * goes lighter and more opaque, which is what frosting looks like against daylight;
     * over a dark one it goes darker and thinner, and gets out of the way.
     *
     * The alpha is bounded rather than scaled freely. A card is a surface to read type
     * against and it has to stay one at both ends.
     */
    fun reactive(base: Int, luminance: Float): Int {
        val r = response(luminance)
        val a = (base ushr 24) and 0xFF
        val toward = if (r > 0f) 0xFF else 0x00
        val mix = if (r > 0f) lerp(LIFT_MIN, LIFT_MAX, r) else lerp(LIFT_MIN, SINK_MAX, -r)
        val alpha = (a * (if (r > 0f) lerp(1f, ALPHA_MAX, r) else lerp(1f, ALPHA_MIN, -r)))
            .toInt().coerceIn(0, 255)
        return (alpha shl 24) or
            (blend((base shr 16) and 0xFF, toward, mix) shl 16) or
            (blend((base shr 8) and 0xFF, toward, mix) shl 8) or
            blend(base and 0xFF, toward, mix)
    }

    private fun blend(from: Int, to: Int, t: Float): Int =
        (from + (to - from) * t.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

    /** How far the tint moves at a middling backdrop — the reference's 0.1 brightness. */
    private const val LIFT_MIN = 0.1f

    /** And at a white one. The reference's 0.5. */
    private const val LIFT_MAX = 0.5f

    /** Toward black at a black one. The reference's -0.2, as a mix rather than an offset. */
    private const val SINK_MAX = 0.2f

    /** How much heavier a pane gets over a bright backdrop, and lighter over a dark one. */
    private const val ALPHA_MAX = 1.25f
    private const val ALPHA_MIN = 0.85f
}
