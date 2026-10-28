package dev.kb896.aiusage.rear

import android.graphics.Canvas
import android.graphics.Paint
import dev.kb896.aiusage.data.PanelTheme

/**
 * A glyph at [h] units tall in [theme]'s set, its box's top left at ([x], [y]) and as wide
 * as [Glyphs.aspect] makes it, drawn in [color] with [paint] (whose colour, style and
 * anti-aliasing this sets). Pure drawing — no View, no Context — so the panel's view and
 * the shell's sheet (PortalSheetPainter, from app_process) draw the same glyph the same way.
 *
 * Kept out of GlassIcons.kt, which is generated: this is the one hand-written piece of it.
 *
 * GLASS, when the theme has an outline for the glyph: Material's path on its 960 grid,
 * ALWAYS anti-aliased — curves without smoothing are a staircase at any angle.
 *
 * Otherwise the traced pixel rows, WITHOUT anti-aliasing unless [smooth]. The tracer emits
 * one rectangle per pixel row of the source glyph, so a solid shape arrives as a stack of
 * abutting bars — and an anti-aliased edge on each of them blends with the background at
 * every shared boundary: the play triangle came out looking woven. [smooth] is for a glyph
 * drawn under a rotation, where there are no pixel rows to align to and the snapping that
 * keeps an upright glyph clean leaves a staircase down every edge instead.
 */
fun Glyphs.draw(
    canvas: Canvas,
    icon: PanelIcons.Icon,
    theme: PanelTheme,
    x: Float,
    y: Float,
    h: Float,
    color: Int,
    paint: Paint,
    smooth: Boolean = false,
) {
    val w = h * aspect(icon, theme)
    val left = x + (h - w) / 2f          // centred in the cell, not left-aligned in it

    val outline = if (theme.isGlass) GlassIcons.of(icon) else null
    if (outline != null) {
        paint.isAntiAlias = true
        paint.color = color
        paint.style = Paint.Style.FILL
        // Material draws on a 960-unit box whose Y axis runs from -960 to 0, so the glyph
        // has to be brought down by a full box before it is anywhere near the cell it was
        // asked for. Done here rather than in the generated data, which stays exactly as
        // the source has it so regenerating stays a copy.
        val k = h / GlassIcons.VIEWPORT
        canvas.save()
        canvas.translate(left, y)
        canvas.scale(k, k)
        canvas.translate(0f, -GlassIcons.ORIGIN_Y)
        canvas.drawPath(outline.path, paint)
        canvas.restore()
        return
    }

    paint.isAntiAlias = smooth
    paint.color = color
    for (r in icon.rects) {
        // SNAPPED TO WHOLE PIXELS. A traced row is one source pixel tall, and at 24 units
        // from a 19-row glyph that is 1.26 device pixels: the rasteriser gives the leftover
        // fraction partial coverage, so the rows came out alternating full and
        // three-quarter brightness — the play triangle visibly banded, measured at
        // (122,129,247) and (92,98,187) on consecutive rows.
        //
        // Rounding each edge fixes it without a fudge factor. Row k's bottom and row k+1's
        // top are the same expression, so they round to the same pixel: no gap, no
        // overlap, no partial coverage. Rows land on 1 or 2 pixels depending where they
        // fall, which is exactly what nearest-neighbour scaling of pixel art is supposed to do.
        canvas.drawRect(
            kotlin.math.round(left + r.x * w),
            kotlin.math.round(y + r.y * h),
            kotlin.math.round(left + (r.x + r.w) * w),
            kotlin.math.round(y + (r.y + r.h) * h),
            paint,
        )
    }
}
