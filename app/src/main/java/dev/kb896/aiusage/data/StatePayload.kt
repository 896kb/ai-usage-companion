package dev.kb896.aiusage.data

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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Broadcast wire format. Carries utilisations, timestamps, the weather reading, and —
 * inside config — the coordinates and place the user typed. Never a token.
 *
 * kotlinx.serialization, not org.json: org.json is a stub in local JVM unit tests
 * and throws "not mocked". See Global Constraints.
 */
/**
 * Everything the rear process needs, in one object.
 *
 * State and config travel together because they ARRIVE together — the panel is
 * relaunched constantly and gets both in its launch intent. Two channels would let a
 * respawned panel draw one poll's numbers with another edit's colours.
 */
data class PanelPayload(
    val state: DashboardState,
    val config: PanelConfig = PanelConfig.DEFAULT,
    /** The last weather reading, or null when there has been none. Crosses as a fact. */
    val weather: WeatherReading? = null,
)

object StatePayload {

    fun encode(
        state: DashboardState,
        config: PanelConfig = PanelConfig.DEFAULT,
        weather: WeatherReading? = null,
    ): String = buildJsonObject {
        put("fetchedAt", state.fetchedAt)
        put("config", Json.parseToJsonElement(PanelConfig.encode(config)))
        putJsonArray("accounts") {
            for (s in state.statuses) {
                addJsonObject {
                    put("id", s.account.slug)
                    put("label", s.account.label)
                    put("state", s.health.wire)
                    put("hasSnapshot", s.snapshot != null)
                    s.planTier?.let { put("plan", it) }
                    put("mascot", s.mascot)
                    put("connected", s.connected)

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
        if (weather != null) {
            putJsonObject("weather") {
                put("temperatureC", weather.temperatureC)
                put("code", weather.code)
                put("isDay", weather.isDay)
                put("place", weather.place)
                put("fetchedAt", weather.fetchedAt)
            }
        }
    }.toString()

    fun decode(json: String): PanelPayload = try {
        val root = Json.parseToJsonElement(json) as? JsonObject ?: return PanelPayload(DashboardState.EMPTY)
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
                mascot = o.str("mascot") ?: MascotMood.WALK,
                connected = (o["connected"] as? JsonPrimitive)?.booleanOrNull ?: true,
            )
        }
        val weather = (root["weather"] as? JsonObject)?.let { w ->
            val t = (w["temperatureC"] as? JsonPrimitive)?.floatOrNull
            val code = (w["code"] as? JsonPrimitive)?.intOrNull
            if (t == null || code == null) null
            else WeatherReading(
                t, code,
                (w["isDay"] as? JsonPrimitive)?.booleanOrNull ?: true,
                w.str("place").orEmpty(),
                (w["fetchedAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
            )
        }
        PanelPayload(
            DashboardState((root["fetchedAt"] as? JsonPrimitive)?.longOrNull ?: 0L, statuses),
            PanelConfig.decode(root["config"]?.toString()),
            weather,
        )
    } catch (_: Exception) {
        PanelPayload(DashboardState.EMPTY)
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}
