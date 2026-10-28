package dev.kb896.aiusage.rear

import android.graphics.RectF
import dev.kb896.aiusage.Panel

/**
 * The App Portal's sheet: the pinned apps as a grid of icons, and — while a guest app is
 * up — a strip above it with the guest's three actions. It rises from the bottom over the
 * blurred panel (or the guest's frozen frame) as the pull opens it; RearDialView owns the
 * pull and the blur, this owns where things are on the sheet, and PortalSheetPainter
 * paints them.
 *
 * Laid out right of the lens column, in panel pixels, as plain numbers: the hit tests are
 * unit-tested, the drawing is checked on the phone.
 */
object PortalSheet {

    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val centerX get() = (left + right) / 2
        val centerY get() = (top + bottom) / 2
        fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
        fun rect() = RectF(left, top, right, bottom)
    }

    sealed interface Hit {
        data class App(val index: Int) : Hit
        /** 0 Panel or Resume, 1 Front, 2 Close. */
        data class Button(val index: Int) : Hit
        data object Outside : Hit
    }

    /** Three rows of four fit under the strip; more would run off the bottom. */
    const val MAX_APPS = 12
    private const val COLUMNS = 4
    const val CELL = 120f
    private const val GAP = 12f
    private const val STRIP = 64f
    private val LEFT = Panel.CUTOUT_RIGHT + 24f
    private const val TOP = 40f

    fun cells(count: Int, withStrip: Boolean): List<Box> {
        val top = TOP + if (withStrip) STRIP + GAP else 0f
        val n = count.coerceAtMost(MAX_APPS)
        return (0 until n).map { i ->
            val col = i % COLUMNS; val row = i / COLUMNS
            val l = LEFT + col * (CELL + GAP); val t = top + row * (CELL + GAP)
            Box(l, t, l + CELL, t + CELL)
        }
    }

    fun buttons(): List<Box> {
        val w = (COLUMNS * CELL + (COLUMNS - 1) * GAP - 2 * GAP) / 3
        return (0 until 3).map { i ->
            val l = LEFT + i * (w + GAP)
            Box(l, TOP, l + w, TOP + STRIP)
        }
    }

    /**
     * Valid only for the fully open sheet: the painter moves everything down by [slide],
     * so callers must ignore taps until the sheet has settled open.
     */
    fun hit(x: Float, y: Float, count: Int, withStrip: Boolean): Hit {
        if (withStrip) buttons().forEachIndexed { i, b -> if (b.contains(x, y)) return Hit.Button(i) }
        cells(count, withStrip).forEachIndexed { i, c -> if (c.contains(x, y)) return Hit.App(i) }
        return Hit.Outside
    }

    /** The first button's name, and the other two's. */
    fun buttonNames(guestOnTop: Boolean) = listOf(if (guestOnTop) "Panel" else "Resume", "Front", "Close")

    /**
     * How far below its place the sheet stands at [progress] (0 shut, 1 open): it slides
     * up by what is left to open, as the control centre arrives.
     */
    fun slide(progress: Float): Float = (1f - progress) * Panel.HEIGHT * 0.4f

    /** The middle of the grid, across: where an empty sheet's words stand. */
    val gridCenterX: Float get() = LEFT + (COLUMNS * CELL + (COLUMNS - 1) * GAP) / 2f
}
