package dev.kb896.aiusage.rear

/**
 * Whether a touch takes the screen hold back.
 *
 * The thirty-second idle lets the display go, and HyperOS powers the rear down ten seconds
 * later (subscreen_display_time). A touch in those ten seconds only re-armed the countdown —
 * it never took the hold back — so the panel went dark ten seconds after somebody had
 * touched it. A finger on a [lit] panel is somebody looking at it, and it holds the display
 * again, except where the panel is letting go on purpose: the double tap's dim (whose own
 * second touch reaches the view after the dim begins), the double tap's sleep until
 * something real wakes it, a spent lock [lettingGo], and always-on, which holds and lets go
 * by its own rules. Pure, so it can be tested without a display.
 */
object TouchHold {
    fun retake(
        holding: Boolean,
        lit: Boolean,
        dimming: Boolean,
        lettingGo: Boolean,
        sleptByDoubleTap: Boolean,
        inAod: Boolean,
    ): Boolean = !holding && lit && !dimming && !lettingGo && !sleptByDoubleTap && !inAod
}
