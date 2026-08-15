package dev.rgarjoaba.claudestatus.service

import org.junit.Assert.assertEquals
import org.junit.Test

class CadenceTest {

    /**
     * One interval regardless of panel state. The old slow interval keyed on the REAR
     * display's power, which MIUI cuts whenever the phone idles — so the app sat on a
     * fifteen-minute cadence most of the time. The usage trail feeds the mascot's burst
     * rule and the limit notifications too, and both are wanted while the phone is in a
     * pocket.
     */
    @Test fun `the cadence does not depend on the panel`() {
        assertEquals(Cadence.FAST_MS, Cadence.forPanelState(panelOn = true))
        assertEquals(Cadence.FAST_MS, Cadence.forPanelState(panelOn = false))
    }

    @Test fun `polling is once a minute`() {
        assertEquals(60_000L, Cadence.FAST_MS)
    }
}
