package dev.kb896.aiusage.rear

import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import dev.kb896.aiusage.Panel
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The back blurring and dimming away while one app gives way to another there, and clearing
 * again over the new one (the owner, 2026-11-10: "the screen looks like it's frozen for half a
 * second … a blur transition with blur in, blur out and fade").
 *
 * WHAT IT COVERS. Starting an app on the back, or bringing the front's over, takes the system
 * some hundreds of milliseconds in which nothing on the rear changes — the old app sits there,
 * still — and then the new one slides in. The curtain comes up as that work begins
 * ([rise]): the rear, whatever is on it, blurs and darkens under it (ShellOverlay's live blur,
 * and a dim of its own over that). It is held while the work is done, a moment more for the
 * new app's first picture, and then it clears ([fall]) — over the new app, already in place.
 *
 * ITS OWN SHELL OVERLAY, under the portal's sheet (ShellLayer.Z_PANES), with nothing drawn on
 * it but the dim. Like every overlay it stops fingers reaching the app under it while it is up.
 *
 * NEVER LEFT UP: [fall] always follows [rise] (the caller's finally), and a curtain nobody
 * lets fall comes down by itself after [HOLD_MAX_MS].
 */
class GuestCurtain(layerStack: Int, rear: RearGeometry) : ShellOverlay.Content {

    private val overlay = ShellOverlay(layerStack, rear, "guest-curtain", "guest curtain", this, z = ShellLayer.Z_PANES, blurMax = BLUR)
    /** Where the loader turns: the middle of the glass right of the lenses. */
    private val middleX = (rear.cutoutUnits + Panel.WIDTH) / 2f
    private val clock = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "guest-curtain-clock").apply { isDaemon = true } }
    private val lock = Any()
    private var from = 0f
    private var to = 0f
    private var startMs = 0L
    private var lengthMs = 1L
    private var pending: ScheduledFuture<*>? = null
    /** How many [rise]s are waiting for their [fall]: it clears when the last has. */
    private var holds = 0

    private fun now() = android.os.SystemClock.uptimeMillis()

    private fun at(nowMs: Long): Float = synchronized(lock) {
        val t = ((nowMs - startMs) / lengthMs.toFloat()).coerceIn(0f, 1f)
        from + (to - from) * (t * t * (3f - 2f * t))
    }

    /** Up it comes, from wherever it is. Any thread. */
    fun rise() {
        synchronized(lock) {
            holds++
            pending?.cancel(false)
            val n = now()
            from = at(n); to = 1f; startMs = n; lengthMs = IN_MS
            // Let fall by itself if nobody does.
            pending = clock.schedule({ synchronized(lock) { holds = 0 }; clear() }, HOLD_MAX_MS, TimeUnit.MILLISECONDS)
        }
        if (!overlay.begin()) Log.i(TAG, "guest curtain: no layer for it — the change shows as it is")
        overlay.changed()
    }

    /** The work is done: a moment for the new app's picture, and it clears. Any thread. */
    fun fall() {
        synchronized(lock) {
            holds = (holds - 1).coerceAtLeast(0)
            if (holds > 0) return
            pending?.cancel(false)
            pending = clock.schedule({ clear() }, SETTLE_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun clear() {
        synchronized(lock) {
            val n = now()
            from = at(n); to = 0f; startMs = n; lengthMs = OUT_MS
            pending = clock.schedule({
                // Still on its way down (not risen again since): the layers go.
                val gone = synchronized(lock) { to == 0f && holds == 0 }
                if (gone) overlay.end()
            }, OUT_MS + 40L, TimeUnit.MILLISECONDS)
        }
        overlay.changed()
    }

    override fun frame(): Float = at(now())

    override fun painter() = object : ShellOverlay.Painter {
        private val dim = Paint()
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val box = android.graphics.RectF()
        override fun draw(canvas: Canvas, progress: Float): Boolean {
            // Over the overlay's own light dim: darker, so the change under it is not seen through the blur.
            dim.color = (Math.round(255 * DIM * progress).coerceIn(0, 255)) shl 24
            canvas.drawRect(0f, 0f, Panel.WIDTH.toFloat(), Panel.HEIGHT.toFloat(), dim)
            // THE LOADER: something is happening, while the rear itself shows nothing of it. A
            // faint ring, and an arc of light going round it, breathing longer and shorter as it
            // goes; in and out with the curtain.
            val t = now() / 1000f
            val cy = Panel.HEIGHT / 2f
            box.set(middleX - LOADER_R, cy - LOADER_R, middleX + LOADER_R, cy + LOADER_R)
            ring.strokeWidth = LOADER_W
            ring.color = 0xFFFFFF or (Math.round(255 * 0.18f * progress) shl 24)
            canvas.drawOval(box, ring)
            val sweep = 90f + 60f * Math.sin(t * 3.1).toFloat()
            ring.color = 0xFFFFFF or (Math.round(255 * 0.95f * progress) shl 24)
            canvas.drawArc(box, (t * 300f) % 360f, sweep, false, ring)
            // Moving of itself for as long as it shows: the loader turns.
            return progress > 0.004f || synchronized(lock) { now() - startMs <= lengthMs + 32L }
        }
    }

    private companion object {
        const val TAG = "AiUsageRear"
        /** Up, the wait for the new app's first picture, and clear again. */
        const val IN_MS = 170L
        const val SETTLE_MS = 240L
        const val OUT_MS = 340L
        /** A curtain nobody lets fall comes down after this. */
        const val HOLD_MAX_MS = 6_000L
        /** Its own dim at full, over the overlay's. */
        const val DIM = 0.42f
        /** How much the rear is blurred under it, in SurfaceFlinger px: enough that what moves under it is not made out. */
        const val BLUR = 200
        /** The loader: its ring's radius and width, in panel units. */
        const val LOADER_R = 26f
        const val LOADER_W = 5f
    }
}
