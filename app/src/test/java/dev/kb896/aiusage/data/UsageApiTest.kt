package dev.kb896.aiusage.data

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UsageApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: UsageApi

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        api = UsageApi(OkHttpClient(), server.url("/"))
    }

    @After fun tearDown() = server.shutdown()

    private fun fixture() =
        javaClass.classLoader!!.getResourceAsStream("usage-personal.json")!!.bufferedReader().readText()

    @Test fun `sends the bearer token and the oauth beta header`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(fixture()))
        api.fetch("tok-123")
        val req = server.takeRequest()
        assertEquals("Bearer tok-123", req.getHeader("Authorization"))
        assertEquals("oauth-2025-04-20", req.getHeader("anthropic-beta"))
        assertTrue(req.path!!.endsWith("/api/oauth/usage"))
    }

    @Test fun `parses a 200 into a snapshot`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(fixture()))
        val snap = api.fetch("t")
        assertEquals(3, snap.windows.size)
    }

    @Test fun `401 raises UNAUTHORIZED`() {
        server.enqueue(MockResponse().setResponseCode(401))
        val f = runCatching { api.fetch("t") }.exceptionOrNull() as UsageApi.Failure
        assertEquals(UsageApi.Kind.UNAUTHORIZED, f.kind)
    }

    @Test fun `429 raises RATE_LIMITED`() {
        server.enqueue(MockResponse().setResponseCode(429))
        val f = runCatching { api.fetch("t") }.exceptionOrNull() as UsageApi.Failure
        assertEquals(UsageApi.Kind.RATE_LIMITED, f.kind)
    }

    @Test fun `500 raises SERVER`() {
        server.enqueue(MockResponse().setResponseCode(500))
        val f = runCatching { api.fetch("t") }.exceptionOrNull() as UsageApi.Failure
        assertEquals(UsageApi.Kind.SERVER, f.kind)
    }

    @Test fun `the failure message never contains the token`() {
        server.enqueue(MockResponse().setResponseCode(401))
        val f = runCatching { api.fetch("sk-ant-secret") }.exceptionOrNull() as UsageApi.Failure
        assertTrue(!f.message!!.contains("sk-ant-secret"))
    }
}
