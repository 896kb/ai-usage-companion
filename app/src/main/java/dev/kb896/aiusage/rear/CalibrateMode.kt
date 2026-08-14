package dev.kb896.aiusage.rear

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The mode travels *inside* the payload (not a separate intent extra) so placement
 * always goes through the one proven path — RearUserService.show(payload, displayId)
 * — with no AIDL change. Tolerates non-JSON payloads (e.g. a bare status string):
 * anything that doesn't parse, or has no "mode":"calibrate", is the normal render.
 *
 * Pure String -> Boolean, no Android types — kept in its own file, like
 * RingGeometry/StackList/RampColor, specifically so it is independently testable
 * without Robolectric.
 */
object CalibrateMode {
    private const val MODE_CALIBRATE = "calibrate"

    fun isCalibrate(payload: String): Boolean = try {
        val root = Json.parseToJsonElement(payload) as? JsonObject
        (root?.get("mode") as? JsonPrimitive)?.contentOrNull == MODE_CALIBRATE
    } catch (_: Exception) {
        false
    }
}
