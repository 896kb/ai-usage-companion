package dev.kb896.aiusage.rear

import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RectF
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl

/**
 * The pill that says the back is being recorded: a dark capsule, a red dot pulsing in it and the
 * time so far, over everything on the rear. A finger drags it anywhere; a tap on it stops the
 * recording (the service's: [contains], [moveBy]).
 *
 * NOT IN THE RECORDING. Both its layers are marked to be left out of screenshots and screen
 * recordings (the hidden Transaction.setSkipScreenshot, as the system's own private windows
 * are): screenrecord mirrors the rear's layers, and these are not mirrored. Where the firmware
 * has no such call, it is logged, and the pill is in the picture.
 *
 * TWO LAYERS, as the sheet's: the pill, trusted, drawn; and under it a blocker as big as it,
 * untrusted, transparent, so a finger on the pill does not also reach the panel under it (a
 * guest's are the service's own to forward, and it does not). In the rear's own pixels.
 */
class RecordPill(private val layerStack: Int, private val rear: RearGeometry) {

    private val w = (PILL_W * rear.fitX).toInt()
    private val h = (PILL_H * rear.fitY).toInt()
    /**
     * Where it stands, its top left, in the rear's pixels: first, in the middle of the glass right of
     * the lenses, a small gap under the top.
     */
    @Volatile private var x = rear.cutoutPx + (rear.widthPx - rear.cutoutPx - w) / 2
    @Volatile private var y = (MARGIN * rear.fitY).toInt()
    @Volatile private var thread: Thread? = null
    @Volatile private var startedAt = 0L
    /** When it began to fade away ([hide]); 0 while it stays. */
    @Volatile private var hidingAt = 0L
    private var pill: SurfaceControl? = null
    private var blocker: SurfaceControl? = null

    /** Up, from [nowMs] (the recording's start, for its time). */
    @Synchronized
    fun show(nowMs: Long) {
        // One still fading from the last recording: gone at once, for this one.
        if (thread != null) { if (hidingAt == 0L) return; drop() }
        startedAt = nowMs
        hidingAt = 0L
        // Each recording's pill starts where the first did: dragged, it stays only for that one.
        x = rear.cutoutPx + (rear.widthPx - rear.cutoutPx - w) / 2
        y = (MARGIN * rear.fitY).toInt()
        try {
            val b = ShellLayer.create("record-pill-blocker", layerStack, ShellLayer.Z_PANES, w, h, trusted = false, visible = false)
            blocker = b
            val p = ShellLayer.create("record-pill", layerStack, ShellLayer.Z_PANES, w, h, trusted = true, visible = false, format = PixelFormat.TRANSLUCENT)
            pill = p
            // The blocker needs a buffer to be an input window at all: one transparent frame.
            Surface(b).also { s -> runCatching { s.lockHardwareCanvas().also { it.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR); s.unlockCanvasAndPost(it) } }; s.release() }
            SurfaceControl.Transaction().use { t ->
                skipScreenshot(t, b); skipScreenshot(t, p)
                t.setPosition(b, x.toFloat(), y.toFloat()).setPosition(p, x.toFloat(), y.toFloat())
                // Faded in by its render thread, from nothing.
                t.setAlpha(p, 0f)
                t.setVisibility(b, true).setVisibility(p, true).apply()
            }
            thread = Thread({ render(p) }, "record-pill").apply { isDaemon = true; start() }
        } catch (e: Throwable) {
            Log.w(TAG, "record pill: not shown (${e.message})")
            drop()
        }
    }

    /** Fading away over [FADE_MS], then gone; no finger finds it meanwhile. Its render thread takes its layers. */
    fun hide() {
        if (thread == null || hidingAt != 0L) return
        hidingAt = android.os.SystemClock.uptimeMillis()
    }

    /** Gone at once, both layers. */
    @Synchronized
    private fun drop() {
        thread?.interrupt()
        thread = null
        ShellLayer.remove(pill); pill = null
        ShellLayer.remove(blocker); blocker = null
    }

    /** Up and staying: what a finger can find. */
    val shown: Boolean get() = thread != null && hidingAt == 0L

    /** Whether a finger at [ux], [uy] (panel units, as the touches come) is on it, with a little room. */
    fun contains(ux: Float, uy: Float): Boolean {
        if (!shown) return false
        val px = ux * rear.fitX
        val py = uy * rear.fitY
        val slop = SLOP * rear.fitX
        return px >= x - slop && px <= x + w + slop && py >= y - slop && py <= y + h + slop
    }

    /** Moved by [dux], [duy] (panel units) from where it stood at [fromX], [fromY] (its [position]), kept on the rear. */
    @Synchronized
    fun moveTo(fromX: Int, fromY: Int, dux: Float, duy: Float) {
        x = (fromX + dux * rear.fitX).toInt().coerceIn(0, rear.widthPx - w)
        y = (fromY + duy * rear.fitY).toInt().coerceIn(0, rear.heightPx - h)
        val p = pill ?: return
        val b = blocker ?: return
        runCatching {
            SurfaceControl.Transaction().use { t ->
                t.setPosition(p, x.toFloat(), y.toFloat()).setPosition(b, x.toFloat(), y.toFloat()).apply()
            }
        }
    }

    /** Where it stands now, its top left in the rear's pixels. */
    val position: Pair<Int, Int> get() = x to y

    private fun render(sc: SurfaceControl) {
        val surface = Surface(sc)
        val ground = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9101014.toInt() }
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * rear.fitX; color = 0x40FFFFFF }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF3B30.toInt() }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = h * 0.42f; isFakeBoldText = true }
        val box = RectF()
        var alpha = -1f
        try {
            while (!Thread.currentThread().isInterrupted) {
                // In over FADE_MS from its start, out over FADE_MS from its hide, then gone.
                val t = android.os.SystemClock.uptimeMillis()
                val fadeIn = ((t - startedAt) / FADE_MS.toFloat()).coerceIn(0f, 1f)
                val hid = hidingAt
                val fadeOut = if (hid == 0L) 1f else (1f - (t - hid) / FADE_MS.toFloat()).coerceIn(0f, 1f)
                val a = minOf(fadeIn, fadeOut)
                if (hid != 0L && fadeOut <= 0f) break
                if (a != alpha) {
                    alpha = a
                    runCatching { SurfaceControl.Transaction().use { it.setAlpha(sc, a).apply() } }
                }
                val c = runCatching { surface.lockHardwareCanvas() }.getOrNull() ?: break
                try {
                    c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                    box.set(1f, 1f, w - 1f, h - 1f)
                    c.drawRoundRect(box, h / 2f, h / 2f, ground)
                    c.drawRoundRect(box, h / 2f, h / 2f, rim)
                    // The dot breathes: once a second, from bright to half and back.
                    val now = android.os.SystemClock.uptimeMillis()
                    val phase = ((now - startedAt) % PULSE_MS) / PULSE_MS.toFloat()
                    dot.alpha = (255 * (0.55f + 0.45f * kotlin.math.cos(phase * 2 * Math.PI).toFloat())).toInt()
                    val r = h * 0.18f
                    val cx = h * 0.5f
                    c.drawCircle(cx, h / 2f, r, dot)
                    val secs = ((now - startedAt) / 1000).toInt()
                    val label = "%d:%02d".format(secs / 60, secs % 60)
                    val m = text.fontMetrics
                    c.drawText(label, cx + r + h * 0.28f, h / 2f - (m.ascent + m.descent) / 2f, text)
                } finally {
                    surface.unlockCanvasAndPost(c)
                }
                Thread.sleep(FRAME_MS)
            }
        } catch (_: InterruptedException) {
        } finally {
            surface.release()
        }
        // Faded out (not interrupted by drop, which has already taken them): the layers go.
        if (hidingAt != 0L) synchronized(this) { if (thread === Thread.currentThread()) drop() }
    }

    private fun skipScreenshot(t: SurfaceControl.Transaction, sc: SurfaceControl) {
        if (ShootMode.capturesShown()) return // shooting for the site: in the picture
        runCatching {
            t.javaClass.getMethod("setSkipScreenshot", SurfaceControl::class.java, Boolean::class.javaPrimitiveType).invoke(t, sc, true)
        }.onFailure { if (!skipLogged) { skipLogged = true; Log.w(TAG, "record pill: no setSkipScreenshot (${it.message}) — it will be in the recording") } }
    }

    private companion object {
        const val TAG = "AiUsageRear"
        /** Its size, and the gap over it in its first place, in panel units. */
        const val PILL_W = 200f
        const val PILL_H = 76f
        const val MARGIN = 12f
        /** How far off it a finger still finds it, panel units. */
        const val SLOP = 10f
        const val PULSE_MS = 1_000L
        const val FRAME_MS = 33L
        const val FADE_MS = 320L
        @Volatile var skipLogged = false
    }
}
