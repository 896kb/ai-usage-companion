package dev.kb896.aiusage.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Open-Meteo, for one current reading and for turning a typed place into coordinates.
 *
 * Free, no key, and the terms ask only for attribution, which the settings row gives.
 * Same shape as LyricsApi: a parsed value or null, and nothing thrown across the
 * boundary — a dead network on a screen nobody is looking at is not news.
 */
class WeatherApi(
    client: OkHttpClient,
    private val forecastUrl: HttpUrl = "https://api.open-meteo.com/".toHttpUrl(),
    private val geocodeUrl: HttpUrl = "https://geocoding-api.open-meteo.com/".toHttpUrl(),
) {
    /** Ten seconds all in: a slow answer is worth exactly as much as none. */
    private val client = client.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()

    data class Place(val label: String, val latitude: Double, val longitude: Double)

    fun current(latitude: Double, longitude: Double, place: String, nowMs: Long): WeatherReading? {
        val url = forecastUrl.newBuilder()
            .addPathSegments("v1/forecast")
            .addQueryParameter("latitude", latitude.toString())
            .addQueryParameter("longitude", longitude.toString())
            .addQueryParameter("current", "temperature_2m,weather_code,is_day")
            .addQueryParameter("timezone", "auto")
            .build()
        val body = get(url) ?: return null
        return try {
            val cur = (Json.parseToJsonElement(body) as? JsonObject)?.get("current") as? JsonObject
                ?: return null
            val t = (cur["temperature_2m"] as? JsonPrimitive)?.floatOrNull ?: return null
            val code = (cur["weather_code"] as? JsonPrimitive)?.intOrNull ?: return null
            val day = ((cur["is_day"] as? JsonPrimitive)?.intOrNull ?: 1) != 0
            WeatherReading(t, code, day, place, nowMs)
        } catch (e: Exception) {
            null
        }
    }

    fun geocode(query: String): Place? {
        if (query.isBlank()) return null
        val url = geocodeUrl.newBuilder()
            .addPathSegments("v1/search")
            .addQueryParameter("name", query.trim())
            .addQueryParameter("count", "1")
            .addQueryParameter("language", "en")
            .addQueryParameter("format", "json")
            .build()
        val body = get(url) ?: return null
        return try {
            val first = ((Json.parseToJsonElement(body) as? JsonObject)?.get("results") as? JsonArray)
                ?.firstOrNull() as? JsonObject ?: return null
            val name = (first["name"] as? JsonPrimitive)?.contentOrNull ?: return null
            val country = (first["country"] as? JsonPrimitive)?.contentOrNull
            val lat = (first["latitude"] as? JsonPrimitive)?.doubleOrNull ?: return null
            val lon = (first["longitude"] as? JsonPrimitive)?.doubleOrNull ?: return null
            Place(if (country.isNullOrBlank()) name else "$name, $country", lat, lon)
        } catch (e: Exception) {
            null
        }
    }

    private fun get(url: HttpUrl): String? {
        val req = Request.Builder().url(url)
            .header("User-Agent", Agent.saying("rear panel weather"))
            .get().build()
        return try {
            client.newCall(req).execute().use { res -> if (!res.isSuccessful) null else res.body?.string() }
        } catch (e: IOException) { null } catch (e: IllegalStateException) { null }
    }
}
