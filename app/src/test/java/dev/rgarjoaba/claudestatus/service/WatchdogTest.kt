package dev.rgarjoaba.claudestatus.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the two guards Task 15 Step 2b requires of the respawn watchdog:
 * transition-only logging (not once per attempt) and never respawning a panel
 * that is mid-calibration. Watchdog.decide is pure Kotlin, so both are testable
 * here without Robolectric — the rest of the watchdog (asking ShizukuBridge for
 * `alive`/`calibrating`, actually calling show()) needs a live Shizuku connection
 * and RearActivity, and is hardware-only.
 */
class WatchdogTest {

    @Test fun `alive panel does not respawn`() {
        val d = Watchdog.decide(previous = null, alive = true, calibrating = false)
        assertEquals(Watchdog.State.ALIVE, d.state)
        assertFalse(d.shouldRespawn)
    }

    @Test fun `dead panel respawns`() {
        val d = Watchdog.decide(previous = null, alive = false, calibrating = false)
        assertEquals(Watchdog.State.DEAD, d.state)
        assertTrue(d.shouldRespawn)
    }

    @Test fun `a panel that looks dead is never respawned while calibrating`() {
        // The scenario the brief calls out: RearActivity crashes mid-calibration
        // (task gone, so it "looks dead"), but blindly respawning with the normal
        // usage payload would silently exit calibrate mode under the user's hands.
        val d = Watchdog.decide(previous = null, alive = false, calibrating = true)
        assertEquals(Watchdog.State.CALIBRATING, d.state)
        assertFalse(d.shouldRespawn)
    }

    @Test fun `calibrating wins even when the panel also reads alive`() {
        val d = Watchdog.decide(previous = null, alive = true, calibrating = true)
        assertEquals(Watchdog.State.CALIBRATING, d.state)
        assertFalse(d.shouldRespawn)
    }

    @Test fun `the first observation is always a new state, whatever it is`() {
        assertTrue(Watchdog.decide(previous = null, alive = true, calibrating = false).isNewState)
        assertTrue(Watchdog.decide(previous = null, alive = false, calibrating = false).isNewState)
        assertTrue(Watchdog.decide(previous = null, alive = false, calibrating = true).isNewState)
    }

    @Test fun `repeated dead readings are not new transitions, but still respawn every time`() {
        val first = Watchdog.decide(previous = null, alive = false, calibrating = false)
        val second = Watchdog.decide(previous = first.state, alive = false, calibrating = false)
        val third = Watchdog.decide(previous = second.state, alive = false, calibrating = false)

        assertTrue(first.isNewState)
        assertFalse(second.isNewState)
        assertFalse(third.isNewState)
        // The "do not respawn in a tight loop" guard is about the LOG, not the
        // retry: the retry itself must keep happening every tick (at the tick
        // cadence, not faster) so placement recovers as soon as it becomes possible.
        assertTrue(second.shouldRespawn)
        assertTrue(third.shouldRespawn)
    }

    @Test fun `recovering from dead to alive is reported as a new transition`() {
        val dead = Watchdog.decide(previous = null, alive = false, calibrating = false)
        val recovered = Watchdog.decide(previous = dead.state, alive = true, calibrating = false)
        assertTrue(recovered.isNewState)
        assertEquals(Watchdog.State.ALIVE, recovered.state)
    }

    @Test fun `entering calibrate mode from alive is a new transition`() {
        val alive = Watchdog.decide(previous = null, alive = true, calibrating = false)
        val calibrating = Watchdog.decide(previous = alive.state, alive = true, calibrating = true)
        assertTrue(calibrating.isNewState)
        assertEquals(Watchdog.State.CALIBRATING, calibrating.state)
    }

    @Test fun `staying calibrating across ticks is not a new transition`() {
        val first = Watchdog.decide(previous = null, alive = true, calibrating = true)
        val second = Watchdog.decide(previous = first.state, alive = false, calibrating = true)
        assertFalse(second.isNewState)
        assertFalse(second.shouldRespawn)
    }
}
