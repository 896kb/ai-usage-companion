package dev.kb896.aiusage.service

import dev.kb896.aiusage.service.NotificationAlert.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which notifications light the back, which only reach a lit one as a card, and which never
 * reach it. Measured complaint: every clearable post woke the rear — a paused song included —
 * so a phone in a pocket lit its back for things the front itself never sounded for.
 */
class NotificationAlertTest {

    private fun verdict(
        media: Boolean = false,
        importance: Int? = 3,
        matchesFilter: Boolean? = true,
        onlyAlertOnce: Boolean = false,
        seenBefore: Boolean = false,
    ) = NotificationAlert.verdict(media, importance, matchesFilter, onlyAlertOnce, seenBefore)

    @Test fun `a message that sounds on the front wakes the back`() {
        assertEquals(Verdict.WAKE, verdict())
        assertEquals(Verdict.WAKE, verdict(importance = 4))
    }

    @Test fun `a quiet notification is a card for a lit back, never a wake`() {
        assertEquals(Verdict.CARD, verdict(importance = 2))
        assertEquals(Verdict.CARD, verdict(importance = 1))
    }

    @Test fun `do not disturb holding it back keeps the back dark too`() {
        assertEquals(Verdict.CARD, verdict(matchesFilter = false))
    }

    @Test fun `no ranking to read is no wake`() {
        assertEquals(Verdict.CARD, verdict(importance = null))
        assertEquals(Verdict.CARD, verdict(matchesFilter = null))
    }

    @Test fun `media never reaches the back, not even as a card`() {
        assertEquals(Verdict.DROP, verdict(media = true))
        assertEquals(Verdict.DROP, verdict(media = true, importance = 4, seenBefore = false))
    }

    @Test fun `an alert-once update of a key already seen is no wake`() {
        assertEquals(Verdict.CARD, verdict(onlyAlertOnce = true, seenBefore = true))
    }

    @Test fun `an alert-once notification's first post still wakes`() {
        assertEquals(Verdict.WAKE, verdict(onlyAlertOnce = true, seenBefore = false))
    }

    @Test fun `a re-post without alert-once is the app asking to alert again`() {
        assertEquals(Verdict.WAKE, verdict(onlyAlertOnce = false, seenBefore = true))
    }

    @Test fun `a key is seen from its first post, ongoing or not`() {
        val seen = NotificationAlert.SeenKeys(max = 8)
        // A player's ongoing post: dropped by the relay, but its key is remembered...
        assertFalse(seen.see("0|player|1|null|10123"))
        // ...so the same key's first non-ongoing post (paused) is a re-post.
        assertTrue(seen.see("0|player|1|null|10123"))
    }

    @Test fun `a removed key is new again`() {
        val seen = NotificationAlert.SeenKeys(max = 8)
        seen.see("a")
        seen.forget("a")
        assertFalse(seen.see("a"))
    }

    @Test fun `the oldest keys go first past the bound`() {
        val seen = NotificationAlert.SeenKeys(max = 2)
        seen.see("a"); seen.see("b"); seen.see("c")
        assertFalse(seen.see("a"))
        assertTrue(seen.see("c"))
    }

    @Test fun `seeding marks what is already on the front as seen`() {
        val seen = NotificationAlert.SeenKeys(max = 8)
        seen.seed(listOf("x", "y"))
        assertTrue(seen.see("x"))
        assertTrue(seen.see("y"))
        assertFalse(seen.see("z"))
    }
}
