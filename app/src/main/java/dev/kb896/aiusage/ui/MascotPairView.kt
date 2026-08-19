package dev.kb896.aiusage.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.rear.ColorTween
import dev.kb896.aiusage.rear.MascotFrames
import dev.kb896.aiusage.rear.MascotRenderer
import dev.kb896.aiusage.rear.RearDialView

/**
 * The flag and the lifting mascots, side by side, in the colours currently chosen.
 *
 * These two specifically. They are the clips that carry ACCENT-coloured hardware — the
 * flag's pole, the dumbbells and the headband — so between them they show both colours a
 * user is picking. The walk has no kit at all, and the dead pose is the walk rig on its
 * side, so neither would show what a kit colour does.
 *
 * Animating, and repainted as the colours change, because a mascot colour is chosen
 * against moving artwork: the headband is on screen for part of a cycle and the dumbbells
 * pass in front of the body, and a still frame of either can look fine while the
 * animation does not.
 *
 * Draws through [MascotRenderer], the same code the panel uses, so what is previewed here
 * cannot differ from what is drawn there.
 */
class MascotPairView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val body = Paint()
    private val eye = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        color = Panel.Palette.BG
    }

    // Eased, exactly as the panel eases them — the same ColorTween, so the preview
    // cannot show a transition the panel would not. Without it a colour lands in one
    // frame here and glides over there, which makes the preview feel like a different
    // thing rather than the same thing smaller.
    private val bodyTween = ColorTween()
    private val accentTween = ColorTween()

    var bodyColor: Int = MascotRenderer.BODY_GREEN
        set(value) { field = value; invalidate() }

    var accentColor: Int = MascotRenderer.ACCENT_ORANGE
        set(value) { field = value; invalidate() }

    private val clips by lazy {
        listOf("flag", "muscle").mapNotNull { name ->
            MascotFrames.CLIPS.firstOrNull { it.name == name }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, FRAME_MS)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(tick, FRAME_MS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    /**
     * Sized to the mascot as it appears in the preview above, not to the width available.
     *
     * The preview scales the whole 904-unit panel to this view's width, so a mascot ends
     * up at MASCOT_HEIGHT * scale on screen. Matching that means the figure a colour is
     * being chosen for is the size it will actually be — a large version can look right
     * while the real one is a smudge.
     */
    private fun figureHeight(): Float {
        val panelScale = width / dev.kb896.aiusage.Panel.WIDTH.toFloat()
        return RearDialView.MASCOT_HEIGHT * panelScale
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        val panelScale = width / dev.kb896.aiusage.Panel.WIDTH.toFloat()
        // Headroom above, because the flag and the confetti reach well outside their
        // clip's own box — the flag was being clipped at the top by exactly this.
        val h = RearDialView.MASCOT_HEIGHT * panelScale * (1f + HEADROOM)
        setMeasuredDimension(width, h.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (clips.isEmpty()) return
        val now = System.currentTimeMillis()
        val cell = width.toFloat() / clips.size

        // One nominal height for both, reduced only as far as the wider of the two
        // needs. Clamping each to its own cell would let one shrink and not the other,
        // which is the opposite of what these previews are for — the flag and the
        // dumbbells are shown side by side precisely so the colours can be compared on
        // the same figure at the same size.
        val fit = clips.minOf { (cell * CELL_FILL) / (it.aspect * it.scale) }
        val base = minOf(figureHeight(), fit)
        for ((i, clip) in clips.withIndex()) {
            // Each clip's own scale, exactly as the panel applies it — the flag's box is
            // dominated by the cloth, so at a shared height its character comes out
            // smaller than the others'.
            val h = base * clip.scale
            val w = h * clip.aspect
            MascotRenderer.draw(
                canvas, clip, now,
                x = i * cell + (cell - w) / 2f,
                // Bottom-aligned, leaving the headroom above where the flag needs it.
                top = height - h - dp(4f),
                h = h,
                body = body,
                eye = eye,
                accent = accentTween.advance(accentColor, now),
                bodyColor = bodyTween.advance(bodyColor, now),
            )
        }
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private companion object {
        /** Fast enough for the quickest clip (83ms) without pinning a redraw loop. */
        const val FRAME_MS = 40L

        /** Extra height above the figures, as a fraction of one. The flag's pole and the
         *  confetti reach outside their clip's box and were being cut off. */
        const val HEADROOM = 0.5f

        /** Fraction of its cell a figure may occupy, leaving a gutter between them. */
        const val CELL_FILL = 0.78f
    }
}
