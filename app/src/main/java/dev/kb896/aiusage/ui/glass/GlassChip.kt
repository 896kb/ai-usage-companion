package dev.kb896.aiusage.ui.glass

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * A colour swatch as a disc of glass with the colour laid over it.
 *
 * The colour is the user's and is shown as chosen — at 0.85 so the glass still reads
 * through the edge — never pushed lighter or darker by what is behind it.
 */
class GlassChip(context: Context) : View(context), Glassy {
    var colour: Int = 0
        set(value) { field = value; invalidate() }

    private val press = PressSpring(this)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        press.onTouch(event)
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = h / 2f
        Pane.draw(canvas, this, 0f, 0f, w, h, r, tint = 0, clear = true, rim = false)
        rect.set(0f, 0f, w, h)
        paint.color = shown(colour)
        canvas.drawRoundRect(rect, r, r, paint)
        Pane.draw(canvas, this, 0f, 0f, w, h, r, tint = 0, clear = false, rim = true)
        Pane.glow(canvas, 0f, 0f, w, h, r, press.progress, press.x, press.y)
    }

    companion object {
        /** The colour at 0.85 alpha, whatever alpha it came with. */
        fun shown(colour: Int): Int = (colour and 0x00FFFFFF) or (0xD9 shl 24)
    }
}
