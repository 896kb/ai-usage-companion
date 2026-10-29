package dev.kb896.aiusage.ui

import dev.kb896.aiusage.data.PanelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalGuideTest {

    private val fresh = PanelConfig()

    @Test fun `a fresh config has every step to do and the card up, led by pinning`() {
        val s = PortalGuide.of(fresh)
        assertFalse(s.pinned); assertFalse(s.tile); assertFalse(s.gestures)
        assertTrue(s.card)
        assertFalse(s.complete)
        assertEquals(PortalGuide.Step.PIN, s.next)
        assertEquals(listOf(false, false, false), PortalGuide.Step.entries.map { s.done(it) })
    }

    @Test fun `pinning one app ticks the first step`() {
        val s = PortalGuide.of(fresh.copy(portalApps = listOf("org.mozilla.firefox")))
        assertTrue(s.done(PortalGuide.Step.PIN))
        assertEquals(PortalGuide.Step.TILE, s.next)
        assertTrue(s.card)
    }

    @Test fun `the tile and the sheet tick theirs, whatever order they come in`() {
        val s = PortalGuide.of(fresh.copy(portalSheetSeen = true))
        assertTrue(s.done(PortalGuide.Step.GESTURES))
        assertFalse(s.done(PortalGuide.Step.TILE))
        assertEquals(PortalGuide.Step.PIN, s.next)
        val t = PortalGuide.of(fresh.copy(portalApps = listOf("a.b"), portalSheetSeen = true, portalTileAdded = true))
        assertTrue(t.done(PortalGuide.Step.TILE))
        assertNull(t.next)
    }

    @Test fun `all three ticked, the card gives way to the row`() {
        val s = PortalGuide.of(fresh.copy(portalApps = listOf("a.b"), portalTileAdded = true, portalSheetSeen = true))
        assertTrue(s.complete)
        assertFalse(s.card)
    }

    @Test fun `dismissed early, the card is down with steps still to do`() {
        val s = PortalGuide.of(fresh.copy(portalGuideDone = true))
        assertFalse(s.complete)
        assertFalse(s.card)
        assertEquals(PortalGuide.Step.PIN, s.next)
    }

    @Test fun `once down it stays down, even when a step comes undone`() {
        // Finished, then the tile taken out of the panel again: no card coming back.
        val c = fresh.copy(portalApps = listOf("a.b"), portalTileAdded = false, portalSheetSeen = true, portalGuideDone = true)
        assertFalse(PortalGuide.of(c).card)
    }

    @Test fun `settled marks a finished guide done for good, and leaves anything else as it is`() {
        val finished = fresh.copy(portalApps = listOf("a.b"), portalTileAdded = true, portalSheetSeen = true)
        assertTrue(PortalGuide.settled(finished).portalGuideDone)
        assertEquals(finished.copy(portalGuideDone = true), PortalGuide.settled(finished))
        val half = fresh.copy(portalApps = listOf("a.b"))
        assertTrue(PortalGuide.settled(half) === half)
        val done = finished.copy(portalGuideDone = true)
        assertTrue(PortalGuide.settled(done) === done)
    }

    @Test fun `the add-tile answers - added or already there is in, refused is out, an error is unknown`() {
        // StatusBarManager's values, pinned: TILE_ADD_REQUEST_RESULT_* and _ERROR_*.
        assertEquals(true, PortalGuide.tileAnswer(2))
        assertEquals(true, PortalGuide.tileAnswer(1))
        assertEquals(false, PortalGuide.tileAnswer(0))
        for (error in 1000..1005) assertNull(PortalGuide.tileAnswer(error))
    }
}
