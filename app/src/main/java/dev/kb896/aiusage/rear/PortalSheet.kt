package dev.kb896.aiusage.rear

import android.graphics.RectF
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.data.PortalGrid
import kotlin.math.ceil

/**
 * The App Portal's sheet: the pinned apps as a grid of icons, a page at a time, and —
 * while a guest app is up — its three actions above them, and under them, one scroll
 * further, the guest's DPI slider. It comes over the whole blurred rear — our panel or the
 * guest, live — as the pull opens it; the shell's RearUserService and ShellSheet own the
 * pull and the blur, SheetMotion where it rests ([detents]), this owns where things are on
 * the sheet, and PortalSheetPainter paints them.
 *
 * ONE AXIS, in pixels of finger travel (SheetMotion): 0 shut, [OPEN] open — the pull's
 * travel, over which the blur, the dim and the contents come in ([progress]) — and with a
 * guest, [REVEALED]: the open sheet's contents scrolled up by [STRIP] ([scroll]), a scroll
 * breakpoint, bringing the slider's row in from under the bottom edge. Everything here is
 * laid out in the sheet's own content space: the panel at scroll 0, the slider's row just
 * below it.
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
        /**
         * On the sheet, but on none of its controls — the slider's row off it, the dots' row off
         * its dots: nothing, and the sheet stays.
         */
        data object Blank : Hit
        /** Off the sheet's boxes — above it, or between them: the sheet shuts. */
        data object Outside : Hit
    }

    /** The most apps the sheet takes, on as many pages as the grid needs: as many as the front lets be pinned. */
    const val MAX_APPS = dev.kb896.aiusage.data.PortalApps.MAX

    /** The panel's height: where the content above the slider's row ends. */
    const val FULL = Panel.HEIGHT * 1f

    /** Open: the pull's whole travel, as the control centre's. */
    const val OPEN = PortalPull.TRAVEL

    /**
     * The slider's row's height, and how far the open sheet scrolls to show it: the row and
     * a margin round it, and no more.
     */
    const val STRIP = 88f

    /** Open and scrolled to the slider. */
    const val REVEALED = OPEN + STRIP

    /**
     * Where the sheet can rest, lowest first: shut and open; with a guest — on top or behind
     * the panel, since its DPI is applied either way while it is the guest — scrolled to
     * the slider too. Without one an up-swipe on the open sheet does nothing.
     */
    fun detents(guestOnTop: Boolean?): List<Float> = if (guestOnTop != null) listOf(0f, OPEN, REVEALED) else listOf(0f, OPEN)

    /** How far open at [position], 0 to 1: the blur, the dim and the contents' arrival follow it. */
    fun progress(position: Float): Float = (position / OPEN).coerceIn(0f, 1f)

    /** How far the open sheet's contents are scrolled up at [position], 0 to [STRIP]. */
    fun scroll(position: Float): Float = (position - OPEN).coerceIn(0f, STRIP)

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
    /** From one page dot to the next. */
    private const val DOT_STEP = 18f
    /**
     * The dots' row is this tall for a finger, and reaches this far past its end dots: a
     * dot is tiny, and a near miss must not shut the sheet. Within it the nearest dot is
     * hit; the rest of the row is [Hit.Blank].
     */
    const val DOTS_HIT_H = 44f
    private const val DOTS_REACH = 22f
    /** Bigger cells than this read as a toy: a 2 × 2 grid stops here. */
    const val CELL_MAX = 168f
    /** An icon is this much of its cell's side; its name sits under it. */
    const val ICON_SCALE = 0.6f
    /** Without names, an icon fills its cell but this much of its side on each side. */
    const val ICON_MARGIN = 0.1f
    private const val CELL_PAD = 0.1f

    /** From one page to the next across: the width right of the lens column, so a page at rest shows none of its neighbours. */
    const val PAGE_STRIDE = (Panel.WIDTH - Panel.CUTOUT_RIGHT) * 1f

    /** The slider's row, in the middle of its strip. */
    private const val ROW_H = 56f
    const val SLIDER_ICON = 40f
    private const val VALUE_W = 112f
    /**
     * The thumb: the front's glass slider's 40 × 24 pill (GlassSlider), at the rear's
     * size. It may stand half of itself past the track's ends, so its hit does too.
     */
    const val THUMB = 48f
    const val THUMB_H = 28f
    private const val ROW_GAP = 16f

    /** The slider's row: the guest's icon, the track, and the value. */
    data class Slider(val icon: Box, val track: Box, val value: Box)

    /**
     * One sheet's boxes, in its content space: the guest's three [buttons] (none without a
     * guest), one page's [slots] (the same on every page), the [dots] under the grid (none
     * for one page) and the row a finger finds them in ([dotsRow]), and below the panel's
     * bottom the [slider] (only with a guest).
     */
    class Layout(
        val count: Int,
        val buttons: List<Box>,
        val slots: List<Box>,
        val pages: Int,
        val dots: List<Box>,
        val dotsRow: Box?,
        val slider: Slider?,
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
     * the guest as the sheet shows it: [guestOnTop] null none, else the buttons, and the
     * slider's row under the panel.
     *
     * The cells are square and as big as the room under the buttons allows — the fewer
     * there are a page, the bigger, up to [CELL_MAX] — and centred in it.
     */
    fun layout(grid: PortalGrid, count: Int, guestOnTop: Boolean?): Layout {
        val n = count.coerceIn(0, MAX_APPS)
        val withGuest = guestOnTop != null
        val top = PAD_TOP + if (withGuest) BUTTON_H + GAP else 0f
        val bottom = FULL - PAD_BOTTOM - DOTS_H
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
            val x = centerX + (i - (pages - 1) / 2f) * DOT_STEP
            Box(x - DOT_STEP / 2, dotsY - DOTS_H / 2, x + DOT_STEP / 2, dotsY + DOTS_H / 2)
        }
        val dotsRow = if (pages < 2) null else Box(LEFT, dotsY - DOTS_HIT_H / 2, RIGHT, dotsY + DOTS_HIT_H / 2)
        return Layout(
            count = n,
            buttons = if (withGuest) buttons() else emptyList(),
            slots = slots, pages = pages, dots = dots, dotsRow = dotsRow,
            slider = if (withGuest) slider() else null,
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
        val top = FULL + (STRIP - ROW_H) / 2
        val bottom = top + ROW_H
        val iconTop = top + (ROW_H - SLIDER_ICON) / 2
        val icon = Box(LEFT, iconTop, LEFT + SLIDER_ICON, iconTop + SLIDER_ICON)
        val value = Box(RIGHT - VALUE_W, top, RIGHT, bottom)
        // The thumb's half past each end stays clear of the icon and the value.
        val track = Box(icon.right + ROW_GAP + THUMB / 2, top, value.left - ROW_GAP - THUMB / 2, bottom)
        return Slider(icon, track, value)
    }

    /**
     * Where an icon goes in its cell [c]: with [names], [ICON_SCALE] of it, centred across,
     * near its top, its name under it; without, grown into the name's room — the cell but
     * [ICON_MARGIN] round it, centred. Either way the whole cell is the app's to a finger.
     */
    fun icon(c: Box, names: Boolean = true): Box {
        if (!names) {
            val m = c.width * ICON_MARGIN
            return Box(c.left + m, c.top + m, c.right - m, c.bottom - m)
        }
        val s = c.width * ICON_SCALE
        val l = c.centerX - s / 2
        val t = c.top + c.height * CELL_PAD
        return Box(l, t, l + s, t + s)
    }

    /** Where a cell's name stands: its baseline, near the cell's bottom. */
    fun nameBaseline(c: Box): Float = c.bottom - c.height * CELL_PAD

    /**
     * What a finger at [x], [y] on the screen is on, for the open sheet at rest scrolled by
     * [scroll] (0, or [STRIP] at the slider) showing [page]. Callers must ignore taps until
     * the sheet has settled: the painter moves everything with it.
     *
     * The slider's row, when it is in, is the slider's whole height: its track (the thumb's
     * overhang with it), its value, or nothing. Over the rest: the buttons, the page's
     * apps, then the dots' row — the nearest dot within reach, else nothing; anything else
     * is [Hit.Outside], and a tap there shuts the sheet.
     */
    fun hit(l: Layout, x: Float, y: Float, scroll: Float, page: Int): Hit {
        val cy = y + scroll
        l.slider?.let { s ->
            if (cy >= FULL) return when {
                x in s.value.left..s.value.right -> Hit.SliderValue
                x in (s.track.left - THUMB / 2)..(s.track.right + THUMB / 2) -> Hit.Slider
                else -> Hit.Blank
            }
        }
        l.buttons.forEachIndexed { i, b -> if (b.contains(x, cy)) return Hit.Button(i) }
        l.slots.forEachIndexed { slot, c ->
            if (c.contains(x, cy)) return l.appAt(page, slot).takeIf { it >= 0 }?.let { Hit.App(it) } ?: Hit.Outside
        }
        l.dotsRow?.let { row ->
            if (!row.contains(x, cy)) return@let
            val nearest = l.dots.indices.minBy { kotlin.math.abs(l.dots[it].centerX - x) }
            return if (kotlin.math.abs(l.dots[nearest].centerX - x) <= DOTS_REACH) Hit.Page(nearest) else Hit.Blank
        }
        return Hit.Outside
    }

    /** Whether [x] is on the slider's thumb, standing at [dpi] on [track]: a tap there changes nothing. */
    fun onThumb(track: Box, dpi: Int, x: Float): Boolean = kotlin.math.abs(x - sliderX(track, dpi)) <= THUMB / 2

    /** What a sideways drag moves: the slider, the pages, or nothing. */
    enum class Swipe { NONE, SLIDER, PAGES }

    /**
     * A sideways drag that landed at [y] on the open sheet at rest scrolled by [scroll]: on
     * the slider's row it is the slider's (it moves the thumb by as much as the finger
     * moves, from where the thumb was — no jump to the finger), anywhere above it with more
     * than one page it turns the page.
     */
    fun swipe(l: Layout, y: Float, scroll: Float): Swipe = when {
        l.slider != null && y + scroll >= FULL -> Swipe.SLIDER
        l.pages > 1 && y + scroll < FULL -> Swipe.PAGES
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

    /** A button's glyph is this tall; a guest's own icon (on Resume) this big. */
    const val MARK_H = 24f
    const val APP_MARK = 28f
    /** Between a button's mark and its name, and kept clear inside its edges. */
    const val MARK_GAP = 8f
    const val BUTTON_PAD = 12f

    /** Where a button's mark and name go: [markLeft]/[markTop] the mark's box, [textCenterX] the name's middle. */
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
    // The sheet covers the whole rear; its blur and dim come in with [progress]. Its
    // CONTENTS arrive as the control centre's do (Arrival, on [progress]): fading in as one
    // layer, sliding up into place by [arrivalShift], each piece growing about its own
    // middle — the buttons and the grid on the one curve. Scrolled, they go up by [scroll]
    // and fade into the top edge (EdgeFade); a page sliding sideways fades into the sides.

    /** How far below their place the contents stand at [progress]: the control centre's slide, upward. */
    fun arrivalShift(progress: Float): Float = Arrival.offset(progress, FULL)

    /** Where pages fade out across: [EdgeFade.BAND] past the grid's sides, the right one at the panel's edge. */
    const val PAGES_LEFT = LEFT - EdgeFade.BAND
    const val PAGES_RIGHT = RIGHT + EdgeFade.BAND

    /** How strongly the top edge fades the scrolled contents: none at rest, all of it at the slider. */
    fun topFade(scroll: Float): Float = (scroll / STRIP).coerceIn(0f, 1f)
}
