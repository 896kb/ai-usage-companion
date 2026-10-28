package dev.kb896.aiusage.rear

import android.graphics.PixelFormat
import android.view.SurfaceControl
import dev.kb896.aiusage.Panel

/**
 * One way to make a layer of the shell's own on the rear display: what the portal frame
 * and the portal sheet stand on. See PortalFrame for why a layer and not a window, and
 * for why a layer that should let touches through must be a trusted overlay.
 *
 * [create] builds the layer, puts it on the rear's layer stack (hidden, by reflection;
 * shell may call it and the frame's spike proved it on OS4.0.5.0), marks it a trusted
 * overlay or not, and sets its Z and its visibility — all in one transaction, so it
 * either arrives whole or not at all.
 *
 * FAIL CLOSED. A layer asked to be trusted that cannot be is never shown: the transaction
 * is not applied, the layer is released, and [Untrusted] is thrown so the caller can say
 * why. Any other failure releases what was made and throws as it came.
 */
internal object ShellLayer {

    /**
     * Z on the rear's layer stack, top-level. The frame stays on top of everything of
     * ours; the sheet goes under it, and the sheet's blocker under the sheet. For touches
     * the order between blocker and sheet does not matter: the InputDispatcher checks
     * every window above the touched one, so the untrusted blocker occludes the windows
     * below wherever it stands, and the trusted sheet and frame occlude nothing.
     */
    const val Z_FRAME = Int.MAX_VALUE
    const val Z_SHEET = Int.MAX_VALUE - 1
    const val Z_BLOCKER = Int.MAX_VALUE - 2

    /** gui::TrustedOverlay::ENABLED. UNSET is 0 and DISABLED 1, and 1 still has touches dropped. */
    const val TRUSTED_OVERLAY_ENABLED = 2

    /** A layer that had to be trusted could not be; [message] says why. */
    class Untrusted(why: String) : Exception(why)

    /**
     * A layer named [name], [Panel.WIDTH] by [Panel.HEIGHT], translucent, on [layerStack]
     * at [z], [trusted] or not, shown at once when [visible], the whole layer at [alpha]
     * (SurfaceFlinger's, on everything drawn into it — nothing to do with trust). Throws [Untrusted] when
     * [trusted] cannot be had, or whatever building it threw; nothing is left behind either way.
     */
    fun create(name: String, layerStack: Int, z: Int, trusted: Boolean, visible: Boolean = true, alpha: Float = 1f): SurfaceControl {
        val layer = SurfaceControl.Builder().setName(name).setBufferSize(Panel.WIDTH, Panel.HEIGHT)
            .setFormat(PixelFormat.TRANSLUCENT).build()
        try {
            SurfaceControl.Transaction().use { t ->
                SurfaceControl.Transaction::class.java
                    .getMethod("setLayerStack", SurfaceControl::class.java, Int::class.javaPrimitiveType)
                    .invoke(t, layer, layerStack)
                if (trusted) trust(t, layer)?.let { throw Untrusted(it) }
                if (alpha < 1f) t.setAlpha(layer, alpha)
                t.setLayer(layer, z).setVisibility(layer, visible).apply()
            }
            return layer
        } catch (e: Throwable) {
            runCatching { layer.release() }
            throw e
        }
    }

    /** Takes [layer] off the screen and lets it go. Safe on a layer already gone. */
    fun remove(layer: SurfaceControl?) {
        if (layer == null) return
        runCatching { SurfaceControl.Transaction().use { it.reparent(layer, null).apply() } }
        runCatching { layer.release() }
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
