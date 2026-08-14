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

    @Test fun `authorizeUrl carries the challenge, state, client id and redirect uri`() {
        val challenge = PkceChallenge.generate()
        val url = client.authorizeUrl(challenge, "http://localhost:54545/callback")
        assertTrue(url.startsWith("https://claude.ai/oauth/authorize?"))
        assertTrue(url.contains("client_id=${OAuthClient.CLIENT_ID}"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("code_challenge=${challenge.challenge}"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("state=${challenge.state}"))
        assertTrue(url.contains("redirect_uri=http%3A%2F%2Flocalhost%3A54545%2Fcallback"))
    }

    @Test fun `exchange on a malformed 200 body raises a Failure without echoing the body, code, or verifier`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>502 Bad Gateway</html>"))
        val f = runCatching {
            client.exchange("auth-code-secret", "verifier-secret", "http://localhost:54545/callback")
        }.exceptionOrNull() as OAuthClient.Failure
        assertTrue(!f.message!!.contains("<html>"))
        assertTrue(!f.message!!.contains("502 Bad Gateway"))
        assertTrue(!f.message!!.contains("auth-code-secret"))
        assertTrue(!f.message!!.contains("verifier-secret"))
    }

    @Test fun `exchange on a non-2xx response raises a Failure without echoing the code or verifier`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
        val f = runCatching {
            client.exchange("auth-code-secret", "verifier-secret", "http://localhost:54545/callback")
        }.exceptionOrNull() as OAuthClient.Failure
        assertTrue(!f.message!!.contains("auth-code-secret"))
        assertTrue(!f.message!!.contains("verifier-secret"))
        assertTrue(!f.message!!.contains("invalid_grant"))
    }

    @Test fun `exchange parses a successful token response`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"access_token":"at","refresh_token":"rt","expires_in":3600}"""
            )
        )
        val record = client.exchange("auth-code-secret", "verifier-secret", "http://localhost:54545/callback")
        assertTrue(record.accessToken == "at")
        assertTrue(record.refreshToken == "rt")
    }
}
