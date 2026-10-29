package dev.kb896.aiusage.data

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Something said once, the first time it can be: [fire] runs its send until one goes
 * through (returns true), then never again. A send that finds nobody to take it (false),
 * or throws — a listener that died — leaves it to the next [fire].
 *
 * For the shell's "the portal sheet was opened" (IRearEvents.onPortalOpened): once a
 * process, and not lost to an app that had not registered yet.
 */
class OnceLatch {
    private val done = AtomicBoolean(false)

    val fired: Boolean get() = done.get()

    fun fire(send: () -> Boolean) {
        if (done.get()) return
        if (runCatching(send).getOrDefault(false)) done.set(true)
    }
}
