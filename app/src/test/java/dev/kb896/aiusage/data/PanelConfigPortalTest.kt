package dev.kb896.aiusage.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PanelConfigPortalTest {

    @Test fun `a config saved before the portal has nothing pinned and the green frame`() {
        val old = PanelConfig.encode(PanelConfig())
            .replace(Regex(""","portalApps":\[[^\]]*\]"""), "")
            .replace(Regex(""","portalFrameColor":-?\d+"""), "")
        val c = PanelConfig.decode(old)
        assertEquals(emptyList<String>(), c.portalApps)
        assertEquals(0xFF28FF85.toInt(), c.portalFrameColor)
    }

    @Test fun `pinned apps keep their order through a save`() {
        val c = PanelConfig(portalApps = listOf("org.mozilla.firefox", "com.reddit.frontpage"))
        assertEquals(c.portalApps, PanelConfig.decode(PanelConfig.encode(c)).portalApps)
    }
}
