package dev.kb896.aiusage.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.rear.MascotFrames
import dev.kb896.aiusage.rear.MascotRenderer
import dev.kb896.aiusage.rear.WalkRig

/**
 * Every mascot state, animating, in the front UI.
 *
 * Exists so the states can be inspected without contriving them on the real panel —
 * checking the dead pose previously meant broadcasting a forged payload with every
 * window at 100%, which is slow, easy to get wrong, and leaves the panel showing
 * fiction until the next poll overwrites it.
 *
 * Draws through MascotRenderer, the same code the panel uses, so this cannot drift into
 * showing something the panel does not. The walk is the exception in both senses: it is
 * drawn by WalkRig, and the panel never selects it — this is the only place it is shown.
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
     * The dead pose is the standing rig tipped on its side, not a clip of its own, so it
     * is listed here explicitly rather than read from MascotFrames.CLIPS.
     */
    private data class Cell(val clip: MascotFrames.Clip, val caption: String, val dead: Boolean)

    /** The baked clips, which stay in their cells, plus the dead pose. */
    private val cells: List<Cell> by lazy {
        val stand = MascotFrames.CLIPS.firstOrNull { it.name == "stand" }
        MascotFrames.CLIPS.map { Cell(it, it.name, dead = false) } +
            listOfNotNull(stand?.let { Cell(it, "dead", dead = true) })
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

        // A grid, not a single row. It was one row divided by the clip count, which was
        // fine at four and cramped at seven: a clip's width is its height times an aspect
        // near 1, so dividing the width divides the figures too, and they shrank to
        // thumbnails in a band with empty air above them. Wrapping keeps each figure the
        // size it is worth looking at, which is the entire purpose of this view.
        val columns = minOf(cells.size, COLUMNS)
        val gridRows = (cells.size + columns - 1) / columns
        val gridH = height * GRID_SHARE
        val rowH = gridH / gridRows

        val cell = width.toFloat() / columns
        // Leave room under the figures for the caption, and above for anything a clip
        // throws over its own head — the confetti sits well outside its mascot box.
        val availableH = (rowH - CAPTION_H - HEADROOM).coerceAtLeast(1f)

        // The walk gets the lower row to itself: it needs the whole width to cross, and
        // its own height again in clear air above, because the jump takes it a full
        // body-height out of its box.
        //
        // Its height is the STANDING clip's, computed the same way, because it is the
        // same rig — letting it fill the row instead made it nearly twice the size of
        // the others, which reads as a different, larger mascot rather than the same one
        // walking. The row fit is then only a floor-case guard for a short strip.
        val walkRow = height - gridH
        val walkH = minOf(
            minOf(availableH, (cell * CELL_FILL) / WalkRig.ASPECT),
            (walkRow - CAPTION_H) / (1f + WalkRig.HEADROOM),
        ).coerceAtLeast(1f)
        WalkRig.draw(
            canvas, now,
            left = 0f,
            top = height - CAPTION_H - walkH,
            h = walkH,
            span = width - walkH * WalkRig.ASPECT,
            paint = body,
        )
        canvas.drawText("walk", width / 2f, height - 6f, label)

        for ((i, c) in cells.withIndex()) {
            val col = i % columns
            val baseline = (i / columns + 1) * rowH - CAPTION_H
            // Height is bounded by the CELL as well as by the view. Sizing on height
            // alone made every figure ~470px wide in a 244px cell on this display, so
            // they overlapped into a single blob — a clip's width is height x aspect,
            // and aspect is close to 1, so a tall strip is also a wide one.
            val h = minOf(availableH, (cell * CELL_FILL) / c.clip.aspect)
            val w = h * c.clip.aspect
            val x = col * cell + (cell - w) / 2f
            MascotRenderer.draw(
                canvas, c.clip, now, x, baseline - h, h, body, eye,
                // The work account's blue is shown on the muscle clip, which is the one
                // carrying the most hardware, so both accents appear somewhere.
                accent = if (c.clip.name == "muscle") MascotRenderer.ACCENT_WORK
                    else MascotRenderer.ACCENT_ORANGE,
                dead = c.dead,
            )
            canvas.drawText(c.caption, col * cell + cell / 2f, baseline + CAPTION_H - 6f, label)
        }
    }

    private companion object {
        /** Fast enough for the quickest clip (83ms) without pinning a redraw loop. */
        const val FRAME_MS = 40L
        const val CAPTION_H = 26f
        const val HEADROOM = 22f

        /** Share of the strip the clip grid takes; the walk gets the rest. */
        const val GRID_SHARE = 0.68f

        /** Clips per row before wrapping. */
        const val COLUMNS = 4

        /** Fraction of its cell a figure may occupy, leaving gutters between them. */
        const val CELL_FILL = 0.72f
    }
}
