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

    /**
     * What the display already is when the panel first finds itself on it — not an edge.
     * Unseeded, a new panel's first change (the refresh rate dropping three seconds after
     * a touch, the brightness) read as a wake: a double tap's dim was called off, or
     * always-on came up under somebody using the panel. Once only: it does nothing after
     * the first seed or report, so a later resume cannot swallow a real edge.
     */
    fun seed(on: Boolean) {
        if (lit == null) lit = on
    }

    /** The display now [on]: that, if it differs from the last report or seed (or is the first); else null. */
    fun next(on: Boolean): Boolean? {
        if (on == lit) return null
        lit = on
        return on
    }
}
