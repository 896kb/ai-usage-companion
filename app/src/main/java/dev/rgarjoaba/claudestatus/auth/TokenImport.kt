package dev.rgarjoaba.claudestatus.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Parses a credential blob exported from the macOS keychain. Accepts either the
 * raw keychain shape ({"claudeAiOauth":{…}}) or an already-unwrapped object.
 * Returns null on anything malformed — never throws, never logs the content.
 *
 * kotlinx.serialization, not org.json: org.json is a stub in local JVM unit
 * tests and throws "not mocked". See Global Constraints.
 */
object TokenImport {

    fun parse(json: String): TokenRecord? = try {
        val root = Json.parseToJsonElement(json) as? JsonObject
        val o = (root?.get("claudeAiOauth") as? JsonObject) ?: root
        val access = o?.str("accessToken").orEmpty()
        val refresh = o?.str("refreshToken").orEmpty()
        if (access.isBlank() || refresh.isBlank()) null
        else TokenRecord(
            accessToken = access,
            refreshToken = refresh,
            expiresAt = o?.long("expiresAt") ?: 0L,
            refreshTokenExpiresAt = o?.long("refreshTokenExpiresAt") ?: 0L,
        )
    } catch (_: Exception) { null }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull
}
