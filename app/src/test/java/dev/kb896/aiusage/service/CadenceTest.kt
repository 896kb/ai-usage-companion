package dev.kb896.aiusage.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CadenceTest {

    @Test fun `panel on uses the fast cadence`() {
        assertEquals(Cadence.FAST_MS, Cadence.forPanelState(panelOn = true))
    }

    @Test fun `panel off uses the slow cadence`() {
        assertEquals(Cadence.SLOW_MS, Cadence.forPanelState(panelOn = false))
    }

    @Test fun `fast is strictly faster than slow`() {
        assertTrue(Cadence.FAST_MS < Cadence.SLOW_MS)
    }
}
