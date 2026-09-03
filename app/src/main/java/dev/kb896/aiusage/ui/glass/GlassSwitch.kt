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
import android.view.ViewConfiguration
import android.widget.Checkable
import kotlin.math.abs
import kotlin.math.min

/**
 * The toggle, ported from catalog/components/LiquidToggle.kt (Apache 2.0).
 *
 * A 64x28dp capsule track, green when on; a 40x24dp thumb inset 2dp that travels 20dp.
 * The thumb is white at rest and turns to glass as it is pressed (the catalog draws
 * white at 1 - pressProgress over it), and while it is glass it refracts the track and
 * the wallpaper beneath — the catalog's nested backdrop, done by drawing both into one
 * small bitmap and handing that to the lens as its content. Drag with squash, or tap.
 *
 * The view is larger than the track by [PAD_X] and [PAD_Y], which is where the pressed
 * thumb goes.
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
    private var localShader: BitmapShader? = null
    private var downX = 0f
    private var downY = 0f
    private var dragged = false
    // The platform's own figure, which is what the enclosing ScrollView measures the same
    // gesture against; a threshold of this view's own invention would only disagree with it.
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val loc = IntArray(2)

    /** Where the value spring is headed. Tests read it to see that a cancel put it back. */
    internal val dragTarget: Float get() = drag.targetValue

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
        setMeasuredDimension(((64 + 2 * PAD_X) * density).toInt(), ((28 + 2 * PAD_Y) * density).toInt())
    }

    // Toggle before the click listeners run, as CompoundButton does, so a listener that
    // reads isChecked sees the state the click produced.
    override fun performClick(): Boolean { toggle(); super.performClick(); return true }

    // A cached local bitmap is sized to the view at the moment it was first drawn; if the
    // view is ever resized (the switch measures itself fixed, so this rarely fires) a
    // stale one would draw the track at the wrong scale. Drop it and let onDraw remake it.
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Dropped, not recycled: a bitmap a queued draw still holds throws the moment
        // that draw runs, and the reference going away is all the collector needs.
        local = null
        localCanvas = null
        localShader = null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val travel = 20 * density
        when (e.actionMasked) {
            // Nothing is disallowed here. Taking the gesture on DOWN is what stopped the
            // ScrollView from ever getting a drag that starts on a switch, and most of
            // those drags are the user scrolling the settings past a row that happens to
            // have a toggle at the end of it.
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; dragged = false; drag.press(); return true }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                if (!dragged) {
                    val dy = e.y - downY
                    // Whichever axis clears the slop first owns the gesture. Sideways is
                    // ours, and only now is it worth asking the scroller to keep out;
                    // downwards is the ScrollView's, and it will intercept and send this
                    // view an ACTION_CANCEL without being asked.
                    if (abs(dx) > slop && abs(dx) > abs(dy)) {
                        dragged = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    } else return true
                }
                drag.updateValue((if (checked) 1f else 0f) + dx / travel)
                return true
            }
            MotionEvent.ACTION_UP -> {
                drag.release()
                if (!dragged) { performClick(); return true }
                val on = drag.targetValue >= 0.5f
                if (on != checked) { checked = on; onCheckedChange?.invoke(on) }
                drag.animateTo(if (on) 1f else 0f)
                return true
            }
            // A cancel is the scroller taking the gesture away, not a choice: put the
            // thumb back where the state says it belongs and report nothing.
            MotionEvent.ACTION_CANCEL -> {
                drag.release()
                drag.animateTo(if (checked) 1f else 0f)
                dragged = false
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
        // The track is inset into the view by the padding; everything else is measured
        // from the track, so the 64x28dp capsule and its 20dp of travel are unchanged.
        val px = PAD_X * density; val py = PAD_Y * density
        track.set(px, py, w - px, h - py)
        val tr = track.height() / 2f
        paint.color = blend(Ink.track(lum), Ink.on(lum), drag.progress)
        canvas.drawRoundRect(track, tr, tr, paint)

        val inset = 2 * density
        val tw = 40 * density; val th = 24 * density
        val x = track.left + inset + (track.width() - 2 * inset - tw) * drag.progress
        thumb.set(x, track.top + inset, x + tw, track.top + inset + th)

        // The thumb's own view of the world: the wallpaper under this switch, then the track.
        if (local == null) {
            val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            local = b
            localCanvas = Canvas(b)
            // Made with the bitmap and kept with it. The contents change every frame of a
            // drag, but a BitmapShader is a handle on the bitmap, not a copy of it, so the
            // one made here goes on showing whatever the canvas above last drew.
            localShader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val lc = localCanvas!!
        val backdrop = Backdrop.bitmap
        if (backdrop != null) {
            getLocationOnScreen(loc)
            lc.drawBitmap(backdrop, -(loc[0] - Backdrop.origin[0]).toFloat(), -(loc[1] - Backdrop.origin[1]).toFloat(), null)
        } else lc.drawColor(dev.kb896.aiusage.Panel.Palette.BG)
        lc.drawRoundRect(track, tr, tr, paint)
        val shader = localShader!!

        val p = drag.pressProgress
        // The squash is a stretch ALONG the direction of travel, so it goes on the
        // magnitude of the velocity. Signed, it stretched a thumb flicking right and
        // pinched one flicking left — half a squash, and the half that reads as a fault.
        val v = abs(drag.velocity)
        var sx = drag.scaleX / (1f - (v * 0.75f).coerceIn(0f, 0.2f))
        var sy = drag.scaleY * (1f - (v * 0.25f).coerceIn(0f, 0.2f))
        // The cap is the thumb's own clearance: at either end of the track it is nearer one
        // edge than the view's centre, and a cap measured from the centre lets a pressed
        // flick past the edge by a few dp there.
        val roomX = thumbCap(thumb.centerX(), w, tw)
        val roomY = thumbCap(thumb.centerY(), h, th)
        sx = min(sx, roomX)
        sy = min(sy, roomY)
        canvas.save()
        canvas.scale(sx, sy, thumb.centerX(), thumb.centerY())
        Pane.draw(canvas, this, thumb.left, thumb.top, thumb.right, thumb.bottom, th / 2f,
            tint = 0, clear = true, rim = true, content = shader)
        paint.color = (0xFFFFFF) or (((1f - p) * 255).toInt() shl 24)
        canvas.drawRoundRect(thumb, th / 2f, th / 2f, paint)
        canvas.restore()
    }

    companion object {
        /**
         * Room around the track for the pressed thumb.
         *
         * The thumb is drawn scaled about its centre — 1.5x while held, more with a
         * flick — and a view measured to the track's own 64x28dp clipped it against its
         * bounds. The switch sits at the right end of its row, so the 12dp of side
         * padding is why the visible track now starts 12dp in from where the platform
         * switch's did.
         */
        const val PAD_X = 12f
        const val PAD_Y = 8f

        /**
         * How far the thumb may be scaled about its own centre before it reaches an edge.
         *
         * [centre] is where its centre sits along an axis of an [extent]-long view and
         * [size] is how long the thumb is on that axis. The nearer edge is what decides
         * it: the thumb travels, so most of the time it is not in the middle of anything.
         */
        internal fun thumbCap(centre: Float, extent: Float, size: Float): Float =
            min(centre, extent - centre) / (size / 2f)
    }

    private fun blend(a: Int, b: Int, t: Float) = Ink.mix(
        (a and 0x00FFFFFF) or (mixA(a ushr 24, b ushr 24, t) shl 24), b, t)
    private fun mixA(a: Int, b: Int, t: Float) = Math.round(a + (b - a) * t).coerceIn(0, 255)
}
