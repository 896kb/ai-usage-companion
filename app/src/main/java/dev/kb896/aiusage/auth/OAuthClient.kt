package dev.kb896.aiusage.auth

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class OAuthClient(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl = "https://platform.claude.com/".toHttpUrl(),
) {
    /** Message deliberately excludes the token — this is thrown into logs. */
    class Failure(message: String) : RuntimeException(message)

    companion object {
        /**
         * Claude Code's public OAuth client.
         *
         * VERIFIED 2026-08-14: a real sign-in on device reached the authorize page,
         * the user approved it, and a code came back — so the client id is accepted.
         * It was the project's last unproven assumption from the design stage.
         */
        const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"

        /**
         * Space-separated, and the set matters: requesting a narrower set than the
         * client is registered for is one of the ways the token exchange returns 400.
         * `user:sessions:claude_code` is the one that makes this a Claude Code session
         * rather than a generic API token.
         */
        const val SCOPES =
            "user:inference user:profile user:sessions:claude_code user:mcp_servers"
    }

    fun refresh(refreshToken: String): TokenRecord {
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", CLIENT_ID)
            .build()
        val req = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("v1/oauth/token").build())
            .post(form).build()
        try {
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw Failure("refresh: HTTP ${res.code}")
                return toRecord(parseObject(res.body?.string().orEmpty(), "refresh"), refreshToken)
            }
        } catch (e: IOException) {
            throw Failure("refresh: ${e.javaClass.simpleName}")
        }
    }

    /** Authorization-code request URL for the personal account's on-device PKCE login (Task 10). */
    fun authorizeUrl(challenge: PkceChallenge, redirectUri: String): String =
        "https://claude.ai/oauth/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("code", "true")
            .addQueryParameter("client_id", CLIENT_ID)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("scope", SCOPES)
            .addQueryParameter("code_challenge", challenge.challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", challenge.state)
            .build().toString()

    /**
     * Exchanges an authorization code (from [LoopbackServer.awaitCode]) and its PKCE
     * verifier for tokens. `code` and `verifier` are single-use credential material —
     * see the class doc on [Failure] — so neither may appear in a thrown message, and
     * neither is logged here.
     */
    fun exchange(
        code: String,
        verifier: String,
        redirectUri: String,
        state: String,
    ): TokenRecord {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("client_id", CLIENT_ID)
            .add("code_verifier", verifier)
            // Anthropic's token endpoint requires `state` in the exchange body, not
            // just on the authorize URL. Omitting it returns HTTP 400.
            .add("state", state)
            .build()
        val req = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("v1/oauth/token").build())
            .post(form).build()
        try {
            client.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw Failure("exchange: HTTP ${res.code}${oauthError(body)}")
                return toRecord(parseObject(body, "exchange"), "")
            }
        } catch (e: IOException) {
            throw Failure("exchange: ${e.javaClass.simpleName}")
        }
    }

    /**
     * `body` on a 2xx token response carries the live access/refresh tokens, and
     * kotlinx.serialization's parse exceptions embed a snippet of the offending input
     * in their message — so a malformed body must never be echoed into a Failure.
     * Mirrors UsageParser's narrow SerializationException catch. [context] only labels
     * which caller failed ("refresh" / "exchange"); it never carries response content.
     */
    /**
     * Extracts RFC 6749's `error` code from a failed token response — and **only** that
     * field. Returns "" if absent or unparseable.
     *
     * Without this a 400 is undiagnosable: "exchange: HTTP 400" cannot distinguish a
     * wrong client id from an expired code from a redirect-uri mismatch, and the OAuth
     * flow is the one part of this app that cannot be tested without a live account.
     *
     * `error` is a bounded, spec-defined token ("invalid_grant", "invalid_client",
     * "unsupported_grant_type"). It carries no credential material.
     *
     * `error_description` is deliberately NOT included, though it is the more
     * informative field. It is free text at the server's discretion, and a server is
     * entirely at liberty to write "the code 'abc123' has expired" — which would put a
     * live authorization code into an exception message that gets logged. The bounded
     * field is worth surfacing; the unbounded one is not.
     */
    private fun oauthError(body: String): String = try {
        val o = Json.parseToJsonElement(body) as? JsonObject
        val err = (o?.get("error") as? JsonPrimitive)?.contentOrNull
        if (err.isNullOrBlank()) "" else " ($err)"
    } catch (_: Exception) { "" }

    internal fun parseObject(body: String, context: String): JsonObject {
        val parsed = try {
            Json.parseToJsonElement(body)
        } catch (_: SerializationException) {
            throw Failure("$context: malformed token response")
        }
        return parsed as? JsonObject ?: throw Failure("token response was not a JSON object")
    }

    internal fun toRecord(o: JsonObject, fallbackRefresh: String): TokenRecord {
        val now = System.currentTimeMillis()
        fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        fun long(k: String, default: Long) = (o[k] as? JsonPrimitive)?.longOrNull ?: default
        return TokenRecord(
            accessToken = str("access_token") ?: throw Failure("token response had no access_token"),
            refreshToken = str("refresh_token") ?: fallbackRefresh,
            expiresAt = now + long("expires_in", 3600L) * 1000L,
            refreshTokenExpiresAt = now + long("refresh_expires_in", 30L * 24 * 3600) * 1000L,
        )
    }
}
