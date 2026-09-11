package dev.kb896.aiusage.service

import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.data.WeatherReading
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric only for run()'s exception guard below: it calls android.util.Log, which
// the plain JVM's android.jar stub throws on ("not mocked") — Robolectric's shadow is
// what lets a bad tick actually get logged instead of the test itself blowing up.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeatherPollTest {
    private val typed = PanelConfig.DEFAULT.copy(
        weatherPlace = "bucharest", weatherPlaceLabel = "Bucharest, Romania",
        weatherLat = 44.4, weatherLon = 26.1,
    )
    private val reading = WeatherReading(14f, 61, true, "Bucharest, Romania", 1L)

    @Before fun awake() { PanelAwake.setDozing(false); WeatherPoll.latest = null }
    @After fun restore() { PanelAwake.setDozing(false); WeatherPoll.latest = null }

    @Test fun `the typed place is the target when the phone is not asked`() {
        val t = WeatherPoll.target(typed, fix = null)!!
        assertEquals(44.4, t.latitude, 1e-9); assertEquals(26.1, t.longitude, 1e-9)
        assertEquals("Bucharest, Romania", t.label)
    }

    @Test fun `a device fix wins, labelled Here, or unlabelled when a place is also typed`() {
        val alone = WeatherPoll.target(PanelConfig.DEFAULT.copy(weatherUseDevice = true), fix = 51.5 to -0.1)!!
        assertEquals("Here", alone.label); assertEquals(51.5, alone.latitude, 1e-9)
        val both = WeatherPoll.target(typed.copy(weatherUseDevice = true), fix = 51.5 to -0.1)!!
        assertEquals("", both.label); assertEquals(-0.1, both.longitude, 1e-9)
    }

    @Test fun `no fix falls back to the typed place, and no place at all is no target`() {
        assertEquals("Bucharest, Romania", WeatherPoll.target(typed.copy(weatherUseDevice = true), fix = null)!!.label)
        assertNull(WeatherPoll.target(PanelConfig.DEFAULT, fix = null))
        assertNull(WeatherPoll.target(typed.copy(weatherLat = null), fix = null))   // typed but unresolved
    }

    @Test fun `a tick fetches for the target, keeps the reading, and tells the service`() = runBlocking {
        var told: WeatherReading? = null
        val poll = WeatherPoll(fetch = { reading }, fix = { null }, configNow = { typed }, onReading = { told = it })
        poll.tick()
        assertEquals(reading, WeatherPoll.latest); assertEquals(reading, told)
    }

    @Test fun `a failed fetch keeps the last reading and says nothing`() = runBlocking {
        WeatherPoll.latest = reading
        var told = 0
        WeatherPoll(fetch = { null }, fix = { null }, configNow = { typed }, onReading = { told++ }).tick()
        assertEquals(reading, WeatherPoll.latest); assertEquals(0, told)
    }

    @Test fun `no target is no fetch`() = runBlocking {
        var fetched = 0
        WeatherPoll(fetch = { fetched++; reading }, fix = { null }, configNow = { PanelConfig.DEFAULT }, onReading = {}).tick()
        assertEquals(0, fetched)
    }

    @Test fun `while the panel is dark the loop parks before fetching`() = runBlocking {
        PanelAwake.setDozing(true)
        var fetched = 0
        val poll = WeatherPoll(fetch = { fetched++; reading }, fix = { null }, configNow = { typed }, onReading = {})
        assertNull(withTimeoutOrNull(300) { poll.run() })
        assertEquals(0, fetched)
    }

    @Test fun `a tick that throws is logged and skipped, not fatal to the loop`() = runBlocking {
        var calls = 0
        val poll = WeatherPoll(
            fetch = { calls++; if (calls == 1) throw RuntimeException("boom") else reading },
            fix = { null }, configNow = { typed }, onReading = {},
        )
        // Bypasses the five-minute wait so the second (surviving) tick runs inside the
        // test's own timeout rather than the real Cadence.WEATHER_MS.
        WeatherPoll.nudge()
        withTimeoutOrNull(300) { poll.run() }
        assertEquals(reading, WeatherPoll.latest)
    }

    @Test fun `a nudge buffered while dark does not also fire the moment the panel wakes`() = runBlocking {
        PanelAwake.setDozing(true)
        WeatherPoll.nudge()
        var fetched = 0
        val poll = WeatherPoll(fetch = { fetched++; reading }, fix = { null }, configNow = { typed }, onReading = {})
        launch { delay(100); PanelAwake.setDozing(false) }
        withTimeoutOrNull(400) { poll.run() }
        assertEquals(1, fetched)
    }
}
