package dev.kb896.aiusage.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.data.StatePayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * DEBUG BUILDS ONLY (declared in the debug manifest): a settings change from adb, saved and sent
 * to the back as the front's own rows send theirs, so a filmed take can change the background or
 * the weather without the panel restarting.
 *
 *   adb shell am broadcast -a dev.kb896.aiusage.dev.DEMO_CONFIG -p dev.kb896.aiusage.dev \
 *     --es json '{"shader":"bends","weather":"rain"}'
 *
 * The JSON is merged into the saved settings, objects key by key. Guarded by DUMP, as
 * [DemoNotifyReceiver] is.
 */
class DemoConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val patch = intent.getStringExtra("json") ?: return
        val store = ConfigStore(context)
        val base = Json.parseToJsonElement(PanelConfig.encode(store.load())).jsonObject
        val next = PanelConfig.decode(merge(base, Json.parseToJsonElement(patch).jsonObject).toString())
        store.save(next)
        val graph = Graph.of(context)
        graph.bridge.update(StatePayload.encode(graph.repository.state.value, next, WeatherPoll.latest))
        Log.i("AiUsageDemo", "config patched: $patch")
    }

    private fun merge(a: JsonObject, b: JsonObject): JsonObject = JsonObject(
        a + b.mapValues { (k, v) ->
            val old: JsonElement? = a[k]
            if (old is JsonObject && v is JsonObject) merge(old, v) else v
        },
    )
}
