package dev.kb896.aiusage.rear

/**
 * How far the portal sheet hides the portal frame, 0 to 1: as far as the sheet is open
 * ([sheet], PortalSheet.progress — scrolled on to the slider counts as open), so the frame
 * goes as the sheet comes up and comes back in step as it shuts ("the electric border
 * should disappear, fading by how finished the opening is").
 *
 * LATCHED FOR A CHOICE. A button's choice (Close, Front, Panel) runs once the sheet is down
 * and fades the frame out — but the sheet shutting first would bring the frame back up in
 * between, and it would flash. So a choice [hold]s the veil where it is while the sheet
 * shuts; a frame then [hidden] keeps it until it is [shown] again; a choice that left it up
 * is [release]d at once.
 *
 * THE CONTROL CENTRE over a guest hides it the same way ([controlCenter]), as far as it is
 * pulled down; the two are never open together, and the veil is whichever is further open.
 *
 * [sheet] and [controlCenter] are written by their render threads (volatile, no lock); the
 * rest is PortalFrame's, under its lock. [alpha] is the frame layer's alpha.
 */
class FrameVeil {

    @Volatile private var open = 0f
    @Volatile private var centre = 0f
    private var latched = 0f

    /** How far the sheet is open now, 0 to 1. Any thread. */
    fun sheet(progress: Float) { open = progress.coerceIn(0f, 1f) }

    /** How far the control centre over a guest is open now, 0 to 1. Any thread. */
    fun controlCenter(progress: Float) { centre = progress.coerceIn(0f, 1f) }

    /** Whichever of the two is further open. */
    private fun opened(): Float = maxOf(open, centre)

    /** A choice is on its way: the veil goes no lower until it is done. */
    fun hold() { latched = maxOf(latched, opened()) }

    /** The frame is fading out: the veil goes no lower while it does. */
    fun hidden() { latched = maxOf(latched, opened()) }

    /** The frame is wanted again: only the sheet hides it. */
    fun shown() { latched = 0f }

    /** The choice is done; if it left the frame up ([stillShown]), the sheet alone hides it again. */
    fun release(stillShown: Boolean) { if (stillShown) latched = 0f }

    /** How much of the frame the sheet hides now. */
    fun veil(): Float = maxOf(opened(), latched)

    companion object {
        /** The frame layer's alpha: [full] (PortalFrame.ALPHA) times its fade's [level], less what the sheet hides. */
        fun alpha(full: Float, level: Float, veil: Float): Float = full * level * (1f - veil.coerceIn(0f, 1f))
    }
}
