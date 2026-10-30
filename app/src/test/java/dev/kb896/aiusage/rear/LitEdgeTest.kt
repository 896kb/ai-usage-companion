package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LitEdgeTest {

    @Test fun `the first report is an edge either way`() {
        assertEquals(true, LitEdge().next(true))
        assertEquals(false, LitEdge().next(false))
    }

    @Test fun `a lit display changing a property is no edge - the refresh rate dropping to 60 Hz mid-dim`() {
        val edge = LitEdge()
        edge.next(true)
        assertNull(edge.next(true))
        assertNull(edge.next(true))
    }

    @Test fun `dark then lit again is`() {
        val edge = LitEdge()
        edge.next(true)
        assertEquals(false, edge.next(false))
        assertNull(edge.next(false))
        assertEquals(true, edge.next(true))
    }

    @Test fun `seeded lit, a new panel's first change is no wake`() {
        val edge = LitEdge()
        edge.seed(true)
        assertNull(edge.next(true))
        assertEquals(false, edge.next(false))
    }

    @Test fun `seeded dark, the display coming on is a wake`() {
        val edge = LitEdge()
        edge.seed(false)
        assertEquals(true, edge.next(true))
    }

    @Test fun `seeded once - a later resume cannot swallow a real edge`() {
        val edge = LitEdge()
        edge.seed(true)
        edge.next(false)
        edge.seed(true) // a resume while it went dark: changes nothing
        assertEquals(true, edge.next(true))
    }

    @Test fun `a seed after a report changes nothing`() {
        val edge = LitEdge()
        edge.next(false)
        edge.seed(true)
        assertEquals(true, edge.next(true))
    }
}
