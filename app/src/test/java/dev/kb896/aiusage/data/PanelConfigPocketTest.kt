package dev.kb896.aiusage.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pocket mode left the always-on tab for General and works whenever the back is lit, so
 * the field is pocketMode now — stored under the key it always had, aodPocket, so a
 * switch somebody had already turned on is still on after the update.
 */
class PanelConfigPocketTest {
    private val lenient = Json { ignoreUnknownKeys = true }

    @Test fun `off by default, and a config from before it decodes off`() {
        assertFalse(PanelConfig().pocketMode)
        assertFalse(lenient.decodeFromString(PanelConfig.serializer(), "{}").pocketMode)
    }

    @Test fun `an aodPocket already on stays on`() {
        assertTrue(lenient.decodeFromString(PanelConfig.serializer(), """{"aodPocket":true}""").pocketMode)
        assertTrue(PanelConfig.decode("""{"aodPocket":true}""").pocketMode)
    }

    @Test fun `written under the same key, read back the same`() {
        val raw = PanelConfig.encode(PanelConfig(pocketMode = true))
        assertTrue(raw.contains("\"aodPocket\":true"))
        assertEquals(true, PanelConfig.decode(raw).pocketMode)
    }
}
