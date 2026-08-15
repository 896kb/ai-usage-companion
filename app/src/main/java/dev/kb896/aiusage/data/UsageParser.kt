package dev.kb896.aiusage.data

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

    /**
     * Row label: what the limit covers, then the window it covers it over.
     *
     * Both halves are load-bearing. "5H" and "7D" alone said the duration but not the
     * SCOPE, so nothing on the panel distinguished the all-models limits from the
     * per-model one — and the scoped row said "FABLE", which named the scope but no
     * duration at all, leaving the only per-model limit as the one row whose window was
     * a guess. "ALL 5H" / "ALL 7D" / "FABLE 7D" makes both readable without a legend.
     *
     * The model name comes from the live response (`scope.model.display_name`) and is
     * never hardcoded — whatever model the account's scoped limit applies to is what
     * appears here.
     */
    private fun labelFor(kind: WindowKind, o: JsonObject): String = when (kind) {
        WindowKind.SESSION -> "ALL 5H"
        WindowKind.WEEKLY_ALL -> "ALL 7D"
        WindowKind.WEEKLY_SCOPED -> {
            val name = (o["scope"] as? JsonObject)
                ?.let { it["model"] as? JsonObject }
                ?.stringOrNull("display_name")
            if (name.isNullOrBlank()) "SCOPED 7D" else "${name.uppercase()} 7D"
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
