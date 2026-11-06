package dev.kb896.aiusage.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RecordingCanvas
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.widget.ScrollView

/**
 * A list in a sheet whose rows go from sharp, through a blur, to nothing at its top and bottom
 * edges, so they never run into the sheet's title or its buttons.
 *
 * A plain ScrollView cuts its rows off hard, right against the title and the buttons. Here the
 * rows are recorded into nodes the size of this view and the two nodes are masked: the sharp one
 * is gone in the bands, the blurred one is there only within them. A band is as deep as there is
 * list beyond it, up to [BAND_DP], so at rest at the top the first row is simply sharp.
 *
 * As TopBlurStrip, its one other user of the idea, it needs a hardware canvas; on any other the
 * list is drawn plainly.
 */
open class SoftEdgeScrollView(context: Context) : ScrollView(context) {
    private val density = resources.displayMetrics.density
    private val sharp = RenderNode("soft-edge-sharp")
    private val soft = RenderNode("soft-edge-blur").apply {
        val blur = BLUR_DP * density
        setRenderEffect(RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.DECAL))
    }
    private val mask = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }

    init {
        isVerticalFadingEdgeEnabled = false
        // Nothing to spring into at the edges: the bands are already where a row ends.
        overScrollMode = OVER_SCROLL_NEVER
    }

    override fun draw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0 || canvas !is RecordingCanvas) {
            super.draw(canvas)
            return
        }
        // The parent has already moved this canvas by the scroll; the nodes are at the view's
        // own 0,0, so that is undone here and the rows moved by it inside them instead.
        canvas.save()
        canvas.translate(scrollX.toFloat(), scrollY.toFloat())
        val band = BAND_DP * density
        val range = ((getChildAt(0)?.height ?: 0) - h).coerceAtLeast(0)
        val top = minOf(band, scrollY.toFloat()).coerceAtLeast(0f)
        val bottom = minOf(band, (range - scrollY).toFloat()).coerceAtLeast(0f)
        record(sharp, w, h)
        if (top <= 0f && bottom <= 0f) {
            canvas.drawRenderNode(sharp)
            canvas.restore()
            return
        }
        record(soft, w, h)
        val wf = w.toFloat()
        val hf = h.toFloat()
        val t = top / hf
        val b = 1f - bottom / hf
        val mid = (t + b) / 2
        // Sharp from the band's inner end on, and nothing at the edge.
        canvas.saveLayer(0f, 0f, wf, hf, null)
        canvas.drawRenderNode(sharp)
        mask.shader = LinearGradient(
            0f, 0f, 0f, hf,
            intArrayOf(CLEAR, WHOLE, WHOLE, CLEAR),
            floatArrayOf(t * HALF, t, b, 1f - (1f - b) * HALF),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, wf, hf, mask)
        canvas.restore()
        // The blurred rows: only in the bands, clear at the outer edge, gone by the inner end.
        canvas.saveLayer(0f, 0f, wf, hf, null)
        canvas.drawRenderNode(soft)
        mask.shader = LinearGradient(
            0f, 0f, 0f, hf,
            intArrayOf(CLEAR, WHOLE, CLEAR, CLEAR, WHOLE, CLEAR),
            floatArrayOf(0f, t * HALF, minOf(t, mid), maxOf(b, mid), 1f - (1f - b) * HALF, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, wf, hf, mask)
        canvas.restore()
        canvas.restore()
    }

    /** ScrollView.draw doesn't apply its own scroll (its parent does), so it is applied here. */
    private fun record(node: RenderNode, w: Int, h: Int) {
        node.setPosition(0, 0, w, h)
        val rc = node.beginRecording(w, h)
        try {
            rc.translate(-scrollX.toFloat(), -scrollY.toFloat())
            super.draw(rc)
        } finally {
            node.endRecording()
        }
    }

    private companion object {
        const val BAND_DP = 36f
        const val BLUR_DP = 7f
        /** How far into a band the blurred rows are at their fullest. */
        const val HALF = 0.5f
        const val WHOLE = 0xFF000000.toInt()
        const val CLEAR = 0x00000000
    }
}
