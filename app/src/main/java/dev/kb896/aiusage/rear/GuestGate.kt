package dev.kb896.aiusage.rear

/**
 * The keep-alive's question while a guest app may be up: leave the rear alone, end the
 * guest, or do the usual recovery. Pure, because getting it wrong either covers the
 * guest every six seconds (measured with Firefox: ~3 s) or leaves a dead rear.
 */
object GuestGate {

    enum class Tick { USUAL, GUEST_ON_TOP, GUEST_BEHIND, GUEST_GONE }

    /**
     * [alive]: the guest's task exists; [onRear]: it is on display 1; [top]: the package on
     * top of the rear (SurfaceDump.topPackage), null when it composes none.
     */
    fun tick(guest: String?, alive: Boolean, onRear: Boolean, top: String?, ours: String): Tick = when {
        guest == null -> Tick.USUAL
        !alive || !onRear -> Tick.GUEST_GONE
        top == guest -> Tick.GUEST_ON_TOP
        top == null || top == ours -> Tick.GUEST_BEHIND
        else -> Tick.USUAL
    }
}
