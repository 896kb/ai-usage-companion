package dev.rgarjoaba.claudestatus.auth

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
    private val baseUrl: HttpUrl = "https://console.anthropic.com/".toHttpUrl(),
) {
    /** Message deliberately excludes the token — this is thrown into logs. */
    class Failure(message: String) : RuntimeException(message)

    companion object {
        /**
         * Claude Code's public OAuth client. UNVERIFIED — the one remaining
         * assumption in the project. Task 10 proves it with a real login; if it
         * fails, the import path from Task 9 already covers both accounts.
         */
        const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
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
            .addQueryParameter("scope", "user:profile user:inference")
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
    fun exchange(code: String, verifier: String, redirectUri: String): TokenRecord {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("client_id", CLIENT_ID)
            .add("code_verifier", verifier)
            .build()
        val req = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("v1/oauth/token").build())
            .post(form).build()
        try {
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw Failure("exchange: HTTP ${res.code}")
                return toRecord(parseObject(res.body?.string().orEmpty(), "exchange"), "")
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
