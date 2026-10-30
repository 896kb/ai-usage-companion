package dev.kb896.aiusage.rear

/**
 * Where a guest app sits on the rear: the whole of display 1, the default, or — for an app asked
 * to "Keep right of the cameras" (PanelConfig.portalConfined) — right of the lenses, by being on a
 * display of its own (PrivateDisplay). Pure; RearUserService applies it.
 *
 * It used to be multi-window bounds on display 1 for that, nudged and re-asserted wherever they
 * could be lost. On HyperOS such a task read x 296–904 and was composed at x 0–608, whatever it
 * was nudged with (2026-10-28 to 30), and it is gone. What is left of a shape is a full-screen
 * guest's, which is only ever the display's own: TaskShaper asserts that after every move onto the
 * rear, since a task left in multi-window by anything is reused so by the app's next launch.
 */
object GuestShape {

    /** WindowConfiguration's windowing mode. */
    const val FULLSCREEN = 1

    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }

    /**
     * Right of the lens strip on [rear], in its real pixels: [296,0][904,572] on the 17 Pro. The
     * lenses keep their pixels on a wider display, so a Pro Max's is wider, not shifted. A private
     * display's size, and where its layer stands.
     */
    fun appArea(rear: RearGeometry) = Bounds(rear.cutoutPx, 0, rear.widthPx, rear.heightPx)

    /** The whole of [rear]: what a fullscreen task's bounds read there. */
    fun full(rear: RearGeometry) = Bounds(0, 0, rear.widthPx, rear.heightPx)

    /** Whether [pkg] goes on a private display, given the apps asked to keep right of the cameras. Opt-in. */
    fun onPrivateDisplay(pkg: String, confinedApps: Set<String>): Boolean = pkg in confinedApps

    /**
     * A full-screen guest's task bounds as read ([current], null when unread) are not the
     * display's ([full] of the real rear): to be shaped again.
     */
    fun drifted(current: Bounds?, full: Bounds): Boolean = current != null && current != full

    /**
     * When to stop putting a guest right. A guest the shell cannot shape — one in size compat
     * keeps its own bounds — was reshaped every keep-alive tick, forever, each time under the
     * launch's lock. So each [K] (the service's: the task and its density) gets [tries] goes
     * ([attempt]) until it is seen right ([placed]); after that it is left as it is until the key
     * changes. Any thread.
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
