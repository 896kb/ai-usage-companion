package dev.kb896.aiusage.rear

import android.util.Log

/**
 * Keeps HyperOS's double tap from sleeping the back while an app is on it.
 *
 * MiuiSubscreenDoubleTapGesture sleeps the rear on two taps unless the FOCUSED window carries a
 * MIUI flag, which the panel's does and another app's never will, so over a guest a double tap
 * to zoom a map put the back to sleep. Its one other way out is Xiaomi's own game pad: while one
 * is connected the gesture stands aside (onPointerEvent, mIsGamePadConnected), and the native
 * side knows it by vendor and product id alone (libmiinputflinger, MiInputManager::onDeviceAdded).
 * So while a guest is on top this holds a virtual input device with those ids, made by the
 * platform's own `uinput` command. It has one key and no game pad sources, so Android classes it
 * as a keyboard and apps see no controller. Measured 2026-10-30: `game pad connect: true`, a
 * double tap reaches the app (a map zooms), no sleep; removed, `game pad connect: false`.
 *
 * ITS LIFE IS `uinput`'S STDIN. The command keeps the device until its input ends, and that pipe
 * closes with this process however it dies, so a killed service cannot leave the gesture off.
 *
 * ONLY EVER ONE. HyperOS does not count them: any one going clears the state for all of them.
 * Not thread-safe: the caller's lock.
 */
class BackGuard {
    private var process: Process? = null
    private var refused = false

    val on: Boolean get() = process?.isAlive == true

    fun set(wanted: Boolean) {
        if (wanted == on) return
        if (wanted) take() else drop()
    }

    private fun take() {
        if (refused) return
        drop()
        try {
            val p = ProcessBuilder("uinput", "-").redirectErrorStream(true).start()
            p.outputStream.apply {
                write(REGISTER.toByteArray())
                write('\n'.code)
                flush()
            }
            // Its output is read and thrown away: a full pipe would block it.
            Thread { runCatching { p.inputStream.use { it.copyTo(NullOutput) } } }
                .apply { isDaemon = true; name = "back-guard-out"; start() }
            process = p
            Log.i(TAG, "portal: double tap left to the app")
        } catch (e: Exception) {
            // No worse than before: HyperOS's double tap goes on sleeping the back.
            refused = true
            Log.w(TAG, "portal: no virtual device for the double tap (${e.message})")
        }
    }

    private fun drop() {
        val p = process ?: return
        process = null
        runCatching { p.outputStream.close() }
        runCatching { p.destroy() }
        Log.i(TAG, "portal: double tap back to HyperOS")
    }

    private object NullOutput : java.io.OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    private companion object {
        const val TAG = "AiUsageRear"
        /** Xiaomi's game pad's ids; EV_KEY (100: UI_SET_EVBIT 1) and one key nobody has (101: UI_SET_KEYBIT, KEY_F24). */
        const val REGISTER = """{"id":1,"command":"register","name":"ai-usage-back-guard","vid":0x2717,"pid":0x50E9,"bus":"bluetooth","configuration":[{"type":100,"data":[1]},{"type":101,"data":[194]}]}"""
    }
}
