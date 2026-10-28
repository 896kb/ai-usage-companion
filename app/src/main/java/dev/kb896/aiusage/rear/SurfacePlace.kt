package dev.kb896.aiusage.rear

/**
 * Where a guest's window really is on the rear, from `dumpsys SurfaceFlinger`'s composition
 * list — not where the window manager says its task is. On the phone (2026-10-28) a
 * confined guest's task read [296,0][904,572] in multi-window (mInSplitScreen=true) while
 * its surface was composed at x 0–608: nobody offset the task's leash on display 1. A
 * further `am task resize` moved it (seen by hand, twice). So the shell checks the surface
 * itself, and nudges until it is where its shape says ([misplaced]). Pure.
 *
 * In the list a layer's bounds read {left,top,bottom,right}. Of the app's windows on the
 * layer stack, the widest is its main one (a popup's are smaller and anywhere).
 */
object SurfacePlace {

    private val stackLine = Regex("""^\s*LayerStack=(\d+)\s*$""")
    private val layerLine = Regex("""^\s*Layer \[\d+] (.*)$""")
    private val boundsLine = Regex("""^\s*bounds=\{(-?\d+),(-?\d+),(-?\d+),(-?\d+)}""")

    /** The left edge of [pkg]'s widest window composed on [layerStack], or null when none is read. */
    fun left(dump: String, layerStack: Int, pkg: String): Int? {
        val start = dump.indexOf("Composition list")
        if (start < 0) return null
        var stack = -1
        var ours = false
        var best: Int? = null
        var bestWidth = -1
        for (line in dump.substring(start).lineSequence().drop(1)) {
            // Blank lines come INSIDE each layer's entry (after its geometry), so they end
            // nothing. The list ends at the next section's heading ("Input list"): the first
            // line at the margin that is neither a layer stack nor the time.
            if (line.isBlank()) continue
            if (!line[0].isWhitespace() && !line.startsWith("LayerStack=") && !line.startsWith("Current time")) break
            stackLine.find(line)?.let { stack = it.groupValues[1].toInt(); ours = false; return@let }
            val layer = layerLine.find(line)
            if (layer != null) { ours = stack == layerStack && layer.groupValues[1].startsWith("VRI-$pkg/"); continue }
            if (!ours) continue
            val b = boundsLine.find(line)?.groupValues ?: continue
            val l = b[1].toInt(); val r = b[4].toInt()
            if (r - l > bestWidth) { bestWidth = r - l; best = l }
            ours = false
        }
        return best
    }

    /** Its window's left edge, as read ([left], null unread), is not its [shape]'s. */
    fun misplaced(left: Int?, shape: GuestShape.Shape): Boolean = left != null && left != shape.bounds.left
}
