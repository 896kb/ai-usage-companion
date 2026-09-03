package dev.kb896.aiusage.ui.glass

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.widget.Checkable
import kotlin.math.abs

/**
 * The toggle, ported from catalog/components/LiquidToggle.kt (Apache 2.0).
 *
 * A 64x28dp capsule track, green when on; a 40x24dp thumb inset 2dp that travels 20dp.
 * The thumb is white at rest and turns to glass as it is pressed (the catalog draws
 * white at 1 - pressProgress over it), and while it is glass it refracts the track and
 * the wallpaper beneath — the catalog's nested backdrop, done by drawing both into one
 * small bitmap and handing that to the lens as its content. Drag with squash, or tap.
 */
class GlassSwitch(context: Context) : View(context), Checkable, Glassy {

    var onCheckedChange: ((Boolean) -> Unit)? = null
    private var checked = false
    private val density = context.resources.displayMetrics.density
    private val drag = DampedDrag(this, initial = 0f, range = 0f..1f, threshold = 0.001f,
        initialScale = 1f, pressedScale = 1.5f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = RectF()
    private val thumb = RectF()
    private var local: Bitmap? = null
    private var localCanvas: Canvas? = null
    private var downX = 0f
    private var dragged = false
    private val loc = IntArray(2)

    init { isClickable = true }

    override fun isChecked() = checked
    override fun toggle() { isChecked = !checked }
    override fun setChecked(b: Boolean) {
        if (b == checked) return
        checked = b
        drag.animateTo(if (b) 1f else 0f)
        onCheckedChange?.invoke(b)
    }
    fun setCheckedSilently(b: Boolean) { checked = b; drag.snap(if (b) 1f else 0f); invalidate() }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension((64 * density).toInt(), (28 * density).toInt())
    }

    // Toggle before the click listeners run, as CompoundButton does, so a listener that
    // reads isChecked sees the state the click produced.
    override fun performClick(): Boolean { toggle(); super.performClick(); return true }

    // A cached local bitmap is sized to the view at the moment it was first drawn; if the
    // view is ever resized (the switch measures itself fixed, so this rarely fires) a
    // stale one would draw the track at the wrong scale. Drop it and let onDraw remake it.
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        local = null
        localCanvas = null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val travel = 20 * density
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; dragged = false; drag.press(); parent?.requestDisallowInterceptTouchEvent(true); return true }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                if (abs(dx) > 4 * density) dragged = true
                if (dragged) drag.updateValue((if (checked) 1f else 0f) + dx / travel)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drag.release()
                if (!dragged) { performClick(); return true }
                val on = drag.targetValue >= 0.5f
                if (on != checked) { checked = on; onCheckedChange?.invoke(on) }
                drag.animateTo(if (on) 1f else 0f)
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // createBitmap throws at zero size, and a view before its first layout has none.
        if (w <= 0f || h <= 0f) return
        val lum = Backdrop.luminanceOf(this).let { if (Backdrop.bitmap == null) Pane.FALLBACK_LUMINANCE else it }
        track.set(0f, 0f, w, h)
        paint.color = blend(Ink.track(lum), Ink.on(lum), drag.progress)
        canvas.drawRoundRect(track, h / 2f, h / 2f, paint)

        val inset = 2 * density
        val tw = 40 * density; val th = 24 * density
        val x = inset + (w - 2 * inset - tw) * drag.progress
        thumb.set(x, inset, x + tw, inset + th)

        // The thumb's own view of the world: the wallpaper under this switch, then the track.
        val content = local ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { local = it; localCanvas = Canvas(it) }
        val lc = localCanvas!!
        val backdrop = Backdrop.bitmap
        if (backdrop != null) {
            getLocationOnScreen(loc)
            lc.drawBitmap(backdrop, -(loc[0] - Backdrop.origin[0]).toFloat(), -(loc[1] - Backdrop.origin[1]).toFloat(), null)
        } else lc.drawColor(dev.kb896.aiusage.Panel.Palette.BG)
        lc.drawRoundRect(track, h / 2f, h / 2f, paint)
        val shader = BitmapShader(content, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)

        val p = drag.pressProgress
        var sx = drag.scaleX; var sy = drag.scaleY
        sx /= 1f - (drag.velocity * 0.75f).coerceIn(-0.2f, 0.2f)
        sy *= 1f - (drag.velocity * 0.25f).coerceIn(-0.2f, 0.2f)
        canvas.save()
        canvas.scale(sx, sy, thumb.centerX(), thumb.centerY())
        Pane.draw(canvas, this, thumb.left, thumb.top, thumb.right, thumb.bottom, th / 2f,
            tint = 0, clear = true, rim = true, content = shader)
        paint.color = (0xFFFFFF) or (((1f - p) * 255).toInt() shl 24)
        canvas.drawRoundRect(thumb, th / 2f, th / 2f, paint)
        canvas.restore()
    }

    private fun blend(a: Int, b: Int, t: Float) = Ink.mix(
        (a and 0x00FFFFFF) or (mixA(a ushr 24, b ushr 24, t) shl 24), b, t)
    private fun mixA(a: Int, b: Int, t: Float) = Math.round(a + (b - a) * t).coerceIn(0, 255)
}
