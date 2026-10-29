package dev.kb896.aiusage.rear

import android.graphics.PixelFormat
import android.view.SurfaceControl

/**
 * One way to make a layer of the shell's own on the rear display: what the portal frame
 * and the portal sheet stand on. See PortalFrame for why a layer and not a window, and
 * for why a layer that should let touches through must be a trusted overlay.
 *
 * [create] builds the layer as a CHILD of this process's one container on the rear's layer
 * stack ([container]), marks it a trusted overlay or not, and sets its Z (among its siblings)
 * and its visibility — all in one transaction, so it either arrives whole or not at all.
 *
 * ONE CONTAINER, AND NOTHING ELSE AT THE ROOT. On this firmware a ROOT layer (no parent, only
 * a layer stack) is never taken out of SurfaceFlinger's tree: reparent(null) does nothing to
 * a root, and release() only drops our handle. Seen on the phone (2026-10-28), before the
 * container (53c7a9e): thirty frame, strip and cover layers — each its own root, released
 * without being hidden — still listed after the service had exited, the latest still faintly
 * drawn over the panel. So there is one root per process (setLayerStack, hidden, by
 * reflection — shell may call it), a container with nothing of its own to draw and NOT
 * trusted (so the untrusted blocker under it stays untrusted); every layer of ours is its
 * child, and a child reparented to null goes offscreen and is freed.
 *
 * A KILL LEAVES NOTHING. Measured on the phone (2026-10-29): a SIGKILLed shell process leaves
 * nothing composed — the container and every child go with it. [removeAll] is the orderly
 * way out (hidden at once, in one transaction, before the process exits), not a rescue of
 * layers that would otherwise stay.
 *
 * FAIL CLOSED. A layer asked to be trusted that cannot be is never shown: the transaction
 * is not applied, the layer is released, and [Untrusted] is thrown so the caller can say
 * why. Any other failure releases what was made and throws as it came.
 */
internal object ShellLayer {

    /**
     * Z among the container's children (the container itself is at the top of the rear's
     * layer stack). The frame stays on top of everything of
     * ours; the sheet goes under it, the sheet's blocker under the sheet, and a guest's
     * arrival cover under them all. For touches the order between blocker and sheet does
     * not matter: the InputDispatcher checks every window above the touched one, so the
     * untrusted blocker occludes the windows below wherever it stands, and the trusted
     * sheet, frame and cover occlude nothing.
     */
    const val Z_FRAME = Int.MAX_VALUE
    const val Z_SHEET = Int.MAX_VALUE - 1
    const val Z_BLOCKER = Int.MAX_VALUE - 2
    /** A guest's arrival (GuestCover): over every app, under the sheet, so a pull meanwhile still opens over it. */
    const val Z_COVER = Int.MAX_VALUE - 3
    /** The camera strip beside a guest kept right of the cameras (CameraStrip): over every app, under the cover. */
    const val Z_STRIP = Int.MAX_VALUE - 4

    /** How long [removeAll] waits for a capture holding layers ([lease]) before it lets them go anyway. */
    private const val LEASE_WAIT_MS = 200L

    /** gui::TrustedOverlay::ENABLED. UNSET is 0 and DISABLED 1, and 1 still has touches dropped. */
    const val TRUSTED_OVERLAY_ENABLED = 2

    /**
     * Every layer made here and not yet [remove]d, for [removeAll]: whatever ends the process
     * first (the service's destroy, its last-chance uncaught-exception handler) takes them off
     * the rear at once, rather than leave them up until the process is gone.
     */
    private val live: MutableSet<SurfaceControl> = java.util.Collections.synchronizedSet(LinkedHashSet())

    /**
     * Read-held while a [lease] uses layers; write-held around each release in [remove]. A
     * capture that leaves our layers out (GuestCover, the camera strip's colour) is handed
     * them by their owners' threads, which may release them at any moment — and a layer
     * released while the capture's arguments are being written is a freed native object.
     */
    private val releasing = java.util.concurrent.locks.ReentrantReadWriteLock()

    /**
     * [use] with [layers] — the nulls, and any already [remove]d, dropped — none of which can
     * be released until it returns. For a capture's leave-out list, held only as long as the
     * capture; never make or [remove] a layer inside it (a read lock cannot become a write lock).
     */
    fun <T> lease(layers: List<SurfaceControl?>, use: (List<SurfaceControl>) -> T): T {
        val lock = releasing.readLock()
        lock.lock()
        try {
            return use(layers.filterNotNull().filter { it in live })
        } finally {
            lock.unlock()
        }
    }

    /** Set by [removeAll], as the process ends: [create] makes nothing more. */
    @Volatile private var closed = false

    /** The container on each layer stack ([container]), made once per process. Under [containers]' lock. */
    private val containers = HashMap<Int, SurfaceControl>()

    /**
     * This process's root on [layerStack], made the first time: a container layer (hidden
     * Builder.setContainerLayer; a layer with no buffer where there is none) at the top of the
     * stack, shown, not trusted.
     */
    fun container(layerStack: Int): SurfaceControl = synchronized(containers) {
        containers[layerStack]?.let { return it }
        val b = SurfaceControl.Builder().setName("ai-usage-shell")
        runCatching { SurfaceControl.Builder::class.java.getMethod("setContainerLayer").invoke(b) }
        val c = b.build()
        try {
            SurfaceControl.Transaction().use { t ->
                SurfaceControl.Transaction::class.java
                    .getMethod("setLayerStack", SurfaceControl::class.java, Int::class.javaPrimitiveType)
                    .invoke(t, c, layerStack)
                t.setLayer(c, Int.MAX_VALUE).setVisibility(c, true).apply()
            }
        } catch (e: Throwable) {
            runCatching { c.release() }
            throw e
        }
        containers[layerStack] = c
        c
    }

    /**
     * Every live layer off the rear in one transaction, then let go — synchronously, as the
     * last thing a dying process does: each child hidden, at alpha 0 and reparented to null
     * (offscreen); each container hidden and at alpha 0, since SurfaceFlinger keeps a root
     * after its handle dies — empty and hidden, then. Quiet: there is nobody left to tell.
     */
    fun removeAll() {
        // Nothing new after this: a render thread still running as the process ends makes no layer.
        closed = true
        val all = synchronized(live) { live.toList().also { live.clear() } }
        val roots = synchronized(containers) { containers.values.toList().also { containers.clear() } }
        if (all.isEmpty() && roots.isEmpty()) return
        runCatching {
            SurfaceControl.Transaction().use { t ->
                for (l in all) runCatching { t.setVisibility(l, false).setAlpha(l, 0f).reparent(l, null) }
                for (r in roots) runCatching { t.setVisibility(r, false).setAlpha(r, 0f) }
                t.apply()
            }
        }
        // Past a capture in flight if it can be ([lease]); the process is ending either way.
        val lock = releasing.writeLock()
        val held = runCatching { lock.tryLock(LEASE_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrDefault(false)
        try {
            for (l in all) runCatching { l.release() }
            for (r in roots) runCatching { r.release() }
        } finally {
            if (held) lock.unlock()
        }
    }

    /** A layer that had to be trusted could not be; [message] says why. */
    class Untrusted(why: String) : Exception(why)

    /**
     * A layer named [name], [width] by [height] real pixels, translucent, on [layerStack]
     * at [z], [trusted] or not, shown at once when [visible], the whole layer at [alpha]
     * (SurfaceFlinger's, on everything drawn into it — nothing to do with trust). Throws [Untrusted] when
     * [trusted] cannot be had, or whatever building it threw; nothing is left behind either way.
     */
    fun create(
        name: String, layerStack: Int, z: Int,
        // No default: the 17 Pro's 904x572 was one, and on a bigger rear it left a strip
        // uncovered at the right and the bottom. The size is the caller's, from RearGeometry.
        width: Int, height: Int,
        trusted: Boolean, visible: Boolean = true, alpha: Float = 1f,
    ): SurfaceControl {
        check(!closed) { "the shell's layers are closed: the process is ending" }
        // A child of this process's container, never a root of its own (see the class comment).
        val layer = SurfaceControl.Builder().setName(name).setBufferSize(width, height)
            .setFormat(PixelFormat.TRANSLUCENT).setParent(container(layerStack)).build()
        try {
            SurfaceControl.Transaction().use { t ->
                if (trusted) trust(t, layer)?.let { throw Untrusted(it) }
                if (alpha < 1f) t.setAlpha(layer, alpha)
                t.setLayer(layer, z).setVisibility(layer, visible).apply()
            }
            live.add(layer)
            return layer
        } catch (e: Throwable) {
            // Off the container too, not only our handle dropped: a released child is still
            // its child, in SurfaceFlinger's tree, until the container goes.
            remove(layer)
            throw e
        }
    }

    /**
     * Takes [layer] off the screen and lets it go: hidden, at alpha 0 and reparented to null
     * (offscreen, so freed once released) in one transaction — each of them on its own would
     * do, and none is left to chance. Safe on a layer already gone.
     */
    fun remove(layer: SurfaceControl?) {
        if (layer == null) return
        live.remove(layer)
        runCatching { SurfaceControl.Transaction().use { it.setVisibility(layer, false).setAlpha(layer, 0f).reparent(layer, null).apply() } }
        // Not while a capture holds it ([lease]): it waits for the capture, which is short.
        val lock = releasing.writeLock()
        lock.lock()
        try { runCatching { layer.release() } } finally { lock.unlock() }
    }

    /** Marks [layer] a trusted overlay in [t]; null when done, else why it could not be. */
    private fun trust(t: SurfaceControl.Transaction, layer: SurfaceControl): String? {
        val call = trustedOverlayCall(SurfaceControl.Transaction::class.java, SurfaceControl::class.java)
            ?: return "no setTrustedOverlay on this firmware"
        return try {
            call.first.invoke(t, layer, call.second)
            null
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            "${cause.javaClass.simpleName}: ${cause.message}"
        }
    }

    /**
     * The hidden setTrustedOverlay on [txClass] and the value that turns it on: the
     * (layer, int) overload with [TRUSTED_OVERLAY_ENABLED] where there is one, else the
     * older (layer, boolean) with true; null for neither. [layerClass] is SurfaceControl,
     * a parameter so a test can hand it stand-ins.
     */
    fun trustedOverlayCall(txClass: Class<*>, layerClass: Class<*>): Pair<java.lang.reflect.Method, Any>? {
        fun find(arg: Class<*>) = runCatching { txClass.getMethod("setTrustedOverlay", layerClass, arg) }.getOrNull()
        find(Int::class.javaPrimitiveType!!)?.let { return it to TRUSTED_OVERLAY_ENABLED }
        find(Boolean::class.javaPrimitiveType!!)?.let { return it to true }
        return null
    }
}
