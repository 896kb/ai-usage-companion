package dev.kb896.aiusage.rear

/**
 * Where a guest's window really is on the rear, from `dumpsys SurfaceFlinger`'s composition
 * list — not where the window manager says its task is. On the phone (2026-10-28) a
 * confined guest's task read [296,0][904,572] in multi-window (mInSplitScreen=true) while
 * its surface was composed at x 0–608: nobody offset the task's leash on display 1. A
 * further `am task resize` moved it (seen by hand, twice). So the shell checks the surface
 * itself, and nudges until it is where its shape says ([misplaced]) — a few times, not
 * forever ([GiveUp]). Pure.
 *
 * In the list a layer's bounds read {left,top,bottom,right}. Of the app's windows on the
 * layer stack, the widest is its main one (a popup's are smaller and anywhere).
 */
object SurfacePlace {

    private val stackLine = Regex("""^\s*LayerStack=(\d+)\s*$""")
    // Every ] and } escaped: Android's regex is ICU's, which refuses a bare } (the JVM's, in the
    // tests, does not) — this object then failed to initialise on the phone, and every check
    // with it threw ("before the reveal: null").
    private val layerLine = Regex("""^\s*Layer \[\d+\] (.*)$""")
    private val boundsLine = Regex("""^\s*bounds=\{(-?\d+),(-?\d+),(-?\d+),(-?\d+)\}""")

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

    /**
     * Its window's left edge, as read ([left], null unread), is left of where its confined
     * [shape] starts: the one failure seen (drawn at x 0, under the lenses), and the only one a
     * nudge puts right. A guest drawn right of its shape's left — letterboxed, portrait only,
     * in size compat after a DPI change — is where the system puts it, and nudging it moved
     * nothing but cost six transitions a tick; a fullscreen guest has no left of its shape.
     */
    fun misplaced(left: Int?, shape: GuestShape.Shape): Boolean =
        shape.confined && left != null && left < shape.bounds.left

    /**
     * When to stop putting a guest right. A guest the shell cannot shape — an app that refuses
     * multi-window keeps fullscreen bounds, one in size compat keeps its own — was nudged or
     * reshaped every keep-alive tick, forever, each time under the launch's lock. So each [K]
     * (the service's: the task, its shape and its density) gets [tries] goes ([attempt]) until
     * it is seen right ([placed]); after that it is left as it is until the key changes. Any
     * thread.
     */
    class GiveUp<K>(private val tries: Int) {
        private var key: K? = null
        private var goes = 0
        private var announced = false

        /** One more go at [key]: true while it may be had, false once [tries] were had without a [placed]. */
        @Synchronized
        fun attempt(key: K): Boolean {
            if (this.key != key) { this.key = key; goes = 0; announced = false }
            if (goes >= tries) return false
            goes++
            return true
        }

        /** [key] is right now: it may be tried again when it next goes wrong. */
        @Synchronized
        fun placed(key: K) {
            if (this.key == key) { this.key = null; goes = 0; announced = false }
        }

        /** Whether [key] has had all its goes. */
        @Synchronized
        fun givenUp(key: K): Boolean = this.key == key && goes >= tries

        /** True the first time it is asked for [key] given up: the giving up logged once. */
        @Synchronized
        fun announce(key: K): Boolean {
            if (!givenUp(key) || announced) return false
            announced = true
            return true
        }
    }
}
