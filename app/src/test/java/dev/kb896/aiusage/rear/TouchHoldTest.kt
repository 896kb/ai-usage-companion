package dev.kb896.aiusage.rear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a touch takes the screen hold back. The case: the thirty-second idle let the display
 * go, somebody touched it in the ten seconds before HyperOS powered it down — and it went
 * dark ten seconds after that touch anyway, nothing having taken the hold back.
 */
class TouchHoldTest {

    private fun retake(
        holding: Boolean = false,
        lit: Boolean = true,
        dimming: Boolean = false,
        lettingGo: Boolean = false,
        sleptByDoubleTap: Boolean = false,
        inAod: Boolean = false,
    ) = TouchHold.retake(holding, lit, dimming, lettingGo, sleptByDoubleTap, inAod)

    @Test fun `a touch on a lit panel the idle let go of takes the hold back`() {
        assertTrue(retake())
    }

    @Test fun `nothing to take when it is already held`() {
        assertFalse(retake(holding = true))
    }

    @Test fun `not on a dark display`() {
        assertFalse(retake(lit = false))
    }

    @Test fun `not in the double tap's dim - its own second touch would undo it`() {
        assertFalse(retake(dimming = true))
    }

    @Test fun `not after the double tap's sleep, until something real wakes it`() {
        assertFalse(retake(sleptByDoubleTap = true))
    }

    @Test fun `not while a spent lock is letting the display go`() {
        assertFalse(retake(lettingGo = true))
    }

    @Test fun `not in always-on, which holds the display itself`() {
        assertFalse(retake(inAod = true))
    }
}
