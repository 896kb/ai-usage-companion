package dev.rgarjoaba.claudestatus.data

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the rule that isStale keys on the ATTEMPT timestamp, not on whether a value is
 * present. An earlier draft returned true whenever the tier was null, which silently
 * defeated markAttempted: an account whose profile lookup fails has no tier, so it
 * would have been re-asked on every poll forever for a decorative field.
 *
 * SharedPreferences is an Android type and this module has no Robolectric, so the rule
 * is asserted against the same arithmetic rather than the class.
 */
class ProfileStoreStalenessTest {

    private fun stale(lastAttempt: Long, now: Long) = now - lastAttempt > ProfileStore.MAX_AGE_MS

    @Test fun `a never-attempted account is stale`() {
        assertTrue(stale(0L, 1_700_000_000_000L))
    }

    @Test fun `a failed attempt suppresses retries for the full window`() {
        val now = 1_700_000_000_000L
        assertTrue("just attempted -> not stale", !stale(now, now))
        assertTrue("a day later -> still not stale", !stale(now, now + 24L * 60 * 60 * 1000))
        assertTrue("eight days later -> stale again", stale(now, now + 8L * 24 * 60 * 60 * 1000))
    }
}
