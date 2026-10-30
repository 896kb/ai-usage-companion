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
}
