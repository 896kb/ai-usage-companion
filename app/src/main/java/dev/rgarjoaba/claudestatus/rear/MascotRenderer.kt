package dev.rgarjoaba.claudestatus.rear

import android.graphics.Canvas
import android.graphics.Paint
import dev.rgarjoaba.claudestatus.Panel

/**
 * Draws one mascot clip. Shared by the rear panel and the in-app preview strip.
 *
 * Extracted rather than duplicated because the dead rendering has two details that are
 * easy to get subtly wrong in a second copy — the canvas rotation and the fact that eyes
 * must be skipped and re-stroked — and a preview whose job is to show what the panel
 * does is worthless if it draws it differently.
 */
object MascotRenderer {

    /** Anthropic orange, as baked into MascotFrames for headband, dumbbells and pole. */
    const val ACCENT_ORANGE = 0xFFD97757.toInt()

    /** the company blue, swapped in for the work account. */
    const val ACCENT_WORK = 0xFF006EFF.toInt()

    /**
     * Cells per side of a dead mascot's X. Odd, so the two diagonals meet in a single
     * centre cell rather than making a fat middle.
     *
     * Three, not five. Both were rendered and compared on the panel: at 3 the X fills 5
     * of 9 cells and its blocks are close to the size of the body's own pixels, so it
     * reads as part of the same artwork. At 5 it fills 9 of 25, and the finer blocks
     * make it look like a drawn cross laid on top of pixel art rather than made of it.
     */
    private const val EYE_GRID = 3

    /**
     * @param x left edge of the figure, [top] its top edge, [h] its drawn height
     * @param accent replaces [ACCENT_ORANGE] wherever it appears; pass ACCENT_ORANGE to
     *   leave the artwork as generated
     * @param dead tips the figure on its side and crosses its eyes
     */
    fun draw(
        canvas: Canvas,
        clip: MascotFrames.Clip,
        nowMs: Long,
        x: Float,
        top: Float,
        h: Float,
        body: Paint,
        eye: Paint,
        accent: Int = ACCENT_ORANGE,
        dead: Boolean = false,
    ) {
        val w = h * clip.aspect
        val frame = clip.frames[((nowMs / clip.frameMs) % clip.frames.size).toInt()]

        if (dead) {
            canvas.save()
            canvas.rotate(90f, x + w / 2f, top + h / 2f)
        }

        for (r in frame) {
            // Eyes are drawn in the background colour, so on a dead mascot they would
            // simply vanish rather than become crosses. Skipped here, stroked below.
            if (dead && r.color == Panel.Palette.BG) continue
            body.color = if (r.color == ACCENT_ORANGE) accent else r.color
            canvas.drawRect(
                x + r.x * w, top + r.y * h,
                x + (r.x + r.w) * w, top + (r.y + r.h) * h,
                body,
            )
        }

        if (dead) {
            // Blocks on a grid, not strokes.
            //
            // These were anti-aliased drawLine diagonals, which were the ONLY smooth
            // edges on a figure that is otherwise strictly axis-aligned pixel art — at
            // panel size they read as a smudge against blocks. Filling cells of an
            // EYE_GRID x EYE_GRID grid inside each eye keeps the X on the same grid the
            // rest of the mascot is drawn on.
            //
            // Both diagonals of an odd-sized grid share the centre cell, which is drawn
            // twice; harmless with an opaque fill, and cheaper than special-casing it.
            eye.style = Paint.Style.FILL
            eye.isAntiAlias = false
            eye.color = Panel.Palette.BG
            for (r in frame) {
                if (r.color != Panel.Palette.BG) continue
                val l = x + r.x * w
                val t = top + r.y * h
                val cw = (r.w * w) / EYE_GRID
                val ch = (r.h * h) / EYE_GRID
                for (i in 0 until EYE_GRID) {
                    for (j in intArrayOf(i, EYE_GRID - 1 - i)) {
                        canvas.drawRect(
                            l + j * cw, t + i * ch,
                            l + (j + 1) * cw, t + (i + 1) * ch,
                            eye,
                        )
                    }
                }
            }
            canvas.restore()
        }
    }
}
