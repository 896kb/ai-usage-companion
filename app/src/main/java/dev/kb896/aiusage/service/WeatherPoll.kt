package dev.kb896.aiusage.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.CancellationSignal
import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.data.WeatherReading
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The weather clock: one reading every five minutes while the panel is awake.
 *
 * Same shape as the usage loop in UsagePollService — park on the awake gate, do one
 * tick, wait — so a dark panel costs nothing and a waking one asks at once. The wait
 * can be cut short by [nudge], which the settings screen sends after a place or the
 * location switch changes; the gate still runs first, so a nudge from a dark panel
 * waits with everything else.
 *
 * The fetch and the location read are handed in as functions so the loop can be run on
 * the JVM with neither a network nor a LocationManager.
 */
class WeatherPoll(
    private val fetch: (Target) -> WeatherReading?,
    private val fix: suspend () -> Pair<Double, Double>?,
    private val configNow: () -> PanelConfig,
    private val onReading: (WeatherReading) -> Unit,
) {
    data class Target(val latitude: Double, val longitude: Double, val label: String)

    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            PanelAwake.awaitAwake()
            tick()
            withTimeoutOrNull(Cadence.WEATHER_MS) { nudges.receive() }
        }
    }

    suspend fun tick() {
        val config = configNow()
        val target = target(config, if (config.weatherUseDevice) fix() else null) ?: return
        // A failed fetch keeps the last reading: a stale fact beats a blank line, and the
        // panel drops it on its own after two hours.
        val reading = fetch(target) ?: return
        latest = reading
        onReading(reading)
    }

    companion object {
        /** The last reading, for every payload the process encodes. Null until the first lands. */
        @Volatile var latest: WeatherReading? = null

        private val nudges = Channel<Unit>(Channel.CONFLATED)

        fun nudge() { nudges.trySend(Unit) }

        /**
         * Device fix when the switch is on and one arrived; otherwise the typed place if
         * it resolved; otherwise nothing. The label is what the line shows after the
         * reading: "Here" for a fix with nothing typed, and nothing at all when a place
         * is typed too — the API cannot say where a fix is, and naming the typed place
         * for a reading taken somewhere else would be a lie.
         */
        fun target(config: PanelConfig, fix: Pair<Double, Double>?): Target? {
            if (config.weatherUseDevice && fix != null) {
                return Target(fix.first, fix.second, if (config.weatherPlace.isBlank()) "Here" else "")
            }
            val lat = config.weatherLat ?: return null
            val lon = config.weatherLon ?: return null
            return Target(lat, lon, config.weatherPlaceLabel)
        }
    }
}

/**
 * The phone's coarse position, or null: no permission, no provider, or nothing within
 * ten seconds. The current fix is asked for first; the last known one is the fallback,
 * because a stale position is still the right city.
 */
fun deviceFix(context: Context): suspend () -> Pair<Double, Double>? = {
    val lm = context.getSystemService(LocationManager::class.java)
    val allowed = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    if (lm == null || !allowed) null else try {
        val current = withTimeoutOrNull(10_000L) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                lm.getCurrentLocation(LocationManager.FUSED_PROVIDER, signal, context.mainExecutor) { loc ->
                    if (cont.isActive) cont.resume(loc)
                }
            }
        }
        val loc = current ?: lm.getLastKnownLocation(LocationManager.FUSED_PROVIDER)
        loc?.let { it.latitude to it.longitude }
    } catch (e: SecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        null   // the fused provider is missing on this device
    }
}
