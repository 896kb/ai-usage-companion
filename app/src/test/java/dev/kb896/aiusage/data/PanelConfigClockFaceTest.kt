package dev.kb896.aiusage.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelConfigClockFaceTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `an old config keeps the digital clock`() {
        val old = json.decodeFromString(PanelConfig.serializer(), "{}")
        assertEquals(PanelConfig.FACE_DIGITAL, old.clockFace)
        assertFalse(old.analogClock)
    }

    @Test fun `analog round-trips through the saved file`() {
        val saved = json.encodeToString(PanelConfig.serializer(), PanelConfig(clockFace = PanelConfig.FACE_ANALOG))
        assertTrue(json.decodeFromString(PanelConfig.serializer(), saved).analogClock)
    }

    @Test fun `an unknown face reads as digital`() {
        assertFalse(PanelConfig(clockFace = "sundial").analogClock)
    }

    @Test fun `always-on shows the clock page's face until given its own`() {
        assertFalse(PanelConfig().aodAnalogClock)
        assertTrue(PanelConfig(clockFace = PanelConfig.FACE_ANALOG).aodAnalogClock)
        assertFalse(PanelConfig(clockFace = PanelConfig.FACE_ANALOG, aodClockFace = PanelConfig.FACE_DIGITAL).aodAnalogClock)
        assertTrue(PanelConfig(aodClockFace = PanelConfig.FACE_ANALOG).aodAnalogClock)
        val saved = json.encodeToString(PanelConfig.serializer(), PanelConfig(aodClockFace = PanelConfig.FACE_ANALOG))
        assertTrue(json.decodeFromString(PanelConfig.serializer(), saved).aodAnalogClock)
        assertEquals(null, json.decodeFromString(PanelConfig.serializer(), "{}").aodClockFace)
    }

    @Test fun `the digital clock lies across until stood on end`() {
        assertFalse(json.decodeFromString(PanelConfig.serializer(), "{}").clockVertical)
        val saved = json.encodeToString(PanelConfig.serializer(), PanelConfig(clockVertical = true))
        assertTrue(json.decodeFromString(PanelConfig.serializer(), saved).clockVertical)
    }

    @Test fun `twelve or twenty-four hours follows the phone until picked`() {
        val old = json.decodeFromString(PanelConfig.serializer(), "{}")
        assertEquals(null, old.clock12h)
        assertTrue(old.twelveHour(phoneIs24h = false))
        assertFalse(old.twelveHour(phoneIs24h = true))
        assertFalse(PanelConfig(clock12h = false).twelveHour(phoneIs24h = false))
        assertTrue(PanelConfig(clock12h = true).twelveHour(phoneIs24h = true))
    }

    @Test fun `the clock's glass keeps its tint until made clearer`() {
        assertEquals(PanelConfig.CLOCK_GLASS_TINT, json.decodeFromString(PanelConfig.serializer(), "{}").clockGlassTint, 0.0001f)
        val saved = json.encodeToString(PanelConfig.serializer(), PanelConfig(clockGlassTint = 0.25f))
        assertEquals(0.25f, json.decodeFromString(PanelConfig.serializer(), saved).clockGlassTint, 0.0001f)
    }
}
