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
 * The settings' scroller, its content fading out over its top edge — and only there: the
 * bottom runs on under the tab bar as it is. With [TopBlurStrip] over that edge, rows blur
 * and fade away under the preview instead of being cut off in a straight line.
 */
class TopFadeScrollView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {
    init {
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength((FADE_DP * resources.displayMetrics.density).toInt())
    }

    override fun getBottomFadingEdgeStrength(): Float = 0f

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
            mask.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
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
        canvas.drawRenderNode(node)
        // Whole at the top, gone at the bottom: the sharp rows show through below.
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), mask)
        canvas.restore()
    }

    private companion object {
        const val BLUR_DP = 14f
    }
}
