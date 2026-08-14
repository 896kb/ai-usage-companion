package dev.rgarjoaba.claudestatus.auth

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OAuthClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OAuthClient

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        client = OAuthClient(OkHttpClient(), server.url("/"))
    }

    @After fun tearDown() = server.shutdown()

    @Test fun `a non-JSON 200 body raises a Failure without echoing the body or the token`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>502 Bad Gateway</html>"))
        val f = runCatching { client.refresh("refresh-secret") }.exceptionOrNull() as OAuthClient.Failure
        assertTrue(!f.message!!.contains("<html>"))
        assertTrue(!f.message!!.contains("502 Bad Gateway"))
        assertTrue(!f.message!!.contains("refresh-secret"))
    }
}
