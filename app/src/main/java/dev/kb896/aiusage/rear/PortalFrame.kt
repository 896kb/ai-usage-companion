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
import dev.kb896.aiusage.Panel

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
 */
class PortalFrame(private val layerStack: Int) {

    @Volatile private var color = 0xFF28FF85.toInt()
    private val shapes: List<ElectricBorder.Shape> = shapesFor()
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

    /** The colour, from the panel's config. Takes effect next frame. */
    fun style(color: Int) {
        this.color = color
    }

    val showing get() = thread?.isAlive == true

    /** Up, if the Portal border switch allows it: wanted either way, for when it does. */
    @Synchronized
    fun show() {
        if (gate.want(true)) start()
    }

    @Synchronized
    fun hide() {
        gate.want(false)
        stop()
    }

    /**
     * The front's Portal border switch (PanelConfig.portalBorder): off, the frame goes at
     * once and stays down; back on while a guest is on top, it is up again at once.
     */
    @Synchronized
    fun allow(on: Boolean) {
        if (gate.allowed == on) return
        if (gate.allow(on)) start() else stop()
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

    private fun stop() {
        val t = thread ?: return
        t.interrupt()
        // The render thread never takes this lock, so waiting for it here is safe.
        runCatching { t.join(300) }
        thread = null
    }

    private fun run(myGen: Int) {
        var sc: SurfaceControl? = null
        var surface: Surface? = null
        try {
            // Without trust the layer occludes the rear and every touch there is dropped:
            // see the class comment. Not trusted, not shown — ShellLayer never applies it.
            // At [ALPHA] as a whole: one number on the layer, where paint alphas would be four.
            val layer = ShellLayer.create("portal-frame", layerStack, ShellLayer.Z_FRAME, trusted = true, alpha = ALPHA)
            sc = layer
            surface = Surface(layer)
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
        val r = Renderer()
        val start = System.nanoTime()
        var slow = 0
        var fast = 0
        var throttled = false
        // As the spike: if the hardware canvas is refused, fall back to the software one.
        var hardware = true
        try {
            while (!Thread.currentThread().isInterrupted && gen == myGen) {
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
            runCatching { surface.release() }
            ShellLayer.remove(sc)
        }
    }

    /**
     * One run's paints and cached outlines, so an overlapping run shares nothing mutable.
     * The front's portal preview (ui.PortalSheetPreview) draws the border with one of its
     * own, so the two cannot drift; the caller sets the frame's [ALPHA].
     */
    class Renderer {
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val halo = BlurMaskFilter(32f * D, BlurMaskFilter.Blur.NORMAL)
        private val soft = BlurMaskFilter(4f * D, BlurMaskFilter.Blur.NORMAL)
        private val fine = BlurMaskFilter(1f * D, BlurMaskFilter.Blur.NORMAL)
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
        // rear px times D. The clean outline is cached; the bolt is rebuilt every frame.
        private fun one(canvas: Canvas, shape: ElectricBorder.Shape, clean: Path, color: Int, time: Float) {
            glow.color = color
            glow.strokeWidth = 14f * D; glow.alpha = 77; glow.maskFilter = halo
            canvas.drawPath(clean, glow)
            glow.strokeWidth = 2f * D; glow.alpha = 255; glow.maskFilter = soft
            canvas.drawPath(clean, glow)
            glow.alpha = 153; glow.maskFilter = fine
            canvas.drawPath(clean, glow)
            line.color = color; line.strokeWidth = 1f * D
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
        /** The rear's density over CSS's 160: one of the original's px. */
        internal const val D = 450f / 160f
        private const val FRAME_MS = 16L
        private const val BUDGET_MS = 8L
        private const val RETRY_NS = 30_000_000_000L
        /**
         * The whole frame's opacity: at full it was louder than the app it frames ("the
         * margin ray should be less visible"), so it is drawn at half.
         */
        internal const val ALPHA = 0.5f
        /** Edge inset and the display's corner radius (dumpsys display: RoundedCorner radius=97). */
        private const val INSET = 10f
        private const val CORNER = 97f

        /**
         * The usable screen's edge only, right of the lenses (Panel.CUTOUT_RIGHT): the frame
         * neither runs round the lenses nor along the strip beside them. Square on the left,
         * where the screen meets the strip; round on the right, as the glass is.
         */
        fun shapesFor(): List<ElectricBorder.Shape> = listOf(
            ElectricBorder.Shape.Rounded(
                Panel.CUTOUT_RIGHT + INSET, INSET, Panel.WIDTH - INSET, Panel.HEIGHT - INSET, CORNER - INSET,
                leftR = 0f,
            ),
        )
    }
}
