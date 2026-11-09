package dev.kb896.aiusage;

/**
 * From the shell service back to the main app process: what was decided on the back that
 * only the app can keep. Registered with IRearService.setEvents. oneway: the shell never
 * waits on the app.
 */
oneway interface IRearEvents {
    // An app's DPI was nudged on the back: [dpi] for [pkg], 0 for its default (the entry
    // cleared). The app saves it in the config and pushes the payload back.
    void onPortalDpi(String pkg, int dpi) = 1;
    // A tile of the control centre the shell holds over a guest was tapped: switch [which]
    // ("silent", "bluetooth", "data" or "wifi") [on], as the panel's own tiles are switched,
    // and send the states back (IRearService.setQuickStates).
    void onQuick(String which, boolean on) = 2;
    // That control centre is coming down: send the tiles' states, as the panel's opening asks.
    void onControlCenterOpening() = 3;
    // The portal sheet came to rest open on the back: said once a process, the first time
    // (and again later only if nobody was listening). The app keeps it as portalSheetSeen,
    // which ticks the Portal tab's "Learn the gestures" step.
    void onPortalOpened() = 4;
    // The panel's broadcasts the shell used to send itself (`am broadcast -p`), now sent by the
    // app: the panel's receiver takes nothing from outside the app (RearActivity's receiver).
    // The sheet was touched or opened over the panel: re-arm its sleep (ACTION_PORTAL_POKE).
    void onPanelPoke() = 5;
    // Resume: the panel moves its own task back, the guest on top (ACTION_PORTAL_STEP_BACK).
    void onPanelStepBack() = 6;
    // "Keep right of the cameras" switched on the back for [pkg]: the app saves it in the config
    // (PanelConfig.portalConfined) and pushes the payload back, as onPortalDpi.
    void onPortalConfined(String pkg, boolean on) = 7;
    // A guest on a private display is up (true) or hidden (false), in our layers over the panel:
    // the panel, still resumed under them, stands down (ACTION_PORTAL_COVERED).
    void onPanelCovered(boolean covered) = 8;
    // A row of the notifications over a guest was swiped away.
    void onNoticeDismissed(String key) = 9;
    // An app kept right of the cameras set to [width] px wide on the back, 0 for the whole width
    // (PanelConfig.portalWidth): saved, and the payload pushed back, as onPortalDpi.
    void onPortalWidth(String pkg, int width) = 10;
    // A pull up from the very bottom with the panel's control centre down: close it, as a swipe up
    // on it would (IRearService.setPanelControlCenter).
    void onPanelCloseControlCenter() = 11;
    // The back's lock (BackLock) came on or went: the panel hides its notifications' words and
    // locks its control centre while it is on. Said on every change and on (re)connect.
    void onBackLock(boolean locked) = 12;
    // A screenshot ([video] false) or recording of the back, made by the shell in its own space and
    // handed over open ([file], read-only): the app copies it into the gallery. A path in the app's
    // files was tried first: what the shell writes there the app may not read.
    void onCaptured(in ParcelFileDescriptor file, boolean video) = 13;
    // The back's recording began or ended: the panel's recording tile follows.
    void onRecording(boolean on) = 14;
    // The screenshot just taken, binned from its polaroid on the back: out of the gallery.
    void onDeleteLastShot() = 15;
}
