package dev.kb896.aiusage.rear

/**
 * The rear display's lit/dark edges, out of DisplayListener's changes. onDisplayChanged
 * fires for every property — brightness, the refresh rate — and a lit display reporting a
 * change is not a display coming back on. HyperOS drops the rear from 120 to 60 Hz three
 * seconds after the last touch; read as a wake, that called off every double tap's dim
 * three seconds in, and the panel stayed up (seen 2026-10-30, nine double taps of nine).
 */
class LitEdge {
    private var lit: Boolean? = null

    /** The display now [on]: that, if it differs from the last report (or is the first); else null. */
    fun next(on: Boolean): Boolean? {
        if (on == lit) return null
        lit = on
        return on
    }
}
