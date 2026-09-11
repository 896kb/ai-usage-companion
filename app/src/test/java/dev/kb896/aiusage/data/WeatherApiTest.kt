package dev.kb896.aiusage.data

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WeatherApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: WeatherApi

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        api = WeatherApi(OkHttpClient(), forecastUrl = server.url("/"), geocodeUrl = server.url("/"))
    }
    @After fun tearDown() = server.shutdown()

    private val current = """{"latitude":44.4,"longitude":26.1,"timezone":"Europe/Bucharest",
        "current_units":{"temperature_2m":"°C"},
        "current":{"time":"2026-09-11T10:00","interval":900,"temperature_2m":14.4,"weather_code":61,"is_day":1}}"""

    @Test fun `parses a current reading and asks only for what it needs`() {
        server.enqueue(MockResponse().setBody(current))
        val r = api.current(44.4, 26.1, "Bucharest, Romania", nowMs = 5_000L)!!
        assertEquals(14.4f, r.temperatureC, 0.001f)
        assertEquals(61, r.code); assertTrue(r.isDay)
        assertEquals("Bucharest, Romania", r.place); assertEquals(5_000L, r.fetchedAt)
        val req = server.takeRequest()
        assertEquals("/v1/forecast", req.requestUrl!!.encodedPath)
        assertEquals("44.4", req.requestUrl!!.queryParameter("latitude"))
        assertEquals("26.1", req.requestUrl!!.queryParameter("longitude"))
        assertEquals("temperature_2m,weather_code,is_day", req.requestUrl!!.queryParameter("current"))
        assertEquals("auto", req.requestUrl!!.queryParameter("timezone"))
        assertTrue(req.getHeader("User-Agent").orEmpty().isNotBlank())
    }

    @Test fun `night is is_day zero`() {
        server.enqueue(MockResponse().setBody(current.replace("\"is_day\":1", "\"is_day\":0")))
        assertEquals(false, api.current(44.4, 26.1, "x", 0L)!!.isDay)
    }

    @Test fun `a failing or mangled response is null, never a throw`() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertNull(api.current(44.4, 26.1, "x", 0L))
        server.enqueue(MockResponse().setBody("not json"))
        assertNull(api.current(44.4, 26.1, "x", 0L))
        server.enqueue(MockResponse().setBody("""{"current":{"temperature_2m":"warm"}}"""))
        assertNull(api.current(44.4, 26.1, "x", 0L))
    }

    private val geo = """{"results":[{"id":683506,"name":"Bucharest","latitude":44.43225,"longitude":26.10626,
        "country":"Romania","admin1":"Bucuresti"}],"generationtime_ms":0.6}"""

    @Test fun `geocodes a typed place to its first match`() {
        server.enqueue(MockResponse().setBody(geo))
        val p = api.geocode("bucharest")!!
        assertEquals("Bucharest, Romania", p.label)
        assertEquals(44.43225, p.latitude, 1e-6); assertEquals(26.10626, p.longitude, 1e-6)
        val req = server.takeRequest()
        assertEquals("/v1/search", req.requestUrl!!.encodedPath)
        assertEquals("bucharest", req.requestUrl!!.queryParameter("name"))
        assertEquals("1", req.requestUrl!!.queryParameter("count"))
    }

    @Test fun `no match is null`() {
        server.enqueue(MockResponse().setBody("""{"generationtime_ms":0.3}"""))
        assertNull(api.geocode("zzzzzz"))
        assertNull(api.geocode("   "))
        assertEquals(1, server.requestCount)   // a blank query never leaves the phone
    }
}
