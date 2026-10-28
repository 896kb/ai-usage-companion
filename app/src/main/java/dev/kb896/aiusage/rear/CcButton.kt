package dev.kb896.aiusage.rear

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/**
 * One of the control centre's buttons, [on] 0 to 1 (eased). Under GLASS a pane, a white
 * tint coming in with "on" and its mark from faint to white. Under ORIGINAL a white
 * outline, and with "on" a white fill coming in and its mark turning to the background's
 * colour to stand out of it.
 *
 * The pane itself is the caller's — its GlassPainter, bending its own backdrop — and this
 * is everything drawn over it, so the control centre and the portal sheet (in the panel
 * or in the shell) are one button. Moved out of RearDialView.drawCcButton.
 */
object CcButton {

    /** A tile's corner, as a share of its shorter side: rounded, never a circle. */
    const val CORNER = 0.22f

    private const val WHITE = 0x00FFFFFF

    /** Over glass: [tint]'s colour, coming in with [on] to the charge liquid's alpha. */
    fun glassFill(tint: Int, on: Float): Int =
        (tint and 0x00FFFFFF) or ((RearDialView.CHARGE_LIQUID_ALPHA * on).toInt() shl 24)

    /** Over glass: the mark, white, from faint to full with [on]. */
    fun glassMark(on: Float): Int = WHITE or (Math.round(0x59 + (0xFF - 0x59) * on) shl 24)

    /** Flat: [tint]'s colour filling in with [on]. */
    fun flatFill(tint: Int, on: Float): Int =
        (tint and 0x00FFFFFF) or ((0xFF * on).toInt().coerceIn(0, 255) shl 24)

    /** Flat: the outline, [tint]'s colour, opaque. */
    fun flatRim(tint: Int): Int = (tint and 0x00FFFFFF) or (0xFF shl 24)

    /** Flat: the mark, white turning to the [background] as the fill comes in. */
    fun flatMark(background: Int, on: Float): Int = RampColor.lerp(0xFFFFFFFF.toInt(), background, on)

    /**
     * Everything over the button's pane. [glassy] is whether a pane of glass was laid
     * down under it (GLASS, on a canvas that could take one): over glass, a tint and a
     * faint-to-white mark; otherwise ORIGINAL's fill and outline. [tint] is what "on"
     * fills it with, and ORIGINAL's outline: white, as RGB with no alpha. [background] is
     * the colour a flat mark turns to, read only when the button is flat. [mark] draws
     * the mark in the colour it is given. [paint] is the caller's, left FILL.
     */
    fun drawBody(
        canvas: Canvas, shape: Path, on: Float, tint: Int, glassy: Boolean,
        background: Int, paint: Paint, mark: (Canvas, Int) -> Unit,
    ) {
        if (glassy) {
            paint.style = Paint.Style.FILL
            paint.color = glassFill(tint, on)
            canvas.drawPath(shape, paint)
            mark(canvas, glassMark(on))
            return
        }
        paint.style = Paint.Style.FILL
        paint.color = flatFill(tint, on)
        canvas.drawPath(shape, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = RearDialView.CHARGE_RIM
        paint.color = flatRim(tint)
        canvas.drawPath(shape, paint)
        paint.style = Paint.Style.FILL
        mark(canvas, flatMark(background, on))
    }
}
