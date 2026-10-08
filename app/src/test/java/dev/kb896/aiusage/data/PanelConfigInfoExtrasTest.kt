package dev.kb896.aiusage.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clock page's time, date and battery, apart from the usage page's corner row. Until
 * set they behave as the shared switches did: the big clock always, the date line by the
 * Date switch, the battery over the clock only with no readout and the Battery switch on.
 */
class PanelConfigInfoExtrasTest {
    private val old = Json { ignoreUnknownKeys = true }.decodeFromString(PanelConfig.serializer(), "{}")

    @Test fun `the big clock shows until switched off`() {
        assertTrue(old.infoClockOn)
        assertFalse(old.copy(infoClock = false).infoClockOn)
    }

    @Test fun `the date line follows the corner row's Date switch until set`() {
        assertTrue(PanelConfig(showDate = true).infoDateOn)
        assertFalse(PanelConfig(showDate = true, infoDate = false).infoDateOn)
        assertTrue(PanelConfig(showDate = false, infoDate = true).infoDateOn)
    }

    @Test fun `the battery over the clock is the no-readout one until set`() {
        assertTrue(PanelConfig(showBattery = true).infoBatteryOn(noReadout = true))
        assertFalse(PanelConfig(showBattery = true).infoBatteryOn(noReadout = false))
        assertTrue(PanelConfig(showBattery = false, infoBattery = true).infoBatteryOn(noReadout = false))
        assertFalse(PanelConfig(showBattery = true, infoBattery = false).infoBatteryOn(noReadout = true))
    }

    @Test fun `the clock page's colours start from the corner row's`() {
        val c = PanelConfig(clockColor = 1, dateColor = 2, batteryColor = 3)
        assertEquals(c.clockTint, c.infoClockTint)
        assertEquals(c.dateTint, c.infoDateTint)
        assertEquals(c.batteryTint, c.infoBatteryTint)
        val own = c.copy(infoClockColor = 4, infoDateColor = 5, infoBatteryColor = 6)
        assertEquals(listOf(4, 5, 6), listOf(own.infoClockTint, own.infoDateTint, own.infoBatteryTint))
    }

    @Test fun `the weather line takes the clock page's date colour`() {
        assertEquals(5, PanelConfig(dateColor = 2, infoDateColor = 5).weatherTint)
    }
}
