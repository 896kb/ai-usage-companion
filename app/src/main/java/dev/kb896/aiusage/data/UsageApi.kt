package dev.kb896.aiusage.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * The ONLY class permitted to know the usage wire format, alongside UsageParser.
 * Verified against live responses from both accounts on 2026-08-14.
 */
class UsageApi(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl = "https://api.anthropic.com/".toHttpUrl(),
) {
    enum class Kind { UNAUTHORIZED, RATE_LIMITED, NETWORK, SERVER }

    /** Message deliberately excludes the token — this is thrown into logs. */
    class Failure(
        val kind: Kind,
        message: String,
        /** Seconds the server asked us to wait, from the Retry-After header. */
        val retryAfterSec: Long? = null,
    ) : RuntimeException(message)

    fun fetch(accessToken: String): UsageSnapshot {
        val url = baseUrl.newBuilder().addPathSegments("api/oauth/usage").build()
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("anthropic-beta", "oauth-2025-04-20")
            .header("User-Agent", "claude-status/0.1 (android)")
            .get().build()

        val body: String
        try {
            client.newCall(req).execute().use { res ->
                when {
                    res.code == 401 || res.code == 403 ->
                        throw Failure(Kind.UNAUTHORIZED, "usage: HTTP ${res.code}")
                    res.code == 429 ->
                        throw Failure(
                            Kind.RATE_LIMITED,
                            "usage: HTTP 429",
                            res.header("Retry-After")?.toLongOrNull(),
                        )
                    !res.isSuccessful ->
                        throw Failure(Kind.SERVER, "usage: HTTP ${res.code}")
                }
                body = res.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            throw Failure(Kind.NETWORK, "usage: ${e.javaClass.simpleName}")
        }
        return UsageParser.parse(body, System.currentTimeMillis())
    }
}
