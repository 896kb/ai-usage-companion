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
 * Every mascot state, animating, in the front UI.
 *
 * Exists so the states can be inspected without contriving them on the real panel —
 * checking the dead pose previously meant broadcasting a forged payload with every
 * window at 100%, which is slow, easy to get wrong, and leaves the panel showing
 * fiction until the next poll overwrites it.
 *
 * Draws through MascotRenderer, the same code the panel uses, so this cannot drift into
 * showing something the panel does not.
 */
class MascotStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val body = Paint()
    private val eye = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        color = Panel.Palette.BG
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Panel.Palette.DIM
        textSize = 22f
        textAlign = Paint.Align.CENTER
    }
    private val bg = Paint().apply { color = Panel.Palette.BG }

    /**
     * The dead pose is the standing rig with a flag, not a clip of its own, so it is
     * listed here explicitly rather than read from MascotFrames.CLIPS.
     */
    private data class Cell(val clip: MascotFrames.Clip, val caption: String, val dead: Boolean)

    /**
     * The sedentary clips share the top row. A travelling clip cannot: it needs the
     * width of the whole strip to cross, so it gets its own row underneath.
     */
    private val cells: List<Cell> by lazy {
        val stand = MascotFrames.CLIPS.firstOrNull { it.name == "stand" }
        MascotFrames.CLIPS.filter { it.travelMs <= 0L }.map { Cell(it, it.name, dead = false) } +
            listOfNotNull(stand?.let { Cell(it, "dead", dead = true) })
    }

    private val travellers: List<MascotFrames.Clip> by lazy {
        MascotFrames.CLIPS.filter { it.travelMs > 0L }
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

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        if (cells.isEmpty()) return

        val now = System.currentTimeMillis()
        val rows = if (travellers.isEmpty()) 1 else 2
        val rowH = height.toFloat() / rows

        val cell = width.toFloat() / cells.size
        // Leave room under the figures for the caption, and above for anything a clip
        // throws over its own head — the confetti sits well outside its mascot box.
        val availableH = (rowH - CAPTION_H - HEADROOM).coerceAtLeast(1f)
        val baseline = rowH - CAPTION_H

        for ((i, t) in travellers.withIndex()) {
            val h = (rowH - CAPTION_H - HEADROOM).coerceAtLeast(1f)
                .coerceAtMost((cell * CELL_FILL) / t.aspect)
            val w = h * t.aspect
            val top = rowH * (rows - 1) + (rowH - CAPTION_H - h)
            val x = MascotRenderer.travelX(t, now, 0f, width.toFloat(), w)
            MascotRenderer.draw(canvas, t, now, x, top, h, body, eye)
            canvas.drawText(t.name, width / 2f, height - 6f, label)
        }

        for ((i, c) in cells.withIndex()) {
            // Height is bounded by the CELL as well as by the view. Sizing on height
            // alone made every figure ~470px wide in a 244px cell on this display, so
            // they overlapped into a single blob — a clip's width is height x aspect,
            // and aspect is close to 1, so a tall strip is also a wide one.
            val h = minOf(availableH, (cell * CELL_FILL) / c.clip.aspect)
            val w = h * c.clip.aspect
            val x = i * cell + (cell - w) / 2f
            MascotRenderer.draw(
                canvas, c.clip, now, x, baseline - h, h, body, eye,
                // The work account's blue is shown on the muscle clip, which is the one
                // carrying the most hardware, so both accents appear somewhere.
                accent = if (c.clip.name == "muscle") MascotRenderer.ACCENT_WORK
                    else MascotRenderer.ACCENT_ORANGE,
                dead = c.dead,
            )
            canvas.drawText(c.caption, i * cell + cell / 2f, rowH - 6f, label)
        }
    }

    private companion object {
        /** Fast enough for the quickest clip (83ms) without pinning a redraw loop. */
        const val FRAME_MS = 40L
        const val CAPTION_H = 26f
        const val HEADROOM = 22f

        /** Fraction of its cell a figure may occupy, leaving gutters between them. */
        const val CELL_FILL = 0.72f
    }
}
