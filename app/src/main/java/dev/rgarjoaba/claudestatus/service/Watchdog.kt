package dev.rgarjoaba.claudestatus.service

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

    enum class State { ALIVE, DEAD, CALIBRATING }

    data class Decision(val state: State, val isNewState: Boolean) {
        /** Only a confirmed-dead, non-calibrating panel gets re-placed. */
        val shouldRespawn: Boolean get() = state == State.DEAD
    }

    /**
     * [calibrating] wins over [alive]: a live activity mid-calibration is a
     * distinct state from an ordinary alive panel — UsagePollService.tick() also
     * skips its own routine update() push in that state, so a periodic broadcast
     * can't silently flip the panel out of the calibration view either.
     */
    fun decide(previous: State?, alive: Boolean, calibrating: Boolean): Decision {
        val state = when {
            calibrating -> State.CALIBRATING
            alive -> State.ALIVE
            else -> State.DEAD
        }
        return Decision(state, isNewState = state != previous)
    }
}
