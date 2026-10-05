package dev.kb896.aiusage.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PanelConfigBackdropTest {

    @Test fun `a picture saved before opacity existed is whole`() {
        val old = """{"backdrop":{"imagePath":"/x/bg.png","panX":0.1,"panY":0.2,"zoom":1.5}}"""
        assertEquals(1f, PanelConfig.decode(old).backdrop.opacity)
    }

    @Test fun `opacity and the colour behind round trip`() {
        val config = PanelConfig(
            background = 0xFF203040.toInt(),
            backdrop = ArtConfig(imagePath = "/x/bg.png", opacity = 0.4f),
        )
        val back = PanelConfig.decode(PanelConfig.encode(config))
        assertEquals(0.4f, back.backdrop.opacity)
        assertEquals(0xFF203040.toInt(), back.background)
    }
}
