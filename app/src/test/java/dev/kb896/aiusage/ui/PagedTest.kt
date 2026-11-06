package dev.kb896.aiusage.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PagedTest {
    @Test fun `hands out ten at a time, the last page what is left`() {
        val p = Paged(23)
        assertEquals(0..9, p.next())
        assertEquals(10..19, p.next())
        assertTrue(p.hasMore)
        assertEquals(20..22, p.next())
        assertFalse(p.hasMore)
        assertNull(p.next())
    }

    @Test fun `an exact page leaves nothing, an empty list has nothing`() {
        val p = Paged(10)
        assertEquals(0..9, p.next())
        assertNull(p.next())
        assertFalse(Paged(0).hasMore)
        assertNull(Paged(0).next())
    }

    @Test fun `near the end of the content there is more to load, far from it not`() {
        assertTrue(LazyRows.nearEnd(contentHeight = 1000, viewHeight = 600, scrollY = 150, margin = 300))
        assertFalse(LazyRows.nearEnd(contentHeight = 2000, viewHeight = 600, scrollY = 100, margin = 300))
    }

    @Test fun `content shorter than the view is always near its end`() {
        assertTrue(LazyRows.nearEnd(contentHeight = 200, viewHeight = 600, scrollY = 0, margin = 300))
    }

    @Test fun `the room kept is the rows to come at the loaded rows' average`() {
        assertEquals(1300, LazyRows.fillerHeight(remaining = 13, loaded = 10, loadedHeight = 1000))
        assertEquals(0, LazyRows.fillerHeight(remaining = 0, loaded = 23, loadedHeight = 2300))
        assertEquals(0, LazyRows.fillerHeight(remaining = 13, loaded = 0, loadedHeight = 0))
    }

    @Test fun `a row taken back comes again with the next page`() {
        val p = Paged(23)
        p.next()
        p.unload()
        assertEquals(9..18, p.next())
    }
}
