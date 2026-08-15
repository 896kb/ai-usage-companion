package dev.rgarjoaba.claudestatus.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Broadcast wire format. Carries utilisations and timestamps ONLY — never a token.
 *
 * kotlinx.serialization, not org.json: org.json is a stub in local JVM unit tests
 * and throws "not mocked". See Global Constraints.
 */
object StatePayload {

    fun encode(state: DashboardState): String = buildJsonObject {
        put("fetchedAt", state.fetchedAt)
        putJsonArray("accounts") {
            for (s in state.statuses) {
                addJsonObject {
                    put("id", s.account.slug)
                    put("label", s.account.label)
                    put("state", s.health.wire)
                    put("hasSnapshot", s.snapshot != null)
                    s.planTier?.let { put("plan", it) }
                    put("mascot", s.mascot)
                    put("fetchedAt", s.snapshot?.fetchedAt ?: 0L)
                    putJsonArray("windows") {
                        s.snapshot?.windows?.forEach { w ->
                            addJsonObject {
                                put("kind", w.kind.wire)
                                put("label", w.label)
                                put("utilization", w.utilization)
                                // Explicit null, not omission — decode must be able to
                                // tell "unused window" from "field missing".
                                if (w.resetsAt == null) put("resetsAt", JsonNull)
                                else put("resetsAt", w.resetsAt)
                                put("isActive", w.isActive)
                            }
                        }
                    }
                }
            }
        }
    }.toString()

    fun decode(json: String): DashboardState = try {
        val root = Json.parseToJsonElement(json) as? JsonObject ?: return DashboardState.EMPTY
        val arr = root["accounts"] as? JsonArray ?: JsonArray(emptyList())
        val statuses = ArrayList<AccountStatus>(arr.size)
        for (element in arr) {
            val o = element as? JsonObject ?: continue
            val account = Account.fromSlug(o.str("id")) ?: continue
            val windows = ArrayList<UsageWindow>()
            for (we in (o["windows"] as? JsonArray ?: JsonArray(emptyList()))) {
                val w = we as? JsonObject ?: continue
                val kind = WindowKind.fromWire(w.str("kind")) ?: continue
                windows += UsageWindow(
                    kind = kind,
                    label = w.str("label").orEmpty(),
                    utilization = (w["utilization"] as? JsonPrimitive)?.floatOrNull ?: 0f,
                    resetsAt = (w["resetsAt"] as? JsonPrimitive)?.longOrNull,
                    isActive = (w["isActive"] as? JsonPrimitive)?.booleanOrNull ?: true,
                )
            }
            statuses += AccountStatus(
                account = account,
                health = Health.fromWire(o.str("state")),
                snapshot = if ((o["hasSnapshot"] as? JsonPrimitive)?.booleanOrNull == true) {
                    UsageSnapshot(windows, (o["fetchedAt"] as? JsonPrimitive)?.longOrNull ?: 0L)
                } else null,
                planTier = o.str("plan"),
                mascot = o.str("mascot") ?: MascotMood.STAND,
            )
        }
        DashboardState((root["fetchedAt"] as? JsonPrimitive)?.longOrNull ?: 0L, statuses)
    } catch (_: Exception) {
        DashboardState.EMPTY
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}
