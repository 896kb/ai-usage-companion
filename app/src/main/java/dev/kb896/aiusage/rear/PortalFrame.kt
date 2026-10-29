package dev.kb896.aiusage.rear

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl

/**
 * The portal frame: the electric border round the rear display's edge only (not the lenses),
 * over a guest app — drawn where no app window may go.
 *
 * NOT A WINDOW. HyperOS refuses windows on the rear to apps outside its list, and a
 * window over a guest would take or block its touches. This runs in the shell service,
 * which holds ACCESS_SURFACE_FLINGER, and makes a SurfaceFlinger layer of its own on the
 * rear display's layer stack, at the top. Measured with tools/spikes/PortalFrameSpike: it
 * composites over everything on the rear, and goes when released.
 *
 * TRUSTED, OR NOT AT ALL. A bare layer has no input window, and that was taken to mean
 * touches pass straight through to the guest. They do not: since Android 12 the input
 * dispatcher checks what covers a touched window, and a layer owned by another uid (this
 * one is shell's, 2000) occludes it. On the phone (OS4.0.5.0, Android 17) every touch on
 * the rear — the guest's and the panel's own — was logged "Untrusted touch due to occlusion
 * by /2000/[BBQ] portal-frame" and dropped while the frame was up. The fix, measured with a
 * spike the same day, is the hidden Transaction.setTrustedOverlay(layer, ENABLED = 2):
 * a trusted overlay never occludes (1, DISABLED, still drops). Shell may set it because
 * it holds ACCESS_SURFACE_FLINGER. Older firmware has a boolean overload instead. Where
 * neither exists or the call is refused there is no frame: one that blocks every touch is
 * far worse than none.
 *
 * Drawn through Surface.lockHardwareCanvas on a thread of its own at [FRAME_MS], halving
 * its pace if it runs over [BUDGET_MS] (see ElectricBorder for where the budget went).
 *
 * FADED, NEVER CUT. [show] and [hide] turn a FrameFade; the render thread sets the layer's
 * alpha to [ALPHA] times its level every frame it changes, so the bolt keeps moving while it
 * fades. A hide lets the thread run on until the fade is done, and then it takes its layer
 * away and ends; a show meanwhile turns the same fade back from where it is. The thread
 * decides to end under this instance's lock, the same lock show takes — so a show either
 * turns a fade the thread will see, or finds the thread gone and starts another.
 *
 * UNDER THE SHEET IT GOES. The portal sheet hides it as far as the sheet is open (FrameVeil,
 * fed by ShellSheet's render thread with each frame it draws): the layer's alpha is ALPHA
 * times the fade times what the sheet leaves. Nothing new wakes for it: the render thread
 * draws every frame while the frame is up anyway, and reads the latest value.
 */
class PortalFrame(private val layerStack: Int, private val rear: RearGeometry) {

    @Volatile private var color = 0xFF28FF85.toInt()
    private val shapes: List<ElectricBorder.Shape> = shapesFor(rear)
    /** Volatile: [showing] reads it from threads that do not hold the lock. */
    @Volatile private var thread: Thread? = null
    /** Bumped by every show(); a render thread of an older generation stops. */
    @Volatile private var gen = 0
    @Volatile private var failedAt = 0L
    private var refusalLogged = false
    /** Once per process: the firmware gives no way to make the layer a trusted overlay. */
    @Volatile private var untrustedLogged = false
    /** Wanted (a guest on top) and allowed (the front's Portal border switch). Under this instance's lock. */
    private val gate = FrameGate()
    /** How much of it shows. Under this instance's lock. */
    private val fade = FrameFade()
    /** How much of it the portal sheet hides. Its latch under this instance's lock; [sheetOpen] any thread. */
    private val veil = FrameVeil()
    /** The running thread's layer, for a capture to leave out (GuestCover); null while there is none. */
    @Volatile private var layer: SurfaceControl? = null

    /**
     * The frame's layer as it is now, or null: for a picture of the rear without the frame
     * in it. It may be released at any moment after, so a user must survive that.
     */
    val currentLayer: SurfaceControl? get() = layer

    /** The colour, from the panel's config. Takes effect next frame. */
    fun style(color: Int) {
        this.color = color
    }

    val showing get() = thread?.isAlive == true

    /** Fades in, if the Portal border switch allows it: wanted either way, for when it does. */
    @Synchronized
    fun show() {
        if (gate.want(true)) up()
    }

    /** Fades out, and is gone when the fade is. */
    @Synchronized
    fun hide() {
        gate.want(false)
        veil.hidden()
        fade.hide(now())
    }

    /** How far the portal sheet is open, 0 to 1 (PortalSheet.progress). Any thread; no lock, no wakeup. */
    fun sheetOpen(progress: Float) = veil.sheet(progress)

    /** How far the control centre over a guest is open, 0 to 1 (FrameVeil.controlCenter). Any thread; no lock. */
    fun controlCenterOpen(progress: Float) = veil.controlCenter(progress)

    /** A sheet button's choice is coming: the frame stays as hidden as the sheet has it while the sheet shuts (FrameVeil.hold). */
    @Synchronized
    fun holdVeil() = veil.hold()

    /** The choice is done: if it left the frame wanted, the sheet alone hides it again. */
    @Synchronized
    fun releaseVeil() = veil.release(stillShown = fade.shown)

    /**
     * The front's Portal border switch (PanelConfig.portalBorder): off, the frame fades out
     * and stays down; back on while a guest is on top, it fades in again at once.
     */
    @Synchronized
    fun allow(on: Boolean) {
        if (gate.allowed == on) return
        if (gate.allow(on)) up() else { veil.hidden(); fade.hide(now()) }
    }

    /** Fading in, and a thread to draw it if none is running. Under the lock. */
    private fun up() {
        if (!fade.shown) veil.shown()
        fade.show(now())
        start()
    }

    private fun start() {
        if (thread?.isAlive == true) return
        val since = System.nanoTime() - failedAt
        if (failedAt != 0L && since < RETRY_NS) {
            if (!refusalLogged) { refusalLogged = true; Log.w(TAG, "portal frame: failed lately, not retrying for 30 s") }
            return
        }
        refusalLogged = false
        val myGen = ++gen
        thread = Thread({ run(myGen) }, "portal-frame").apply { isDaemon = true; start() }
    }

    /**
     * This frame's layer alpha, or null when [myGen]'s thread should stop: the thread is
     * forgotten once the fade has gone all the way out (or another thread has taken over).
     * Under the lock, so a show cannot slip between the decision and the thread's end.
     */
    @Synchronized
    private fun alphaNow(myGen: Int): Float? {
        if (gen != myGen) return null
        val now = now()
        if (fade.gone(now)) { thread = null; return null }
        return FrameVeil.alpha(ALPHA, fade.level(now), veil.veil())
    }

    private fun run(myGen: Int) {
        var sc: SurfaceControl? = null
        var surface: Surface? = null
        try {
            // Without trust the layer occludes the rear and every touch there is dropped:
            // see the class comment. Not trusted, not shown — ShellLayer never applies it.
            // At [ALPHA] as a whole: one number on the layer, where paint alphas would be four.
            // Made out of sight: the first frame sets its alpha, faded in from nothing.
            // The whole rear, in its own pixels: the frame is the display's edge, not the panel's.
            val made = ShellLayer.create(
                "portal-frame", layerStack, ShellLayer.Z_FRAME, rear.widthPx, rear.heightPx,
                trusted = true, visible = false, alpha = 0f,
            )
            sc = made
            surface = Surface(made)
        } catch (e: ShellLayer.Untrusted) {
            if (!untrustedLogged) { untrustedLogged = true; Log.w(TAG, "portal frame: not shown, no trusted overlay: ${e.message}") }
            failedAt = System.nanoTime()
            return
        } catch (e: Throwable) {
            Log.w(TAG, "portal frame: no layer: ${e.message}")
            failedAt = System.nanoTime()
            ShellLayer.remove(sc)
            return
        }
        val r = try { Renderer(cssPx(rear)) } catch (e: Throwable) {
            Log.w(TAG, "portal frame: no renderer: ${e.message}")
            failedAt = System.nanoTime()
            runCatching { surface.release() }
            ShellLayer.remove(sc)
            synchronized(this) { if (gen == myGen && thread === Thread.currentThread()) thread = null }
            return
        }
        val start = System.nanoTime()
        var slow = 0
        var fast = 0
        var throttled = false
        // As the spike: if the hardware canvas is refused, fall back to the software one.
        var hardware = true
        var alpha = -1f
        layer = sc
        try {
            while (!Thread.currentThread().isInterrupted) {
                val a = alphaNow(myGen) ?: break
                val began = System.nanoTime()
                val canvas = if (hardware) {
                    try { surface.lockHardwareCanvas() } catch (e: Throwable) {
                        Log.w(TAG, "portal frame: hardware canvas refused (${e.message}); software")
                        hardware = false; continue
                    }
                } else surface.lockCanvas(null)
                try {
                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                    r.draw(canvas, shapes, color, (began - start) / 1e9f)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                // The whole layer, with the frame it goes with, and only when it moves.
                if (a != alpha) {
                    // Hidden, not only clear, while it shows nothing: SurfaceFlinger then has
                    // nothing of it to compose.
                    SurfaceControl.Transaction().use { it.setAlpha(sc, a).setVisibility(sc, a > 0f).apply() }
                    alpha = a
                }
                val took = (System.nanoTime() - began) / 1_000_000
                // Over budget three frames running: halve the pace; back to full only
                // after 30 frames within budget, so it does not flap between the two.
                if (took > BUDGET_MS) { slow++; fast = 0; if (slow >= 3) throttled = true }
                else { slow = 0; fast++; if (fast >= 30) throttled = false }
                val pace = if (throttled) FRAME_MS * 2 else FRAME_MS
                Thread.sleep(maxOf(pace - took, took / 2, 1L))
            }
        } catch (_: InterruptedException) {
        } catch (e: Throwable) {
            Log.w(TAG, "portal frame stopped: ${e.message}")
            // A loop that died is a failure like a refused layer: without this, every
            // keep-alive tick's show() would start it again, only for it to die again.
            failedAt = System.nanoTime()
        } finally {
            if (layer === sc) layer = null
            runCatching { surface.release() }
            ShellLayer.remove(sc)
            // Died rather than faded out: forgotten, so the next show starts one.
            synchronized(this) { if (gen == myGen && thread === Thread.currentThread()) thread = null }
        }
    }

    /**
     * One run's paints and cached outlines, so an overlapping run shares nothing mutable.
     * The front's portal preview (ui.PortalSheetPreview) draws the border with one of its
     * own, so the two cannot drift; the caller sets the frame's [ALPHA]. [d] is one of the
     * original's CSS px in the canvas's units ([cssPx] on the rear; the 17 Pro's [D] by default).
     */
    class Renderer(private val d: Float = D) {
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val halo = BlurMaskFilter(32f * d, BlurMaskFilter.Blur.NORMAL)
        private val soft = BlurMaskFilter(4f * d, BlurMaskFilter.Blur.NORMAL)
        private val fine = BlurMaskFilter(1f * d, BlurMaskFilter.Blur.NORMAL)
        private var cachedFor: List<ElectricBorder.Shape>? = null
        private var cleanPaths: List<Path> = emptyList()

        fun draw(canvas: Canvas, shapes: List<ElectricBorder.Shape>, color: Int, time: Float) {
            if (cachedFor !== shapes) {
                cleanPaths = shapes.map { path(ElectricBorder.outline(it)) }
                cachedFor = shapes
            }
            shapes.forEachIndexed { i, shape -> one(canvas, shape, cleanPaths[i], color, time + i * 3.7f) }
        }

        // The original's layers: a 32px-blurred halo at 30%, a 2px stroke blurred 4px, a 2px
        // stroke at 60% blurred 1px, and over them the 1px noise-displaced line. CSS px are
        // rear px times d. The clean outline is cached; the bolt is rebuilt every frame.
        private fun one(canvas: Canvas, shape: ElectricBorder.Shape, clean: Path, color: Int, time: Float) {
            glow.color = color
            glow.strokeWidth = 14f * d; glow.alpha = 77; glow.maskFilter = halo
            canvas.drawPath(clean, glow)
            glow.strokeWidth = 2f * d; glow.alpha = 255; glow.maskFilter = soft
            canvas.drawPath(clean, glow)
            glow.alpha = 153; glow.maskFilter = fine
            canvas.drawPath(clean, glow)
            line.color = color; line.strokeWidth = 1f * d
            canvas.drawPath(path(ElectricBorder.bolt(shape, time)), line)
        }

        private fun path(pts: FloatArray) = Path().apply {
            moveTo(pts[0], pts[1])
            for (i in 2 until pts.size step 2) lineTo(pts[i], pts[i + 1])
            close()
        }
    }

    companion object {
        private const val TAG = "AiUsageRear"
        /** The 17 Pro rear's density over CSS's 160: one of the original's px there. */
        internal const val D = 450f / 160f
        private const val FRAME_MS = 16L
        private const val BUDGET_MS = 8L
        private const val RETRY_NS = 30_000_000_000L

        private fun now() = android.os.SystemClock.uptimeMillis()

        /** One of the original's px on [rear]: its own density over CSS's 160. */
        fun cssPx(rear: RearGeometry): Float = rear.dpi / 160f

        /**
         * The whole frame's opacity: at full it was louder than the app it frames ("the
         * margin ray should be less visible"), so it is drawn at half.
         */
        internal const val ALPHA = 0.5f
        /**
         * The usable screen's edge only, right of the lenses ([RearGeometry.cutoutPx]): the frame
         * neither runs round the lenses nor along the strip beside them. Square on the left,
         * where the screen meets the strip; round on the right, as the glass is.
         *
         * The line's middle runs ON the app's edge, not inside it ("the center of the electric
         * lines should fall exactly on the edge of the apps"): half of it, and of its glow, is
         * off the glass at the display's edges, and over the lens strip on the left.
         *
         * In [rear]'s own pixels, rounded by its own corner (dumpsys display: RoundedCorner
         * radius=97 on the 17 Pro): on a wider display the edge is that display's, not the
         * panel's 904x572 drawn where it would be. The front's preview passes the rear in panel
         * units (RearGeometry.inPanelUnits).
         */
        fun shapesFor(rear: RearGeometry): List<ElectricBorder.Shape> = listOf(
            ElectricBorder.Shape.Rounded(
                rear.cutoutPx.toFloat(), 0f, rear.widthPx.toFloat(), rear.heightPx.toFloat(), rear.cornerPx.toFloat(),
                leftR = 0f,
            ),
        )
    }
}
