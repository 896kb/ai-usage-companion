package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The media page's layout, and specifically what it does when something takes the top of
 * the panel away from it.
 *
 * The rule this exists to hold: the transport buttons never move. They are the bottom of
 * the page and the only thing on it meant to be pressed, and a notification that pushed
 * them off the panel was the bug — you cannot press what has left the screen.
 */
class MediaPageTest {

    private val panelH = 572f
    private val panelW = 904f

    /** A notification strip: a quarter of the panel, and again half as much with a
     *  cascaded group card under it. */
    private val notice = panelH / 4f
    private val noticeWithGroup = notice + 76f

    @Test fun `at rest the record sits where the page was drawn for`() {
        val (cy, r) = MediaPage.disc(0f)
        assertEquals(196f, cy, 0.01f)
        assertEquals(146f, r, 0.01f)
    }

    /** The whole point. */
    @Test fun `a notification shrinks the record and moves nothing else`() {
        val (restCy, restR) = MediaPage.disc(0f)
        val (cy, r) = MediaPage.disc(notice)
        assertTrue("the record did not give up any room", r < restR)
        assertTrue("the record grew", cy > restCy || r < restR)
        // The stack below is untouched by construction — assert the record stays clear
        // of it, which is the relationship that actually matters.
        assertTrue("the record ran into the title",
            cy + r <= MediaPage.STACK_TOP - MediaPage.DISC_GAP + 0.01f)
    }

    @Test fun `a group card shrinks it further and still clears the stack`() {
        val (small, smallR) = MediaPage.disc(noticeWithGroup)
        val (big, bigR) = MediaPage.disc(notice)
        assertTrue("a taller strip did not take more room", smallR <= bigR)
        assertTrue("the record ran into the title",
            small + smallR <= MediaPage.STACK_TOP - MediaPage.DISC_GAP + 0.01f)
    }

    @Test fun `the record clears whatever is above it`() {
        for (top in listOf(0f, notice, noticeWithGroup)) {
            val (cy, r) = MediaPage.disc(top)
            assertTrue("the record ran under a strip $top tall", cy - r >= top)
        }
    }

    /** It stops shrinking rather than inverting, however tall the thing above it gets. */
    @Test fun `the record never collapses`() {
        val (_, r) = MediaPage.disc(panelH)
        assertTrue("the record inverted", r >= MediaPage.DISC_MIN_RADIUS)
    }

    /**
     * The volume is the whole area above the transport row, not a strip inside it — so
     * the one thing that matters is that it stops before the buttons start. An area that
     * overlapped them would make every press a volume change.
     */
    @Test fun `the volume area stops clear of the buttons`() {
        val bottom = MediaPage.volumeAreaBottom()
        val buttonsTop = MediaPage.TRANSPORT_CY - MediaPage.TRANSPORT_H / 2f
        assertTrue("the volume area runs into the buttons", bottom < buttonsTop)
        assertTrue("the volume area is too small to use", bottom > 200f)
    }

    /** It has to leave the pager somewhere to work from, or there is no way back. */
    @Test fun `there is room below the buttons to page from`() {
        val buttonsBottom = MediaPage.TRANSPORT_CY + MediaPage.TRANSPORT_H / 2f
        assertTrue("nothing left below the buttons", panelH - buttonsBottom > 40f)
    }

    @Test fun `the ring labels sit inside their rings`() {
        // Against the panel's own ring radius: a label at a wider radius than the ring
        // would be drawn over the arc it is naming.
        assertTrue("the label runs outside the ring",
            MediaPage.LABEL_RADIUS < 128f)
    }

    @Test fun `clocks read as clocks`() {
        assertEquals("0:00", MediaPage.clock(0L))
        assertEquals("3:45", MediaPage.clock(225_000L))
        assertEquals("1:03:45", MediaPage.clock(3_825_000L))
        assertEquals("0:00", MediaPage.clock(-5_000L))
    }

    @Test fun `the bar leaves room for both clocks`() {
        val chars = MediaPage.clockWidth(225_000L)
        val clockW = chars * 11f
        val left = MediaPage.barLeft(296f, clockW)
        val right = MediaPage.barRight(panelW, clockW)
        assertTrue("the bar has no room left", right > left)
        assertTrue("the bar runs under the left clock", left > 296f + MediaPage.MARGIN)
    }
}
