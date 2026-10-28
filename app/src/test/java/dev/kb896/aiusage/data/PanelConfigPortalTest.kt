package dev.kb896.aiusage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelConfigPortalTest {

    @Test fun `a config saved before the portal has nothing pinned and the green frame`() {
        val old = PanelConfig.encode(PanelConfig())
            .replace(Regex(""","portalApps":\[[^\]]*\]"""), "")
            .replace(Regex(""","portalFrameColor":-?\d+"""), "")
        assertFalse("portalApps" in old)
        assertFalse("portalFrameColor" in old)
        val c = PanelConfig.decode(old)
        assertEquals(emptyList<String>(), c.portalApps)
        assertEquals(0xFF28FF85.toInt(), c.portalFrameColor)
    }

    @Test fun `pinned apps keep their order through a save`() {
        val c = PanelConfig(portalApps = listOf("org.mozilla.firefox", "com.reddit.frontpage"))
        assertEquals(c.portalApps, PanelConfig.decode(PanelConfig.encode(c)).portalApps)
    }

    @Test fun `a config saved before the grid choice has three rows of four`() {
        val old = PanelConfig.encode(PanelConfig()).replace(Regex(""","portalGrid":"[A-Z0-9]+""""), "")
        assertFalse("portalGrid" in old)
        assertEquals(PortalGrid.R3C4, PanelConfig.decode(old).portalGrid)
    }

    @Test fun `the grid keeps its choice through a save`() {
        for (g in PortalGrid.entries) {
            assertEquals(g, PanelConfig.decode(PanelConfig.encode(PanelConfig(portalGrid = g))).portalGrid)
        }
    }

    @Test fun `each grid says its rows and columns, and holds their product`() {
        assertEquals(listOf("3 × 4", "3 × 3", "2 × 3", "2 × 2"), PortalGrid.entries.map { it.label })
        assertEquals(listOf(12, 9, 6, 4), PortalGrid.entries.map { it.perPage })
    }

    @Test fun `a grid this build does not know is three rows of four, and the rest of the config is kept`() {
        val c = PanelConfig(title = "MINE", portalApps = listOf("a.b"), portalGrid = PortalGrid.R2C2)
        val newer = PanelConfig.encode(c).replace("\"portalGrid\":\"R2C2\"", "\"portalGrid\":\"R9C9\"")
        assertTrue("R9C9" in newer)
        val read = PanelConfig.decode(newer)
        assertEquals(PortalGrid.R3C4, read.portalGrid)
        assertEquals("MINE", read.title)
        assertEquals(listOf("a.b"), read.portalApps)
    }

    @Test fun `a config saved before the border and names switches shows both`() {
        val old = PanelConfig.encode(PanelConfig())
            .replace(Regex(""","portalBorder":(true|false)"""), "")
            .replace(Regex(""","portalAppNames":(true|false)"""), "")
        assertFalse("portalBorder" in old)
        assertFalse("portalAppNames" in old)
        val c = PanelConfig.decode(old)
        assertTrue(c.portalBorder)
        assertTrue(c.portalAppNames)
    }

    @Test fun `the border and names switches keep their choice through a save`() {
        val c = PanelConfig(portalBorder = false, portalAppNames = false)
        val read = PanelConfig.decode(PanelConfig.encode(c))
        assertFalse(read.portalBorder)
        assertFalse(read.portalAppNames)
    }
}
