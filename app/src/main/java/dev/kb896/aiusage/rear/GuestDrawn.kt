package dev.kb896.aiusage.rear

/**
 * Whether a guest moved onto the rear has drawn there yet, from `dumpsys window <pkg>`
 * (only that package's windows: a few KB, where the whole dump is megabytes). The sign the
 * GuestReveal cover waits for before it slides away.
 *
 * A window of the task's own (ty=BASE_APPLICATION) on the display, that ASKS for a size
 * within its shape on the rear ("Requested w= h=": the app's own relayout, so it has taken in
 * the move — WM's frame changes at the move itself, before the app has done anything; the
 * front's size is bigger than the rear's either way round), and whose draw has reached the
 * screen (mHasSurface=true, mDrawState=HAS_DRAWN). Within, not exactly: a guest that does not
 * fill its shape — letterboxed, portrait only, in size compat — never asks for the whole of
 * it, and every reveal of one waited the whole GuestReveal.MAX_WAIT_MS. Each window is read on
 * its own: one window's lines never stand in for another's.
 */
object GuestDrawn {

    private val windowStart = Regex("""(?m)^\s*Window #\d+ Window\{""")
    private val displayTask = Regex("""mDisplayId=(\d+) taskId=(\d+)""")
    private val requested = Regex("""Requested w=(\d+) h=(\d+)""")

    fun drawn(dump: String, task: Int, display: Int, width: Int, height: Int): Boolean {
        val starts = windowStart.findAll(dump).map { it.range.first }.toList()
        return starts.indices.any { i ->
            val block = dump.substring(starts[i], if (i + 1 < starts.size) starts[i + 1] else dump.length)
            val dt = displayTask.find(block)?.groupValues ?: return@any false
            val wh = requested.find(block)?.groupValues ?: return@any false
            dt[1].toInt() == display && dt[2].toInt() == task &&
                block.contains("ty=BASE_APPLICATION ") &&
                wh[1].toInt() in 1..width && wh[2].toInt() in 1..height &&
                block.contains("mHasSurface=true") && block.contains("mDrawState=HAS_DRAWN")
        }
    }
}
