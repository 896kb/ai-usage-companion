package dev.kb896.aiusage.ui.glass

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.Button
import android.widget.ImageButton

/**
 * A capsule of clear glass that presses like jelly.
 *
 * Ported from catalog/components/LiquidButton.kt (Apache 2.0): 48dp tall, 16dp side
 * padding, a scale of 1 + 4dp/height while pressed, the highlight under the finger. A
 * button with a [tint] takes it the catalog's way — a hue blend, then the colour at 0.75
 * over the surface — and its text goes white; a clear one takes its ink from its cell.
 *
 * Under ORIGINAL it is android.widget.Button and nothing here runs.
 */
open class GlassButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : Button(context, attrs), Glassy {

    val glass = UiTheme.isGlass(context)
    var tint: Int = 0
        set(value) { field = value; invalidate() }

    private val press = PressSpring(this)
    private val density = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var inked = 0

    init {
        if (glass) {
            background = null
            stateListAnimator = null
            isAllCaps = false
            val side = (16 * density).toInt()
            setPadding(side, 0, side, 0)
            minHeight = (48 * density).toInt()
            minimumHeight = minHeight
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (glass) press.onTouch(event)
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        if (!glass) { super.onDraw(canvas); return }
        val w = width.toFloat(); val h = height.toFloat(); val r = h / 2f
        // A view mid-collapse or before its first layout has no height, and the press
        // scale divides by it. Pane declines to draw at zero size; so does this.
        if (w <= 0f || h <= 0f) return
        val scale = 1f + (4 * density / h) * press.progress
        canvas.save()
        canvas.scale(scale, scale, w / 2f, h / 2f)
        val lum = Pane.draw(canvas, this, 0f, 0f, w, h, r, tint = 0, clear = tint == 0)
        if (tint != 0) {
            rect.set(0f, 0f, w, h)
            paint.color = tint; paint.blendMode = BlendMode.HUE
            canvas.drawRoundRect(rect, r, r, paint)
            paint.blendMode = null
            paint.color = (tint and 0x00FFFFFF) or (0xBF shl 24)
            canvas.drawRoundRect(rect, r, r, paint)
        }
        Pane.glow(canvas, 0f, 0f, w, h, r, press.progress, press.x, press.y)
        val ink = if (tint != 0) 0xFFFFFFFF.toInt() else Ink.primary(lum)
        // setTextColor invalidates, and this runs from onDraw — an unconditional call
        // here would redraw forever. Only set it when the ink actually changed.
        if (ink != inked) { inked = ink; setTextColor(ink) }
        super.onDraw(canvas)
        canvas.restore()
    }
}

/** The same capsule around an icon. The trash button is an ImageButton, so this exists. */
class GlassIconButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : ImageButton(context, attrs), Glassy {

    val glass = UiTheme.isGlass(context)
    private val press = PressSpring(this)
    private val density = context.resources.displayMetrics.density
    private var inked = 0

    init {
        if (glass) {
            background = null
            stateListAnimator = null
            minimumHeight = (48 * density).toInt()
            minimumWidth = (52 * density).toInt()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (glass) press.onTouch(event)
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        if (!glass) { super.onDraw(canvas); return }
        val w = width.toFloat(); val h = height.toFloat(); val r = h / 2f
        // A view mid-collapse or before its first layout has no height, and the press
        // scale divides by it. Pane declines to draw at zero size; so does this.
        if (w <= 0f || h <= 0f) return
        val scale = 1f + (4 * density / h) * press.progress
        canvas.save()
        canvas.scale(scale, scale, w / 2f, h / 2f)
        val lum = Pane.draw(canvas, this, 0f, 0f, w, h, r, tint = 0, clear = true)
        Pane.glow(canvas, 0f, 0f, w, h, r, press.progress, press.x, press.y)
        val ink = Ink.primary(lum)
        // Same guard as GlassButton: setting imageTintList invalidates, so only do it
        // when the cell's ink has actually flipped.
        if (ink != inked) { inked = ink; imageTintList = android.content.res.ColorStateList.valueOf(ink) }
        super.onDraw(canvas)
        canvas.restore()
    }
}
