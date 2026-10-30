package dev.kb896.aiusage.rear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the notification strip has nothing left to play — what the lock's expiry waits for,
 * and always-on's notification peek with it: the peek used to end on a fixed six seconds
 * while queued cards were still turning, and the frozen page stopped them mid-strip until
 * the minute tick.
 */
class NoticeStripTest {

    private fun idle(showing: Boolean = false, queued: Int = 0, turning: Boolean = false, slide: Float = 0f) =
        NoticeStrip.idle(showing, queued, turning, slide)

    @Test fun `nothing up, nothing queued, the strip retracted`() {
        assertTrue(idle())
    }

    @Test fun `a card up is not idle`() {
        assertFalse(idle(showing = true, slide = 1f))
    }

    @Test fun `cards queued behind the one leaving are not idle`() {
        assertFalse(idle(queued = 2))
    }

    @Test fun `the wheel turning one out is not idle`() {
        assertFalse(idle(turning = true))
    }

    @Test fun `the strip still sliding away is not idle - the last card would be cut in half`() {
        assertFalse(idle(slide = 0.3f))
        assertTrue(idle(slide = 0.0005f))
    }
}
