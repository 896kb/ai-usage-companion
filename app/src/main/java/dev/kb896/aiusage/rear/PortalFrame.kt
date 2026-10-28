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
import dev.kb896.aiusage.data.RingLayout

/**
 * The portal frame: the electric border round the rear display's edge and both lenses,
 * over a guest app — drawn where no app window may go.
 *
 * NOT A WINDOW. HyperOS refuses windows on the rear to apps outside its list, and a
 * window over a guest would take or block its touches. This runs in the shell service,
 * which holds ACCESS_SURFACE_FLINGER, and makes a SurfaceFlinger layer of its own on the
 * rear display's layer stack, at the top. A bare layer has no input window: touches go to
 * the guest as if it were not there. Measured with tools/spikes/PortalFrameSpike: it
 * composites over everything on the rear, and goes when released.
 *
 * Drawn through Surface.lockHardwareCanvas on a thread of its own at [FRAME_MS], halving
 * its pace if it runs over [BUDGET_MS] (see ElectricBorder for where the budget went).
 */
class PortalFrame(private val layerStack: Int) {

    @Volatile private var color = 0xFF28FF85.toInt()
    @Volatile private var shapes: List<ElectricBorder.Shape> = shapesFor(RingLayout.forDevice())
    @Volatile private var thread: Thread? = null

    /** The clean outlines for one [shapes] instance; rebuilt only when style() swaps the list. */
    private class Clean(val shapes: List<ElectricBorder.Shape>, val paths: List<Path>)
    private var clean: Clean? = null

    /** The colour and the lens geometry, from the panel's config. Takes effect next frame. */
    fun style(color: Int, rings: RingLayout) {
        this.color = color
        shapes = shapesFor(rings)
    }

    val showing get() = thread?.isAlive == true

    fun show() {
        if (showing) return
        thread = Thread({ run() }, "portal-frame").apply { isDaemon = true; start() }
    }

    fun hide() {
        thread?.interrupt()
        thread = null
    }

    private fun run() {
        val sc = try {
            SurfaceControl.Builder().setName("portal-frame").setBufferSize(Panel.WIDTH, Panel.HEIGHT)
                .setFormat(PixelFormat.TRANSLUCENT).build()
        } catch (e: Exception) { Log.w(TAG, "portal frame: no layer: ${e.message}"); return }
        val t = SurfaceControl.Transaction()
        try {
            // Hidden; shell may call it, and the spike proved it on OS4.0.5.0.
            SurfaceControl.Transaction::class.java
                .getMethod("setLayerStack", SurfaceControl::class.java, Int::class.javaPrimitiveType)
                .invoke(t, sc, layerStack)
            t.setLayer(sc, Int.MAX_VALUE).setVisibility(sc, true).apply()
        } catch (e: Exception) {
            Log.w(TAG, "portal frame: could not place the layer: ${e.message}")
            sc.release(); return
        }
        val surface = Surface(sc)
        val start = System.nanoTime()
        var slow = 0
        // As the spike: if the hardware canvas is refused, fall back to the software one.
        var hardware = true
        try {
            while (!Thread.currentThread().isInterrupted) {
                val began = System.nanoTime()
                val canvas = if (hardware) {
                    try { surface.lockHardwareCanvas() } catch (e: Throwable) {
                        Log.w(TAG, "portal frame: hardware canvas refused (${e.message}); software")
                        hardware = false; continue
                    }
                } else surface.lockCanvas(null)
                try {
                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                    val time = (began - start) / 1e9f
                    val s = shapes
                    val c = clean?.takeIf { it.shapes === s } ?: Clean(s, s.map { path(ElectricBorder.outline(it)) }).also { clean = it }
                    s.forEachIndexed { i, shape -> draw(canvas, shape, c.paths[i], time + i * 3.7f) }
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                val took = (System.nanoTime() - began) / 1_000_000
                // Over budget three frames running: halve the pace rather than stutter.
                slow = if (took > BUDGET_MS) slow + 1 else 0
                val pace = if (slow >= 3) FRAME_MS * 2 else FRAME_MS
                Thread.sleep((pace - took).coerceAtLeast(1))
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.w(TAG, "portal frame stopped: ${e.message}")
        } finally {
            runCatching { SurfaceControl.Transaction().reparent(sc, null).apply() }
            surface.release()
            sc.release()
        }
    }

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val halo = BlurMaskFilter(32f * D, BlurMaskFilter.Blur.NORMAL)
    private val soft = BlurMaskFilter(4f * D, BlurMaskFilter.Blur.NORMAL)
    private val fine = BlurMaskFilter(1f * D, BlurMaskFilter.Blur.NORMAL)

    // The original's layers: a 32px-blurred halo at 30%, a 2px stroke blurred 4px, a 2px
    // stroke at 60% blurred 1px, and over them the 1px noise-displaced line. CSS px are
    // rear px times D. The clean outline is cached; the bolt is rebuilt every frame.
    private fun draw(canvas: Canvas, shape: ElectricBorder.Shape, clean: Path, time: Float) {
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

    companion object {
        private const val TAG = "AiUsageRear"
        /** The rear's density over CSS's 160: one of the original's px. */
        private const val D = 450f / 160f
        private const val FRAME_MS = 16L
        private const val BUDGET_MS = 8L
        /** Edge inset and the display's corner radius (dumpsys display: RoundedCorner radius=97). */
        private const val INSET = 10f
        private const val CORNER = 97f

        /** The edge, and a circle just outside each ring, concentric with its lens. */
        fun shapesFor(rings: RingLayout): List<ElectricBorder.Shape> {
            val r = rings.radius + rings.stroke / 2f + 6f
            return listOf(
                ElectricBorder.Shape.Rounded(INSET, INSET, Panel.WIDTH - INSET, Panel.HEIGHT - INSET, CORNER - INSET),
                ElectricBorder.Shape.Circle(rings.cx.toFloat(), rings.cyFirst.toFloat(), r),
                ElectricBorder.Shape.Circle(rings.cx.toFloat(), rings.cySecond.toFloat(), r),
            )
        }
    }
}
