package dev.kb896.aiusage;

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
}
