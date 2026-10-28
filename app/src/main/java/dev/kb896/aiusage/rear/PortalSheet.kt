package dev.kb896.aiusage.rear

import android.graphics.RectF
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.data.PortalGrid
import kotlin.math.ceil

/**
 * The App Portal's sheet: the pinned apps as a grid of icons, a page at a time, and —
 * while a guest app is up — its three actions above them; while that guest is ON TOP, its
 * DPI slider in a strip along the bottom. It slides up from the bottom over the blurred
 * rear — our panel or the guest, live — as the pull opens it; the shell's RearUserService
 * and ShellSheet own the pull and the blur, SheetMotion where it rests ([detents]), this
 * owns where things are on the sheet, and PortalSheetPainter paints them.
 *
 * HEIGHTS. The sheet is measured by how high it stands, its top edge at Panel.HEIGHT minus
 * that: 0 shut, [PEEK] the strip alone (only over a guest on top: the guest stays in view
 * above it), [FULL] all of it. Everything here is laid out for the sheet at [FULL], in panel
 * pixels; the painter moves the grid's part with the sheet's top, and keeps the strip at
 * the bottom once it is in.
 *
 * Laid out right of the lens column, as plain numbers: the layout and the hit tests are
 * unit-tested, the drawing is checked on the phone.
 */
object PortalSheet {

    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val centerX get() = (left + right) / 2
        val centerY get() = (top + bottom) / 2
        val width get() = right - left
        val height get() = bottom - top
        fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
        fun intersects(o: Box) = left < o.right && o.left < right && top < o.bottom && o.top < bottom
        fun rect() = RectF(left, top, right, bottom)
    }

    sealed interface Hit {
        /** App [index] of all the pinned apps (not of its page). */
        data class App(val index: Int) : Hit
        /** 0 Panel or Resume, 1 Front, 2 Close. */
        data class Button(val index: Int) : Hit
        /** Page [index]'s dot under the grid. */
        data class Page(val index: Int) : Hit
        /** The DPI slider's track, the thumb with it: the value under the finger. */
        data object Slider : Hit
        /** The slider's value: a tap there is Default. */
        data object SliderValue : Hit
        /** On the strip, but none of its controls: nothing. */
        data object Strip : Hit
        /** Off the sheet's boxes — above it, or between them: the sheet shuts. */
        data object Outside : Hit
    }

    /** The most apps the sheet takes, on as many pages as the grid needs. */
    const val MAX_APPS = 48

    /** The sheet all the way up: the whole panel's height. */
    const val FULL = Panel.HEIGHT * 1f

    /**
     * The strip's height, and the sheet's in PEEK: the DPI slider's row and a margin round
     * it, and no more — the guest above it stays in view and in reach of the eye.
     */
    const val PEEK = 88f

    /**
     * Where the sheet can rest, lowest first. Over a guest on top it stops at [PEEK] on the
     * way (the slider alone); anywhere else — over our panel, a guest behind it — it is shut
     * or [FULL], as it always was.
     */
    fun detents(guestOnTop: Boolean?): List<Float> = if (guestOnTop == true) listOf(0f, PEEK, FULL) else listOf(0f, FULL)

    private const val GAP = 12f
    private const val BUTTON_H = 64f
    private const val LEFT = Panel.CUTOUT_RIGHT + 24f
    /** The grid's width: four cells of 120 as the portal was born, ending at 836, clear of Xiaomi's Back strip (x ≥ 842). */
    private const val GRID_WIDTH = 4 * 120f + 3 * GAP
    private const val RIGHT = LEFT + GRID_WIDTH
    private const val PAD_TOP = 24f
    private const val PAD_BOTTOM = 8f
    /** The page dots' band under the grid. Kept whether or not there are pages, so the cells do not jump. */
    const val DOTS_H = 20f
    private const val DOT_HIT = 18f
    /** Bigger cells than this read as a toy: a 2 × 2 grid stops here. */
    const val CELL_MAX = 168f
    /** An icon is this much of its cell's side; its name sits under it. */
    const val ICON_SCALE = 0.6f
    private const val CELL_PAD = 0.1f

    /** From one page to the next across: the width right of the lens column, so a page at rest shows none of its neighbours. */
    const val PAGE_STRIDE = (Panel.WIDTH - Panel.CUTOUT_RIGHT) * 1f

    /** The slider's row, in the middle of the strip. */
    private const val ROW_H = 56f
    const val SLIDER_ICON = 40f
    private const val VALUE_W = 112f
    /** The thumb's side; it may stand half of itself past the track's ends, so its hit does too. */
    const val THUMB = 36f
    private const val ROW_GAP = 16f

    /** The slider's row: the guest's icon, the track, and the value. */
    data class Slider(val icon: Box, val track: Box, val value: Box)

    /**
     * One sheet's boxes, for the sheet at [FULL]: the strip's three [buttons] (none without
     * a guest), one page's [slots] (the same on every page), the [dots] under the grid (none
     * for one page), and the [slider] (only over a guest on top). [upperBottom] is where the
     * grid's part of the sheet ends — the strip's top, or the panel's bottom without one.
     */
    class Layout(
        val count: Int,
        val buttons: List<Box>,
        val slots: List<Box>,
        val pages: Int,
        val dots: List<Box>,
        val slider: Slider?,
        val upperBottom: Float,
        /** Where an empty sheet's words stand. */
        val emptyX: Float,
        val emptyY: Float,
    ) {
        val perPage: Int get() = slots.size

        /** The app in [slot] of [page], or -1 for an empty slot. */
        fun appAt(page: Int, slot: Int): Int {
            if (slot !in slots.indices || page !in 0 until pages) return -1
            return (page * perPage + slot).takeIf { it < count } ?: -1
        }
    }

    /**
     * The sheet's boxes for [count] pinned apps (no more than [MAX_APPS]) in [grid], with
     * the guest as the sheet shows it: [guestOnTop] null none (no buttons), false behind
     * the panel (buttons), true on top (buttons and the slider's strip).
     *
     * The cells are square and as big as the room under the buttons allows — the fewer
     * there are a page, the bigger, up to [CELL_MAX] — and centred in it.
     */
    fun layout(grid: PortalGrid, count: Int, guestOnTop: Boolean?): Layout {
        val n = count.coerceIn(0, MAX_APPS)
        val withButtons = guestOnTop != null
        val withSlider = guestOnTop == true
        val upperBottom = if (withSlider) FULL - PEEK else FULL
        val top = PAD_TOP + if (withButtons) BUTTON_H + GAP else 0f
        val bottom = upperBottom - PAD_BOTTOM - DOTS_H
        val (rows, cols) = grid.rows to grid.columns
        val cell = minOf(
            (GRID_WIDTH - (cols - 1) * GAP) / cols,
            (bottom - top - (rows - 1) * GAP) / rows,
            CELL_MAX,
        )
        val w = cols * cell + (cols - 1) * GAP
        val h = rows * cell + (rows - 1) * GAP
        val l0 = LEFT + (GRID_WIDTH - w) / 2
        val t0 = top + (bottom - top - h) / 2
        val slots = (0 until rows * cols).map { i ->
            val l = l0 + (i % cols) * (cell + GAP)
            val t = t0 + (i / cols) * (cell + GAP)
            Box(l, t, l + cell, t + cell)
        }
        val pages = maxOf(1, ceil(n / grid.perPage.toFloat()).toInt())
        val centerX = LEFT + GRID_WIDTH / 2
        val dotsY = t0 + h + DOTS_H / 2 + 2f
        val dots = if (pages < 2) emptyList() else (0 until pages).map { i ->
            val x = centerX + (i - (pages - 1) / 2f) * DOT_HIT
            Box(x - DOT_HIT / 2, dotsY - DOTS_H / 2, x + DOT_HIT / 2, dotsY + DOTS_H / 2)
        }
        return Layout(
            count = n,
            buttons = if (withButtons) buttons() else emptyList(),
            slots = slots, pages = pages, dots = dots,
            slider = if (withSlider) slider() else null,
            upperBottom = upperBottom,
            emptyX = centerX, emptyY = (top + bottom) / 2,
        )
    }

    private fun buttons(): List<Box> {
        val w = (GRID_WIDTH - 2 * GAP) / 3
        return (0 until 3).map { i ->
            val l = LEFT + i * (w + GAP)
            Box(l, PAD_TOP, l + w, PAD_TOP + BUTTON_H)
        }
    }

    private fun slider(): Slider {
        val top = FULL - PEEK + (PEEK - ROW_H) / 2
        val bottom = top + ROW_H
        val iconTop = top + (ROW_H - SLIDER_ICON) / 2
        val icon = Box(LEFT, iconTop, LEFT + SLIDER_ICON, iconTop + SLIDER_ICON)
        val value = Box(RIGHT - VALUE_W, top, RIGHT, bottom)
        // The thumb's half past each end stays clear of the icon and the value.
        val track = Box(icon.right + ROW_GAP + THUMB / 2, top, value.left - ROW_GAP - THUMB / 2, bottom)
        return Slider(icon, track, value)
    }

    /** Where [slot]'s icon goes in its cell: [ICON_SCALE] of it, centred across, near its top. */
    fun icon(c: Box): Box {
        val s = c.width * ICON_SCALE
        val l = c.centerX - s / 2
        val t = c.top + c.height * CELL_PAD
        return Box(l, t, l + s, t + s)
    }

    /** Where a cell's name stands: its baseline, near the cell's bottom. */
    fun nameBaseline(c: Box): Float = c.bottom - c.height * CELL_PAD

    /**
     * What a finger at [x], [y] is on, for the sheet at rest at height [at] (one of its
     * [detents]) showing [page]. Callers must ignore taps until the sheet has settled: the
     * painter moves everything with it.
     *
     * Above the sheet's top is [Hit.Outside] — in PEEK that is the guest above the strip,
     * and a tap there shuts the sheet. The strip, when there is one, is the slider's whole
     * height: its track (the thumb's overhang with it), its value, or nothing. Over the rest:
     * the buttons, the page's apps, the page dots; anything else is outside.
     */
    fun hit(l: Layout, x: Float, y: Float, at: Float, page: Int): Hit {
        if (y < Panel.HEIGHT - at) return Hit.Outside
        l.slider?.let { s ->
            if (y >= FULL - PEEK) return when {
                x in s.value.left..s.value.right -> Hit.SliderValue
                x in (s.track.left - THUMB / 2)..(s.track.right + THUMB / 2) -> Hit.Slider
                else -> Hit.Strip
            }
        }
        l.buttons.forEachIndexed { i, b -> if (b.contains(x, y)) return Hit.Button(i) }
        l.slots.forEachIndexed { slot, c ->
            if (c.contains(x, y)) return l.appAt(page, slot).takeIf { it >= 0 }?.let { Hit.App(it) } ?: Hit.Outside
        }
        l.dots.forEachIndexed { i, d -> if (d.contains(x, y)) return Hit.Page(i) }
        return Hit.Outside
    }

    /** What a sideways drag moves: the slider, the pages, or nothing. */
    enum class Swipe { NONE, SLIDER, PAGES }

    /**
     * A sideways drag that landed at [y] on the sheet at rest at [at]: anywhere on the strip
     * it is the slider's (it moves the thumb by as much as the finger moves, from where the
     * thumb was — no jump to the finger), anywhere above the strip on the full sheet with
     * more than one page it turns the page. Not above the sheet; callers pass only a sheet
     * at rest.
     */
    fun swipe(l: Layout, y: Float, at: Float): Swipe = when {
        y < Panel.HEIGHT - at -> Swipe.NONE
        l.slider != null && y >= FULL - PEEK -> Swipe.SLIDER
        at >= FULL - 0.5f && l.pages > 1 && y < l.upperBottom -> Swipe.PAGES
        else -> Swipe.NONE
    }

    // -- the DPI slider --------------------------------------------------------------------

    /** The DPI at [x] on [track]: RearDensity's range across it, in its steps, held at its ends. */
    fun sliderDpi(track: Box, x: Float): Int {
        val t = ((x - track.left) / track.width).coerceIn(0f, 1f)
        return RearDensity.clamp(Math.round(RearDensity.MIN + t * (RearDensity.MAX - RearDensity.MIN)))
    }

    /** Where [dpi]'s thumb centre stands on [track]. */
    fun sliderX(track: Box, dpi: Int): Float =
        track.left + (RearDensity.clamp(dpi) - RearDensity.MIN) / (RearDensity.MAX - RearDensity.MIN).toFloat() * track.width

    /** The DPI after a drag of [dx] that began with the thumb at [from]. */
    fun sliderDrag(track: Box, from: Int, dx: Float): Int = sliderDpi(track, sliderX(track, from) + dx)

    /**
     * What the slider's value says: the number being dragged to, or the app's own, or
     * "Default" for an app without one (the thumb then stands at the display's own).
     */
    fun sliderLabel(dragged: Int?, own: Int?): String = (dragged ?: own)?.let { "$it dpi" } ?: "Default"

    // -- the buttons' marks and names --------------------------------------------------------

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

    /** The first button's name, and the other two's. */
    fun buttonNames(guestOnTop: Boolean) = listOf(if (guestOnTop) "Panel" else "Resume", "Front", "Close")

    // -- motion ------------------------------------------------------------------------------
    //
    // The sheet's top edge — and its crop, and so its blur — follows the finger. Its
    // CONTENTS arrive as the control centre's do (Arrival), each part over its own stage of
    // the rise: the strip over shut→PEEK, the grid with its buttons over PEEK→FULL (or
    // shut→FULL with no strip). Each stage has its own progress, 0 to 1, through the one
    // curve: so everything in a stage — the buttons and the grid alike — arrives together.

    /** How far through its stage, shut to [PEEK], the strip is at sheet height [h]. */
    fun stripStage(h: Float): Float = (h / PEEK).coerceIn(0f, 1f)

    /** How far through its stage the grid's part is at height [h]: from [PEEK] with the strip under it, else from 0, to [FULL]. */
    fun upperStage(h: Float, withSlider: Boolean): Float {
        val start = upperStart(withSlider)
        return ((h - start) / (FULL - start)).coerceIn(0f, 1f)
    }

    /** How far the grid's part's stage travels. */
    fun upperTravel(withSlider: Boolean): Float = FULL - upperStart(withSlider)

    private fun upperStart(withSlider: Boolean) = if (withSlider) PEEK else 0f

    /**
     * How far below their place the strip's boxes stand at height [h]: below [PEEK] the
     * strip rides up with the sheet's top, and its contents arrive (Arrival.offset over the
     * strip's own travel) on top of that; from there it stays at the bottom while the rest
     * comes up.
     */
    fun stripShift(h: Float): Float = (PEEK - h).coerceAtLeast(0f) + Arrival.offset(stripStage(h), PEEK)

    /**
     * How far below their place the grid's part's boxes stand at height [h]: they go with
     * the sheet's top — so the buttons come in with its edge, not after the grid — and
     * arrive on their stage's curve on top of that, sliding up into place.
     */
    fun upperShift(h: Float, withSlider: Boolean): Float =
        (FULL - h) + Arrival.offset(upperStage(h, withSlider), upperTravel(withSlider))
}
