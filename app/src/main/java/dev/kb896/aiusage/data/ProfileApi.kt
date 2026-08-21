package dev.kb896.aiusage.data

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
 * Only the tier reaches the PANEL, and that limit is load-bearing: the panel renders on
 * the back of the phone, facing outward, readable by anyone standing behind the user, and
 * an org name there would identify their employer to a stranger. A tier is a plan
 * bracket, not an identity.
 *
 * Identity is extracted too, for the SETTINGS screen only, where the question is "which
 * account am I editing" and the screen is in the user's hand rather than facing the room.
 * It never enters StatePayload, so it cannot reach the panel by accident.
 */
class ProfileApi(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl = "https://api.anthropic.com/".toHttpUrl(),
) {
    /**
     * Who an account belongs to, for recognising it in settings.
     *
     * Every field nullable: this is used to tell two accounts apart, so a partial answer
     * is still useful and a missing one must not read as an error.
     */
    data class Identity(val name: String?, val email: String?, val organization: String?)

    /**
     * Returns a short display tier ("MAX 5X", "PRO"), or null when it cannot be read.
     * Never throws: a missing plan tag is a cosmetic loss and must not fail a refresh
     * or disturb the usage numbers, which are the point of the app.
     */
    /**
     * Tier and identity from ONE request.
     *
     * The same response carries both, so fetching them separately would double this
     * app's calls to an endpoint family that also enforces its rate limits — for a
     * string that changes about as often as a job does.
     */
    fun profile(accessToken: String): Pair<String?, Identity?> {
        val body = fetchBody(accessToken) ?: return null to null
        return parseTier(body) to parseIdentity(body)
    }

    private fun fetchBody(accessToken: String): String? {
        val req = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("api/oauth/profile").build())
            .header("Authorization", "Bearer $accessToken")
            .header("anthropic-beta", "oauth-2025-04-20")
            .header("User-Agent", Agent.VALUE)
            .build()
        return try {
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) null else res.body?.string()
            }
        } catch (_: IOException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    fun tier(accessToken: String): String? {
        val req = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("api/oauth/profile").build())
            .header("Authorization", "Bearer $accessToken")
            .header("anthropic-beta", "oauth-2025-04-20")
            .header("User-Agent", Agent.VALUE)
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
        /** Name, email and organisation, for the settings screen. */
        internal fun parseIdentity(body: String): Identity? {
            val root = try {
                Json.parseToJsonElement(body) as? JsonObject
            } catch (_: SerializationException) {
                null
            } ?: return null
            val account = root["account"] as? JsonObject
            val org = root["organization"] as? JsonObject
            fun str(o: JsonObject?, key: String) =
                (o?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            val identity = Identity(
                name = str(account, "full_name") ?: str(root, "full_name"),
                email = str(account, "email_address") ?: str(account, "email")
                    ?: str(root, "email_address"),
                organization = str(org, "name"),
            )
            return if (identity.name == null && identity.email == null &&
                identity.organization == null) null else identity
        }

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
