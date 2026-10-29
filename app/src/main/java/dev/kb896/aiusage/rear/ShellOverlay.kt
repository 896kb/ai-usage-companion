package dev.kb896.aiusage.rear

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl
import dev.kb896.aiusage.Panel
import java.util.concurrent.atomic.AtomicInteger

/**
 * A sheet the shell service lays over the rear — the portal sheet (ShellSheet) or the
 * control centre over a guest (ShellControlCenter) — on layers of its own, over our panel
 * or a guest, locked or not, since nothing is ever brought "above" anything. Moved out of
 * ShellSheet, which it was; see specs/2026-10-28-app-portal-shell-sheet-design.md. What is
 * drawn on it is the [Content]'s.
 *
 * TWO LAYERS, made with ShellLayer:
 * - the SHEET ([ShellLayer.Z_SHEET], under the frame), a trusted overlay — fail closed, as
 *   the frame: if it cannot be trusted nothing is shown — drawn through
 *   Surface.lockHardwareCanvas (a RecordingCanvas, measured on the 17 Pro), and asking
 *   SurfaceFlinger to blur whatever is under it ([blurPx]);
 * - the BLOCKER ([ShellLayer.Z_BLOCKER], under the sheet), full screen, one fully
 *   transparent buffer, and NOT trusted. Its whole job is Android's untrusted-touch
 *   occlusion: while it is up, every touch to the windows below — the guest, our panel —
 *   is dropped, so a tap on Close cannot also tap the app. The touches themselves are
 *   read off the raw touchscreen by the service.
 * Both appear in one transaction and go in one transaction: a blocker without its sheet
 * would be an invisible wall over the rear, so there is never one without the other. The
 * two sheets are never up together (the service keeps them apart), so they share the
 * z-order.
 *
 * THE BLUR IS SURFACEFLINGER'S, OF THE LIVE REAR. The sheet layer's background blur radius
 * (the hidden Transaction.setBackgroundBlurRadius) follows how far open the content says it
 * is drawn, [blurPx] — so whatever is under it, the guest or our panel, is blurred as it is
 * NOW, every frame. Measured on the 17 Pro (ro.surface_flinger.supports_background_blur=1):
 * a shell layer with a translucent buffer blurs what is under it, smoothly ramped 0→80
 * over 61 frames. Where the call is missing or refused, the sheet is drawn with the dim
 * alone, and that is logged once.
 *
 * The layer covers the whole rear, uncropped: a crop to a sheet rising from the bottom was
 * tried and read as a hard straight cut through the blur.
 *
 * WHAT IS DRAWN, per frame: cleared; the dim over the whole layer, by how far open (so the
 * blur has the whole rear to cover); then the content.
 *
 * IN PANEL UNITS, STRETCHED TO THE REAR. Both layers are the rear's real size ([rear]) — a
 * 904x572 layer on a Pro Max's 976x596 left its right and bottom unblurred and, the blocker
 * being that size too, let touches there through to the app. The content draws in the 17
 * Pro's 904x572 panel units, as the panel does, under one canvas scale by the rear's fit
 * (RearGeometry.fitX, fitY): the touches come in panel units (RearTouches), so what is
 * drawn under a finger is what its hit test finds there. PER AXIS, ON PURPOSE, as the panel
 * itself draws (RearDialView.fitX/fitY; only its lab, taller than the panel, scales by fitX
 * alone): the two differ by under four per cent on a Pro Max, so a round shape is that much
 * squashed, as the panel's own control centre is there. A uniform scale would letterbox the
 * contents, the touches would have to be letterboxed alike, and the shell's control centre
 * would no longer stand where the panel's does.
 *
 * THREADING. [begin] makes both layers and shows them on the caller's thread, under the
 * instance lock, so when it says true they are there. The content's setters may be called
 * from any thread; they publish and call [changed]. One render thread per opening only
 * draws: while the version moves — or the content says it moves of itself — sleeping
 * [FRAME_MS] between looks (~60 fps while anything changes, nothing redrawn while nothing
 * does: the last buffer stays on screen). The render thread never takes the instance lock,
 * so [end] may join it. All painting in this process happens under [DRAW]: the painters
 * share Glass's shaders, and a run outliving its join (a render thread stuck in the
 * driver) must not paint over the next one's uniforms.
 *
 * @param layerStack the rear display's layer stack (RearUserService.rearLayerStack).
 * @param rear the rear display as it is (RearGeometry): the layers' size and the content's scale.
 * @param layer the layers' and the render thread's name ("portal-sheet").
 * @param what what the log calls it ("portal sheet").
 */
class ShellOverlay(
    private val layerStack: Int,
    private val rear: RearGeometry,
    private val layer: String,
    private val what: String,
    private val content: Content,
) {

    /** What one sheet draws. Its methods run on the render thread, under [DRAW]. */
    interface Content {
        /**
         * Read once at the top of a frame: how far open it is to be drawn, 0 to 1 — the dim,
         * the blur and [onProgress] follow it. The content keeps what it read for [draw].
         */
        fun frame(): Float

        /**
         * One run's painting, made on its render thread when it starts (a run's paints and
         * painters are its own, so an overlapping run shares nothing mutable).
         */
        fun painter(): Painter

        /** Told when a frame was posted, [sinceBeganMs] after [begin] showed run [gen]. */
        fun drew(gen: Int, sinceBeganMs: Long, progress: Float) {}
    }

    /** One run's drawing. */
    fun interface Painter {
        /**
         * Draws over the dim at [progress] (as [Content.frame] said). True when it moves of
         * itself — liquid, an easing — and wants the next frame though nothing [changed].
         */
        fun draw(canvas: Canvas, progress: Float): Boolean
    }

    /**
     * One opening: its layers, made by [begin]. The layers are guarded by the Run's own
     * monitor — [end] hides them, the render thread takes them away and releases them.
     * The surfaces are the render thread's from the moment it starts.
     */
    private class Run(
        val gen: Int,
        var blocker: SurfaceControl?,
        var sheet: SurfaceControl?,
        val blockerSurface: Surface,
        val surface: Surface,
    ) {
        /** Between a successful [begin] and [end], and not after the render loop died. */
        @Volatile var live = true
        /** When [begin] showed it (uptime ms), for [Content.drew]. */
        val beganAt = android.os.SystemClock.uptimeMillis()
    }

    private val version = AtomicInteger()

    /**
     * Told how far open the sheet is drawn, 0 to 1, with each frame the render thread posts,
     * and 0 once it has [end]ed — the portal frame fades under it (PortalFrame). Called on
     * the render thread, or [end]'s; it must be quick and take no lock of the caller's.
     */
    @Volatile var onProgress: ((Float) -> Unit)? = null
    /** So a render thread outliving its [end] cannot tell [onProgress] about a sheet that is gone. */
    private val progressLock = Any()

    /** Volatile: [up] reads it from threads that do not hold the lock. */
    @Volatile private var thread: Thread? = null
    @Volatile private var run: Run? = null
    /** Bumped by every begin() and end(); a render thread of another generation stops. */
    @Volatile private var gen = 0
    @Volatile private var failedAt = 0L
    /** Once per process: the firmware gives no way to make the sheet a trusted overlay. */
    @Volatile private var untrustedLogged = false

    /**
     * The sheet and its blocker are on the rear: true from a [begin] that returned true
     * until [end], false at once if the render loop dies (its layers are then gone).
     * Callers must gate hit-testing on it — a tap is the sheet's only while it is up;
     * otherwise it went to whatever is under it.
     */
    val up: Boolean get() = run?.live == true

    /** Something to draw changed: the next frame draws it. Any thread. */
    fun changed() { version.incrementAndGet() }

    /**
     * Makes the blocker and the sheet, shows both in one transaction, and starts the render
     * thread — all before returning, so true means they are up. False when it cannot (no
     * trusted overlay, no layer, the blocker's buffer refused, or any of these in the last
     * [RETRY_NS]); then there is no sheet and no blocker and the caller must not act as if
     * one were up. Already up: true.
     */
    @Synchronized
    fun begin(): Boolean {
        changed()
        if (up) return true
        if (failedAt != 0L && System.nanoTime() - failedAt < RETRY_NS) return false
        var blocker: SurfaceControl? = null
        var sheet: SurfaceControl? = null
        var blockerSurface: Surface? = null
        var surface: Surface? = null
        try {
            // Both made hidden, and shown together below.
            blocker = ShellLayer.create("$layer-blocker", layerStack, ShellLayer.Z_BLOCKER, rear.widthPx, rear.heightPx, trusted = false, visible = false)
            sheet = ShellLayer.create(layer, layerStack, ShellLayer.Z_SHEET, rear.widthPx, rear.heightPx, trusted = true, visible = false)
            // A layer with no buffer has no input window and occludes nothing: the blocker
            // gets one, transparent, once. Assigned first, so a refused post still releases it.
            blockerSurface = Surface(blocker)
            clear(blockerSurface)
            surface = Surface(sheet)
            SurfaceControl.Transaction().use { t ->
                t.setVisibility(blocker, true).setVisibility(sheet, true).apply()
            }
            val r = Run(++gen, blocker, sheet, blockerSurface, surface)
            run = r
            thread = Thread({ render(r) }, layer).apply { isDaemon = true; start() }
            return true
        } catch (e: Throwable) {
            if (e is ShellLayer.Untrusted) {
                if (!untrustedLogged) { untrustedLogged = true; Log.w(TAG, "$what: not shown, no trusted overlay: ${e.message}") }
            } else {
                Log.w(TAG, "$what: no layer: ${e.message}")
            }
            failedAt = System.nanoTime()
            run = null
            thread = null
            hide(blocker, sheet)
            runCatching { surface?.release() }
            runCatching { blockerSurface?.release() }
            ShellLayer.remove(sheet)
            ShellLayer.remove(blocker)
            return false
        }
    }

    /**
     * Hides the blocker and the sheet at once — in one transaction, on this thread, so the
     * blocker is gone no later than the sheet even if the render thread is stuck — stops
     * the render thread, and tells [onProgress] it is shut.
     */
    @Synchronized
    fun end() {
        val r = run
        run = null
        gen++
        if (r != null) synchronized(r) {
            r.live = false
            hide(r.blocker, r.sheet)
        }
        thread?.let { t ->
            t.interrupt()
            // The render thread never takes this lock, so waiting for it here is safe. If
            // it does not stop in time it still ends itself (its generation is gone) and
            // releases its own layers, which are hidden already.
            runCatching { t.join(JOIN_MS) }
        }
        thread = null
        // After gen moved on: a late frame of the old run is not told after this.
        synchronized(progressLock) { onProgress?.invoke(0f) }
        changed()
    }

    /** Draws [r] until it ends, then takes its layers off the rear and lets everything go. */
    private fun render(r: Run) {
        try {
            loop(r)
        } catch (_: InterruptedException) {
        } catch (e: Throwable) {
            Log.w(TAG, "$what stopped: ${e.message}")
            failedAt = System.nanoTime()
        } finally {
            // Taken out of [end]'s reach first, then let go: end() never touches a released layer.
            val blocker: SurfaceControl?
            val sheet: SurfaceControl?
            synchronized(r) {
                r.live = false
                blocker = r.blocker; sheet = r.sheet
                r.blocker = null; r.sheet = null
            }
            runCatching { r.surface.release() }
            runCatching { r.blockerSurface.release() }
            ShellLayer.remove(sheet)
            ShellLayer.remove(blocker)
        }
    }

    private fun loop(r: Run) {
        val surface = r.surface
        val painter = synchronized(DRAW) { content.painter() }
        val dim = Paint()
        // In panel units, under the fit's scale: the whole layer.
        val full = RectF(0f, 0f, Panel.WIDTH.toFloat(), Panel.HEIGHT.toFloat())
        var drawn = -1
        var moving = false
        // As the frame: if the hardware canvas is refused, the software one (flat buttons).
        var hardware = true
        var blurred = -1
        while (!Thread.currentThread().isInterrupted && gen == r.gen) {
            val began = System.nanoTime()
            val v = version.get()
            if (v != drawn || moving) {
                val canvas = if (hardware) {
                    try { surface.lockHardwareCanvas() } catch (e: Throwable) {
                        Log.w(TAG, "$what: hardware canvas refused (${e.message}); software")
                        hardware = false; continue
                    }
                } else surface.lockCanvas(null)
                var p = 0f
                try {
                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                    // Panel units from here on, stretched to the rear (see the class comment).
                    canvas.scale(rear.fitX, rear.fitY)
                    synchronized(DRAW) {
                        p = content.frame().coerceIn(0f, 1f)
                        // Over the whole layer: SurfaceFlinger blurs under it, and the dim is the sheet's ground.
                        dim.color = dimColor(p)
                        canvas.drawRect(full, dim)
                        moving = painter.draw(canvas, p)
                    }
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                // With the frame it goes with, and only when it moves.
                val radius = blurPx(p)
                if (radius != blurred && blur(r, radius)) blurred = radius
                drawn = v
                content.drew(r.gen, android.os.SystemClock.uptimeMillis() - r.beganAt, p)
                synchronized(progressLock) { if (gen == r.gen) onProgress?.invoke(p) }
            }
            val took = (System.nanoTime() - began) / 1_000_000
            Thread.sleep(maxOf(FRAME_MS - took, 1L))
        }
    }

    /**
     * Sets [r]'s sheet layer's background blur to [radius] SurfaceFlinger px. True when
     * done; false when the layer is gone or the firmware has no such call — then never
     * again (logged once): the sheet keeps its dim and goes without.
     */
    private fun blur(r: Run, radius: Int): Boolean {
        val call = backgroundBlur ?: return false
        if (blurBroken) return false
        return synchronized(r) {
            val sheet = r.sheet ?: return@synchronized false
            try {
                SurfaceControl.Transaction().use { t -> call.invoke(t, sheet, radius); t.apply() }
                true
            } catch (e: Throwable) {
                val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
                blurBroken = true
                Log.w(TAG, "$what: no background blur (${cause.javaClass.simpleName}: ${cause.message}); the dim alone")
                false
            }
        }
    }

    /** One transparent buffer, from the software canvas: no hardware renderer for a buffer posted once. */
    private fun clear(s: Surface) {
        val c = s.lockCanvas(null)
        try { c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR) } finally { s.unlockCanvasAndPost(c) }
    }

    /** Hides [blocker] and [sheet] in one transaction; quiet if they are gone. */
    private fun hide(blocker: SurfaceControl?, sheet: SurfaceControl?) {
        if (blocker == null && sheet == null) return
        runCatching {
            SurfaceControl.Transaction().use { t ->
                blocker?.let { t.setVisibility(it, false) }
                sheet?.let { t.setVisibility(it, false) }
                t.apply()
            }
        }.onFailure { Log.w(TAG, "$what: hide failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "AiUsageRear"
        private const val FRAME_MS = 16L
        private const val JOIN_MS = 300L
        /** After a failure, pulls do not try again for this long. */
        private const val RETRY_NS = 5_000_000_000L

        /**
         * SurfaceFlinger's blur radius under the sheet when fully open, in its px. Meant to
         * read as the control centre's 22-unit RenderEffect blur over the panel
         * (RearDialView.CC_BLUR), but the two are not the same scale: picked from the
         * spike's 0–80 ramp on the phone, and the number to tune by eye.
         */
        const val BLUR_MAX = 64

        /**
         * The dim over the blurred rear when fully open: the control centre's (RearDialView.CC_DIM),
         * repeated here rather than reached for — that one is private to the panel's view.
         */
        const val DIM = 0.25f

        /** Every painter in the process paints under this: they share Glass's shaders. */
        val DRAW = Any()

        /** The blur at [progress], in whole SurfaceFlinger px, so a settled sheet sends no new transaction. */
        internal fun blurPx(progress: Float): Int = Math.round(BLUR_MAX * progress.coerceIn(0f, 1f))

        /** The dim's colour at [progress]: as the control centre's, coming in with the pull. */
        internal fun dimColor(progress: Float): Int =
            ((DIM * progress.coerceIn(0f, 1f) * 255).toInt().coerceIn(0, 255)) shl 24

        /**
         * The hidden Transaction.setBackgroundBlurRadius(SurfaceControl, int), looked up once
         * as ShellLayer looks up setLayerStack; null where the firmware has none (logged once).
         */
        private val backgroundBlur: java.lang.reflect.Method? by lazy {
            runCatching {
                SurfaceControl.Transaction::class.java
                    .getMethod("setBackgroundBlurRadius", SurfaceControl::class.java, Int::class.javaPrimitiveType)
            }.onFailure { Log.w(TAG, "shell sheets: no setBackgroundBlurRadius on this firmware; the dim alone") }.getOrNull()
        }

        /** The call threw once: no more tries in this process. */
        @Volatile private var blurBroken = false
    }
}
