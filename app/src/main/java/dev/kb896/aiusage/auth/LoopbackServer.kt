package dev.kb896.aiusage.auth

import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder

/**
 * One-shot localhost listener for the OAuth redirect. Blocking; call off the
 * main thread.
 *
 * Binds [InetAddress.getLoopbackAddress] explicitly rather than the wildcard
 * address `ServerSocket(port)` would otherwise pick — so this only ever
 * listens on 127.0.0.1, never on the phone's Wi-Fi interface. Anything else
 * on the LAN cannot reach it or hand it a spoofed authorization code.
 */
class LoopbackServer(private val port: Int = 54545) {

    /**
     * Waits for one browser redirect and returns its `code` query parameter.
     *
     * Returns null on timeout, a malformed request, or a missing/mismatched
     * `state`. The state check is the CSRF defence: a callback that doesn't
     * carry the exact state this instance handed out is never trusted with an
     * exchange, regardless of what `code` it carries — so a code from a
     * different, attacker-initiated authorization flow delivered to this
     * loopback port is rejected rather than exchanged.
     *
     * The listening socket is closed on every return path — success,
     * timeout, and state mismatch alike — via `use`, so the port is never
     * left held open by this instance.
     */
    fun awaitCode(expectedState: String, timeoutMs: Int = 180_000): String? {
        ServerSocket(port, 0, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = timeoutMs
            val socket = try { server.accept() } catch (_: Exception) { return null }
            socket.use { s ->
                val line = try {
                    s.getInputStream().bufferedReader().readLine()
                } catch (_: Exception) { null } ?: return null
                val query = line.substringAfter("GET ", "").substringBefore(" HTTP").substringAfter("?", "")
                val params = query.split("&").mapNotNull {
                    val k = it.substringBefore("=")
                    val v = it.substringAfter("=", "")
                    if (k.isEmpty()) null else k to URLDecoder.decode(v, "UTF-8")
                }.toMap()

                val ok = params["state"] == expectedState && !params["code"].isNullOrEmpty()
                val body = if (ok) "Signed in. Return to AI Usage." else "Sign-in failed."
                try {
                    s.getOutputStream().writer().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n$body")
                        flush()
                    }
                } catch (_: Exception) {
                    // Best-effort response; the browser tab doesn't need it to complete the flow.
                }
                return if (ok) params["code"] else null
            }
        }
    }

    fun redirectUri(): String = "http://localhost:$port/callback"
}
