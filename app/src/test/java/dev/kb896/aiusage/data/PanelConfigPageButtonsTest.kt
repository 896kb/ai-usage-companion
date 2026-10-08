package dev.kb896.aiusage.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The camera and the game per page, and the clock in always-on. */
class PanelConfigPageButtonsTest {
    private val old = Json { ignoreUnknownKeys = true }.decodeFromString(PanelConfig.serializer(), "{}")

    @Test fun `the clock page's camera and game start off, also in a config written before them`() {
        assertFalse(PanelConfig().showCameraInfo)
        assertFalse(PanelConfig().showCloseInfo)
        assertFalse(old.showCameraInfo)
        assertFalse(old.showCloseInfo)
    }

    @Test fun `the clock page's colours start from the usage page's`() {
        val c = PanelConfig(cameraColor = 0xFF112233.toInt(), closeColor = 0xFF445566.toInt())
        assertEquals(c.cameraTint, c.cameraInfoTint)
        assertEquals(c.closeColor, c.closeInfoTint)
        val own = c.copy(cameraInfoColor = 0xFF778899.toInt(), closeInfoColor = 0xFFAABBCC.toInt())
        assertEquals(0xFF778899.toInt(), own.cameraInfoTint)
        assertEquals(0xFFAABBCC.toInt(), own.closeInfoTint)
    }

    @Test fun `the clock shows in always-on unless switched off`() {
        assertTrue(PanelConfig().aodClock)
        assertTrue(old.aodClock)
    }
}
