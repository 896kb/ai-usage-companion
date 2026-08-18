package dev.kb896.aiusage.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenStatusTest {

    private val now = 1_787_000_000_000L
    private fun record(accessIn: Long, refreshIn: Long) = TokenRecord(
        accessToken = "a", refreshToken = "r",
        expiresAt = now + accessIn, refreshTokenExpiresAt = now + refreshIn,
    )

    @Test fun `no credential says so plainly`() {
        assertEquals("Not connected", TokenStatus.describe(null, now))
        assertFalse(TokenStatus.healthy(null, now))
    }

    /**
     * The failure most worth phrasing correctly. A dead refresh token means the panel
     * quietly stops updating, and "connected" would send the user hunting for a
     * placement bug that does not exist.
     */
    @Test fun `an expired sign-in is reported as expired`() {
        val dead = record(accessIn = -1000, refreshIn = -1000)
        assertTrue(TokenStatus.describe(dead, now).startsWith("Expired"))
        assertFalse(TokenStatus.healthy(dead, now))
    }

    @Test fun `an expired access token is not a problem worth alarming about`() {
        val stale = record(accessIn = -1000, refreshIn = 7 * 24 * 3600_000L)
        val text = TokenStatus.describe(stale, now)
        assertTrue(text, text.contains("will refresh"))
        assertTrue(TokenStatus.healthy(stale, now))
    }

    @Test fun `a healthy token reports both lifetimes`() {
        val ok = record(accessIn = 3 * 3600_000L, refreshIn = 30 * 24 * 3600_000L)
        val text = TokenStatus.describe(ok, now)
        assertTrue(text, text.contains("3h"))
        assertTrue(text, text.contains("30d"))
    }

    /** A missing expiry is unknown, not infinite — saying "never" would be a promise. */
    @Test fun `a token with no refresh expiry does not claim to last forever`() {
        val noExpiry = TokenRecord("a", "r", expiresAt = now + 3600_000L, refreshTokenExpiresAt = 0L)
        val text = TokenStatus.describe(noExpiry, now)
        assertFalse(text, text.contains("never"))
        assertFalse(text, text.contains("sign-in lasts"))
    }

    /** The example must be something TokenImport actually accepts. */
    @Test fun `the example parses`() {
        val parsed = TokenImport.parse(TokenStatus.EXAMPLE)
        assertTrue("the example shown to users does not parse", parsed != null)
    }
}
