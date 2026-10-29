package dev.kb896.aiusage.rear

import dev.kb896.aiusage.Panel

/**
 * Where a guest app sits on the rear: the whole display, or — for an app asked to "Keep
 * right of the cameras" (PanelConfig.portalConfined, experimental) — right of the lenses. Pure; RearUserService
 * applies it (TaskShaper, else `am task resize`) after every move onto the rear.
 *
 * Fullscreen, an app drew under the lenses ("my store app shows fullscreen", "the map runs
 * under the cameras"). Measured by hand on the phone: started in multi-window
 * (`am start --windowingMode 6`), moved to display 1, THEN resized to [CONFINED] — resized
 * on display 0 first, it landed at x 0–608 — the app lays out in x 296–904. A task left in
 * multi-window is reused by the app's next launch, so the shape is asserted on every
 * launch, and wherever a task can lose it (back from the front, a density change, the
 * keep-alive seeing it [drifted]).
 */
object GuestShape {

    /** WindowConfiguration's windowing modes. */
    const val FULLSCREEN = 1
    const val MULTI_WINDOW = 6

    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }

    /** A task's [mode] and its [bounds] on the rear. */
    data class Shape(val mode: Int, val bounds: Bounds) {
        val confined get() = mode == MULTI_WINDOW
    }

    /** Right of the lens strip. */
    val CONFINED = Bounds(Panel.CUTOUT_RIGHT, 0, Panel.WIDTH, Panel.HEIGHT)
    val FULL = Bounds(0, 0, Panel.WIDTH, Panel.HEIGHT)

    fun of(fullscreen: Boolean): Shape = if (fullscreen) Shape(FULLSCREEN, FULL) else Shape(MULTI_WINDOW, CONFINED)

    /**
     * [pkg]'s shape, given the apps asked to keep right of the cameras: fullscreen unless it is
     * one of them. Opt-in, not the default (2026-10-29): a confined guest's task reads
     * [CONFINED] but HyperOS composes it at x 0–608 — the multi-window task is organised by
     * its split screen, which never offsets it on display 1 — and no nudge (resize, CHANGE
     * transition, freeform) moved it reliably.
     */
    fun forApp(pkg: String, confinedApps: Set<String>): Shape = of(pkg !in confinedApps)

    /** The task's bounds as read ([current], null when unread) are not [shape]'s: it is to be shaped again. */
    fun drifted(current: Bounds?, shape: Shape): Boolean = current != null && current != shape.bounds

    /** What `am start` adds for [shape]: started in multi-window when it is to be confined. */
    fun launchFlags(shape: Shape): String = if (shape.confined) "--windowingMode $MULTI_WINDOW " else ""
}
