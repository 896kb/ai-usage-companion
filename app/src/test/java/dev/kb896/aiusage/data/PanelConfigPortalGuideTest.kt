package dev.kb896.aiusage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelConfigPortalGuideTest {

    @Test fun `a config saved before the guide has none of its flags set`() {
        val old = PanelConfig.encode(PanelConfig())
            .replace(Regex(""","portal(TileAdded|SheetSeen|GuideDone)":(true|false)"""), "")
        assertFalse("portalTileAdded" in old || "portalSheetSeen" in old || "portalGuideDone" in old)
        val c = PanelConfig.decode(old)
        assertFalse(c.portalTileAdded)
        assertFalse(c.portalSheetSeen)
        assertFalse(c.portalGuideDone)
    }

    @Test fun `the flags keep through a save`() {
        val c = PanelConfig(portalTileAdded = true, portalSheetSeen = true, portalGuideDone = true)
        val read = PanelConfig.decode(PanelConfig.encode(c))
        assertTrue(read.portalTileAdded)
        assertTrue(read.portalSheetSeen)
        assertTrue(read.portalGuideDone)
    }

    @Test fun `a flag in a shape this build cannot read is off, and the rest is kept`() {
        val c = PanelConfig(title = "MINE", portalSheetSeen = true)
        val odd = PanelConfig.encode(c).replace("\"portalSheetSeen\":true", "\"portalSheetSeen\":null")
        val read = PanelConfig.decode(odd)
        assertFalse(read.portalSheetSeen)
        assertEquals("MINE", read.title)
    }
}
