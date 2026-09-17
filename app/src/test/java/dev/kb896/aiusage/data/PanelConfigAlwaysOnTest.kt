package dev.kb896.aiusage.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PanelConfigAlwaysOnTest {
    @Test
    fun `always-on is off by default, and a config written before it still decodes`() {
        assertFalse(PanelConfig().alwaysOn)
        val old = Json { ignoreUnknownKeys = true }.decodeFromString(PanelConfig.serializer(), "{}")
        assertFalse(old.alwaysOn)
        val on = Json.decodeFromString(PanelConfig.serializer(), Json.encodeToString(PanelConfig.serializer(), PanelConfig(alwaysOn = true)))
        assertEquals(true, on.alwaysOn)
    }
}
