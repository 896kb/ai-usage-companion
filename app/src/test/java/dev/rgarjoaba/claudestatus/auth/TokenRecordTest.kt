package dev.rgarjoaba.claudestatus.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenRecordTest {

    private fun rec(exp: Long, refExp: Long = Long.MAX_VALUE) =
        TokenRecord("a", "r", expiresAt = exp, refreshTokenExpiresAt = refExp)

    @Test fun `access token is live well before expiry`() {
        // NOTE: brief originally specified exp = 10_000L here. With the mandated default
        // skewMs = 60_000L, that leaves only a 9s buffer — inside the 60s skew window — so
        // accessExpired() correctly returns true per the spec formula (now + skewMs >= expiresAt),
        // contradicting the assertFalse the brief also specified. This is a test-fixture defect,
        // not an implementation bug: the other three cases (including the explicit-skew case)
        // pass against the same formula. Corrected exp to comfortably clear the 60s skew so the
        // case actually exercises "well before expiry." See task-7-report.md for detail.
        assertFalse(rec(exp = 100_000L).accessExpired(now = 1_000L))
    }

    @Test fun `access token is expired after expiry`() {
        assertTrue(rec(exp = 1_000L).accessExpired(now = 10_000L))
    }

    @Test fun `skew treats an almost-expired token as expired`() {
        // expires in 30s, default skew is 60s — refresh proactively
        assertTrue(rec(exp = 30_000L).accessExpired(now = 0L, skewMs = 60_000L))
    }

    @Test fun `refresh expiry is independent of access expiry`() {
        val r = rec(exp = 0L, refExp = 5_000L)
        assertTrue(r.accessExpired(now = 1_000L))
        assertFalse(r.refreshExpired(now = 1_000L))
        assertTrue(r.refreshExpired(now = 6_000L))
    }
}
