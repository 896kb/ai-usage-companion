package dev.kb896.aiusage.rear

/**
 * Whether "Keep panel awake" takes the screen hold back.
 *
 * Keep awake means the panel never lets go on its own timer — not that nothing may ever
 * let go. A lock raised by a wake (a notification, the double tap's sleep, the charger) and
 * left unswiped still runs out and releases the display, and while it is [lettingGo] the
 * hold stays released: every display event on the way down re-armed the sleep, and keep
 * awake re-took the hold there, so a notification at night kept the back lit until morning.
 * Pure, so it can be tested without a display.
 */
object KeepAwakeHold {
    fun retake(keepAwake: Boolean, holding: Boolean, lettingGo: Boolean): Boolean =
        keepAwake && !holding && !lettingGo
}
