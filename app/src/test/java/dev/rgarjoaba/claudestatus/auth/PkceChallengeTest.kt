package dev.rgarjoaba.claudestatus.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/**
 * Uses java.util.Base64, not android.util.Base64: the latter is an Android
 * platform stub under local JVM unit tests and throws "not mocked" — the same
 * trap as org.json (see UsageParser.kt / Global Constraints). java.util.Base64
 * .getUrlEncoder().withoutPadding() is pure JVM (API 26+) and needs no
 * Robolectric. See PkceChallenge.kt for the matching implementation choice.
 */
class PkceChallengeTest {

    @Test fun `verifier is within the RFC 7636 length bounds`() {
        val c = PkceChallenge.generate()
        assertTrue("was ${c.verifier.length}", c.verifier.length in 43..128)
    }

    @Test fun `challenge is the base64url sha256 of the verifier without padding`() {
        val c = PkceChallenge.generate()
        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(c.verifier.toByteArray(Charsets.US_ASCII))
        )
        assertEquals(expected, c.challenge)
    }

    @Test fun `is url-safe with no padding`() {
        val c = PkceChallenge.generate()
        assertTrue(!c.challenge.contains('+'))
        assertTrue(!c.challenge.contains('/'))
        assertTrue(!c.challenge.contains('='))
    }

    @Test fun `successive challenges differ`() {
        assertNotEquals(PkceChallenge.generate().verifier, PkceChallenge.generate().verifier)
        assertNotEquals(PkceChallenge.generate().state, PkceChallenge.generate().state)
    }
}
