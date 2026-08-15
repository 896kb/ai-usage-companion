package dev.rgarjoaba.claudestatus.data

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Reads the `limits[]` array from GET /api/oauth/usage.
 *
 * Deliberately ignores the top-level window keys: they carry a rotating set of
 * codenamed experimental buckets (tangelo, iguana_necktie, nimbus_quill, …) that
 * churn and are not a stable contract. `limits[]` is self-describing and stable.
 *
 * Uses kotlinx.serialization's JsonElement tree (already a project dependency) rather
 * than org.json: org.json.JSONObject is part of the Android platform stub jar, and its
 * methods throw ("not mocked") in plain JVM unit tests without Robolectric — which this
 * module does not use. This is tree-walking only; there is no typed/@Serializable wire
 * model here, that is Task 11's concern.
 *
 * Malformed input (not valid JSON at all, or valid JSON that isn't the expected shape)
 * degrades to an empty snapshot rather than throwing — this is the one place that knows
 * the wire format, so it is the one place responsible for not letting a captive-portal
 * page, a truncated body, or an empty response crash the caller. Any parse failure is
 * swallowed without inspecting or logging the offending text, since exception messages
 * from a JSON parser can embed fragments of the input.
 */
object UsageParser {

    fun parse(json: String, fetchedAt: Long): UsageSnapshot {
        val parsed = try {
            Json.parseToJsonElement(json)
        } catch (_: SerializationException) {
            return UsageSnapshot(emptyList(), fetchedAt)
        }
        val root = parsed as? JsonObject
            ?: return UsageSnapshot(emptyList(), fetchedAt)
        val limits = root["limits"] as? JsonArray
            ?: return UsageSnapshot(emptyList(), fetchedAt)

        val windows = ArrayList<UsageWindow>(3)
        for (element in limits) {
            val o = element as? JsonObject ?: continue
            val kind = WindowKind.fromWire(o.stringOrNull("kind")) ?: continue
            windows += UsageWindow(
                kind = kind,
                label = labelFor(kind, o),
                utilization = (o.percentOrZero() / 100.0).toFloat().coerceIn(0f, 1f),
                resetsAt = epochMillis(o.stringOrNull("resets_at")),
                // Absent means active: older responses had no such field, and a window
                // we cannot classify should read as running rather than as idle.
                isActive = (o["is_active"] as? JsonPrimitive)?.booleanOrNull ?: true,
            )
        }
        windows.sortBy { it.kind.ordinal }
        return UsageSnapshot(windows, fetchedAt)
    }

    private fun labelFor(kind: WindowKind, o: JsonObject): String = when (kind) {
        WindowKind.SESSION -> "5H"
        WindowKind.WEEKLY_ALL -> "7D"
        WindowKind.WEEKLY_SCOPED -> {
            val name = (o["scope"] as? JsonObject)
                ?.let { it["model"] as? JsonObject }
                ?.stringOrNull("display_name")
            if (name.isNullOrBlank()) "SCOPED" else name.uppercase()
        }
    }

    private fun epochMillis(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.percentOrZero(): Double =
        (this["percent"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
}
