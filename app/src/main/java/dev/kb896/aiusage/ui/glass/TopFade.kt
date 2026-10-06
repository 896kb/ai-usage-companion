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
import android.util.AttributeSet
import android.view.View
import android.widget.ScrollView

/**
 * The settings' scroller. Its own fading edge is off: a scroller fades from below its top
 * padding, which here is the pinned preview's height, so it dimmed the rows BELOW the
 * preview and left those behind it untouched. The fading is [TopBlurStrip]'s alone.
 */
class TopFadeScrollView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {
    init {
        isVerticalFadingEdgeEnabled = false
    }

    companion object {
        /** How far down from the top edge the settings fade and blur. */
        const val FADE_DP = 56f
    }
}

/**
 * A band over the top of the scroller where the rows passing under it are blurred: the
 * silk and the scroller's own content, blurred, faded from whole at the top to nothing at
 * the bottom of the band — so a row softens as it rises and is gone at the edge. Drawn from
 * the scroller's display lists, as the bottom bar's glass is, and redrawn with the silk.
 */
class TopBlurStrip @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs), Glassy {
    /** The scroller under the band, and the root whose background is the silk. */
    var scroll: ScrollView? = null
    var host: View? = null

    private val density = resources.displayMetrics.density
    private val node = RenderNode("top-blur")
    private val mask = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private var maskFor = -1
    /** The rows' own fade inside the band: none of them at the top, all of them at the end. */
    private val rowsMask = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }

    /**
     * How far down the band stays whole — the pinned header and preview, which the rows
     * pass behind — before it fades out over the rest of its height.
     */
    var solid = 0
        set(value) { field = value; maskFor = -1; invalidate() }
    private val here = IntArray(2)
    private val there = IntArray(2)

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        val s = scroll ?: return
        val root = host ?: return
        val w = width; val h = height
        if (w <= 0 || h <= 0 || canvas !is RecordingCanvas) return
        if (maskFor != h) {
            maskFor = h
            val stop = (solid.toFloat() / h).coerceIn(0f, 0.99f)
            mask.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
                intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt(), 0x00000000),
                floatArrayOf(0f, stop, 1f), Shader.TileMode.CLAMP)
            // NOTHING OF THE ROWS AT THE TOP: they are gone from the top of the screen down
            // to just above the preview's bottom, and only come in — blurred — from there.
            // Without this the blurred rows showed above the preview, beside the header.
            val from = ((solid - ROWS_IN_DP * density) / h).coerceIn(0f, 0.98f)
            rowsMask.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
                intArrayOf(0x00000000, 0x00000000, 0xFF000000.toInt()),
                floatArrayOf(0f, from, 1f), Shader.TileMode.CLAMP)
        }
        getLocationInWindow(here)
        canvas.saveLayer(0f, 0f, w.toFloat(), h.toFloat(), null)
        // The silk, where this band is.
        root.getLocationInWindow(there)
        canvas.save()
        canvas.translate((there[0] - here[0]).toFloat(), (there[1] - here[1]).toFloat())
        root.background?.draw(canvas)
        canvas.restore()
        // The rows under the band, blurred. ScrollView.draw does not apply its own scroll
        // (its parent does, when it draws it), so the offset is taken here.
        s.getLocationInWindow(there)
        node.setPosition(0, 0, w, h)
        val blur = BLUR_DP * density
        node.setRenderEffect(RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP))
        val rc = node.beginRecording(w, h)
        try {
            rc.translate((there[0] - here[0] - s.scrollX).toFloat(), (there[1] - here[1] - s.scrollY).toFloat())
            s.draw(rc)
        } finally {
            node.endRecording()
        }
        canvas.saveLayer(0f, 0f, w.toFloat(), h.toFloat(), null)
        canvas.drawRenderNode(node)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), rowsMask)
        canvas.restore()
        // Whole at the top, gone at the bottom: the sharp rows show through below.
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), mask)
        canvas.restore()
    }

    private companion object {
        const val BLUR_DP = 14f
        /** How far above the band's solid end the blurred rows start to show. */
        const val ROWS_IN_DP = 24f
    }
}
