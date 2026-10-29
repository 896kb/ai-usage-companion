package dev.kb896.aiusage.ui

import dev.kb896.aiusage.data.PanelConfig

/**
 * The Portal tab's guide: three steps, each ticked by what the app can see for itself —
 * never by a button saying "done" — and a card at the top of the tab until they are
 * (PortalGuideCard). Pure: the steps come from the config's own fields.
 *
 * - [Step.PIN]: an app pinned (portalApps).
 * - [Step.TILE]: the "Send to back" tile in Quick Settings (portalTileAdded: the add-tile
 *   request's answer, and PortalTileService's added / removed / listening).
 * - [Step.GESTURES]: the sheet opened on the back once (portalSheetSeen, from the shell's
 *   IRearEvents.onPortalOpened).
 *
 * Finished, or dismissed early, it goes down for good (portalGuideDone) and leaves a
 * "How to use the portal" row at the bottom of the tab. For good: a step that comes undone
 * afterwards — the tile taken out of the panel again — does not bring the card back.
 */
object PortalGuide {

    enum class Step { PIN, TILE, GESTURES }

    data class State(
        val pinned: Boolean,
        val tile: Boolean,
        val gestures: Boolean,
        /** Down for good: finished once, or dismissed. */
        val down: Boolean,
    ) {
        fun done(step: Step): Boolean = when (step) {
            Step.PIN -> pinned
            Step.TILE -> tile
            Step.GESTURES -> gestures
        }

        val complete: Boolean get() = pinned && tile && gestures

        /** The card at the top of the tab; otherwise the row at the bottom. */
        val card: Boolean get() = !down && !complete

        /** The first step not done yet, or null with all three ticked. */
        val next: Step? get() = Step.entries.firstOrNull { !done(it) }
    }

    fun of(config: PanelConfig) = State(
        pinned = config.portalApps.isNotEmpty(),
        tile = config.portalTileAdded,
        gestures = config.portalSheetSeen,
        down = config.portalGuideDone,
    )

    /**
     * [config] with a finished guide marked down for good, or [config] itself (the same
     * instance: nothing to save) when there is nothing to mark.
     */
    fun settled(config: PanelConfig): PanelConfig =
        if (!config.portalGuideDone && of(config).complete) config.copy(portalGuideDone = true) else config

    /**
     * StatusBarManager.requestAddTileService's answer: true for a tile now in the panel or
     * already there, false for one the user turned down, null for a request that could not
     * be made at all (the ERROR_ codes — a system that will not ask, say) — then the user is
     * pointed at the Quick Settings editor instead.
     */
    fun tileAnswer(result: Int): Boolean? = when (result) {
        android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
        android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> true
        android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> false
        else -> null
    }
}
