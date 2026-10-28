package dev.kb896.aiusage;

import android.os.Bundle;
import dev.kb896.aiusage.IPortalCallback;

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

    /**
     * If RearActivity is compositing on the MAIN display, move it back to displayId.
     *
     * Separate from show() because the strand does not only happen at placement time.
     * Locking the device makes the system hoist this activity onto the primary display
     * over the keyguard — it holds FLAG_SHOW_WHEN_LOCKED, so that is the system doing
     * exactly what it was asked — and no placement attempt is in flight when it
     * happens. Recovery therefore has to be callable on its own, from the watchdog and
     * from the screen-off/on broadcasts.
     *
     * Returns true only when it actually moved something, so the caller can log a real
     * correction without logging every quiet check.
     */
    boolean ensureOnRear(int displayId) = 6;

    /**
     * Put the system camera on [displayId].
     *
     * A NAMED action rather than a general "run this component", deliberately. This
     * interface runs at shell uid, so anything it will launch for the asking is a
     * privilege the app hands to whatever can reach it. One camera is a feature; an
     * arbitrary component is a lever.
     */
    boolean openCamera(int displayId) = 7;

    /**
     * The panel has deliberately let its display go dark. Stand down until told otherwise.
     *
     * Needed because this service cannot tell a panel that is asleep on purpose from one
     * that has been shoved aside, and the two look identical from here: our layer stops
     * compositing and MIUI's rear-screen doze service starts. The keep-alive read that as
     * "rear display taken", re-placed the panel, and re-placing wakes it — so the panel
     * woke itself about ten seconds after every deliberate sleep, and the sleep looked
     * like it simply did not work. Composition cannot answer this question; only the panel
     * knows, so the panel says.
     */
    void setDozing(boolean dozing) = 8;

    /**
     * Hide HyperOS's own rear-screen app from this user — `pm uninstall -k --user 0`, which
     * removes nothing from the system partition. Two NAMED actions for one fixed package,
     * for the same reason openCamera is not "run this component": at shell uid a general
     * uninstall would be a lever, not a feature.
     */
    boolean removeXiaomiRearApp() = 9;

    /**
     * Bring it back — `pm install-existing`, the exact undo of the above — and lift a
     * suspension left by an earlier workaround, so restored means running, not present.
     */
    boolean restoreXiaomiRearApp() = 10;
    /** What the system says about the rear display, for a bug report. See RearUserService. */
    String rearReport() = 11;
    // The rear display's own brightness, HyperOS's system sub_display_screen_brightness
    // (1 to 255); -1 when it cannot be read. Setting it also puts its mode on manual.
    int rearBrightness() = 12;
    void setRearBrightness(int value) = 13;
    // Whether HyperOS sets the rear brightness from the rear light sensor
    // (secure sub_display_screen_brightness_mode 1), and switching it.
    boolean rearAutoBrightness() = 14;
    void setRearAutoBrightness(boolean on) = 15;
    // The control centre's switches, as "bluetooth,data,wifi" each 1 or 0; and switching
    // one ("bluetooth", "data" or "wifi").
    String quickStates() = 16;
    void setQuick(String which, boolean on) = 17;
    // App Portal. Launch [pkg] onto the rear as the guest; false if it could not be placed.
    boolean launchGuest(String pkg, int displayId) = 18;
    // The app focused on the front, moved to the rear as the guest; its package, or "".
    String sendFocusedToBack(int displayId) = 19;
    // What to do with the guest: "panel" — the panel stays in front, the guest alive behind
    // it; "resume" — the guest on top again, the panel behind; "front" — the guest moved to
    // the main display; "close" — the guest force-stopped. The last two bring the panel back.
    void guestAction(String which) = 20;
    // Where relayed pulls, frames and guest changes go; null when the panel goes away.
    void setPortalCallback(IPortalCallback callback) = 21;
    // The pinned apps for the shell's sheet: "packages" and "labels" (String[], same order)
    // and "icon:<pkg>" (PNG bytes, 96x96). See PortalAppsSnapshot.
    void setPortalApps(in Bundle apps) = 22;
    /** Whether to keep poking the rear display awake while the panel holds it. See RearUserService. */

}
