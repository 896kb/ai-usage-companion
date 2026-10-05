package dev.kb896.aiusage.ui.glass

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.SeekBar

/**
 * The advanced sliders, ported from catalog/components/LiquidSlider.kt (Apache 2.0).
 *
 * SeekBar keeps the progress, the range and the touch handling — those are right and
 * tested by the platform. Under GLASS it is only asked to stop drawing: a 6dp capsule
 * track with the filled part in the accent, and a 40x24dp thumb of glass that swells
 * to 1.5x while held. Under ORIGINAL nothing here runs and the PanelSeekBar style applies.
 */
class GlassSlider @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : SeekBar(context, attrs), Glassy {

    val glass = UiTheme.isGlass(context)
    private val density = context.resources.displayMetrics.density
    private val press = PressSpring(this)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = RectF()
    private val fill = RectF()

    init {
        if (glass) {
            thumb = null
            progressDrawable = null
            splitTrack = false
            // ROOM FOR THE THUMB AT ITS BIGGEST. It is 40dp wide and grows to one and a half
            // times that while held, so at either end of the track half of it — 30dp — hangs
            // past the track's end; with 20dp of padding the last 10dp were cut off by the
            // view's own edge whenever it was pressed at 0% or 100%.
            val side = (32 * density).toInt(); val vert = (14 * density).toInt()
            setPadding(side, vert, side, vert)
            minimumHeight = (48 * density).toInt()
            // NO RIPPLE. A SeekBar's default background is a borderless ripple, drawn as a
            // pale circle round its middle; the layouts' PanelSeekBar style nulls it, and a
            // slider made in code — the background dialog's — showed it.
            background = null
        }
    }

    /**
     * At least [MIN_HEIGHT_DP] tall under GLASS. ProgressBar measures its own height from
     * its drawables and padding and ignores the view's minimum height, and this one has
     * no drawables: made in code it came out 28dp tall, and the held thumb — 36dp — was
     * cut at top and bottom.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (!glass) return
        val min = (MIN_HEIGHT_DP * density).toInt()
        if (measuredHeight < min) setMeasuredDimension(measuredWidth, resolveSize(min, heightMeasureSpec))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (glass) press.onTouch(event)
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        if (!glass) { super.onDraw(canvas); return }
        // The thumb's rect is a fixed size, so Pane's own zero-size guard never sees a
        // view that has not been laid out; decline here, as GlassButton does.
        if (width <= 0 || height <= 0) return
        // The track/fill colours read the backdrop directly, the same way GlassCard's
        // ink() does — Pane.draw below only wants a shader lens for the thumb itself.
        val lum = if (Backdrop.bitmap == null) Pane.FALLBACK_LUMINANCE else Backdrop.luminanceOf(this)
        val left = paddingLeft.toFloat(); val right = (width - paddingRight).toFloat()
        val cy = height / 2f
        val th = 6 * density
        track.set(left, cy - th / 2f, right, cy + th / 2f)
        paint.color = Ink.track(lum)
        canvas.drawRoundRect(track, th / 2f, th / 2f, paint)
        // max == 0 would divide by zero; a slider with no range is drawn empty.
        val frac = if (max == 0) 0f else progress.toFloat() / max
        val x = left + (right - left) * frac
        fill.set(left, track.top, x, track.bottom)
        paint.color = Ink.accent(lum)
        canvas.drawRoundRect(fill, th / 2f, th / 2f, paint)

        val tw = 40 * density; val ht = 24 * density
        val scale = 1f + 0.5f * press.progress
        canvas.save()
        canvas.scale(scale, scale, x, cy)
        // At rest the white below is opaque, and an opaque fill hides whatever the lens
        // refracted under it — a shader pass per slider per frame for pixels nobody can
        // see. The rim and the body are drawn either way, so the thumb at rest is the
        // same picture; only the pass that could not be seen goes.
        Pane.draw(canvas, this, x - tw / 2f, cy - ht / 2f, x + tw / 2f, cy + ht / 2f, ht / 2f,
            tint = 0, clear = true, lens = press.progress > REST_PRESS)
        // Plain white at rest, fading to clear glass as it is grabbed (press.progress
        // 0 -> 1): the lens underneath is only worth paying for while a finger is on
        // it. Same formula as GlassSwitch's thumb.
        paint.color = 0xFFFFFF or (((1f - press.progress) * 255).toInt() shl 24)
        canvas.drawRoundRect(x - tw / 2f, cy - ht / 2f, x + tw / 2f, cy + ht / 2f, ht / 2f, ht / 2f, paint)
        canvas.restore()
    }

    private companion object {
        const val MIN_HEIGHT_DP = 48
    }
}
