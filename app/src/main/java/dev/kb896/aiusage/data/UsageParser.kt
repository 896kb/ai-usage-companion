package dev.kb896.aiusage.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Reads the `limits[]` array from GET /api/oauth/usage.
 *
 * Deliberately ignores the top-level window keys: they carry a rotating set of
 * codenamed experimental buckets (tangelo, iguana_necktie, nimbus_quill, …) that
 * churn, and the managed account carries extra keys the personal one does not.
 * `limits[]` is self-describing and stable across both.
 *
 * Uses kotlinx.serialization's JsonElement tree (already a project dependency) rather
 * than org.json: org.json.JSONObject is part of the Android platform stub jar, and its
 * methods throw ("not mocked") in plain JVM unit tests without Robolectric — which this
 * module does not use. This is tree-walking only; there is no typed/@Serializable wire
 * model here, that is Task 11's concern.
 */
object UsageParser {

    fun parse(json: String, fetchedAt: Long): UsageSnapshot {
        val root = Json.parseToJsonElement(json) as? JsonObject
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
