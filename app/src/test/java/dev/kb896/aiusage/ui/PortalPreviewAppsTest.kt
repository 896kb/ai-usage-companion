package dev.kb896.aiusage.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PortalPreviewAppsTest {

    @Test fun `everything is missing at first, in order`() {
        val apps = PortalPreviewApps<String>()
        assertEquals(listOf("b", "a"), apps.missing(listOf("b", "a")))
    }

    @Test fun `only what is new is missing after a load`() {
        val apps = PortalPreviewApps<String>()
        apps.put("a", "Alpha", "A")
        apps.put("b", "Beta", null)
        assertEquals(listOf("c"), apps.missing(listOf("b", "c", "a")))
    }

    @Test fun `arranged follows the order asked for, a reorder loading nothing`() {
        val apps = PortalPreviewApps<String>()
        apps.put("a", "Alpha", "A")
        apps.put("b", "Beta", "B")
        assertEquals(listOf("Beta", "Alpha") to listOf("B", "A"), apps.arranged(listOf("b", "a")))
        assertEquals(emptyList<String>(), apps.missing(listOf("b", "a")))
    }

    @Test fun `an app not loaded yet goes by its package, without an icon`() {
        val apps = PortalPreviewApps<String>()
        apps.put("a", "Alpha", null)
        assertEquals(listOf("Alpha", "x.y") to listOf(null, null), apps.arranged(listOf("a", "x.y")))
    }

    @Test fun `an unpinned app is forgotten, and loaded again if pinned again`() {
        val apps = PortalPreviewApps<String>()
        apps.put("a", "Alpha", "A")
        apps.put("b", "Beta", "B")
        apps.arranged(listOf("b"))
        assertEquals(listOf("a"), apps.missing(listOf("a", "b")))
    }

    @Test fun `nothing pinned arranges to nothing`() {
        val apps = PortalPreviewApps<String>()
        apps.put("a", "Alpha", "A")
        assertEquals(emptyList<String>() to emptyList<String?>(), apps.arranged(emptyList()))
    }
}
