package dev.kb896.aiusage.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PortalAppsTest {

    @Test fun `pinning adds at the end and unpinning removes`() {
        val one = PortalApps.pinned(emptyList(), "a", on = true)
        assertEquals(listOf("a"), one)
        assertEquals(listOf("a", "b"), PortalApps.pinned(one, "b", on = true))
        assertEquals(listOf("b"), PortalApps.pinned(listOf("a", "b"), "a", on = false))
    }

    @Test fun `unpinning drops every copy`() {
        assertEquals(listOf("b"), PortalApps.pinned(listOf("a", "b", "a"), "a", on = false))
    }

    @Test fun `moving up swaps with the one above, and the first stays first`() {
        assertEquals(listOf("b", "a", "c"), PortalApps.movedUp(listOf("a", "b", "c"), "b"))
        assertEquals(listOf("a", "b"), PortalApps.movedUp(listOf("a", "b"), "a"))
        assertEquals(listOf("a", "b"), PortalApps.movedUp(listOf("a", "b"), "z"))
    }

    @Test fun `only installed apps are shown, once each, in pinned order`() {
        assertEquals(listOf("c", "a"), PortalApps.present(listOf("c", "gone", "a", "c"), setOf("a", "c")))
    }

    @Test fun `pinning adds at the end, unpinning removes, either twice changes nothing`() {
        assertEquals(listOf("a", "b"), PortalApps.pinned(listOf("a"), "b", on = true))
        assertEquals(listOf("a", "b"), PortalApps.pinned(listOf("a", "b"), "b", on = true))
        assertEquals(listOf("b"), PortalApps.pinned(listOf("a", "b"), "a", on = false))
        assertEquals(listOf("b"), PortalApps.pinned(listOf("b"), "a", on = false))
    }

    @Test fun `no more than MAX can be pinned, and unpinning still works when full`() {
        val full = (1..PortalApps.MAX).map { "p$it" }
        assertEquals(full, PortalApps.pinned(full, "new", on = true))
        assertEquals(PortalApps.MAX - 1, PortalApps.pinned(full, "p3", on = false).size)
        assertEquals(listOf("a", "b"), PortalApps.pinned(listOf("a", "b"), "c", on = true, max = 2))
    }
}
