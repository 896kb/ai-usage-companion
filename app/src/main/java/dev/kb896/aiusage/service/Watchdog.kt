package dev.kb896.aiusage.service

/**
 * Pure decision logic for the respawn watchdog (Task 15 Step 2b). The panel is
 * always-on at the user's request, so a killed RearActivity — memory pressure, a
 * crash, a reboot — stays dead until something else revives it; UsagePollService
 * owns that. This object decides what "something else" should DO with a
 * (alive?, calibrating?) reading; it never touches Android, so both required
 * guards can be unit tested directly instead of only on hardware:
 *
 *  - "do not respawn in a tight loop" — the state machine below still asks to
 *    retry every tick while dead (there is no backoff timer here; the tick
 *    cadence itself, Cadence.FAST_MS/SLOW_MS, IS the backoff), but [Decision.isNewState]
 *    lets the caller log only on a transition instead of once per attempt.
 *  - "never respawn while calibrate mode is active" — CALIBRATING always wins
 *    over both ALIVE and DEAD, so a panel that died mid-calibration is left dead
 *    rather than silently respawned back into the normal dial view. This branch
 *    is currently unreachable in practice — see ShizukuBridge.isCalibrating()'s
 *    KDoc for exactly why — but it is not unreachable in principle: `calibrating`
 *    is tracked in this (surviving) process rather than read live from the dead
 *    one, specifically so this comparison still works after RearActivity crashes.
 */
object Watchdog {

    enum class State { ALIVE, DEAD, CALIBRATING, DOZING }

    data class Decision(val state: State, val isNewState: Boolean) {
        /** Only a confirmed-dead panel that is neither calibrating nor dozing. */
        val shouldRespawn: Boolean get() = state == State.DEAD
    }

    /**
     * [dozing] and [calibrating] both win over [alive], and dozing wins outright.
     *
     * DOZING is the panel having let its own display go dark on purpose, and it has to
     * stop the respawn for a reason the other two do not share: re-placing the panel
     * WAKES the display. So the watchdog doing exactly its job undid every deliberate
     * sleep about ten seconds after it began, and the sleep looked like a feature that
     * did not work. Measured on device — "keep-alive: rear display taken — re-placing"
     * followed by a wake, on a display that had gone dark four hundred milliseconds
     * earlier.
     *
     * It wins over `alive` because a dozing panel IS alive; the reading is not in
     * dispute, only what should be done about it. And over `calibrating` because
     * nothing calibrates on a dark screen.
     *
     * Safe to stand down for the same reason CALIBRATING is: it lasts exactly as long as
     * the panel says it does, and anything that lights the display ends it — including
     * the display simply coming back on, which UsagePollService clears the flag from
     * before this next runs.
     */
    fun decide(
        previous: State?,
        alive: Boolean,
        calibrating: Boolean,
        dozing: Boolean = false,
    ): Decision {
        val state = when {
            dozing -> State.DOZING
            calibrating -> State.CALIBRATING
            alive -> State.ALIVE
            else -> State.DEAD
        }
        return Decision(state, isNewState = state != previous)
    }
}
