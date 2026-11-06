package dev.kb896.aiusage.rear

import android.net.LocalServerSocket
import android.util.Log

/**
 * DEBUG BUILDS ONLY: made-up fingers for filming the back. Lines in `getevent <device>` form
 * ("type code value", hex) sent to the abstract local socket [SOCKET] are handed to the running touch
 * watch exactly as the rear touchscreen's own lines are, so a script can pull the sheet, the
 * control centre and the notifications, and touch a guest app, as a finger would.
 *
 * It exists because nothing else can: SELinux keeps shell from writing to the rear's evdev node
 * (and from making a named pipe in /data/local/tmp), and `input -d 1` reaches apps but never the
 * shell's getevent. A socket in the abstract namespace needs no file at all. One reader thread for the
 * process's life; each watch [attach]es itself, and lines arriving with no live watch are dropped.
 */
object DemoTouchFeed {
    private const val TAG = "AiUsageRear"
    const val SOCKET = "magic-rear-demo-touch"

    @Volatile private var sink: Pair<Process, (String) -> Unit>? = null
    private var started = false

    @Synchronized
    fun attach(watch: Process, take: (String) -> Unit) {
        sink = watch to take
        if (started) return
        started = true
        Thread {
            val server = try { LocalServerSocket(SOCKET) } catch (e: Exception) {
                Log.w(TAG, "demo touches: no socket (${e.message})"); return@Thread
            }
            Log.i(TAG, "demo touches: listening on @$SOCKET")
            while (true) {
                try {
                    // One writer at a time, for as long as it stays connected.
                    server.accept().use { client ->
                        client.inputStream.bufferedReader().useLines { lines ->
                            for (line in lines) {
                                val s = sink ?: continue
                                if (s.first.isAlive) s.second(line)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "demo touches: ${e.message}")
                    Thread.sleep(1000)
                }
            }
        }.apply { isDaemon = true; name = "demo-touches"; start() }
    }
}
