package dev.kb896.aiusage.service

import kotlinx.coroutines.flow.first

/**
 * Whether the rear panel is currently something a person could be looking at.
 *
 * ONE FLAG, READ FROM TWO THREADS, and deliberately nothing more than that. The panel
 * announces its own state on ACTION_DOZING whenever it takes or releases the screen hold
 * — see RearActivity.holdScreen — and both the poll loop and the media watcher live in
 * this process, so a volatile boolean is the whole of the plumbing needed.
 *
 * WHAT IT IS FOR. A dark panel is worth nothing to look at, and some of what this app
 * does exists only to be looked at: fetching a lyric sheet over the network for a screen
 * that is off is a radio wake-up spent on nobody. Work that is only ever SEEN should ask
 * here first. Work that has other reasons to happen — a limit notification, the trail the
 * mascot's burst rule reads — must not, because those matter with the phone in a pocket.
 *
 * Defaults to awake. Being wrong that way costs one fetch; being wrong the other way
 * means a panel that never fills in.
 */
object PanelAwake {

    /**
     * A flow rather than a flag, so a coroutine can PARK on it.
     *
     * This is the difference between pausing and pretending to. A loop that checks a
     * boolean every few seconds and goes back to sleep is still waking the CPU on a
     * schedule; one suspended on `first { it }` is not scheduled at all until the panel
     * says otherwise. The poll loop uses the second kind — see awaitAwake.
     */
    private val awakeFlow = kotlinx.coroutines.flow.MutableStateFlow(true)

    /** Anything deferred while the panel was dark, to be run when it comes back. */
    @Volatile
    private var onWake: (() -> Unit)? = null

    private val awake: Boolean get() = awakeFlow.value

    fun isAwake(): Boolean = awake

    /** Suspends — costing nothing — until the panel is something a person could see. */
    suspend fun awaitAwake() {
        awakeFlow.first { it }
    }

    /**
     * Told by the panel itself. [dozing] is the sense ACTION_DOZING carries.
     *
     * The callback fires OUTSIDE the flag change and is cleared first, so a handler that
     * defers again — because the panel went back to sleep in between — replaces itself
     * rather than being wiped by its own invocation.
     */
    fun setDozing(dozing: Boolean) {
        val was = awake
        awakeFlow.value = !dozing
        if (!was && !dozing) {
            val pending = onWake
            onWake = null
            pending?.invoke()
        }
    }

    /** Run [work] now if the panel can be seen, or the next time it can. */
    fun whenAwake(work: () -> Unit) {
        if (awake) work() else onWake = work
    }
}
