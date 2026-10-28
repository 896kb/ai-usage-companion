package dev.kb896.aiusage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PanelConfigDpiTest {

    @Test fun `a config saved before per-app DPI has no entries`() {
        val old = PanelConfig.encode(PanelConfig())
            .replace(Regex(""","portalDpi":\{[^}]*\}"""), "")
        assertFalse("portalDpi" in old)
        assertEquals(emptyMap<String, Int>(), PanelConfig.decode(old).portalDpi)
    }

    @Test fun `DPI entries keep their values through a save`() {
        val c = PanelConfig(portalDpi = mapOf("org.mozilla.firefox" to 320, "com.reddit.frontpage" to 280))
        assertEquals(c.portalDpi, PanelConfig.decode(PanelConfig.encode(c)).portalDpi)
    }
}
