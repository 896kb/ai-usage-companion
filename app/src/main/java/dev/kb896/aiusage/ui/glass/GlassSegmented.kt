package dev.kb896.aiusage.ui.glass

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dev.kb896.aiusage.ui.UiType
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A choice from a fixed set, as a capsule with a sliding pane of glass over the chosen one.
 *
 * Ported from catalog/components/LiquidBottomTabs.kt (Apache 2.0), sized for a settings
 * row: 44dp container, 4dp padding, a 36dp selection that springs between options and can
 * be dragged. Replaces the Spinner under GLASS; the Spinner stays under ORIGINAL.
 */
class GlassSegmented(
    context: Context,
    val options: List<String>,
    selected: Int,
) : View(context), Glassy {

    var selected: Int = selected.coerceIn(0, options.size - 1); private set
    var onChange: ((Int) -> Unit)? = null

    private val density = context.resources.displayMetrics.density
    private val drag = DampedDrag(this, initial = this.selected.toFloat(),
        range = 0f..(options.size - 1).toFloat(), threshold = 0.001f,
        initialScale = 1f, pressedScale = 1.06f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 14 * density
        typeface = UiType.of(context)
        UiType.variation(context)?.let { fontVariationSettings = it }
    }
    private val rect = RectF()
    // Reused across frames rather than built fresh in onDraw: a per-frame allocation on a
    // 120Hz screen is the one cost this project refuses.
    private val sel = RectF()
    private var downX = 0f
    private var downY = 0f
    private var dragged = false
    // The platform's own figure, which is what the enclosing ScrollView measures the same
    // gesture against; a threshold of this view's own invention would only disagree with it.
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    /** Where the value spring is headed. Tests read it to see that a cancel put it back. */
    internal val dragTarget: Float get() = drag.targetValue

    init { isClickable = true }

    fun select(index: Int, animate: Boolean = true) {
        val i = index.coerceIn(0, options.size - 1)
        if (animate) drag.animateTo(i.toFloat()) else drag.snap(i.toFloat())
        if (i == selected) return
        selected = i
        onChange?.invoke(i)
    }

    private val pad get() = 4 * density
    private val slot get() = (width - 2 * pad) / options.size

    /** Which option sits under an x, in view coordinates. */
    fun optionAt(x: Float): Int = ((x - pad) / slot).toInt().coerceIn(0, options.size - 1)

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(resolveSize((200 * density).toInt(), w), (44 * density).toInt())
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            // Nothing is disallowed here. Taking the gesture on DOWN is what stopped the
            // ScrollView from ever getting a drag that starts on the control — and this
            // one spans the row, so most touches that land on it are scrolls.
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; dragged = false; drag.press(); return true }
            MotionEvent.ACTION_MOVE -> {
                if (!dragged) {
                    val dx = e.x - downX; val dy = e.y - downY
                    // Whichever axis clears the slop first owns the gesture. Sideways is
                    // ours, and only now is it worth asking the scroller to keep out;
                    // downwards is the ScrollView's, and it will intercept and send this
                    // view an ACTION_CANCEL without being asked.
                    if (abs(dx) > slop && abs(dx) > abs(dy)) {
                        dragged = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    } else return true
                }
                drag.updateValue((e.x - pad - slot / 2f) / slot)
                return true
            }
            MotionEvent.ACTION_UP -> {
                drag.release()
                if (!dragged) { select(optionAt(e.x)); performClick() }
                else select(drag.targetValue.roundToInt())
                return true
            }
            // A cancel is the scroller taking the gesture away, not a choice: slide the
            // selection back onto the option that is still selected and report nothing.
            MotionEvent.ACTION_CANCEL -> {
                drag.release()
                drag.animateTo(selected.toFloat())
                dragged = false
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val lum = if (Backdrop.bitmap == null) Pane.FALLBACK_LUMINANCE else Backdrop.luminanceOf(this)
        rect.set(0f, 0f, w, h)
        paint.color = Ink.track(lum)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, paint)

        val x = pad + slot * drag.value
        sel.set(x, pad, x + slot, h - pad)
        canvas.save()
        canvas.scale(drag.scaleX, drag.scaleY, sel.centerX(), sel.centerY())
        Pane.draw(canvas, this, sel.left, sel.top, sel.right, sel.bottom, sel.height() / 2f, tint = 0, clear = true)
        canvas.restore()

        val ink = Ink.primary(lum)
        val baseline = h / 2f - (text.descent() + text.ascent()) / 2f
        // An indexed loop rather than forEachIndexed: the inline function is fine on an
        // array and allocates an Iterator on a List, which this is, once per frame.
        for (i in options.indices) {
            val label = options[i]
            text.color = ink
            text.alpha = if (i == selected) 255 else 153
            canvas.drawText(label, pad + slot * i + slot / 2f, baseline, text)
        }
    }
}
