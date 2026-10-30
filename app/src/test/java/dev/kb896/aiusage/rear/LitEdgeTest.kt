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
}
