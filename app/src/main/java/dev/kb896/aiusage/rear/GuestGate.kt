package dev.kb896.aiusage.rear

/**
 * The keep-alive's question while a guest app may be up: leave the rear alone, end the
 * guest, or do the usual recovery. Pure, because getting it wrong either covers the
 * guest every six seconds (measured with Firefox: ~3 s) or leaves a dead rear.
 */
object GuestGate {

    /**
     * [GUEST_EXPOSED]: a guest the sheet's Panel hid on display 0 behind the keyguard, and
     * the front is now unlocked — it is showing there; it goes back to the rear.
     */
    enum class Tick { USUAL, GUEST_ON_TOP, GUEST_BEHIND, GUEST_GONE, GUEST_EXPOSED }

    /**
     * [alive]: the guest's task exists; [onRear]: it is on display 1; [top]: the package on
     * top of the rear (SurfaceDump.topPackage), null when it composes none.
     *
     * [hiddenOnFront]: Panel hid it on display 0 behind the keyguard, and [onFront]: its task
     * is on display 0 now. Such a guest is NOT gone — it is behind, like one under the panel
     * on the rear — as long as the front is locked ([frontLocked]; only read for it). With the
     * front unlocked it is [Tick.GUEST_EXPOSED]. Something else on top of the rear is still
     * the usual recovery.
     */
    fun tick(
        guest: String?, alive: Boolean, onRear: Boolean, top: String?, ours: String,
        hiddenOnFront: Boolean = false, onFront: Boolean = false, frontLocked: () -> Boolean = { true },
    ): Tick = when {
        guest == null -> Tick.USUAL
        !alive -> Tick.GUEST_GONE
        hiddenOnFront && onFront -> when {
            !frontLocked() -> Tick.GUEST_EXPOSED
            top == null || top == ours -> Tick.GUEST_BEHIND
            else -> Tick.USUAL
        }
        !onRear -> Tick.GUEST_GONE
        top == guest -> Tick.GUEST_ON_TOP
        top == null || top == ours -> Tick.GUEST_BEHIND
        else -> Tick.USUAL
    }
}
