package dev.kb896.aiusage.rear

import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl

/**
 * The Back chevron over a guest on a private display (PrivateDisplay). Xiaomi draws its own as a
 * finger comes in from the right edge (SubScreenGestureBack), but that window is on display 1,
 * under the app's picture, and the picture being untrusted, it gets none of the touches
 * ("Dropping untrusted touch event due to /2000"): Back still works (EdgeGestures, a key on the
 * app's display), with nothing to show it coming. This shows it: a dark tab out of the right edge
 * at the finger's height, growing as it travels left, its chevron full white once the lift would
 * be Back ([progress] by EdgeGestures' own rule), fading at the lift.
 *
 * Fed by the touch reader ([touch]), which never waits: it only writes volatiles and wakes this
 * one's thread, which draws. Its layer (trusted, so touch-transparent) is made at the first
 * gesture and kept, hidden, for the next; [close] lets it go.
 */
class BackChevron(private val layerStack: Int, private val rear: RearGeometry) {

    private val lock = Object()
    /** The gesture's landing, in panel units; NaN with none going. */
    @Volatile private var fromX = Float.NaN
    @Volatile private var fromY = 0f
    @Volatile private var atX = 0f
    @Volatile private var atY = 0f
    /** When the finger lifted (uptime ms); 0 while it is down. */
    @Volatile private var liftedAt = 0L
    @Volatile private var thread: Thread? = null
    @Volatile private var failedAt = 0L

    /**
     * One of the reader's events. [open]: asked at a landing only, whether a private guest has the
     * back (the caller's forwardOpen); a gesture that did not start there is not followed.
     */
    fun touch(e: RearTouches.Event, open: () -> Boolean) {
        when (e) {
            is RearTouches.Down -> {
                if (e.x < EdgeGestures.BACK_LEFT || !open()) { fromX = Float.NaN; return }
                fromX = e.x; fromY = e.y; atX = e.x; atY = e.y; liftedAt = 0L
                wake()
            }
            is RearTouches.Move -> if (!fromX.isNaN() && liftedAt == 0L) { atX = e.x; atY = e.y; wake() }
            is RearTouches.Up -> if (!fromX.isNaN() && liftedAt == 0L) { atX = e.x; atY = e.y; liftedAt = now(); wake() }
        }
    }

    /** The gesture dropped where it is (the sheet or the control centre took the finger): faded out. */
    fun cancel() {
        if (!fromX.isNaN() && liftedAt == 0L) { liftedAt = now(); wake() }
    }

    fun close() {
        thread?.interrupt()
    }

    private fun wake() {
        synchronized(lock) {
            if (thread?.isAlive != true) {
                if (failedAt != 0L && now() - failedAt < RETRY_MS) return
                thread = Thread(::run, "portal-back-chevron").apply { isDaemon = true; start() }
            }
            lock.notifyAll()
        }
    }

    private fun run() {
        var sc: SurfaceControl? = null
        var surface: Surface? = null
        try {
            val w = (LAYER_W * rear.fitX).toInt()
            sc = ShellLayer.create("portal-back-chevron", layerStack, ShellLayer.Z_CHEVRON, w, rear.heightPx, trusted = true, visible = false)
            SurfaceControl.Transaction().use { it.setPosition(sc, (rear.widthPx - w).toFloat(), 0f).apply() }
            surface = Surface(sc)
            val tab = Paint(Paint.ANTI_ALIAS_FLAG)
            val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                strokeWidth = STROKE * rear.fitX
            }
            val rect = RectF()
            val path = Path()
            var shown = false
            while (!Thread.currentThread().isInterrupted) {
                val x0 = fromX
                val lifted = liftedAt
                val fade = if (lifted == 0L) 1f else 1f - (now() - lifted) / FADE_MS.toFloat()
                if (x0.isNaN() || fade <= 0f) {
                    if (shown) { SurfaceControl.Transaction().use { it.setVisibility(sc, false).apply() }; shown = false }
                    if (fade <= 0f) fromX = Float.NaN
                    synchronized(lock) { if (fromX.isNaN() || liftedAt == lifted) lock.wait(IDLE_MS) }
                    continue
                }
                val p = progress(x0, fromY, atX, atY)
                val c = surface.lockCanvas(null)
                try {
                    c.drawColor(0, PorterDuff.Mode.CLEAR)
                    val armed = p >= 1f
                    val grow = p.coerceIn(0f, 1f)
                    val reach = (MIN_REACH + (MAX_REACH - MIN_REACH) * grow) * rear.fitX
                    val half = HALF_H * rear.fitY
                    val cy = (atY * rear.fitY).coerceIn(half, rear.heightPx - half)
                    val right = w.toFloat()
                    rect.set(right - reach, cy - half, right + reach, cy + half)
                    tab.color = argb((if (armed) 0.78f else 0.55f) * fade, 0, 0, 0)
                    c.drawRoundRect(rect, reach, reach, tab)
                    // The chevron in the tab, pointing left, opening as it grows.
                    val arm = ARM * rear.fitY * (0.6f + 0.4f * grow)
                    val tipX = right - reach * 0.62f
                    val backX = tipX + arm * 0.7f
                    path.reset()
                    path.moveTo(backX, cy - arm); path.lineTo(tipX, cy); path.lineTo(backX, cy + arm)
                    arrow.color = argb((if (armed) 1f else 0.45f + 0.4f * grow) * fade, 255, 255, 255)
                    if (reach > (MIN_REACH + 4f) * rear.fitX) c.drawPath(path, arrow)
                } finally { surface.unlockCanvasAndPost(c) }
                if (!shown) { SurfaceControl.Transaction().use { it.setVisibility(sc, true).apply() }; shown = true }
                if (lifted == 0L) synchronized(lock) { lock.wait(FRAME_MS) } else Thread.sleep(FRAME_MS)
            }
        } catch (_: InterruptedException) {
        } catch (e: ShellLayer.Untrusted) {
            failedAt = now()
            Log.w(TAG, "back chevron: not shown, no trusted overlay: ${e.message}")
        } catch (e: Throwable) {
            failedAt = now()
            Log.w(TAG, "back chevron stopped: ${e.message}")
        } finally {
            runCatching { surface?.release() }
            ShellLayer.remove(sc)
            synchronized(lock) { if (thread === Thread.currentThread()) thread = null }
        }
    }

    companion object {
        private const val TAG = "AiUsageRear"
        /** The layer's width at the right edge, panel units: the tab at its longest and a margin. */
        private const val LAYER_W = 96f
        private const val MIN_REACH = 10f
        private const val MAX_REACH = 44f
        private const val HALF_H = 56f
        private const val ARM = 13f
        private const val STROKE = 4.5f
        private const val FADE_MS = 160L
        private const val FRAME_MS = 16L
        private const val IDLE_MS = 1_000L
        private const val RETRY_MS = 30_000L

        /**
         * How far to a Back, by EdgeGestures' rule: the travel left beyond the travel up or down,
         * over BACK_TRAVEL. At 1 or more the lift is Back.
         */
        fun progress(fromX: Float, fromY: Float, x: Float, y: Float): Float =
            ((fromX - x) - kotlin.math.abs(y - fromY)).coerceAtLeast(0f) / EdgeGestures.BACK_TRAVEL

        private fun argb(a: Float, r: Int, g: Int, b: Int) =
            ((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or (r shl 16) or (g shl 8) or b

        private fun now() = android.os.SystemClock.uptimeMillis()
    }
}
