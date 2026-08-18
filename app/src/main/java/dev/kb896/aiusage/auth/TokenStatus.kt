package dev.kb896.aiusage.auth

/**
 * What to tell a user about an account's credential, in words.
 *
 * Pure and JVM-testable, because the thing most worth getting right here is the phrasing
 * of a failure — "signed in" against a refresh token that expired yesterday is worse than
 * saying nothing, since the panel will simply stop updating and look like a placement
 * bug.
 */
object TokenStatus {

    /** A single line: what this account has, and whether it still works. */
    fun describe(record: TokenRecord?, now: Long = System.currentTimeMillis()): String {
        if (record == null) return "Not connected"
        if (record.refreshExpired(now)) return "Expired — sign in or import again"
        val refreshLeft = record.refreshTokenExpiresAt - now
        val access = if (record.accessExpired(now)) "will refresh on the next poll"
            else "valid for ${humanise(record.expiresAt - now)}"
        return if (record.refreshTokenExpiresAt <= 0L) {
            // A token with no refresh expiry is not a token that never expires; it is a
            // token whose expiry was not in the JSON. Saying "never" would be a promise
            // this app cannot keep.
            "Connected — access $access"
        } else {
            "Connected — access $access, sign-in lasts ${humanise(refreshLeft)}"
        }
    }

    /** Whether this account can currently fetch, so the UI can colour the line. */
    fun healthy(record: TokenRecord?, now: Long = System.currentTimeMillis()): Boolean =
        record != null && !record.refreshExpired(now)

    private fun humanise(ms: Long): String {
        if (ms <= 0) return "no time"
        val minutes = ms / 60_000
        val hours = minutes / 60
        val days = hours / 24
        return when {
            days > 0 -> "${days}d"
            hours > 0 -> "${hours}h"
            else -> "${minutes}m"
        }
    }

    /**
     * The shape the paste field expects, with every value replaced.
     *
     * Shown rather than described, because "paste your credentials" is not an
     * instruction anyone can follow — this is a specific JSON object out of a keychain
     * entry, and the fields are named in a way nobody guesses.
     *
     * Both accepted forms are covered by showing the wrapper: TokenImport takes the whole
     * keychain blob or just the inner object, and the wrapper is what a user actually
     * copies.
     */
    const val EXAMPLE = """{
  "claudeAiOauth": {
    "accessToken": "sk-ant-oat01-xxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "refreshToken": "sk-ant-ort01-xxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "expiresAt": 1787000000000,
    "refreshTokenExpiresAt": 1789000000000
  }
}"""
}
