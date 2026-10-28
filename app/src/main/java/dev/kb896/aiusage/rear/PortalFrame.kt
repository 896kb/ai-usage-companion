package dev.kb896.aiusage.rear

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
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

    /** The colour, from the panel's config. Takes effect next frame. */
    fun style(color: Int) {
        this.color = color
    }

    val showing get() = thread?.isAlive == true

    @Synchronized
    fun show() {
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

    @Synchronized
    fun hide() {
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
            val layer = SurfaceControl.Builder().setName("portal-frame").setBufferSize(Panel.WIDTH, Panel.HEIGHT)
                .setFormat(PixelFormat.TRANSLUCENT).build()
            sc = layer
            SurfaceControl.Transaction().use { t ->
                // Hidden; shell may call it, and the spike proved it on OS4.0.5.0.
                SurfaceControl.Transaction::class.java
                    .getMethod("setLayerStack", SurfaceControl::class.java, Int::class.javaPrimitiveType)
                    .invoke(t, layer, layerStack)
                // Without it the layer occludes the rear and every touch there is dropped:
                // see the class comment. Not trusted, not shown — the transaction is never
                // applied, and the layer goes below.
                val why = trust(t, layer)
                if (why != null) {
                    if (!untrustedLogged) { untrustedLogged = true; Log.w(TAG, "portal frame: not shown, no trusted overlay: $why") }
                    failedAt = System.nanoTime()
                    runCatching { layer.release() }
                    return
                }
                t.setLayer(layer, Int.MAX_VALUE).setVisibility(layer, true).apply()
            }
            surface = Surface(layer)
        } catch (e: Throwable) {
            Log.w(TAG, "portal frame: no layer: ${e.message}")
            failedAt = System.nanoTime()
            runCatching { sc?.release() }
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
            runCatching { SurfaceControl.Transaction().use { it.reparent(sc, null).apply() } }
            runCatching { surface.release() }
            runCatching { sc.release() }
        }
    }

    /** Marks [layer] a trusted overlay in [t]; null when done, else why it could not be. */
    private fun trust(t: SurfaceControl.Transaction, layer: SurfaceControl): String? {
        val call = trustedOverlayCall(SurfaceControl.Transaction::class.java, SurfaceControl::class.java)
            ?: return "no setTrustedOverlay on this firmware"
        return try {
            call.first.invoke(t, layer, call.second)
            null
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            "${cause.javaClass.simpleName}: ${cause.message}"
        }
    }

    /** One run's paints and cached outlines, so an overlapping run shares nothing mutable. */
    private class Renderer {
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
        /** Edge inset and the display's corner radius (dumpsys display: RoundedCorner radius=97). */
        private const val INSET = 10f
        private const val CORNER = 97f

        /** gui::TrustedOverlay::ENABLED. UNSET is 0 and DISABLED 1, and 1 still has touches dropped. */
        internal const val TRUSTED_OVERLAY_ENABLED = 2

        /**
         * The hidden setTrustedOverlay on [txClass] and the value that turns it on: the
         * (layer, int) overload with [TRUSTED_OVERLAY_ENABLED] where there is one, else the
         * older (layer, boolean) with true; null for neither. [layerClass] is SurfaceControl,
         * a parameter so a test can hand it stand-ins.
         */
        internal fun trustedOverlayCall(txClass: Class<*>, layerClass: Class<*>): Pair<java.lang.reflect.Method, Any>? {
            fun find(arg: Class<*>) = runCatching { txClass.getMethod("setTrustedOverlay", layerClass, arg) }.getOrNull()
            find(Int::class.javaPrimitiveType!!)?.let { return it to TRUSTED_OVERLAY_ENABLED }
            find(Boolean::class.javaPrimitiveType!!)?.let { return it to true }
            return null
        }

        /** The display edge only; the frame does not run round the lenses. */
        fun shapesFor(): List<ElectricBorder.Shape> = listOf(
            ElectricBorder.Shape.Rounded(INSET, INSET, Panel.WIDTH - INSET, Panel.HEIGHT - INSET, CORNER - INSET),
        )
    }
}
