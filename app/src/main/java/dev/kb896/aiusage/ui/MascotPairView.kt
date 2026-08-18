package dev.kb896.aiusage.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.rear.MascotFrames
import dev.kb896.aiusage.rear.MascotRenderer

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

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(width, (width * HEIGHT_RATIO).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (clips.isEmpty()) return
        val now = System.currentTimeMillis()
        val cell = width.toFloat() / clips.size

        for ((i, clip) in clips.withIndex()) {
            // Bounded by the CELL as well as by the height: a clip's width is its height
            // times its aspect, and the flag's box is wide, so sizing on height alone
            // would let the two overlap.
            val h = minOf(height * FILL, (cell * CELL_FILL) / clip.aspect)
            val w = h * clip.aspect
            MascotRenderer.draw(
                canvas, clip, now,
                x = i * cell + (cell - w) / 2f,
                top = (height - h) / 2f,
                h = h,
                body = body,
                eye = eye,
                accent = accentColor,
                bodyColor = bodyColor,
            )
        }
    }

    private companion object {
        /** Fast enough for the quickest clip (83ms) without pinning a redraw loop. */
        const val FRAME_MS = 40L
        const val HEIGHT_RATIO = 0.34f
        const val FILL = 0.86f

        /** Fraction of its cell a figure may occupy, leaving a gutter between them. */
        const val CELL_FILL = 0.78f
    }
}
