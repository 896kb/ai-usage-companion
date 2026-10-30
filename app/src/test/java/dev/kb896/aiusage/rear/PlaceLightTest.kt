package dev.kb896.aiusage.rear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a placement ends by lighting the back. It always did, unconditionally — so the
 * app restarting, an update or a swipe from recents lit a back that was asleep in a pocket.
 */
class PlaceLightTest {

    @Test fun `asked for, it is lit whatever it was`() {
        assertTrue(PlaceLight.wake(asked = true, wasLit = false, dozing = false))
        assertTrue(PlaceLight.wake(asked = true, wasLit = false, dozing = true))
        assertTrue(PlaceLight.wake(asked = true, wasLit = null, dozing = false))
    }

    @Test fun `a recovery leaves a dark back dark`() {
        assertFalse(PlaceLight.wake(asked = false, wasLit = false, dozing = false))
    }

    @Test fun `a recovery keeps a lit back lit`() {
        assertTrue(PlaceLight.wake(asked = false, wasLit = true, dozing = false))
    }

    @Test fun `a recovery never calls off a panel letting go`() {
        // Lit still, but dimming on purpose: a wake would end the sleep.
        assertFalse(PlaceLight.wake(asked = false, wasLit = true, dozing = true))
    }

    @Test fun `an unreadable display is not lit by a recovery`() {
        assertFalse(PlaceLight.wake(asked = false, wasLit = null, dozing = false))
    }
}
