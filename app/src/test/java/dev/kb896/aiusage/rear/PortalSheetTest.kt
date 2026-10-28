package dev.kb896.aiusage.rear

import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.data.PortalGrid
import dev.kb896.aiusage.rear.PortalSheet.FULL
import dev.kb896.aiusage.rear.PortalSheet.Hit
import dev.kb896.aiusage.rear.PortalSheet.PEEK
import dev.kb896.aiusage.rear.PortalSheet.Swipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalSheetTest {

    private val guests = listOf<Boolean?>(null, false, true)

    private fun layout(count: Int = 7, guest: Boolean? = null, grid: PortalGrid = PortalGrid.R3C4) =
        PortalSheet.layout(grid, count, guest)

    // -- the stops ---------------------------------------------------------------------

    @Test fun `over a guest on top the sheet stops at the strip on the way, elsewhere only shut and full`() {
        assertEquals(listOf(0f, PEEK, FULL), PortalSheet.detents(true))
        assertEquals(listOf(0f, FULL), PortalSheet.detents(false))
        assertEquals(listOf(0f, FULL), PortalSheet.detents(null))
    }

    // -- the grid, for every size ------------------------------------------------------

    @Test fun `every grid has its rows and columns of square cells, centred right of the lenses`() {
        for (grid in PortalGrid.entries) for (guest in guests) {
            val l = layout(48, guest, grid)
            assertEquals(grid.perPage, l.slots.size)
            val tops = l.slots.map { it.top }.distinct()
            val lefts = l.slots.map { it.left }.distinct()
            assertEquals("$grid $guest", grid.rows, tops.size)
            assertEquals("$grid $guest", grid.columns, lefts.size)
            l.slots.forEach { assertEquals(it.width, it.height, 0.01f) }
            val left = l.slots.minOf { it.left }; val right = l.slots.maxOf { it.right }
            assertEquals(l.emptyX, (left + right) / 2, 0.01f)
            assertTrue(left >= Panel.CUTOUT_RIGHT)
        }
    }

    @Test fun `every cell, button, dot and the slider stay on the panel and clear of Xiaomi's Back strip`() {
        for (grid in PortalGrid.entries) for (guest in guests) {
            val l = layout(48, guest, grid)
            val boxes = l.slots + l.buttons + l.dots + listOfNotNull(l.slider?.icon, l.slider?.track, l.slider?.value)
            boxes.forEach {
                assertTrue("$grid $guest $it", it.left >= Panel.CUTOUT_RIGHT && it.right < EdgeGestures.BACK_LEFT)
                assertTrue("$grid $guest $it", it.top >= 0f && it.bottom <= Panel.HEIGHT)
            }
            // The thumb's overhang past the track's end too.
            l.slider?.let { assertTrue(it.track.right + PortalSheet.THUMB / 2 < EdgeGestures.BACK_LEFT) }
        }
    }

    @Test fun `the grid stays above the strip, and under the buttons`() {
        for (grid in PortalGrid.entries) for (guest in guests) {
            val l = layout(48, guest, grid)
            val gridTop = l.slots.minOf { it.top }
            val below = (l.slots + l.dots).maxOf { it.bottom }
            assertTrue("$grid $guest", below <= l.upperBottom)
            l.buttons.forEach { assertTrue(it.bottom < gridTop) }
        }
        assertEquals(FULL - PEEK, layout(guest = true).upperBottom, 0f)
        assertEquals(FULL, layout(guest = false).upperBottom, 0f)
    }

    @Test fun `fewer cells a page are bigger, and three rows of four are no smaller than they were`() {
        for (guest in guests) {
            val sides = PortalGrid.entries.map { layout(48, guest, it).slots[0].width }
            assertTrue("$guest $sides", sides.zipWithNext().all { (a, b) -> b >= a })
            assertTrue(sides.all { it <= PortalSheet.CELL_MAX })
        }
        assertEquals(120f, layout(guest = null).slots[0].width, 0.01f)
        assertEquals(120f, layout(guest = false).slots[0].width, 0.01f)
        // With the strip under it, three rows still fit, a little smaller.
        assertTrue(layout(guest = true).slots[0].width >= 100f)
    }

    @Test fun `an icon and its name sit inside their cell`() {
        val c = layout(guest = true).slots[5]
        val icon = PortalSheet.icon(c)
        assertEquals(c.width * PortalSheet.ICON_SCALE, icon.width, 0.01f)
        assertEquals(c.centerX, icon.centerX, 0.01f)
        assertTrue(icon.top > c.top && icon.bottom < PortalSheet.nameBaseline(c) && PortalSheet.nameBaseline(c) < c.bottom)
    }

    // -- pages --------------------------------------------------------------------------

    @Test fun `more apps than a page holds go on more pages, each with its dot`() {
        assertEquals(1, layout(12).pages)
        assertEquals(emptyList<PortalSheet.Box>(), layout(12).dots)
        assertEquals(2, layout(13).pages)
        assertEquals(12, layout(48, grid = PortalGrid.R2C2).pages)
        assertEquals(12, layout(48, grid = PortalGrid.R2C2).dots.size)
        assertEquals(1, layout(0).pages)
    }

    @Test fun `no more than MAX_APPS are taken`() {
        val l = layout(60)
        assertEquals(PortalSheet.MAX_APPS, l.count)
        assertEquals(4, l.pages)
    }

    @Test fun `a page's slots are the apps of that page, an empty slot none`() {
        val l = layout(13, grid = PortalGrid.R3C3)
        assertEquals(0, l.appAt(0, 0))
        assertEquals(9, l.appAt(1, 0))
        assertEquals(12, l.appAt(1, 3))
        assertEquals(-1, l.appAt(1, 4))
        assertEquals(-1, l.appAt(2, 0))
    }

    @Test fun `the dots stand under the grid, centred, one after another`() {
        val l = layout(30)
        val gridBottom = l.slots.maxOf { it.bottom }
        l.dots.forEach { assertTrue(it.top >= gridBottom) }
        assertEquals(l.emptyX, (l.dots.first().left + l.dots.last().right) / 2, 0.01f)
        l.dots.zipWithNext().forEach { (a, b) -> assertTrue(a.right <= b.left + 0.01f) }
    }

    // -- hits on the full sheet ------------------------------------------------------------

    @Test fun `a tap on a cell is that app, on the page that shows`() {
        val l = layout(20)
        val c = l.slots[5]
        assertEquals(Hit.App(5), PortalSheet.hit(l, c.centerX, c.centerY, FULL, 0))
        assertEquals(Hit.App(17), PortalSheet.hit(l, c.centerX, c.centerY, FULL, 1))
        // Past the last app on its page, an empty slot is outside.
        val empty = l.slots[9]
        assertEquals(Hit.Outside, PortalSheet.hit(l, empty.centerX, empty.centerY, FULL, 1))
    }

    @Test fun `with a guest, the strip's three buttons sit above the grid`() {
        for (guest in listOf(false, true)) {
            val l = layout(guest = guest)
            assertEquals(3, l.buttons.size)
            val b = l.buttons[2]
            assertEquals(Hit.Button(2), PortalSheet.hit(l, b.centerX, b.centerY, FULL, 0))
        }
        assertEquals(emptyList<PortalSheet.Box>(), layout(guest = null).buttons)
    }

    @Test fun `a dot is its page`() {
        val l = layout(30)
        val d = l.dots[2]
        assertEquals(Hit.Page(2), PortalSheet.hit(l, d.centerX, d.centerY, FULL, 0))
    }

    @Test fun `anywhere else is outside, the gaps between cells too`() {
        val l = layout(guest = false)
        assertEquals(Hit.Outside, PortalSheet.hit(l, 100f, 300f, FULL, 0))
        val c = l.slots[0]
        assertEquals(Hit.Outside, PortalSheet.hit(l, c.right + 6f, c.centerY, FULL, 0))
    }

    // -- the strip and its slider --------------------------------------------------------

    @Test fun `the slider is only there over a guest on top, along the bottom`() {
        assertNull(layout(guest = null).slider)
        assertNull(layout(guest = false).slider)
        val s = layout(guest = true).slider!!
        listOf(s.icon, s.track, s.value).forEach { assertTrue(it.top >= FULL - PEEK && it.bottom <= FULL) }
        assertTrue(s.icon.right < s.track.left - PortalSheet.THUMB / 2)
        assertTrue(s.track.right + PortalSheet.THUMB / 2 < s.value.left)
    }

    @Test fun `in PEEK the strip is the slider's and anything above it is outside`() {
        val l = layout(guest = true)
        val s = l.slider!!
        assertEquals(Hit.Slider, PortalSheet.hit(l, s.track.centerX, s.track.centerY, PEEK, 0))
        assertEquals(Hit.Slider, PortalSheet.hit(l, s.track.left - PortalSheet.THUMB / 2 + 1f, FULL - 4f, PEEK, 0))
        assertEquals(Hit.SliderValue, PortalSheet.hit(l, s.value.centerX, s.value.centerY, PEEK, 0))
        assertEquals(Hit.Strip, PortalSheet.hit(l, s.icon.centerX, s.icon.centerY, PEEK, 0))
        // The guest above the strip — where the grid would be on the full sheet — is outside.
        val c = l.slots[0]
        assertEquals(Hit.Outside, PortalSheet.hit(l, c.centerX, c.centerY, PEEK, 0))
        assertEquals(Hit.Outside, PortalSheet.hit(l, s.track.centerX, FULL - PEEK - 1f, PEEK, 0))
    }

    @Test fun `on the full sheet the strip is still the slider's`() {
        val l = layout(guest = true)
        val s = l.slider!!
        assertEquals(Hit.Slider, PortalSheet.hit(l, s.track.centerX, s.track.centerY, FULL, 0))
        assertEquals(Hit.SliderValue, PortalSheet.hit(l, s.value.centerX, s.value.centerY, FULL, 0))
    }

    @Test fun `the slider spans RearDensity's range in its steps, held at its ends`() {
        val t = layout(guest = true).slider!!.track
        assertEquals(RearDensity.MIN, PortalSheet.sliderDpi(t, t.left))
        assertEquals(RearDensity.MAX, PortalSheet.sliderDpi(t, t.right))
        assertEquals(RearDensity.MIN, PortalSheet.sliderDpi(t, t.left - 50f))
        assertEquals(RearDensity.MAX, PortalSheet.sliderDpi(t, t.right + 50f))
        assertEquals(420, PortalSheet.sliderDpi(t, t.left + t.width / 2))
        for (dpi in RearDensity.MIN..RearDensity.MAX step RearDensity.STEP) {
            assertEquals(dpi, PortalSheet.sliderDpi(t, PortalSheet.sliderX(t, dpi)))
            assertEquals(0, PortalSheet.sliderDpi(t, PortalSheet.sliderX(t, dpi) + 2f) % RearDensity.STEP)
        }
    }

    @Test fun `a drag moves the thumb by as much as the finger, from where it was`() {
        val t = layout(guest = true).slider!!.track
        val perStep = t.width / ((RearDensity.MAX - RearDensity.MIN) / RearDensity.STEP)
        assertEquals(400, PortalSheet.sliderDrag(t, 400, 0f))
        assertEquals(430, PortalSheet.sliderDrag(t, 400, perStep * 3))
        assertEquals(380, PortalSheet.sliderDrag(t, 400, -perStep * 2))
        assertEquals(RearDensity.MAX, PortalSheet.sliderDrag(t, 400, 1_000f))
    }

    @Test fun `the value says what is dragged to, else the app's own, else Default`() {
        assertEquals("380 dpi", PortalSheet.sliderLabel(380, 300))
        assertEquals("300 dpi", PortalSheet.sliderLabel(null, 300))
        assertEquals("Default", PortalSheet.sliderLabel(null, null))
    }

    // -- which way a sideways drag goes ---------------------------------------------------

    @Test fun `a sideways drag on the strip moves the slider, in PEEK and on the full sheet`() {
        val l = layout(30, guest = true)
        assertEquals(Swipe.SLIDER, PortalSheet.swipe(l, FULL - 20f, PEEK))
        assertEquals(Swipe.SLIDER, PortalSheet.swipe(l, FULL - 20f, FULL))
        assertEquals(Swipe.NONE, PortalSheet.swipe(l, 300f, PEEK))
    }

    @Test fun `above the strip on the full sheet it turns the pages, when there are pages`() {
        assertEquals(Swipe.PAGES, PortalSheet.swipe(layout(30, guest = true), 200f, FULL))
        assertEquals(Swipe.PAGES, PortalSheet.swipe(layout(30), 540f, FULL))
        assertEquals(Swipe.NONE, PortalSheet.swipe(layout(12), 200f, FULL))
    }

    // -- motion ----------------------------------------------------------------------------

    @Test fun `each part arrives over its own stage, the strip first, the grid after it`() {
        assertEquals(0f, PortalSheet.stripStage(0f), 0f)
        assertEquals(0.5f, PortalSheet.stripStage(PEEK / 2), 1e-4f)
        assertEquals(1f, PortalSheet.stripStage(PEEK), 0f)
        assertEquals(1f, PortalSheet.stripStage(FULL), 0f)
        // Over a guest on top the grid's stage is the strip to full; with no strip, shut to full.
        assertEquals(0f, PortalSheet.upperStage(PEEK, withSlider = true), 0f)
        assertEquals(0.5f, PortalSheet.upperStage((PEEK + FULL) / 2, withSlider = true), 1e-4f)
        assertEquals(1f, PortalSheet.upperStage(FULL, withSlider = true), 0f)
        assertEquals(0.5f, PortalSheet.upperStage(FULL / 2, withSlider = false), 1e-4f)
        assertEquals(FULL - PEEK, PortalSheet.upperTravel(withSlider = true), 0f)
        assertEquals(FULL, PortalSheet.upperTravel(withSlider = false), 0f)
    }

    @Test fun `the strip rises with the sheet's top and arrives on the curve, then stays at the bottom`() {
        // Shut: a whole strip's height below, and the arrival's quarter of its travel.
        assertEquals(PEEK + PEEK / 4, PortalSheet.stripShift(0f), 1e-3f)
        assertEquals(PEEK - 60f + Arrival.offset(60f / PEEK, PEEK), PortalSheet.stripShift(60f), 1e-3f)
        assertEquals(0f, PortalSheet.stripShift(PEEK), 0f)
        assertEquals(0f, PortalSheet.stripShift(FULL), 0f)
    }

    @Test fun `the grid's part goes with the sheet's top and slides up into place on the curve`() {
        assertEquals(0f, PortalSheet.upperShift(FULL, withSlider = true), 0f)
        assertEquals(0f, PortalSheet.upperShift(FULL, withSlider = false), 0f)
        val h = 400f
        assertEquals(
            (FULL - h) + Arrival.offset(PortalSheet.upperStage(h, true), FULL - PEEK),
            PortalSheet.upperShift(h, withSlider = true), 1e-3f,
        )
        // Always below its place, by less as the sheet rises.
        val shifts = (0..20).map { PortalSheet.upperShift(PEEK + it * (FULL - PEEK) / 20, withSlider = true) }
        assertTrue(shifts.zipWithNext().all { (a, b) -> b <= a })
    }

    // -- the buttons' marks ----------------------------------------------------------------

    private val buttons = layout(guest = false).buttons

    @Test fun `each button's mark and name sit inside it, centred together, the mark first`() {
        for (b in buttons) for ((mark, textW) in listOf(PortalSheet.MARK_H to 50f, PortalSheet.APP_MARK to 70f)) {
            val room = PortalSheet.labelRoom(b, mark)
            val w = minOf(textW, room)
            val at = PortalSheet.labelled(b, mark, mark, w)
            val textLeft = at.textCenterX - w / 2
            val textRight = at.textCenterX + w / 2
            // Mark left of the name, a gap between.
            assertEquals(PortalSheet.MARK_GAP, textLeft - (at.markLeft + mark), 0.01f)
            // Inside the button, clear of its edges.
            assertEquals(true, at.markLeft >= b.left + PortalSheet.BUTTON_PAD - 0.01f)
            assertEquals(true, textRight <= b.right - PortalSheet.BUTTON_PAD + 0.01f)
            assertEquals(true, at.markTop >= b.top && at.markTop + mark <= b.bottom)
            // Centred together across, the mark centred down.
            assertEquals(b.centerX, (at.markLeft + textRight) / 2, 0.01f)
            assertEquals(b.centerY, at.markTop + mark / 2, 0.01f)
        }
    }

    @Test fun `a name as wide as its room still fits beside the mark`() {
        val b = buttons[0]
        val room = PortalSheet.labelRoom(b, PortalSheet.APP_MARK)
        assertEquals(true, room > 60f)
        val at = PortalSheet.labelled(b, PortalSheet.APP_MARK, PortalSheet.APP_MARK, room)
        assertEquals(b.left + PortalSheet.BUTTON_PAD, at.markLeft, 0.01f)
        assertEquals(b.right - PortalSheet.BUTTON_PAD, at.textCenterX + room / 2, 0.01f)
    }

    @Test fun `without a mark the name is centred alone`() {
        val b = buttons[1]
        assertEquals(b.centerX, PortalSheet.labelled(b, 0f, 0f, 40f).textCenterX, 0.01f)
        assertEquals(b.width - 2 * PortalSheet.BUTTON_PAD, PortalSheet.labelRoom(b, 0f), 0.01f)
    }
}
