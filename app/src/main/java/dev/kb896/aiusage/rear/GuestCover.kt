package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.DataSpace
import android.hardware.HardwareBuffer
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The picture that covers the rear while a guest comes onto it: what the rear showed a
 * moment before — our panel, or the guest before this one, its frame and all — on a layer
 * of the shell's own ([ShellLayer.Z_COVER]: over every app, under the sheet). The new app is
 * moved in and draws underneath it; then GuestReveal slides it off ([place]) and it goes
 * ([remove]). Driven from the launch's own thread; see RearUserService.launchGuest.
 *
 * THE PICTURE, in this process and quick: the window manager's captureDisplay (hidden
 * IWindowManager.captureDisplay with the synchronous listener of ScreenCaptureInternal, as
 * Android 16 calls it — ScreenCapture before), which shell may call, holding
 * READ_FRAME_BUFFER. Its HardwareBuffer goes onto the layer as it is (Transaction.setBuffer,
 * in the capture's colour space). Where that is missing or refused, the `screencap`
 * command's picture ([screencap]), drawn on the layer's software canvas — slower (a PNG),
 * once. Neither: no cover, and the guest arrives as it always did.
 *
 * A picture with secure layers in it (a banking app, DRM video) shows them black: that is
 * not what was on the rear, so there is no cover then either.
 *
 * TRUSTED, OR NOT AT ALL, as the frame and the sheet: an untrusted layer would drop every
 * touch on the rear while it is up (ShellLayer). Touches go through it to the arriving app.
 */
internal class GuestCover private constructor(
    private val layer: SurfaceControl,
    private val buffer: HardwareBuffer?,
) {

    /** [dx] px across (negative left), at [alpha], in one transaction; quiet once the layer is gone. */
    fun place(dx: Float, alpha: Float) {
        runCatching { SurfaceControl.Transaction().use { it.setPosition(layer, dx, 0f).setAlpha(layer, alpha).apply() } }
    }

    /** Off the rear, and everything let go. */
    fun remove() {
        ShellLayer.remove(layer)
        runCatching { buffer?.close() }
    }

    companion object {
        private const val TAG = "AiUsageRear"
        /** The longest to wait for SurfaceFlinger to take the cover on before what is under it moves. */
        private const val COMMIT_WAIT_MS = 120L

        /** Hidden capture classes, newest first: Android 16's name, then 14–15's. */
        private val CAPTURE_CLASSES = listOf("android.window.ScreenCaptureInternal", "android.window.ScreenCapture")

        /** Once per process per reason, so a firmware without the call does not log every launch. */
        private val logged = java.util.Collections.synchronizedSet(HashSet<String>())

        private fun once(why: String) { if (logged.add(why)) Log.w(TAG, "guest cover: $why") }

        /**
         * The rear (logical [displayId]) as it is now, on a new layer on [layerStack], shown,
         * and taken on by SurfaceFlinger (or [COMMIT_WAIT_MS] gone by) before this returns,
         * so what is under it may move. Null when there can be no cover: no picture, secure
         * content in it, or no trusted layer — nothing left behind then.
         */
        fun up(layerStack: Int, displayId: Int, screencap: () -> Bitmap?): GuestCover? {
            val shot = runCatching { capture(displayId) }
                .onFailure { once("no in-process capture (${(it.cause ?: it).javaClass.simpleName}: ${(it.cause ?: it).message}); screencap instead") }
                .getOrNull()
            if (shot?.secure == true) {
                runCatching { shot.buffer.close() }
                Log.i(TAG, "guest cover: the rear shows secure content — no cover")
                return null
            }
            val picture = if (shot == null) runCatching { screencap() }.getOrNull() else null
            if (shot == null && picture == null) { once("no picture of the rear — guests arrive uncovered"); return null }
            var layer: SurfaceControl? = null
            try {
                layer = ShellLayer.create("portal-cover", layerStack, ShellLayer.Z_COVER, trusted = true, visible = false)
                val t = SurfaceControl.Transaction()
                if (shot != null) {
                    t.setBuffer(layer, shot.buffer).setDataSpace(layer, dataSpace(shot.space))
                } else {
                    val surface = Surface(layer)
                    try {
                        val c = surface.lockCanvas(null)
                        try { c.drawBitmap(picture!!, 0f, 0f, null) } finally { surface.unlockCanvasAndPost(c) }
                    } finally {
                        surface.release()
                        picture?.recycle()
                    }
                }
                val committed = CountDownLatch(1)
                t.addTransactionCommittedListener({ it.run() }) { committed.countDown() }
                t.setVisibility(layer, true).apply()
                t.close()
                committed.await(COMMIT_WAIT_MS, TimeUnit.MILLISECONDS)
                return GuestCover(layer, shot?.buffer)
            } catch (e: Throwable) {
                if (e is ShellLayer.Untrusted) once("no trusted overlay (${e.message}) — guests arrive uncovered")
                else Log.w(TAG, "guest cover: no layer: ${e.message}")
                ShellLayer.remove(layer)
                runCatching { shot?.buffer?.close() }
                if (e is InterruptedException) Thread.currentThread().interrupt()
                return null
            }
        }

        private class Shot(val buffer: HardwareBuffer, val space: ColorSpace?, val secure: Boolean)

        /** The capture's colour space as a layer's dataspace: Display P3 or sRGB, the two the rear composes in. */
        private fun dataSpace(space: ColorSpace?): Int =
            if (space == ColorSpace.get(ColorSpace.Named.DISPLAY_P3)) DataSpace.DATASPACE_DISPLAY_P3 else DataSpace.DATASPACE_SRGB

        /**
         * IWindowManager.captureDisplay([displayId], default args, a synchronous listener),
         * by reflection: null when the window manager answers with no picture; throws when
         * the calls are not there or are refused.
         */
        private fun capture(displayId: Int): Shot? {
            val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)
                ?: return null
            val iwm = Class.forName("android.view.IWindowManager")
            var missing: Throwable? = null
            for (name in CAPTURE_CLASSES) {
                val call: java.lang.reflect.Method
                val makeListener: java.lang.reflect.Method
                try {
                    val args = Class.forName("$name\$CaptureArgs")
                    val listenerType = Class.forName("$name\$ScreenCaptureListener")
                    call = iwm.getMethod("captureDisplay", Int::class.javaPrimitiveType, args, listenerType)
                    makeListener = Class.forName(name).getMethod("createSyncCaptureListener")
                } catch (e: ReflectiveOperationException) { missing = e; continue }
                val listener = makeListener.invoke(null) ?: return null
                call.invoke(wm, displayId, null, listener)
                val shot = listener.javaClass.getMethod("getBuffer").apply { isAccessible = true }.invoke(listener) ?: return null
                fun get(method: String) = shot.javaClass.getMethod(method).apply { isAccessible = true }.invoke(shot)
                val buffer = get("getHardwareBuffer") as? HardwareBuffer ?: return null
                val secure = runCatching { get("containsSecureLayers") as Boolean }.getOrDefault(false)
                val space = runCatching { get("getColorSpace") as? ColorSpace }.getOrNull()
                return Shot(buffer, space, secure)
            }
            throw missing ?: ClassNotFoundException("no ScreenCapture")
        }
    }
}
