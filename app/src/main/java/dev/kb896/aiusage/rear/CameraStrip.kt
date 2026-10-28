package dev.kb896.aiusage.rear

import android.util.Log
import android.view.Surface
import android.view.SurfaceControl
import dev.kb896.aiusage.Panel

/**
 * The camera strip (x < Panel.CUTOUT_RIGHT) beside a guest kept right of the cameras
 * (GuestShape): a solid layer of the shell's own over it, so whatever task lies under the
 * strip — a stale app, our panel — does not show there. In the guest's own background
 * colour where it has one, black where not (StripColor), read off the guest's leftmost
 * columns by [sample]: once as it is shown (the caller samples before the reveal, so it
 * arrives right), then every [SAMPLE_MS] while it is up, the rear lit ([dark] false) and
 * the sheet shut. A new colour eases in over StripColor.EASE_MS.
 *
 * Over every app, under the guest's cover ([ShellLayer.Z_STRIP]); trusted and so
 * touch-transparent, or not shown at all. Fades in and out as the frame does (FrameFade),
 * on a thread of its own that lives while it is up or fading and ends once faded out —
 * decided under this instance's lock, which [show] takes too, as in PortalFrame.
 *
 * @param sample the guest's edge colour now (StripColor.pick), or null when it cannot be read
 *   (the strip then keeps the colour it has).
 */
class CameraStrip(private val layerStack: Int, private val sample: () -> Int?) {

    private val fade = FrameFade()
    @Volatile private var thread: Thread? = null
    @Volatile private var gen = 0
    @Volatile private var failedAt = 0L
    /** The colour it is going to. */
    @Volatile private var target = BLACK
    @Volatile private var sheet = 0f
    /** The rear is dark: nothing sampled. */
    @Volatile var dark = false
    /** The running thread's layer, for a capture to leave out; null while there is none. */
    @Volatile var currentLayer: SurfaceControl? = null
        private set

    /** Fades in, at [color] if given (else the last one), and samples on from there. */
    @Synchronized
    fun show(color: Int?) {
        color?.let { target = it }
        fade.show(now())
        if (thread?.isAlive == true) return
        if (failedAt != 0L && System.nanoTime() - failedAt < RETRY_NS) return
        val myGen = ++gen
        thread = Thread({ run(myGen) }, "portal-strip").apply { isDaemon = true; start() }
    }

    /** Fades out; gone once faded. */
    @Synchronized
    fun hide() = fade.hide(now())

    /** How far the sheet is open: while it is open at all, no samples. Any thread. */
    fun sheetOpen(progress: Float) { sheet = progress }

    /** This frame's level, or null once faded out (and the thread forgotten). Under the lock. */
    @Synchronized
    private fun level(myGen: Int): Float? {
        if (gen != myGen) return null
        val now = now()
        if (fade.gone(now)) { thread = null; return null }
        return fade.level(now)
    }

    @Synchronized
    private fun wanted() = fade.shown

    private fun run(myGen: Int) {
        var sc: SurfaceControl? = null
        var surface: Surface? = null
        try {
            sc = ShellLayer.create("portal-strip", layerStack, ShellLayer.Z_STRIP, trusted = true, alpha = 0f,
                width = Panel.CUTOUT_RIGHT, height = Panel.HEIGHT)
            surface = Surface(sc)
            currentLayer = sc
            val ease = StripColor.Ease()
            var drawn: Int? = null
            var alpha = -1f
            var nextSample = now() + SAMPLE_MS
            var samples = 0
            while (!Thread.currentThread().isInterrupted) {
                val level = level(myGen) ?: break
                val now = now()
                if (now >= nextSample && wanted() && !dark && sheet <= 0f) {
                    val began = System.nanoTime()
                    sample()?.let { target = it }
                    val took = (System.nanoTime() - began) / 1_000
                    // A few measured, then one now and then: the capture's cost, for the rate.
                    if (samples < 3 || samples % 120 == 0) Log.i(TAG, "camera strip: sampled in $took µs")
                    samples++
                    nextSample = now() + SAMPLE_MS
                }
                val color = ease.at(target, now)
                if (color != drawn) {
                    val c = surface.lockCanvas(null)
                    try { c.drawColor(color) } finally { surface.unlockCanvasAndPost(c) }
                    drawn = color
                }
                if (level != alpha) {
                    SurfaceControl.Transaction().use { it.setAlpha(sc, level).apply() }
                    alpha = level
                }
                // Frames while it fades or eases; otherwise only until the next sample.
                val busy = level < 1f || !ease.settled(now)
                Thread.sleep(if (busy) FRAME_MS else minOf(IDLE_MS, maxOf(nextSample - now(), 1L)))
            }
        } catch (_: InterruptedException) {
        } catch (e: ShellLayer.Untrusted) {
            failedAt = System.nanoTime()
            Log.w(TAG, "camera strip: not shown, no trusted overlay: ${e.message}")
        } catch (e: Throwable) {
            failedAt = System.nanoTime()
            Log.w(TAG, "camera strip stopped: ${e.message}")
        } finally {
            if (currentLayer === sc) currentLayer = null
            runCatching { surface?.release() }
            ShellLayer.remove(sc)
            synchronized(this) { if (gen == myGen && thread === Thread.currentThread()) thread = null }
        }
    }

    companion object {
        private const val TAG = "AiUsageRear"
        private const val BLACK = 0xFF000000.toInt()
        private const val FRAME_MS = 16L
        /** Never asleep longer than this, so a hide or a show is seen within it. */
        private const val IDLE_MS = 100L
        /**
         * Between samples of the guest's edge. One is a captureDisplay cropped to 6 × 572
         * px and a read of those pixels — logged, see [run]; a guess to be tuned by what
         * the log measures on the phone.
         */
        const val SAMPLE_MS = 500L
        private const val RETRY_NS = 30_000_000_000L

        private fun now() = android.os.SystemClock.uptimeMillis()
    }
}
