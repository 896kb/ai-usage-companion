package dev.kb896.aiusage.rear

import android.graphics.ColorSpace
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.util.Log
import android.view.SurfaceControl

/**
 * A picture of the rear, in this process: the window manager's captureDisplay (hidden
 * IWindowManager.captureDisplay with the synchronous listener of ScreenCaptureInternal, as
 * Android 16 calls it — ScreenCapture before), which shell may call, holding
 * READ_FRAME_BUFFER. Our own layers can be left out of it (CaptureArgs exclude-layers), and
 * it can be cropped (CaptureArgs source crop). For the guest's cover (GuestCover). And a
 * picture of one layer of ours ([layer]), straight from SurfaceFlinger: the camera strip's colour
 * off a private display's picture (CameraStrip).
 */
internal object RearCapture {

    private const val TAG = "AiUsageRear"

    /** Hidden capture classes, newest first: Android 16's name, then 14–15's. */
    private val CAPTURE_CLASSES = listOf("android.window.ScreenCaptureInternal", "android.window.ScreenCapture")

    /**
     * A capture: its [buffer] (the caller's to close), its colour [space], whether secure
     * layers in it came out black ([secure]), and whether the layers asked to be left out
     * were ([leftOut]: false when there were none, or the exclusion was refused).
     */
    class Shot(val buffer: HardwareBuffer, val space: ColorSpace?, val secure: Boolean, val leftOut: Boolean)

    private val logged = java.util.Collections.synchronizedSet(HashSet<String>())
    private fun once(why: String) { if (logged.add(why)) Log.w(TAG, "rear capture: $why") }

    /**
     * IWindowManager.captureDisplay([displayId], args leaving [leaveOut] out and cropped to
     * [crop], a synchronous listener), by reflection: null when the window manager answers
     * with no picture; throws when the calls are not there or are refused. Args that cannot
     * be built, or a capture refused with them (a layer released meanwhile cannot be sent),
     * are dropped for the default ones — then the layers are in the picture, [Shot.leftOut]
     * false, and a crop is lost too: the caller checks the buffer's size.
     */
    fun capture(displayId: Int, leaveOut: List<SurfaceControl>, crop: Rect? = null): Shot? = retried { captureOnce(displayId, leaveOut, crop) }

    private inline fun retried(once: () -> Shot?): Shot? = try {
        once()
    } catch (e: Throwable) {
        // The first capture in this process failed on the phone with "ApplicationSharedMemory
        // not initialized" (Android 16's shared-memory caches, never set up in app_process);
        // the ones after it worked. So once more, at once.
        val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
        if (cause is IllegalStateException && cause.message?.contains("ApplicationSharedMemory") == true) {
            Log.i(TAG, "rear capture: ${cause.message} — once more")
            once()
        } else throw e
    }

    /**
     * [layer], [crop] of it in its own pixels and scaled by [scaleY] down its height (fewer rows to
     * make and read): SurfaceFlinger's captureLayers, the layer's own buffer and not only its
     * children, no window manager in it, 8–37 ms on the phone (the spike, 2026-10-30). In whichever
     * shape this firmware has it. Null with no picture; throws when the calls are not there.
     */
    fun layer(layer: SurfaceControl, crop: Rect, scaleY: Float = 1f): Shot? = retried { layerOnce(layer, crop, scaleY) }

    private fun open(m: java.lang.reflect.Method) = m.apply { isAccessible = true }

    private fun layerOnce(layer: SurfaceControl, crop: Rect, scaleY: Float): Shot? {
        var missing: Throwable? = null
        for (name in CAPTURE_CLASSES) {
            try {
                val cap = Class.forName(name)
                val argsType = Class.forName("$name\$LayerCaptureArgs")
                val builderType = Class.forName("$name\$LayerCaptureArgs\$Builder")
                val b = builderType.getConstructor(SurfaceControl::class.java).newInstance(layer)
                open(builderType.getMethod("setChildrenOnly", Boolean::class.javaPrimitiveType)).invoke(b, false)
                open(builderType.getMethod("setSourceCrop", Rect::class.java)).invoke(b, crop)
                if (scaleY != 1f) runCatching {
                    open(builderType.getMethod("setFrameScale", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)).invoke(b, 1f, scaleY)
                }
                val args = open(builderType.getMethod("build")).invoke(b)
                val shot = try {
                    cap.getMethod("captureLayers", argsType).invoke(null, args)
                } catch (e: NoSuchMethodException) {
                    val listenerType = Class.forName("$name\$ScreenCaptureListener")
                    val listener = cap.getMethod("createSyncCaptureListener").invoke(null)
                    cap.getMethod("captureLayers", argsType, listenerType).invoke(null, args, listener)
                    open(listener.javaClass.getMethod("getBuffer")).invoke(listener)
                } ?: return null
                return shotOf(shot, leftOut = false)
            } catch (e: ReflectiveOperationException) {
                // A refusal comes wrapped: not a missing method, and the caller's to hear.
                if (e is java.lang.reflect.InvocationTargetException) throw e
                missing = e
            }
        }
        throw missing ?: ClassNotFoundException("no ScreenCapture")
    }

    /** A capture's ScreenshotHardwareBuffer as a [Shot]; null with no buffer in it. */
    private fun shotOf(shot: Any, leftOut: Boolean): Shot? {
        fun get(method: String) = open(shot.javaClass.getMethod(method)).invoke(shot)
        val buffer = get("getHardwareBuffer") as? HardwareBuffer ?: return null
        val secure = runCatching { get("containsSecureLayers") as Boolean }.getOrDefault(false)
        val space = runCatching { get("getColorSpace") as? ColorSpace }.getOrNull()
        return Shot(buffer, space, secure, leftOut)
    }

    /**
     * One throwaway capture, a pixel's worth, so the first that matters (a guest's cover) is
     * neither the one that trips over the process's first-use failure nor a screencap's
     * 387 ms. Once per process; quiet.
     */
    fun warm(displayId: Int) {
        if (warmed) return
        warmed = true
        runCatching { capture(displayId, emptyList(), Rect(0, 0, 1, 1))?.buffer?.close() }
            .onFailure { Log.i(TAG, "rear capture: warm-up failed (${(it.cause ?: it).message})") }
    }
    @Volatile private var warmed = false

    private fun captureOnce(displayId: Int, leaveOut: List<SurfaceControl>, crop: Rect?): Shot? {
        val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)
            ?: return null
        val iwm = Class.forName("android.view.IWindowManager")
        var missing: Throwable? = null
        for (name in CAPTURE_CLASSES) {
            val call: java.lang.reflect.Method
            val makeListener: java.lang.reflect.Method
            try {
                val argsType = Class.forName("$name\$CaptureArgs")
                val listenerType = Class.forName("$name\$ScreenCaptureListener")
                call = iwm.getMethod("captureDisplay", Int::class.javaPrimitiveType, argsType, listenerType)
                makeListener = Class.forName(name).getMethod("createSyncCaptureListener")
            } catch (e: ReflectiveOperationException) { missing = e; continue }
            val args = if (leaveOut.isEmpty() && crop == null) null else
                runCatching { args(name, leaveOut, crop) }
                    .onFailure { once("no capture args (${(it.cause ?: it).message}); our layers stay in the picture") }
                    .getOrNull()
            var listener = makeListener.invoke(null) ?: return null
            var leftOut = args != null && leaveOut.isNotEmpty()
            try {
                call.invoke(wm, displayId, args, listener)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                if (args == null) throw e
                once("a capture with args was refused (${e.targetException.message}); without them")
                listener = makeListener.invoke(null) ?: return null
                leftOut = false
                call.invoke(wm, displayId, null, listener)
            }
            val shot = listener.javaClass.getMethod("getBuffer").apply { isAccessible = true }.invoke(listener) ?: return null
            return shotOf(shot, leftOut)
        }
        throw missing ?: ClassNotFoundException("no ScreenCapture")
    }

    /** [name]'s CaptureArgs leaving [layers] out and cropped to [crop] (CaptureArgs.Builder), by reflection. */
    private fun args(name: String, layers: List<SurfaceControl>, crop: Rect?): Any {
        val builder = Class.forName("$name\$CaptureArgs\$Builder").getDeclaredConstructor().newInstance()
        val cls = builder.javaClass
        if (layers.isNotEmpty()) {
            cls.getMethod("setExcludeLayers", Array<SurfaceControl>::class.java)
                .apply { isAccessible = true }.invoke(builder, layers.toTypedArray())
        }
        if (crop != null) cls.getMethod("setSourceCrop", Rect::class.java).apply { isAccessible = true }.invoke(builder, crop)
        return cls.getMethod("build").apply { isAccessible = true }.invoke(builder)
            ?: throw IllegalStateException("no CaptureArgs")
    }
}
