package dev.kb896.aiusage.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.CancellationSignal
import android.util.Log
import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.data.WeatherReading
import kotlinx.coroutines.CancellationException
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
 * waits with everything else, and is then spent by the wake-up tick rather than also
 * winning the wait behind it — see the comment in [run] for why that second win would
 * be one settings change buying two readings.
 *
 * [tick] is guarded the same way UsagePollService.safeTick guards the usage loop: one
 * bad fetch is logged and skipped, not left free to take the whole loop down with it.
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
            // Checked BEFORE the gate, not after: a nudge that piled up while the panel
            // was dark is already answered by the tick this wake is about to run — that
            // tick reads fresh config regardless of why it fired — so the token must not
            // ALSO win the wait below and buy a second reading for the same settings
            // change. A nudge sent while the panel was already awake is aimed at THIS
            // iteration's wait instead, and is left alone.
            val wasDark = !PanelAwake.isAwake()
            PanelAwake.awaitAwake()
            if (wasDark) nudges.tryReceive()
            try {
                tick()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Same hazard UsagePollService.safeTick guards against (see its KDoc):
                // left bare, one bad tick either escapes this while loop — ending the
                // weather line for the rest of the process's life — or reaches
                // Dispatchers.Default's uninstalled handler. One bad fetch must cost one
                // reading, not the loop.
                Log.e(TAG, "weather tick failed", e)
            }
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
        private const val TAG = "AiUsageWeather"

        /** The last reading, for every payload the process encodes. Null until the first lands. */
        @Volatile var latest: WeatherReading? = null

        private val nudges = Channel<Unit>(Channel.CONFLATED)

        fun nudge() { nudges.trySend(Unit) }

        /**
         * Device fix when the switch is on and one arrived; otherwise the typed place if
         * it resolved; otherwise nothing. The label is what the line shows after the
         * reading: "Here" for a fix with no place resolved, and nothing at all once one
         * has — the API cannot say where a fix is, and naming a resolved place for a
         * reading taken somewhere else would be a lie. Keyed on weatherLat, not on the
         * typed text: a place typed but never geocoded (lat still null) has nothing to
         * name, so the fix is still the only source this reading has — that is "Here",
         * not silence.
         */
        fun target(config: PanelConfig, fix: Pair<Double, Double>?): Target? {
            if (config.weatherUseDevice && fix != null) {
                return Target(fix.first, fix.second, if (config.weatherLat == null) "Here" else "")
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
