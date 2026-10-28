package dev.kb896.aiusage.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PortalAppsTest {

    @Test fun `pinning adds at the end and unpinning removes`() {
        val one = PortalApps.toggled(emptyList(), "a")
        assertEquals(listOf("a"), one)
        assertEquals(listOf("a", "b"), PortalApps.toggled(one, "b"))
        assertEquals(listOf("b"), PortalApps.toggled(listOf("a", "b"), "a"))
    }

    @Test fun `unpinning drops every copy`() {
        assertEquals(listOf("b"), PortalApps.toggled(listOf("a", "b", "a"), "a"))
    }

    @Test fun `moving up swaps with the one above, and the first stays first`() {
        assertEquals(listOf("b", "a", "c"), PortalApps.movedUp(listOf("a", "b", "c"), "b"))
        assertEquals(listOf("a", "b"), PortalApps.movedUp(listOf("a", "b"), "a"))
        assertEquals(listOf("a", "b"), PortalApps.movedUp(listOf("a", "b"), "z"))
    }

    @Test fun `only installed apps are shown, once each, in pinned order`() {
        assertEquals(listOf("c", "a"), PortalApps.present(listOf("c", "gone", "a", "c"), setOf("a", "c")))
    }
}
