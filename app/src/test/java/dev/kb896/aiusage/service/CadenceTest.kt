package dev.kb896.aiusage.service

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

    /**
     * Two minutes. 60s was measured on device and produced backoff in seven of twelve
     * samples while the endpoint itself answered 200 to a direct probe — a steady rate
     * can be too fast on its own, not only a bursty one.
     */
    @Test fun `polling is every two minutes`() {
        assertEquals(120_000L, Cadence.FAST_MS)
    }
}
