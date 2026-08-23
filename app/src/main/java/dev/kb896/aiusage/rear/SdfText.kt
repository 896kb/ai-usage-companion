package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * The title, as a distance-field texture the glass shader can read.
 *
 * BUILT AT RUNTIME, and it has to be: the reference bakes one texture for one fixed
 * clock, where this is a title somebody types on the front screen. So the text is drawn
 * to a mask, the field is computed from it — see SdfField — and the result is uploaded
 * as a bitmap for the shader to sample.
 *
 * CACHED ON EVERYTHING THAT WOULD CHANGE IT. A distance transform over a few hundred
 * thousand pixels is nothing once and far too much sixty times a second, and the title
 * changes about as often as somebody edits it.
 */
class SdfText {

    private var key: String? = null
    private var bitmap: Bitmap? = null

    /** Where the glyphs sit inside the texture, so the caller can place it. */
    var padding: Float = 0f
        private set

    /**
     * The texture for this text, rebuilding only if something about it has moved.
     *
     * Returns null for text that has no pixels — an empty title, or one whose glyphs the
     * face does not have. The caller draws nothing rather than a lens shaped like
     * nothing.
     */
    fun of(text: String, paint: Paint, spread: Float): Bitmap? {
        val id = "$text|${paint.textSize}|${paint.typeface}|${paint.fontVariationSettings}|$spread"
        if (id == key) return bitmap
        key = id
        bitmap = build(text, paint, spread)
        return bitmap
    }

    private fun build(text: String, paint: Paint, spread: Float): Bitmap? {
        if (text.isBlank()) return null
        val pad = Math.ceil(spread.toDouble()).toInt() + 2
        padding = pad.toFloat()

        val metrics = paint.fontMetrics
        val w = Math.ceil(paint.measureText(text).toDouble()).toInt() + pad * 2
        val h = Math.ceil((metrics.bottom - metrics.top).toDouble()).toInt() + pad * 2
        if (w <= pad * 2 || h <= pad * 2) return null

        // The mask. Drawn in white on nothing: only the coverage matters, and the colour
        // the title is actually painted in has no part in a lens.
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(mask)
        val stamp = Paint(paint)
        stamp.color = Color.WHITE
        stamp.textAlign = Paint.Align.LEFT
        c.drawText(text, pad.toFloat(), pad - metrics.top, stamp)

        val pixels = IntArray(w * h)
        mask.getPixels(pixels, 0, w, 0, 0, w, h)
        mask.recycle()

        val alpha = FloatArray(w * h) { ((pixels[it] ushr 24) and 0xFF) / 255f }
        if (alpha.none { it > 0f }) return null

        val packed = SdfField.build(alpha, w, h, spread)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(packed, 0, w, 0, 0, w, h)
        return out
    }
}
