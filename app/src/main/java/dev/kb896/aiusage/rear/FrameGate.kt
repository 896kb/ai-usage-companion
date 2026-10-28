package dev.kb896.aiusage.rear

/**
 * Whether the portal frame runs: wanted — a guest on top, as the shell service says with
 * PortalFrame.show / hide — and allowed — the front's Portal border switch
 * (PanelConfig.portalBorder). Pure: PortalFrame keeps one, under its own lock.
 *
 * Switched off, the frame goes at once and every show is a no-op; switched back on while
 * it is still wanted, it comes back without waiting for the next show.
 */
class FrameGate {
    var wanted = false
        private set
    var allowed = true
        private set

    /** Whether the frame should be up now. */
    val runs: Boolean get() = wanted && allowed

    /** Wanted or not ([on]); whether it should then run. */
    fun want(on: Boolean): Boolean { wanted = on; return runs }

    /** Allowed or not ([on]); whether it should then run. */
    fun allow(on: Boolean): Boolean { allowed = on; return runs }
}
