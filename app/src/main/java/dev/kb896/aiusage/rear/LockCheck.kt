package dev.kb896.aiusage.rear

import android.util.Log

/**
 * Asks the system whether a code is the phone's lock code: the lock settings service's own
 * `verify`, run in the system (LockSettingsShellCommand), which reads it as the phone's lock type
 * reads (a pattern as its cells 1–9) and counts a wrong one as the front does.
 *
 * STRAIGHT TO THE SERVICE, NOT `cmd lock_settings`. A command line would carry the code in a
 * process's arguments for as long as it ran; this is one binder call from the shell's own process
 * (IBinder.shellCommand, hidden, reflected), its words read back through a pipe. Nothing is kept:
 * the code lives in the caller's Entry until it is cleared.
 */
object LockCheck {
    private const val TAG = "AiUsageRear"

    sealed interface Result {
        data object Right : Result
        data object Wrong : Result
        /** Too many wrong ones: the phone makes you wait, as on the front. */
        data object Throttled : Result
        /** The system could not be asked. */
        data class Failed(val why: String) : Result
    }

    /** Whether [code] is the phone's lock code. Blocks for the call; never on the reader thread. */
    fun verify(code: String): Result = try {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "lock_settings") as? android.os.IBinder ?: throw IllegalStateException("no lock_settings service")
        val said = shellCommand(binder, arrayOf("verify", "--old", code))
        when {
            said.contains("verified successfully", ignoreCase = true) -> Result.Right
            said.contains("throttled", ignoreCase = true) -> Result.Throttled
            said.contains("didn't match", ignoreCase = true) || said.contains("did not match", ignoreCase = true) -> Result.Wrong
            // Never logged as it is: a refusal names the code it was given.
            else -> Result.Failed("unrecognised answer (${said.length} chars)")
        }
    } catch (e: Exception) {
        Result.Failed((e.cause ?: e).javaClass.simpleName + ": " + (e.cause ?: e).message)
    }.also { if (it !is Result.Right) Log.i(TAG, "back lock: code checked — ${it.javaClass.simpleName}") }

    /** [args] run by [binder]'s own shell command, its output and errors together. */
    private fun shellCommand(binder: android.os.IBinder, args: Array<String>): String {
        val out = android.os.ParcelFileDescriptor.createPipe()
        val err = android.os.ParcelFileDescriptor.createPipe()
        val nothing = android.os.ParcelFileDescriptor.open(java.io.File("/dev/null"), android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val callback = Class.forName("android.os.ShellCallback")
            val m = binder.javaClass.getMethod(
                "shellCommand",
                java.io.FileDescriptor::class.java, java.io.FileDescriptor::class.java, java.io.FileDescriptor::class.java,
                Array<String>::class.java, callback, android.os.ResultReceiver::class.java,
            )
            m.invoke(binder, nothing.fileDescriptor, out[1].fileDescriptor, err[1].fileDescriptor, args, null, android.os.ResultReceiver(null))
        } finally {
            out[1].close(); err[1].close(); nothing.close()
        }
        val said = android.os.ParcelFileDescriptor.AutoCloseInputStream(out[0]).bufferedReader().readText() +
            android.os.ParcelFileDescriptor.AutoCloseInputStream(err[0]).bufferedReader().readText()
        return said
    }
}
