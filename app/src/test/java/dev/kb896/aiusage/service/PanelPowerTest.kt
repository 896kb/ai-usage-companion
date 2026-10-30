package dev.kb896.aiusage.service

import dev.kb896.aiusage.service.PanelPower.Change
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rear display's power, as the poll service's display listener follows it. The listener
 * hears every property change — HyperOS drops the rear from 120 to 60 Hz three seconds
 * after the last touch — and only a real dark-to-lit is a wake.
 */
class PanelPowerTest {

    @Test fun `seeded lit, a property change of a lit back is no wake`() {
        // The bug: seeded dark after every service start, the 120-to-60 Hz switch of a back
        // that was lit all along read as a wake, and cleared the dozing flag mid-dim.
        val p = PanelPower(on = true)
        assertEquals(Change.NONE, p.see(on = true))
    }

    @Test fun `dark to lit is a wake, once`() {
        val p = PanelPower(on = false)
        assertEquals(Change.WOKE, p.see(on = true))
        assertEquals(Change.NONE, p.see(on = true))
    }

    @Test fun `lit to dark is the back going dark, once`() {
        val p = PanelPower(on = true)
        assertEquals(Change.WENT_DARK, p.see(on = false))
        assertEquals(Change.NONE, p.see(on = false))
    }
}
