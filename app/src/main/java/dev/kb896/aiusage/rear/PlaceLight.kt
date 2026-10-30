package dev.kb896.aiusage.rear

/**
 * Whether a placement of the panel ends by lighting the rear display.
 *
 * It always did, unconditionally, and that was right only for the placement somebody asked
 * for. Every other one is a recovery — the app process new after HyperOS restarted it, an
 * update, a swipe from recents, the keep-alive putting the panel back over something — and
 * those ran in pockets: the back lit up with nobody looking, and stayed lit for the rear's
 * whole timeout.
 *
 * So the back is lit when it was ASKED for ("Show on the back", the notification's tap) and
 * otherwise left as the placement found it: lit if it was lit, dark if it was dark. The
 * wake is still needed to keep a lit back lit — the panel's start can go through display 0,
 * where FLAG_TURN_SCREEN_ON has nothing to act on, and the rear goes dark with nothing of
 * ours on top — but not while the panel is letting it go on purpose ([dozing]): a wake ends
 * that sleep, so the recovery would undo the very dim it found in progress.
 *
 * [wasLit] is the display's state read BEFORE the placement (nothing afterwards can tell);
 * null when it could not be read, which is not lit — dark is the safe way to be wrong here.
 */
object PlaceLight {
    fun wake(asked: Boolean, wasLit: Boolean?, dozing: Boolean): Boolean =
        asked || (wasLit == true && !dozing)
}
