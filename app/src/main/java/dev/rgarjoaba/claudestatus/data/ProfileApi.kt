package dev.rgarjoaba.claudestatus.data

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * GET /api/oauth/profile, for one field: the account's rate-limit tier.
 *
 * Separate from UsageApi because it has an entirely different cadence. Usage changes
 * minute to minute; a plan does not change for months. This is fetched once and cached
 * for [ProfileStore.MAX_AGE_MS], so it costs roughly one request per account per week
 * against the same endpoint family that reports rate limits. Polling it on the usage
 * cadence would multiply this app's request count for a string that never moves.
 *
 * ONLY the tier is extracted. The response also carries full_name, email, the
 * organization's name and several uuids, and none of that belongs on this panel: it
 * renders on the BACK of the phone, facing outward, readable by anyone standing behind
 * the user. An org name in particular would identify their employer to a stranger. The
 * tier leaks nothing — it is a plan bracket, not an identity.
 */
class ProfileApi(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl = "https://api.anthropic.com/".toHttpUrl(),
) {
    /**
     * Returns a short display tier ("MAX 5X", "PRO"), or null when it cannot be read.
     * Never throws: a missing plan tag is a cosmetic loss and must not fail a refresh
     * or disturb the usage numbers, which are the point of the app.
     */
    fun tier(accessToken: String): String? {
        val req = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("api/oauth/profile").build())
            .header("Authorization", "Bearer $accessToken")
            .header("anthropic-beta", "oauth-2025-04-20")
            .header("User-Agent", "claude-status/0.1 (android)")
            .build()
        return try {
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return null
                parseTier(res.body?.string().orEmpty())
            }
        } catch (_: IOException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /**
         * Pulls `organization.rate_limit_tier` and shortens it for the panel.
         *
         * Observed live: "default_claude_max_5x". The transformation is deliberately
         * generic rather than a lookup table of known plans — this string is not a
         * documented contract, and a table would render an unrecognised future tier as
         * nothing at all. Stripping the boilerplate prefixes and upper-casing degrades
         * gracefully instead: an unknown "default_claude_wombat_9x" still reads as
         * "WOMBAT 9X", which is wrong-looking but informative, rather than blank.
         */
        internal fun parseTier(body: String): String? {
            val root = try {
                Json.parseToJsonElement(body) as? JsonObject
            } catch (_: SerializationException) {
                null
            } ?: return null
            val org = root["organization"] as? JsonObject ?: return null
            val raw = (org["rate_limit_tier"] as? JsonPrimitive)?.contentOrNull
            if (raw.isNullOrBlank()) return null
            return raw
                .removePrefix("default_")
                .removePrefix("claude_")
                .replace('_', ' ')
                .uppercase()
                .takeIf { it.isNotBlank() }
        }
    }
}
