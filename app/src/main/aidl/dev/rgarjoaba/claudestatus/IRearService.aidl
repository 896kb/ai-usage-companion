package dev.rgarjoaba.claudestatus;

/** Runs inside Shizuku's shell-privileged process (uid 2000). */
interface IRearService {
    // Shizuku-reserved destroy transaction id.
    void destroy() = 16777114;

    /** Place RearActivity on displayId with an initial payload, then wake the panel. */
    void show(String payload, int displayId) = 1;

    /** Push a new payload to the already-placed activity. Never relaunches. */
    void update(String payload) = 2;

    /** Wake the panel. Required: FLAG_TURN_SCREEN_ON does not cover this. */
    void wake(int displayId) = 3;

    /** Tell the activity to finish. */
    void hide() = 4;

    /**
     * Whether RearActivity's task still exists. Task EXISTENCE, not display
     * placement — am stack list bounds and dumpsys window displays have both been
     * observed lying about which display something is actually on (see
     * RearUserService.isOnDisplay's KDoc and the progress ledger's "CRITICAL METHOD
     * FINDING"), but a task either shows up in `am stack list` or it does not. Used
     * by UsagePollService's respawn watchdog (Task 15) to decide whether the
     * always-on panel needs re-placing after being killed by memory pressure, a
     * crash, or a reboot.
     */
    boolean isAlive() = 5;
}
