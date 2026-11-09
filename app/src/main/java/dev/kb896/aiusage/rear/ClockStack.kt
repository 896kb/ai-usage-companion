package dev.kb896.aiusage.rear

/**
 * The digital clock stood on end (PanelConfig.clockVertical): hours, minutes under them, seconds
 * under those, no colons (the owner, 2026-11-09). Pure, in panel units, so it is tested away
 * from the view.
 *
 * AS SHIFTS FROM THE LINE, not as a second layout: each figure is drawn where the line puts it
 * and moved by its shift, so every way the figures are drawn — the glass fields, plain type, the
 * goo's flat copy — stands on end the same way, and turning it on or off slides each pair to its
 * row rather than swapping one clock for another. The colons stay in the line's places, for the
 * caller to shrink away.
 */
object ClockStack {
    /** A row's height, in the figures' size. */
    const val ROW = 1.08f
    /** How far above its baseline a figure's middle is, in its size. */
    const val CENTRE = 0.35f

    /**
     * Into [out] (dx, dy for each of [figures]' characters, in order): how far each moves from
     * its place in the line — laid out from [cx] across [span], [slot] a figure and [colon] a
     * colon, the seconds' slots at [secondsScale], on [baseline] — to its place stood on end, at
     * [v] of the way (0 the line, 1 on end), in rows of [size] stacked in the middle of the band
     * from [top] to [bottom]. Seconds shrinking take their row with them, and the others close
     * up to the middle.
     */
    fun shifts(
        figures: String, cx: Float, span: Float, slot: Float, colon: Float, secondsScale: Float,
        baseline: Float, size: Float, top: Float, bottom: Float, v: Float, out: FloatArray,
        /** AM/PM's share of a row under the last (twelve hours), 0 to 1. */
        marker: Float = 0f,
    ) {
        val s = secondsScale.coerceIn(0f, 1f)
        val rowH = size * ROW
        val row0 = firstRowMiddle(size, top, bottom, s, marker)
        var x = cx - span / 2f
        for ((i, ch) in figures.withIndex()) {
            val seconds = i >= 5
            val full = when (ch) { ':' -> colon; ClockFigures.BLANK -> 0f; else -> slot }
            val w = if (seconds) full * s else full
            val lineX = x + w / 2f
            val row = i / 3
            val stackX: Float
            val stackMiddle: Float
            if (ch == ':') {
                // Between the rows it parts, for the caller to shrink away.
                stackX = cx
                stackMiddle = row0 + (row + 0.5f) * rowH
            } else {
                val half = if (seconds) slot * s / 2f else slot / 2f
                // A lone hour figure (twelve hours, before ten) stands in the middle of its row.
                val alone = i == 1 && figures[0] == ClockFigures.BLANK
                stackX = if (alone) cx else cx + if (i % 3 == 0) -half else half
                stackMiddle = row0 + row * rowH
            }
            val stackBase = stackMiddle + CENTRE * size
            if (2 * i + 1 < out.size) {
                out[2 * i] = (stackX - lineX) * v
                out[2 * i + 1] = (stackBase - baseline) * v
            }
            x += w
        }
    }

    /**
     * The middle of the stack's first row: two rows, the seconds' share of a third and AM/PM's
     * share of a fourth ([marker]), centred in the band.
     */
    private fun firstRowMiddle(size: Float, top: Float, bottom: Float, secondsScale: Float, marker: Float): Float {
        val rows = 2f + secondsScale.coerceIn(0f, 1f) + marker.coerceAtLeast(0f)
        return (top + bottom) / 2f - (rows - 1f) / 2f * size * ROW
    }

    /** The baseline of the stack's last row of figures — the seconds, or the minutes as the seconds go ([secondsScale]). */
    fun lastRowBaseline(size: Float, top: Float, bottom: Float, secondsScale: Float, marker: Float = 0f): Float {
        val s = secondsScale.coerceIn(0f, 1f)
        return firstRowMiddle(size, top, bottom, s, marker) + (1f + s) * size * ROW + CENTRE * size
    }

    /** AM/PM's baseline: the row under the last row of figures (the owner, 2026-11-09). */
    fun markerBaseline(size: Float, top: Float, bottom: Float, secondsScale: Float, marker: Float): Float =
        lastRowBaseline(size, top, bottom, secondsScale, marker) + size * ROW
}
