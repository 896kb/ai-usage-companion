package dev.kb896.aiusage.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Not in the task brief's file list (only PkceChallengeTest.kt was), but added
 * because this task's security points call out two properties of
 * LoopbackServer that code-reading alone can't prove: the CSRF `state` check
 * actually rejects a mismatch instead of exchanging an attacker's code, and
 * the listening socket is genuinely released on every path (so a second bind
 * on the same port never fails). Real loopback sockets, no mocking, no
 * network access beyond 127.0.0.1.
 */
class LoopbackServerTest {

    private val pool = Executors.newCachedThreadPool()

    private fun freePort(): Int =
        ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }

    /** Retries the connect until the server thread has actually bound and is listening. */
    private fun sendGet(port: Int, path: String) {
        val deadline = System.currentTimeMillis() + 3_000
        var lastError: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                    socket.getOutputStream().writer().apply {
                        write("GET $path HTTP/1.1\r\nHost: localhost\r\n\r\n")
                        flush()
                    }
                    socket.getInputStream().bufferedReader().readLine()
                }
                return
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(10)
            }
        }
        throw IllegalStateException("could not connect to loopback server", lastError)
    }

    @Test fun `returns the code when state matches`() {
        val port = freePort()
        val future = pool.submit<String?> {
            LoopbackServer(port).awaitCode(expectedState = "abc123", timeoutMs = 5_000)
        }
        sendGet(port, "/callback?code=the-code&state=abc123")
        assertEquals("the-code", future.get(5, TimeUnit.SECONDS))
    }

    @Test fun `a state mismatch is rejected — the code is never returned`() {
        val port = freePort()
        val future = pool.submit<String?> {
            LoopbackServer(port).awaitCode(expectedState = "expected-state", timeoutMs = 5_000)
        }
        sendGet(port, "/callback?code=attacker-supplied-code&state=wrong-state")
        assertNull(future.get(5, TimeUnit.SECONDS))
    }

    @Test fun `a missing state is rejected`() {
        val port = freePort()
        val future = pool.submit<String?> {
            LoopbackServer(port).awaitCode(expectedState = "expected-state", timeoutMs = 5_000)
        }
        sendGet(port, "/callback?code=some-code")
        assertNull(future.get(5, TimeUnit.SECONDS))
    }

    @Test fun `an empty code is rejected even with a matching state`() {
        val port = freePort()
        val future = pool.submit<String?> {
            LoopbackServer(port).awaitCode(expectedState = "s", timeoutMs = 5_000)
        }
        sendGet(port, "/callback?code=&state=s")
        assertNull(future.get(5, TimeUnit.SECONDS))
    }

    @Test fun `on timeout the port is released, not held open`() {
        val port = freePort()
        assertNull(LoopbackServer(port).awaitCode(expectedState = "x", timeoutMs = 200))
        // If the previous call left the socket open, this bind throws BindException.
        ServerSocket(port, 0, InetAddress.getLoopbackAddress()).close()
    }

    @Test fun `after a served request the port is released, not held open`() {
        val port = freePort()
        val future = pool.submit<String?> {
            LoopbackServer(port).awaitCode(expectedState = "s", timeoutMs = 5_000)
        }
        sendGet(port, "/callback?code=c&state=s")
        future.get(5, TimeUnit.SECONDS)
        ServerSocket(port, 0, InetAddress.getLoopbackAddress()).close()
    }

    @Test fun `redirectUri points at the loopback callback path on the given port`() {
        assertEquals("http://localhost:54545/callback", LoopbackServer().redirectUri())
        assertEquals("http://localhost:9999/callback", LoopbackServer(9999).redirectUri())
    }
}
