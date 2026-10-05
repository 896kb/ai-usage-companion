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
 * A colour swatch: the colour as a circle, under a bigger circle of glass that sits on top
 * of it — the same dot everywhere a colour is shown or chosen, in the settings' rows and
 * grid and in the picker's palette.
 *
 * Drawn as the largest circle the view holds, centred, whatever its shape. The glass
 * refracts the wallpaper behind it; the colour sits inside, opaque and exactly as chosen;
 * a sheen and the rim go over both, which is what makes the glass read as ON the colour
 * rather than round it. [ringed] adds a ring, for the palette's current colour.
 */
class GlassChip @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs), Glassy {

    val glass = UiTheme.isGlass(context)

    var colour: Int = 0
        set(value) { field = value; invalidate() }

    /** Ringed, as the palette's current colour. */
    var ringed: Boolean = false
        set(value) { field = value; invalidate() }

    private val press = PressSpring(this)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var sheenFor = -1f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        press.onTouch(event)
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val r = minOf(width, height) / 2f
        val cx = width / 2f; val cy = height / 2f
        val l = cx - r; val t = cy - r; val rr = cx + r; val b = cy + r
        // The glass body, refracting what is behind it.
        Pane.draw(canvas, this, l, t, rr, b, r, tint = 0, clear = true, rim = false)
        // The colour, inside it.
        paint.color = colour or (0xFF shl 24)
        canvas.drawCircle(cx, cy, r * DOT, paint)
        // The glass over the colour: a sheen falling from the top left, then the rim.
        if (sheenFor != r) {
            sheenFor = r
            sheen.shader = android.graphics.RadialGradient(
                cx - r * 0.35f, cy - r * 0.45f, r * 1.1f,
                intArrayOf(0x55FFFFFF, 0x14FFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.55f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(cx, cy, r, sheen)
        Pane.draw(canvas, this, l, t, rr, b, r, tint = 0, clear = false, rim = true, lens = false)
        if (ringed) {
            ring.color = 0xF2FFFFFF.toInt()
            ring.strokeWidth = r * 0.09f
            canvas.drawCircle(cx, cy, r * DOT + ring.strokeWidth * 1.4f, ring)
        }
        Pane.glow(canvas, l, t, rr, b, r, press.progress, press.x, press.y)
    }

    companion object {
        /** The colour's circle, as a share of the glass's radius. */
        const val DOT = 0.62f

        /** The colour at 0.85 alpha, whatever alpha it came with. */
        fun shown(colour: Int): Int = (colour and 0x00FFFFFF) or (0xD9 shl 24)
    }
}
