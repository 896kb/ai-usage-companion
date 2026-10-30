package dev.kb896.aiusage.service

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

    // -- dozing -----------------------------------------------------------------------

    /**
     * The bug this state exists for: re-placing the panel WAKES the display, so a
     * watchdog that respawned a deliberately-sleeping panel undid the sleep. Measured on
     * device as "rear display taken — re-placing" followed by a wake, on a display that
     * had gone dark four hundred milliseconds earlier.
     */
    @Test fun `a dozing panel is never respawned`() {
        val d = Watchdog.decide(previous = null, alive = false, calibrating = false, dozing = true)
        assertEquals(Watchdog.State.DOZING, d.state)
        assertFalse("re-placing a dozing panel wakes it", d.shouldRespawn)
    }

    /** A dozing panel is alive; the reading is not in dispute, only what to do about it. */
    @Test fun `dozing wins over alive`() {
        val d = Watchdog.decide(previous = null, alive = true, calibrating = false, dozing = true)
        assertEquals(Watchdog.State.DOZING, d.state)
    }

    /** Nothing calibrates on a dark screen. */
    @Test fun `dozing wins over calibrating`() {
        val d = Watchdog.decide(previous = null, alive = false, calibrating = true, dozing = true)
        assertEquals(Watchdog.State.DOZING, d.state)
    }

    /** And the moment it stops dozing, an absent panel is a dead one again. */
    @Test fun `waking restores the respawn`() {
        val d = Watchdog.decide(
            previous = Watchdog.State.DOZING, alive = false, calibrating = false, dozing = false,
        )
        assertEquals(Watchdog.State.DEAD, d.state)
        assertTrue("a panel that died while dozing is left dead", d.shouldRespawn)
    }

    /** Omitted entirely, nothing changes — every existing caller keeps its behaviour. */
    @Test fun `not dozing by default`() {
        assertEquals(
            Watchdog.State.DEAD,
            Watchdog.decide(previous = null, alive = false, calibrating = false).state,
        )
    }

    // -- not bound yet ------------------------------------------------------------------

    /**
     * A new app process has no binding to the shell, so it cannot know whether the panel
     * is there. That used to read as DEAD, and the respawn lit a sleeping back in a pocket
     * every time HyperOS restarted the app. Unknown is asked again once bound, never acted on.
     */
    @Test fun `an unknown panel is not respawned`() {
        val d = Watchdog.decide(previous = null, alive = null, calibrating = false)
        assertEquals(Watchdog.State.UNKNOWN, d.state)
        assertFalse(d.shouldRespawn)
    }

    @Test fun `an answer once bound is a new transition`() {
        val unknown = Watchdog.decide(previous = null, alive = null, calibrating = false)
        val dead = Watchdog.decide(previous = unknown.state, alive = false, calibrating = false)
        assertTrue(dead.isNewState)
        assertTrue(dead.shouldRespawn)
    }

    @Test fun `dozing and calibrating still win over unknown`() {
        assertEquals(Watchdog.State.DOZING, Watchdog.decide(null, alive = null, calibrating = false, dozing = true).state)
        assertEquals(Watchdog.State.CALIBRATING, Watchdog.decide(null, alive = null, calibrating = true).state)
    }
}
