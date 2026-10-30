package dev.kb896.aiusage.rear

/**
 * Whether the notification strip has nothing left to play: no card [showing], none
 * [queued], none [turning] out on the wheel, and the strip fully retracted ([slide] at
 * rest). RETRACTED rather than merely empty: the queue drains a moment before the strip
 * finishes sliding away, and stopping there would cut the last notification in half.
 *
 * What the lock's expiry waits for, and always-on's notification peek: that ended on a
 * fixed six seconds while queued cards were still turning, and the frozen page then held
 * the strip mid-card until the minute tick. Pure, so it can be tested without a display.
 */
object NoticeStrip {
    fun idle(showing: Boolean, queued: Int, turning: Boolean, slide: Float): Boolean =
        !showing && queued == 0 && !turning && slide <= 0.001f
}
