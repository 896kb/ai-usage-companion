package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.data.MascotMood

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
     * A mood as a square bitmap on a transparent background, for places that can only
     * take a picture — the HyperIsland pill, a notification.
     *
     * ONE frame, not an animation. Nothing outside the app's own views can be driven at
     * an animation's frame rate: a notification would have to be re-posted per frame,
     * which the system throttles and which would burn the shade. So the still has to say
     * as much as possible on its own, and the frame chosen is the one carrying the MOST
     * rects — the fullest confetti burst, the dumbbells at their peak, the flag extended.
     * Frame 0 of the confetti is the mascot alone with no confetti in the air at all,
     * which is exactly the frame that says nothing.
     *
     * Fitted on the chosen frame's real bounds rather than on the clip's nominal box.
     * The two are not the same: confetti is positioned relative to the mascot and sits
     * well outside it, at negative coordinates, so fitting the box would push the burst
     * off the bitmap.
     */
    fun bitmap(mood: String, px: Int, accent: Int = ACCENT_ORANGE): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val body = Paint()
        val eye = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.SQUARE
            color = Panel.Palette.BG
        }

        val dead = mood == MascotMood.DEAD
        // WALK resolves to the stand clip rather than to WalkRig. A still of the walk is
        // the rig at rest — the pose WalkRig itself holds at t=0, before the lean — and
        // routing it through the clip means one code path punches the eyes out instead
        // of two.
        val name = if (dead || mood == MascotMood.WALK) MascotMood.STAND else mood
        val clip = MascotFrames.CLIPS.firstOrNull { it.name == name } ?: return bmp
        val index = clip.frames.indices.maxByOrNull { clip.frames[it].size } ?: 0
        val frame = clip.frames[index]
        if (frame.isEmpty()) return bmp

        val x0 = frame.minOf { it.x }
        val y0 = frame.minOf { it.y }
        val x1 = frame.maxOf { it.x + it.w }
        val y1 = frame.maxOf { it.y + it.h }
        // Solving "the frame's extents fill the square" for h, given w = h * aspect.
        // The dead pose is rotated a quarter turn about its own centre, so it occupies a
        // box of the same two dimensions swapped — already inside the square this fits.
        val h = px * FILL / maxOf((x1 - x0) * clip.aspect, y1 - y0)
        val w = h * clip.aspect
        draw(
            canvas, clip, index * clip.frameMs,
            x = (px - (x1 - x0) * w) / 2f - x0 * w,
            top = (px - (y1 - y0) * h) / 2f - y0 * h,
            h = h, body = body, eye = eye, accent = accent, dead = dead,
            holes = true,
        )
        return bmp
    }

    /** Fraction of the bitmap the figure fills, leaving it from touching the edges. */
    private const val FILL = 0.94f

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
        holes: Boolean = false,
    ) {
        val w = h * clip.aspect
        val frame = clip.frames[((nowMs / clip.frameMs) % clip.frames.size).toInt()]

        // Eyes as transparency rather than as dark paint.
        //
        // On the panel the mascot is drawn onto a known background, so an eye painted in
        // that background colour IS a hole. Anywhere else it is a lie that survives only
        // while nothing recolours the image — and HyperOS recolours it. The island tints
        // the picture flat white, which turned the eyes white too and erased the face,
        // leaving a blank blob whose remaining notches read as tear tracks.
        //
        // Punching them out instead survives any tint, because alpha is the one channel
        // a tint cannot overwrite.
        val punch = if (holes) Paint().apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        } else null

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
                if (punch != null && r.color == Panel.Palette.BG) punch else body,
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
            val cross = punch ?: eye
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
                            cross,
                        )
                    }
                }
            }
            canvas.restore()
        }
    }
}
