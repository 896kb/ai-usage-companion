package dev.kb896.aiusage.ui.glass

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * A colour swatch as a disc of glass with the colour laid over it.
 *
 * The colour is the user's and is shown as chosen — at 0.85 so the glass still reads
 * through the edge — never pushed lighter or darker by what is behind it.
 *
 * Under ORIGINAL it is the plain View the title row always had: [colour] is its
 * background and nothing here draws, so declaring it in the XML changes no pixel.
 */
class GlassChip @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs), Glassy {

    val glass = UiTheme.isGlass(context)

    var colour: Int = 0
        set(value) {
            field = value
            // setBackgroundColor invalidates by itself, so ORIGINAL asks for one repaint
            // rather than two; under GLASS the colour is painted in onDraw, not held as
            // a background, so this is the only thing that schedules it.
            if (glass) invalidate() else setBackgroundColor(value)
        }

    private val press = PressSpring(this)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!glass) return super.onTouchEvent(event)
        press.onTouch(event)
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        if (!glass) { super.onDraw(canvas); return }
        val w = width.toFloat(); val h = height.toFloat(); val r = h / 2f
        Pane.draw(canvas, this, 0f, 0f, w, h, r, tint = 0, clear = true, rim = false)
        rect.set(0f, 0f, w, h)
        paint.color = shown(colour)
        canvas.drawRoundRect(rect, r, r, paint)
        // The rim only. Without lens = false this second pass re-ran the refraction over
        // the colour that had just been drawn, which repainted the backdrop on top of it
        // — the swatch came out as wallpaper with a rim, whatever colour was picked.
        Pane.draw(canvas, this, 0f, 0f, w, h, r, tint = 0, clear = false, rim = true, lens = false)
        Pane.glow(canvas, 0f, 0f, w, h, r, press.progress, press.x, press.y)
    }

    companion object {
        /** The colour at 0.85 alpha, whatever alpha it came with. */
        fun shown(colour: Int): Int = (colour and 0x00FFFFFF) or (0xD9 shl 24)
    }
}
