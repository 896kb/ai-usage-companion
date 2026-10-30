package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RearDensityTest {
    private val dpi = mapOf("a.app" to 320, "b.app" to 1000)

    @Test fun `no guest means the display default`() {
        assertNull(RearDensity.target(null, true, dpi))
    }

    @Test fun `a guest no longer live means the display default`() {
        assertNull(RearDensity.target("a.app", false, dpi))
    }

    @Test fun `an app without an entry means the display default`() {
        assertNull(RearDensity.target("c.app", true, dpi))
    }

    @Test fun `a live guest gets its own clamped density`() {
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

    @Test fun `a private display defaults to two thirds of the rear's density, 300 on the 17 Pro`() {
        assertEquals(300, RearDensity.privateDefault(RearGeometry.PRO17))
        assertEquals(300, RearDensity.ofPrivate("a", emptyMap(), RearGeometry.PRO17))
    }

    @Test fun `a private display takes the app's own DPI, clamped`() {
        assertEquals(380, RearDensity.ofPrivate("a", mapOf("a" to 380), RearGeometry.PRO17))
        assertEquals(240, RearDensity.ofPrivate("a", mapOf("a" to 100), RearGeometry.PRO17))
    }
}
