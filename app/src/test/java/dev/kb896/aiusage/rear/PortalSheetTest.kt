package dev.kb896.aiusage.rear

import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.data.PortalGrid
import dev.kb896.aiusage.rear.PortalSheet.FULL
import dev.kb896.aiusage.rear.PortalSheet.Hit
import dev.kb896.aiusage.rear.PortalSheet.OPEN
import dev.kb896.aiusage.rear.PortalSheet.REVEALED
import dev.kb896.aiusage.rear.PortalSheet.STRIP
import dev.kb896.aiusage.rear.PortalSheet.Swipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalSheetTest {

    private val guests = listOf<Boolean?>(null, false, true)

    /** The 17 Pro's lens column, in panel units; and a Pro Max's, whose lenses keep their pixels on a wider rear. */
    private val pro = Panel.CUTOUT_RIGHT.toFloat()
    private val proMax = RearGeometry(976, 596, 296, 110, 450).cutoutUnits

    private fun layout(count: Int = 7, guest: Boolean? = null, grid: PortalGrid = PortalGrid.R3C4, lensRight: Float = pro) =
        PortalSheet.layout(grid, count, guest, lensRight)

    // -- the stops ---------------------------------------------------------------------

    @Test fun `with a guest, on top or behind, the open sheet scrolls on to the slider, without one it only opens`() {
        assertEquals(listOf(0f, OPEN, REVEALED), PortalSheet.detents(true))
        assertEquals(listOf(0f, OPEN, REVEALED), PortalSheet.detents(false))
        assertEquals(listOf(0f, OPEN), PortalSheet.detents(null))
        assertEquals(PortalPull.TRAVEL, OPEN, 0f)
        assertEquals(OPEN + STRIP, REVEALED, 0f)
    }

    @Test fun `the pull opens it, past that the contents scroll`() {
        assertEquals(0f, PortalSheet.progress(0f), 0f)
        assertEquals(0.5f, PortalSheet.progress(OPEN / 2), 1e-5f)
        assertEquals(1f, PortalSheet.progress(REVEALED), 0f)
        assertEquals(0f, PortalSheet.scroll(OPEN), 0f)
        assertEquals(0f, PortalSheet.scroll(OPEN / 2), 0f)
        assertEquals(40f, PortalSheet.scroll(OPEN + 40f), 1e-4f)
        assertEquals(STRIP, PortalSheet.scroll(REVEALED + 30f), 0f)
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
            val slider = listOfNotNull(l.slider?.icon, l.slider?.track, l.slider?.value)
            (l.slots + l.buttons + l.dots + slider).forEach {
                assertTrue("$grid $guest $it", it.left >= Panel.CUTOUT_RIGHT && it.right < EdgeGestures.BACK_LEFT)
            }
            // On the panel as laid out; the slider's row on it once scrolled.
            (l.slots + l.buttons + l.dots).forEach { assertTrue("$grid $guest $it", it.top >= 0f && it.bottom <= Panel.HEIGHT) }
            slider.forEach { assertTrue(it.top - STRIP >= 0f && it.bottom - STRIP <= Panel.HEIGHT) }
            // The thumb's overhang past the track's end too.
            l.slider?.let { assertTrue(it.track.right + PortalSheet.THUMB / 2 < EdgeGestures.BACK_LEFT) }
        }
    }

    @Test fun `the grid stays on the panel, under the buttons, the slider's row under it all`() {
        for (grid in PortalGrid.entries) for (guest in guests) {
            val l = layout(48, guest, grid)
            val gridTop = l.slots.minOf { it.top }
            val below = (l.slots + l.dots).maxOf { it.bottom }
            assertTrue("$grid $guest", below <= FULL)
            l.buttons.forEach { assertTrue(it.bottom < gridTop) }
            l.slider?.let { s -> listOf(s.icon, s.track, s.value).forEach { assertTrue(it.top >= FULL && it.bottom <= FULL + STRIP) } }
        }
    }

    @Test fun `fewer cells a page are bigger, and three rows of four are no smaller than they were`() {
        for (guest in guests) {
            val sides = PortalGrid.entries.map { layout(48, guest, it).slots[0].width }
            assertTrue("$guest $sides", sides.zipWithNext().all { (a, b) -> b >= a })
            assertTrue(sides.all { it <= PortalSheet.CELL_MAX })
        }
        assertEquals(120f, layout(guest = null).slots[0].width, 0.01f)
        assertEquals(120f, layout(guest = true).slots[0].width, 0.01f)
    }

    @Test fun `an icon and its name sit inside their cell`() {
        for (grid in PortalGrid.entries) {
            val c = layout(guest = true, grid = grid).slots[0]
            val icon = PortalSheet.icon(c)
            assertEquals(c.width * PortalSheet.ICON_SCALE, icon.width, 0.01f)
            assertEquals(icon.width, icon.height, 0.01f)
            assertEquals(c.centerX, icon.centerX, 0.01f)
            assertTrue(icon.top > c.top && icon.bottom < PortalSheet.nameBaseline(c) && PortalSheet.nameBaseline(c) < c.bottom)
        }
    }

    @Test fun `without names an icon fills its cell but a margin, centred, for every grid`() {
        for (grid in PortalGrid.entries) for (guest in guests) {
            val c = layout(guest = guest, grid = grid).slots[3]
            val with = PortalSheet.icon(c, names = true)
            val without = PortalSheet.icon(c, names = false)
            val m = c.width * PortalSheet.ICON_MARGIN
            assertEquals("$grid", c.width - 2 * m, without.width, 0.01f)
            assertEquals(without.width, without.height, 0.01f)
            assertEquals(c.centerX, without.centerX, 0.01f)
            assertEquals(c.centerY, without.centerY, 0.01f)
            assertTrue(without.width > with.width)
            assertTrue(without.left >= c.left && without.right <= c.right && without.top >= c.top && without.bottom <= c.bottom)
        }
    }

    @Test fun `without names the whole cell is still the app's`() {
        val l = layout(7)
        val c = l.slots[2]
        assertEquals(Hit.App(2), PortalSheet.hit(l, c.left + 1f, c.bottom - 1f, 0f, 0))
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
        assertEquals(Hit.App(5), PortalSheet.hit(l, c.centerX, c.centerY, 0f, 0))
        assertEquals(Hit.App(17), PortalSheet.hit(l, c.centerX, c.centerY, 0f, 1))
        // Past the last app on its page, an empty slot is outside.
        val empty = l.slots[9]
        assertEquals(Hit.Outside, PortalSheet.hit(l, empty.centerX, empty.centerY, 0f, 1))
    }

    @Test fun `with a guest, the strip's three buttons sit above the grid`() {
        for (guest in listOf(false, true)) {
            val l = layout(guest = guest)
            assertEquals(3, l.buttons.size)
            val b = l.buttons[2]
            assertEquals(Hit.Button(2), PortalSheet.hit(l, b.centerX, b.centerY, 0f, 0))
        }
        assertEquals(emptyList<PortalSheet.Box>(), layout(guest = null).buttons)
    }

    @Test fun `a dot is its page`() {
        val l = layout(30)
        val d = l.dots[2]
        assertEquals(Hit.Page(2), PortalSheet.hit(l, d.centerX, d.centerY, 0f, 0))
    }

    @Test fun `a finger near a dot finds it, the row is tall enough for one`() {
        for (guest in guests) {
            val l = layout(30, guest)
            val row = l.dotsRow!!
            assertTrue(row.height >= 44f)
            val d = l.dots[0]
            assertEquals(Hit.Page(0), PortalSheet.hit(l, d.centerX - 15f, d.centerY + 15f, 0f, 0))
            assertEquals(Hit.Page(2), PortalSheet.hit(l, l.dots[2].centerX + 15f, d.centerY - 8f, 0f, 0))
        }
    }

    @Test fun `a miss in the dots' row keeps the sheet up`() {
        val l = layout(30)
        val y = l.dots[0].centerY
        assertEquals(Hit.Blank, PortalSheet.hit(l, l.dotsRow!!.left + 4f, y, 0f, 0))
        assertEquals(Hit.Blank, PortalSheet.hit(l, l.dotsRow!!.right - 4f, y, 0f, 0))
        assertNull(layout(12).dotsRow)
    }

    @Test fun `the dots' row takes nothing from the apps over it`() {
        val l = layout(30)
        val c = l.slots.last()
        assertEquals(Hit.App(11), PortalSheet.hit(l, c.centerX, c.bottom - 2f, 0f, 0))
    }

    @Test fun `anywhere else is outside, the gaps between cells too`() {
        val l = layout(guest = false)
        assertEquals(Hit.Outside, PortalSheet.hit(l, 100f, 300f, 0f, 0))
        val c = l.slots[0]
        assertEquals(Hit.Outside, PortalSheet.hit(l, c.right + 6f, c.centerY, 0f, 0))
    }

    // -- the strip and its slider --------------------------------------------------------

    @Test fun `the slider is there with any guest, under the panel until the sheet scrolls`() {
        assertNull(layout(guest = null).slider)
        assertNotNull(layout(guest = false).slider)
        val s = layout(guest = true).slider!!
        assertTrue(s.icon.right < s.track.left - PortalSheet.THUMB / 2)
        assertTrue(s.track.right + PortalSheet.THUMB / 2 < s.value.left)
    }

    @Test fun `scrolled to the slider, its row is the slider's, and what has gone up is not hit`() {
        val l = layout(guest = true)
        val s = l.slider!!
        // On the screen: the row's content y less the scroll.
        fun y(b: PortalSheet.Box) = b.centerY - STRIP
        assertEquals(Hit.Slider, PortalSheet.hit(l, s.track.centerX, y(s.track), STRIP, 0))
        assertEquals(Hit.Slider, PortalSheet.hit(l, s.track.left - PortalSheet.THUMB / 2 + 1f, FULL - 4f, STRIP, 0))
        assertEquals(Hit.SliderValue, PortalSheet.hit(l, s.value.centerX, y(s.value), STRIP, 0))
        assertEquals(Hit.Blank, PortalSheet.hit(l, s.icon.centerX, y(s.icon), STRIP, 0))
        // The grid moved up with it: a cell is hit where it now stands.
        val c = l.slots[4]
        assertEquals(Hit.App(4), PortalSheet.hit(l, c.centerX, c.centerY - STRIP, STRIP, 0))
    }

    @Test fun `not scrolled, the bottom of the panel is the grid's, not the slider's`() {
        val l = layout(30, guest = true)
        val h = PortalSheet.hit(l, l.slider!!.track.centerX, FULL - 4f, 0f, 0)
        assertTrue(h != Hit.Slider && h != Hit.SliderValue)
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

    @Test fun `a finger within half a thumb of the thumb is on it`() {
        val t = layout(guest = true).slider!!.track
        val x = PortalSheet.sliderX(t, 400)
        assertTrue(PortalSheet.onThumb(t, 400, x))
        assertTrue(PortalSheet.onThumb(t, 400, x + PortalSheet.THUMB / 2))
        assertTrue(PortalSheet.onThumb(t, 400, x - 5f))
        assertTrue(!PortalSheet.onThumb(t, 400, x + PortalSheet.THUMB / 2 + 1f))
        // An off-step value stands where its step does.
        assertTrue(PortalSheet.onThumb(t, 455, PortalSheet.sliderX(t, 460)))
    }

    @Test fun `the value says what is dragged to, else the app's own, else Default`() {
        assertEquals("380 dpi", PortalSheet.sliderLabel(380, 300))
        assertEquals("300 dpi", PortalSheet.sliderLabel(null, 300))
        assertEquals("Default", PortalSheet.sliderLabel(null, null))
    }

    // -- which way a sideways drag goes ---------------------------------------------------

    @Test fun `a sideways drag on the slider's row moves the slider, anywhere above it turns the pages`() {
        val l = layout(30, guest = true)
        assertEquals(Swipe.SLIDER, PortalSheet.swipe(l, FULL - 20f, STRIP))
        assertEquals(Swipe.PAGES, PortalSheet.swipe(l, FULL - 20f, 0f))
        assertEquals(Swipe.PAGES, PortalSheet.swipe(l, 200f, STRIP))
        assertEquals(Swipe.NONE, PortalSheet.swipe(layout(12), 200f, 0f))
        assertEquals(Swipe.SLIDER, PortalSheet.swipe(layout(12, guest = false), FULL - 20f, STRIP))
    }

    // -- motion ----------------------------------------------------------------------------

    @Test fun `the contents slide up into place as the control centre's come down`() {
        assertEquals(Arrival.offset(0.7f, FULL), PortalSheet.arrivalShift(0.7f), 0f)
        assertEquals(0f, PortalSheet.arrivalShift(1f), 0f)
        assertTrue(PortalSheet.arrivalShift(0.6f) > PortalSheet.arrivalShift(0.8f))
    }

    @Test fun `the top edge fades only as the contents scroll`() {
        assertEquals(0f, PortalSheet.topFade(0f), 0f)
        assertEquals(0.5f, PortalSheet.topFade(STRIP / 2), 1e-5f)
        assertEquals(1f, PortalSheet.topFade(STRIP), 0f)
    }

    @Test fun `pages fade out past the grid's sides, the right band ending at the panel's edge`() {
        val l = layout(30)
        assertEquals(l.slots.minOf { it.left } - EdgeFade.BAND, l.pagesLeft, 1f)
        assertEquals(Panel.WIDTH.toFloat(), PortalSheet.PAGES_RIGHT, 0.01f)
        // At rest no app is in a band.
        l.slots.forEach { assertTrue(it.left >= l.pagesLeft + EdgeFade.BAND - 0.01f && it.right <= PortalSheet.PAGES_RIGHT - EdgeFade.BAND + 0.01f) }
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


    // -- another rear ------------------------------------------------------------------------

    @Test fun `on a Pro Max the contents start where its lenses end, and still stop short of Xiaomi's Back strip`() {
        for (grid in PortalGrid.entries) for (guest in guests) {
            val l = layout(48, guest, grid, lensRight = proMax)
            val slider = listOfNotNull(l.slider?.icon, l.slider?.track, l.slider?.value)
            (l.slots + l.buttons + l.dots + slider).forEach {
                assertTrue("$grid $guest $it", it.left >= proMax && it.right < EdgeGestures.BACK_LEFT)
            }
            // The same gap from the lenses as on the 17 Pro, so further left.
            assertEquals(layout(48, guest, grid).left - (pro - proMax), l.left, 0.01f)
            assertTrue(l.buttons.isEmpty() || l.buttons.first().left < layout(48, guest, grid).buttons.first().left)
        }
    }

    @Test fun `a page is the width right of the lenses across, on either rear`() {
        assertEquals(608f, layout().pageStride, 0.01f)
        assertEquals(Panel.WIDTH - proMax, layout(lensRight = proMax).pageStride, 0.01f)
        assertEquals(layout(30, lensRight = proMax).slots.minOf { it.left } - EdgeFade.BAND, layout(30, lensRight = proMax).pagesLeft, 1f)
    }
}
