package dev.kb896.aiusage.rear

/**
 * Where the Info page's wheel of notifications stands when a new one arrives. The newest is
 * card 0, so a new one pushes every card one further back: the wheel moves with them, so the
 * card in view stays where it is ([Arrival.at]) — and then, if that was the newest (or it
 * was on its way there), it turns to the new one ([Arrival.settleTo]): the card slides down
 * and the new one comes in from above, as though it had been there. Reading an older one,
 * or holding the wheel, it is left alone.
 */
object NoticeWheel {
    class Arrival(val at: Float, val settleTo: Float?)

    fun afterArrival(at: Float, held: Boolean): Arrival {
        val moved = at + 1f
        return Arrival(moved, if (!held && at < NEWEST) 0f else null)
    }

    /** Nearer the newest than the next card: on it, or turning to it. */
    private const val NEWEST = 0.5f
}
