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
        val width get() = right - left
        fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
        fun intersects(o: Box) = left < o.right && o.left < right && top < o.bottom && o.top < bottom
        fun rect() = RectF(left, top, right, bottom)
    }

    /** The DPI row's four boxes, left to right. VALUE only shows the number. */
    enum class DpiPart { MINUS, VALUE, PLUS, DEFAULT }

    sealed interface Hit {
        data class App(val index: Int) : Hit
        /** 0 Panel or Resume, 1 Front, 2 Close. */
        data class Button(val index: Int) : Hit
        /** A box of app [app]'s DPI row (see [dpiRow]). */
        data class Dpi(val app: Int, val part: DpiPart) : Hit
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
    private const val GRID_WIDTH = COLUMNS * CELL + (COLUMNS - 1) * GAP
    /** The DPI row: −, the value, +, Default. */
    private val DPI_WIDTHS = listOf(76f, 120f, 76f, 136f)
    private const val DPI_HEIGHT = 52f
    private const val DPI_GAP = 8f
    /** The DPI row keeps this far off the panel's bottom edge (its rounded corners, the pull's band). */
    const val DPI_MARGIN = 16f

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
     * App [app]'s DPI row — `−  <dpi>  +  Default` — as four boxes in [DpiPart] order, or
     * null when there is no such cell. Under its cell, centred on it and held within the
     * grid's width; above the cell instead when under it would come closer than
     * [DPI_MARGIN] to the panel's bottom edge (the last row, with the strip). It stands over
     * the cells next to it, which are then neither drawn nor hit ([dpiCovered]).
     */
    fun dpiRow(app: Int, count: Int, withStrip: Boolean): List<Box>? {
        val cell = cells(count, withStrip).getOrNull(app) ?: return null
        val width = DPI_WIDTHS.sum() + (DPI_WIDTHS.size - 1) * DPI_GAP
        val gridRight = LEFT + GRID_WIDTH
        val left = (cell.centerX - width / 2).coerceIn(LEFT, gridRight - width)
        val below = cell.bottom + DPI_GAP
        val top = if (below + DPI_HEIGHT <= Panel.HEIGHT - DPI_MARGIN) below else cell.top - DPI_GAP - DPI_HEIGHT
        var l = left
        return DPI_WIDTHS.map { w -> Box(l, top, l + w, top + DPI_HEIGHT).also { l += w + DPI_GAP } }
    }

    /** The cells app [app]'s DPI row stands over: hidden while it is open. Never its own. */
    fun dpiCovered(app: Int, count: Int, withStrip: Boolean): Set<Int> {
        val row = dpiRow(app, count, withStrip) ?: return emptySet()
        val span = Box(row.first().left, row.first().top, row.last().right, row.last().bottom)
        return cells(count, withStrip).withIndex().filter { (i, c) -> i != app && c.intersects(span) }.map { it.index }.toSet()
    }

    /** The [ShellSheet.press] index of a DPI row's button: past the strip's 0 to 2. */
    fun dpiPress(part: DpiPart): Int = DPI_PRESS + part.ordinal
    const val DPI_PRESS = 10

    /**
     * What the row's middle box says: the app's own DPI, or without one the display's own
     * ([default]) — the row's last box already says "Default" — and a dash while that is unknown.
     */
    fun dpiLabel(dpi: Int?, default: Int?): String = dpi?.toString() ?: default?.toString() ?: "–"

    /**
     * Valid only for the fully open sheet: the painter moves everything down by [slide],
     * so callers must ignore taps until the sheet has settled open. [dpiApp] (-1 none) is
     * the app whose DPI row is open: its boxes come first, and the cells under it are not
     * hit; anything else on the sheet is what it always is (the caller decides that a tap
     * there only closes the row).
     */
    fun hit(x: Float, y: Float, count: Int, withStrip: Boolean, dpiApp: Int = -1): Hit {
        val covered = if (dpiApp >= 0) {
            dpiRow(dpiApp, count, withStrip)?.forEachIndexed { i, b -> if (b.contains(x, y)) return Hit.Dpi(dpiApp, DpiPart.entries[i]) }
            dpiCovered(dpiApp, count, withStrip)
        } else emptySet()
        if (withStrip) buttons().forEachIndexed { i, b -> if (b.contains(x, y)) return Hit.Button(i) }
        cells(count, withStrip).forEachIndexed { i, c -> if (i !in covered && c.contains(x, y)) return Hit.App(i) }
        return Hit.Outside
    }

    /** A strip button's glyph is this tall; a guest's own icon (on Resume) this big. */
    const val MARK_H = 24f
    const val APP_MARK = 28f
    /** Between a button's mark and its name, and kept clear inside its edges. */
    const val MARK_GAP = 8f
    const val BUTTON_PAD = 12f

    /** Where a strip button's mark and name go: [markLeft]/[markTop] the mark's box, [textCenterX] the name's middle. */
    data class Labelled(val markLeft: Float, val markTop: Float, val textCenterX: Float)

    /** The width a name may take beside a mark [markW] wide in [box] (ellipsized to it). */
    fun labelRoom(box: Box, markW: Float): Float =
        (box.width - 2 * BUTTON_PAD - (if (markW > 0f) markW + MARK_GAP else 0f)).coerceAtLeast(0f)

    /**
     * A mark [markW] by [markH] left of a name [textW] wide, the two centred TOGETHER in
     * [box] across and the mark centred down it — so "Panel" with its glyph sits in the
     * middle of its button as the bare word did. No mark ([markW] 0): the name alone, centred.
     */
    fun labelled(box: Box, markW: Float, markH: Float, textW: Float): Labelled {
        if (markW <= 0f) return Labelled(box.centerX, box.centerY, box.centerX)
        val total = markW + MARK_GAP + textW
        val left = box.centerX - total / 2
        return Labelled(left, box.centerY - markH / 2, left + markW + MARK_GAP + textW / 2)
    }

    /** The DPI hint's line under the grid ("Hold an app for its DPI"): its baseline, or null with no apps. */
    fun hintBaseline(count: Int, withStrip: Boolean): Float? {
        val last = cells(count, withStrip).lastOrNull() ?: return null
        return minOf(last.bottom + HINT_BELOW, Panel.HEIGHT - DPI_MARGIN)
    }
    private const val HINT_BELOW = 26f
    const val HINT = "Hold an app for its DPI"
    /** The hint shows on this many openings with apps on the sheet, then never again (until the shell restarts). */
    const val HINT_OPENINGS = 5

    /** The first button's name, and the other two's. */
    fun buttonNames(guestOnTop: Boolean) = listOf(if (guestOnTop) "Panel" else "Resume", "Front", "Close")

    /**
     * How far below its place the sheet stands at [progress] (0 shut, 1 open): it slides
     * up by what is left to open, as the control centre arrives.
     */
    fun slide(progress: Float): Float = (1f - progress) * Panel.HEIGHT * 0.4f

    /** The middle of the grid, across: where an empty sheet's words stand. */
    val gridCenterX: Float get() = LEFT + GRID_WIDTH / 2f
}
