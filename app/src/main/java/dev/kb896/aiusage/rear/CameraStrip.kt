package dev.kb896.aiusage.rear

import android.graphics.Paint
import android.graphics.PorterDuff
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl

/**
 * The camera strip (x < RearGeometry.cutoutPx) beside a guest on a private display
 * (PrivateDisplay): a layer of the shell's own over it, so whatever lies under the strip — our
 * panel, always, on these — does not show there. It covers the bars of an app narrower than its
 * display too (StripColor.around), black in the app's picture, clear over the app. In the guest's own background
 * colour where it has one, black where not (StripColor), read off the guest's leftmost
 * columns by [sample]: once as it is shown (the caller samples before the reveal, so it
 * arrives right), then at StripColor.Pace's pace while it is up, the rear lit ([dark] false) and
 * the sheet shut. A new colour eases in over StripColor.EASE_MS.
 *
 * Over the app's picture's Z, under the guest's cover ([ShellLayer.Z_STRIP]); trusted and so
 * touch-transparent, or not shown at all. Fades in and out as the frame does (FrameFade),
 * on a thread of its own that lives while it is up or fading and ends once faded out —
 * decided under this instance's lock, which [show] takes too, as in PortalFrame.
 *
 * @param rear the rear display as it is (RearGeometry): the layer is the whole of it.
 * THE KEYBOARD. The app's display is wider than a narrow app, and its keyboard is the display's
 * whole width, so it reaches into the bars this covers: the bars stop above it ([keyboardTop],
 * StripColor.around). Read by [keyboard] off the app's picture, often for a while after a touch
 * (a keyboard comes up on a tap), seldom otherwise.
 *
 * @param sample the guest's edge colour now (StripColor.pick), or null when it cannot be read
 *   (the strip then keeps the colour it has).
 * @param keyboard where a keyboard starts on the app's display now (StripColor.keyboardTop), -1
 *   with none, null when it cannot be read (the strip keeps what it had).
 */
class CameraStrip(
    private val layerStack: Int, private val rear: RearGeometry,
    private val keyboard: () -> Int? = { -1 }, private val sample: () -> Int?,
) {

    private val fade = FrameFade()
    @Volatile private var thread: Thread? = null
    @Volatile private var gen = 0
    @Volatile private var failedAt = 0L
    /** Where the app stands (GuestShape.appOnRear); null, the whole area. */
    @Volatile private var app: GuestShape.Bounds? = null
    /** The colour it is going to. */
    @Volatile private var target = BLACK

    /** The colour it stands at, as near as a picture of it needs. */
    val color: Int get() = target

    /** Where a keyboard on the app's display starts, as last read; null with none. */
    @Volatile var keyboardTop: Int? = null
        private set
    @Volatile private var pokedAt = 0L
    @Volatile private var sheet = 0f
    /** The rear is dark: nothing sampled. */
    @Volatile var dark = false
    /** The running thread's layer, for a capture to leave out; null while there is none. */
    @Volatile var currentLayer: SurfaceControl? = null
        private set

    /** Fades in, at [color] if given (else the last one), and samples on from there. */
    @Synchronized
    fun show(color: Int?) {
        color?.let { target = it; poke() }
        fade.show(now())
        if (thread?.isAlive == true) return
        if (failedAt != 0L && System.nanoTime() - failedAt < RETRY_NS) return
        val myGen = ++gen
        thread = Thread({ run(myGen) }, "portal-strip").apply { isDaemon = true; start() }
    }

    /** Fades out; gone once faded. */
    @Synchronized
    fun hide() = fade.hide(now())

    /** Around [app] from the next frame (GuestShape.appOnRear), or the whole area for null. Any thread. */
    fun follow(app: GuestShape.Bounds?) { this.app = app }

    /** Something on the rear may have changed (a touch, a new guest): sample quickly again (StripColor.Pace). Any thread. */
    fun poke() { poked = true; pokedAt = now() }
    @Volatile private var poked = false

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
            // The whole rear, in its own pixels: where the colour goes moves with the app.
            sc = ShellLayer.create("portal-strip", layerStack, ShellLayer.Z_STRIP, rear.widthPx, rear.heightPx, trusted = true, visible = false, alpha = 0f)
            surface = Surface(sc)
            currentLayer = sc
            val ease = StripColor.Ease()
            var drawn: Int? = null
            var drawnAround: List<GuestShape.Bounds>? = null
            val paint = Paint()
            var alpha = -1f
            val pace = StripColor.Pace()
            var nextSample = now() + StripColor.FAST_MS
            var nextKeyboard = now()
            var samples = 0
            var keyboardReads = 0
            while (!Thread.currentThread().isInterrupted) {
                val level = level(myGen) ?: break
                val now = now()
                // A poke brings the next sample forward to a quick one.
                if (poked) { poked = false; pace.poke(); nextSample = minOf(nextSample, now + StripColor.FAST_MS) }
                if (now >= nextSample && wanted() && !dark && sheet <= 0f) {
                    val began = System.nanoTime()
                    val was = target
                    sample()?.let { target = it }
                    val took = (System.nanoTime() - began) / 1_000
                    // A few measured, then one now and then: the capture's cost, for the rate.
                    if (samples < 3 || samples % 120 == 0) Log.i(TAG, "camera strip: sampled in $took µs")
                    samples++
                    nextSample = now() + pace.next(changed = target != was)
                }
                if (now >= nextKeyboard && wanted() && !dark && sheet <= 0f) {
                    val read = keyboard()
                    val was = keyboardTop
                    read?.let { keyboardTop = it.takeIf { top -> top >= 0 } }
                    if (keyboardTop != was) Log.i(TAG, "camera strip: keyboard ${keyboardTop?.let { "from y $it" } ?: "gone"}")
                    if (keyboardReads++ < 3) Log.i(TAG, "camera strip: keyboard read $read")
                    // Quick for a while after a touch, which is what brings a keyboard up or down.
                    nextKeyboard = now() + if (now - pokedAt < KEYBOARD_QUICK_FOR_MS) KEYBOARD_QUICK_MS else KEYBOARD_SLOW_MS
                }
                val color = ease.at(target, now)
                val around = StripColor.around(rear, app ?: GuestShape.appArea(rear), keyboardTop)
                if (color != drawn || around != drawnAround) {
                    val c = surface.lockCanvas(null)
                    try {
                        c.drawColor(0, PorterDuff.Mode.CLEAR)
                        paint.color = color
                        for (b in around) c.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), paint)
                    } finally { surface.unlockCanvasAndPost(c) }
                    drawn = color
                    drawnAround = around
                }
                if (level != alpha) {
                    // Hidden, not only clear, while it shows nothing (ShellLayer).
                    SurfaceControl.Transaction().use { it.setAlpha(sc, level).setVisibility(sc, level > 0f).apply() }
                    alpha = level
                }
                // Frames while it fades or eases; otherwise only until the next sample.
                val busy = level < 1f || !ease.settled(now)
                Thread.sleep(if (busy) FRAME_MS else minOf(IDLE_MS, maxOf(minOf(nextSample, nextKeyboard) - now(), 1L)))
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
        private const val RETRY_NS = 30_000_000_000L
        /** Keyboard reads this often for [KEYBOARD_QUICK_FOR_MS] after a touch; [KEYBOARD_SLOW_MS] otherwise. */
        private const val KEYBOARD_QUICK_MS = 80L
        private const val KEYBOARD_QUICK_FOR_MS = 2_500L
        private const val KEYBOARD_SLOW_MS = 1_000L

        private fun now() = android.os.SystemClock.uptimeMillis()
    }
}
