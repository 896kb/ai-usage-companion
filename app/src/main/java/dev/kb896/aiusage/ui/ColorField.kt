package dev.kb896.aiusage.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import dev.kb896.aiusage.Panel

/**
 * The two surfaces a colour is actually picked on: a saturation/brightness square and a
 * hue strip.
 *
 * These replaced three sliders. HUE, SATURATION and BRIGHTNESS as separate bars are
 * honest about the model and hopeless in use: none of them shows you what the others are
 * doing, so "a slightly warmer green" is found by dragging one bar, looking at a swatch
 * somewhere else, dragging it back, and repeating. On a square you can see every colour
 * one hue can make, all at once, and put a finger on the one you want.
 *
 * Drawn rather than pulled in: a picker library is a dependency, a theme to fight, and a
 * set of rounded controls that would look imported on a screen made of squares. Two
 * gradients and a cursor is less code than the styling would have been.
 */
internal object ColorField {

    /** Cursor size and stroke, in dp, shared by both views so they read as one control. */
    private const val CURSOR_DP = 13f
    private const val STROKE_DP = 2f

    /**
     * Every colour a single hue can make: saturation left to right, brightness top to
     * bottom.
     *
     * @param onPick called with saturation and brightness, both 0..1, on every touch
     */
    class SatVal(context: Context) : View(context) {

        private val base = Paint()
        private val shade = Paint()
        private val light = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.WHITE
        }
        private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Panel.Palette.BG
        }

        var onPick: (Float, Float) -> Unit = { _, _ -> }

        var hue: Float = 0f
            set(value) { field = value; rebuild(); invalidate() }

        var saturation: Float = 1f
        var brightness: Float = 1f

        /** Set the cursor without reporting it — for a preset or a typed hex. */
        fun place(sat: Float, value: Float) {
            saturation = sat; brightness = value; invalidate()
        }

        private fun rebuild() {
            if (width == 0 || height == 0) return
            // White to the pure hue across, then transparent to black down. The two
            // together are the standard square: the top edge is every fully-lit colour of
            // this hue, and the bottom edge is black whatever the saturation.
            base.shader = LinearGradient(
                0f, 0f, width.toFloat(), 0f,
                Color.WHITE, Color.HSVToColor(floatArrayOf(hue, 1f, 1f)),
                Shader.TileMode.CLAMP,
            )
            shade.shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP,
            )
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = rebuild()

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), base)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)

            // A square cursor, in white with a dark inner line. One colour alone
            // disappears at one end of the square or the other — white is invisible on
            // the white corner, dark on the black edge — and this is the one thing on the
            // view that must never be lost.
            val r = dp(CURSOR_DP) / 2f
            val cx = (saturation * width).coerceIn(r, width - r)
            val cy = ((1f - brightness) * height).coerceIn(r, height - r)
            light.strokeWidth = dp(STROKE_DP)
            dark.strokeWidth = dp(STROKE_DP)
            canvas.drawRect(cx - r, cy - r, cx + r, cy + r, dark)
            canvas.drawRect(cx - r + dark.strokeWidth, cy - r + dark.strokeWidth,
                cx + r - dark.strokeWidth, cy + r - dark.strokeWidth, light)
        }

        @Suppress("ClickableViewAccessibility")   // a colour field has no click semantics
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    // Or a scrolling ancestor claims the gesture the moment the finger
                    // moves vertically, which on this view is half of every drag.
                    parent?.requestDisallowInterceptTouchEvent(true)
                    saturation = (event.x / width).coerceIn(0f, 1f)
                    brightness = (1f - event.y / height).coerceIn(0f, 1f)
                    onPick(saturation, brightness)
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    parent?.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }

        private fun dp(value: Float) = value * resources.displayMetrics.density
    }

    /** The hue wheel, unrolled. Choosing which square to work in. */
    class Hue(context: Context) : View(context) {

        private val bar = Paint()
        private val light = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.WHITE
        }
        private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Panel.Palette.BG
        }

        var onPick: (Float) -> Unit = {}

        var hue: Float = 0f
            set(value) { field = value; invalidate() }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            // Seven stops, because the wheel starts and ends on red: six would close the
            // circle a sixth of the way early and shift every hue along the bar.
            bar.shader = LinearGradient(
                0f, 0f, w.toFloat(), 0f,
                IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) },
                null, Shader.TileMode.CLAMP,
            )
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bar)
            val w = dp(CURSOR_DP) / 2f
            val cx = (hue / 360f * width).coerceIn(w, width - w)
            light.strokeWidth = dp(STROKE_DP)
            dark.strokeWidth = dp(STROKE_DP)
            canvas.drawRect(cx - w, 0f, cx + w, height.toFloat(), dark)
            canvas.drawRect(cx - w + dark.strokeWidth, dark.strokeWidth,
                cx + w - dark.strokeWidth, height - dark.strokeWidth, light)
        }

        @Suppress("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    // 359.999 rather than 360: HSVToColor wraps 360 back to 0, which
                    // makes the far right of the bar jump to the far left's colour.
                    hue = (event.x / width * 360f).coerceIn(0f, 359.999f)
                    onPick(hue)
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    parent?.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }

        private fun dp(value: Float) = value * resources.displayMetrics.density
    }
}
