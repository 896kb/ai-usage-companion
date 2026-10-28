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
}
