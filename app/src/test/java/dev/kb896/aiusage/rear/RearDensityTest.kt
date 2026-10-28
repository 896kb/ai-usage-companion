package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RearDensityTest {
    private val dpi = mapOf("a.app" to 320, "b.app" to 1000)

    @Test fun `no guest means the display default`() {
        assertNull(RearDensity.target(null, true, dpi))
    }

    @Test fun `a guest behind the panel means the display default`() {
        assertNull(RearDensity.target("a.app", false, dpi))
    }

    @Test fun `an app without an entry means the display default`() {
        assertNull(RearDensity.target("c.app", true, dpi))
    }

    @Test fun `the guest on top gets its own clamped density`() {
        assertEquals(320, RearDensity.target("a.app", true, dpi))
        assertEquals(600, RearDensity.target("b.app", true, dpi))
    }

    @Test fun `clamp rounds to the step`() {
        assertEquals(320, RearDensity.clamp(324))
        assertEquals(330, RearDensity.clamp(326))
    }

    @Test fun `clamp bounds both ends`() {
        assertEquals(240, RearDensity.clamp(100))
        assertEquals(240, RearDensity.clamp(-5))
        assertEquals(600, RearDensity.clamp(900))
    }

    @Test fun `nudge starts from the default when unset`() {
        assertEquals(380, RearDensity.nudge(null, 360, up = true))
        assertEquals(340, RearDensity.nudge(null, 360, up = false))
    }

    @Test fun `nudge starts from a set value`() {
        assertEquals(340, RearDensity.nudge(320, 360, up = true))
        assertEquals(300, RearDensity.nudge(320, 360, up = false))
    }

    @Test fun `nudge stays within bounds`() {
        assertEquals(600, RearDensity.nudge(590, 360, up = true))
        assertEquals(240, RearDensity.nudge(250, 360, up = false))
    }
}
