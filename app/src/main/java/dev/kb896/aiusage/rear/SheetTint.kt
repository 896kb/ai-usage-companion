package dev.kb896.aiusage.rear

import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.data.GlassStyle

/**
 * The tint of the shell sheet's clear glass, as the panel tints its own: Glass settings'
 * style when one is chosen (GlassTint), else the clear glass reacting to how bright it
 * is behind it (GlassLuminance.clearTint) — the panel's styledTint and clearReactive,
 * read off the sheet's backdrop instead of the panel's.
 *
 * Given a picture of what is behind ([cells], from it scaled down to GlassLuminance.SIDE
 * square), every pane reads a handful of array cells, as the panel's do. The shell sheet
 * now has none — SurfaceFlinger blurs the live rear under it, and no picture of it ever
 * reaches this process — so it passes null: clear glass over a middling ground.
 */
object SheetTint {

    /** The luminance cells of [pixels], GlassLuminance.SIDE square, ARGB, row-major. */
    fun cells(pixels: IntArray): FloatArray {
        val n = GlassLuminance.SIDE * GlassLuminance.SIDE
        require(pixels.size == n) { "want $n pixels, got ${pixels.size}" }
        return FloatArray(n) { GlassLuminance.of(pixels[it]) }
    }

    /**
     * A clear pane's body at [left], [top], [right], [bottom] (panel pixels) over a
     * backdrop of [cells] (null: none yet, read as middling — clear glass stays clear).
     * Not [glass]: the built-in DISC_TINT, as the panel's clearReactive under ORIGINAL.
     */
    fun of(
        cells: FloatArray?, left: Float, top: Float, right: Float, bottom: Float,
        glass: Boolean, style: GlassStyle?,
        width: Float = Panel.WIDTH.toFloat(), height: Float = Panel.HEIGHT.toFloat(),
    ): Int {
        val local = cells?.let { GlassLuminance.average(it, left, top, right, bottom, width, height) } ?: MIDDLING
        if (style != null) return GlassTint.of(style, local, cells?.average()?.toFloat() ?: MIDDLING)
        if (!glass) return Glass.DISC_TINT
        return GlassLuminance.clearTint(local)
    }

    /** The brightness assumed before a backdrop has come: neither end, so nothing reacts. */
    const val MIDDLING = 0.5f
}
