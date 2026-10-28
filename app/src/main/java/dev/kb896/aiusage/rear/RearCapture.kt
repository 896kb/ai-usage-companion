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
 * it can be cropped to a strip (CaptureArgs source crop) when only a few columns are wanted.
 * For the guest's cover (GuestCover) and the camera strip's colour (CameraStrip).
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
     * [crop] (and scaled by [scaleY] down its height: fewer rows to make and read), a
     * synchronous listener), by reflection: null when the window manager answers
     * with no picture; throws when the calls are not there or are refused. Args that cannot
     * be built, or a capture refused with them (a layer released meanwhile cannot be sent),
     * are dropped for the default ones — then the layers are in the picture, [Shot.leftOut]
     * false, and a crop is lost too: the caller checks the buffer's size.
     */
    fun capture(displayId: Int, leaveOut: List<SurfaceControl>, crop: Rect? = null, scaleY: Float = 1f): Shot? = try {
        captureOnce(displayId, leaveOut, crop, scaleY)
    } catch (e: Throwable) {
        // The first capture in this process failed on the phone with "ApplicationSharedMemory
        // not initialized" (Android 16's shared-memory caches, never set up in app_process);
        // the ones after it worked. So once more, at once.
        val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
        if (cause is IllegalStateException && cause.message?.contains("ApplicationSharedMemory") == true) {
            Log.i(TAG, "rear capture: ${cause.message} — once more")
            captureOnce(displayId, leaveOut, crop, scaleY)
        } else throw e
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

    private fun captureOnce(displayId: Int, leaveOut: List<SurfaceControl>, crop: Rect?, scaleY: Float): Shot? {
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
            val args = if (leaveOut.isEmpty() && crop == null && scaleY == 1f) null else
                runCatching { args(name, leaveOut, crop, scaleY) }
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
            fun get(method: String) = shot.javaClass.getMethod(method).apply { isAccessible = true }.invoke(shot)
            val buffer = get("getHardwareBuffer") as? HardwareBuffer ?: return null
            val secure = runCatching { get("containsSecureLayers") as Boolean }.getOrDefault(false)
            val space = runCatching { get("getColorSpace") as? ColorSpace }.getOrNull()
            return Shot(buffer, space, secure, leftOut)
        }
        throw missing ?: ClassNotFoundException("no ScreenCapture")
    }

    /** [name]'s CaptureArgs leaving [layers] out and cropped to [crop] (CaptureArgs.Builder), by reflection. */
    private fun args(name: String, layers: List<SurfaceControl>, crop: Rect?, scaleY: Float): Any {
        val builder = Class.forName("$name\$CaptureArgs\$Builder").getDeclaredConstructor().newInstance()
        val cls = builder.javaClass
        if (layers.isNotEmpty()) {
            cls.getMethod("setExcludeLayers", Array<SurfaceControl>::class.java)
                .apply { isAccessible = true }.invoke(builder, layers.toTypedArray())
        }
        if (crop != null) cls.getMethod("setSourceCrop", Rect::class.java).apply { isAccessible = true }.invoke(builder, crop)
        if (scaleY != 1f) {
            cls.getMethod("setFrameScale", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(builder, 1f, scaleY)
        }
        return cls.getMethod("build").apply { isAccessible = true }.invoke(builder)
            ?: throw IllegalStateException("no CaptureArgs")
    }
}
