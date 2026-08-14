package dev.kb896.aiusage.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * RFC 7636 PKCE verifier/challenge/state, generated with SecureRandom.
 *
 * Uses java.util.Base64, not android.util.Base64: the latter is an Android
 * platform stub under local JVM unit tests and throws "not mocked" — the same
 * trap as org.json (see Global Constraints). java.util.Base64.getUrlEncoder()
 * .withoutPadding() is pure JVM, available since API 26, and needs no
 * Robolectric.
 */
data class PkceChallenge(val verifier: String, val challenge: String, val state: String) {
    companion object {
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()

        fun generate(random: SecureRandom = SecureRandom()): PkceChallenge {
            val verifier = ENCODER.encodeToString(ByteArray(64).also(random::nextBytes))
            val challenge = ENCODER.encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            )
            val state = ENCODER.encodeToString(ByteArray(24).also(random::nextBytes))
            return PkceChallenge(verifier, challenge, state)
        }
    }
}
