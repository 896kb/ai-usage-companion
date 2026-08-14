package dev.kb896.aiusage.auth

/** Never logged. Never broadcast. Never placed in an intent extra. */
data class TokenRecord(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val refreshTokenExpiresAt: Long,
) {
    fun accessExpired(now: Long, skewMs: Long = 60_000L): Boolean = now + skewMs >= expiresAt
    fun refreshExpired(now: Long): Boolean = now >= refreshTokenExpiresAt

    /** Guards against a token ending up in a log line by accident. */
    override fun toString(): String = "TokenRecord(expiresAt=$expiresAt, refreshExpiresAt=$refreshTokenExpiresAt)"
}
