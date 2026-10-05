package dev.kb896.aiusage.rear

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import dev.kb896.aiusage.Panel
import kotlin.math.roundToInt

/**
 * A close button over the camera, on the rear display, for as long as the camera the
 * panel opened is in front of it.
 *
 * The camera's rear-screen layout has no way out of its own — closing it took the front
 * screen. Its left part is an empty grey band beside the lenses, so the button goes there,
 * where the panel's own corner controller sits, and covers none of the camera's controls.
 *
 * An overlay window on the rear display: the app holds "display over other apps", which
 * the privileged service grants it when it opens the camera. Without it there is no button
 * and the camera closes from the front, as before.
 */
class CameraCloseOverlay(private val context: Context, private val onTap: () -> Unit) {

    private var view: View? = null
    private var wm: WindowManager? = null

    val showing: Boolean get() = view != null

    fun show(display: Display) {
        if (view != null) return
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "no overlay permission — the camera closes from the front only")
            return
        }
        try {
            val wc = context.createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            val wm = wc.getSystemService(WindowManager::class.java)
            val bounds = wm.currentWindowMetrics.bounds
            val fitX = bounds.width() / Panel.WIDTH.toFloat()
            val fitY = bounds.height() / Panel.HEIGHT.toFloat()
            val r = RADIUS * fitX
            val size = ((r + EDGE * fitX) * 2f).roundToInt()
            val v = Disc(wc, r)
            val lp = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (CX * fitX - size / 2f).roundToInt()
                y = (CY * fitY - size / 2f).roundToInt()
                title = "AiUsage camera close"
            }
            v.alpha = 0f
            wm.addView(v, lp)
            v.animate().alpha(1f).setDuration(FADE_MS).start()
            this.view = v
            this.wm = wm
            Log.i(TAG, "camera close button up on display ${display.displayId}")
        } catch (e: Exception) {
            Log.w(TAG, "camera close button failed: ${e.message}")
            view = null
            wm = null
        }
    }

    fun hide() {
        val v = view ?: return
        view = null
        runCatching { wm?.removeViewImmediate(v) }
        wm = null
    }

    /** A dark disc with a light rim and a cross, readable over the camera's grey and its picture. */
    @SuppressLint("ViewConstructor")
    private inner class Disc(context: Context, private val r: Float) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8C000000.toInt() }
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = 0x59FFFFFF; strokeWidth = r * 0.06f
        }
        private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = 0xF2FFFFFF.toInt()
            strokeWidth = r * 0.12f; strokeCap = Paint.Cap.ROUND
        }
        private var pressed = false

        override fun onDraw(canvas: Canvas) {
            val c = width / 2f
            val k = if (pressed) 0.92f else 1f
            canvas.drawCircle(c, c, r * k, fill)
            canvas.drawCircle(c, c, r * k - rim.strokeWidth / 2f, rim)
            val a = r * 0.34f * k
            canvas.drawLine(c - a, c - a, c + a, c + a, cross)
            canvas.drawLine(c - a, c + a, c + a, c - a, cross)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { pressed = true; invalidate() }
                MotionEvent.ACTION_UP -> {
                    pressed = false; invalidate()
                    val c = width / 2f
                    if (Math.hypot((e.x - c).toDouble(), (e.y - c).toDouble()) <= width / 2.0) onTap()
                }
                MotionEvent.ACTION_CANCEL -> { pressed = false; invalidate() }
            }
            return true
        }
    }

    companion object {
        private const val TAG = "AiUsageRear"
        /** In panel units, like everything on the back: the panel's corner controller's spot. */
        private const val CX = 290f
        private const val RADIUS = 28f
        private const val CY = Panel.HEIGHT - 22f - RADIUS
        /** Room around the disc so its rim is not cut by the window's edge. */
        private const val EDGE = 4f
        private const val FADE_MS = 180L
    }
}
