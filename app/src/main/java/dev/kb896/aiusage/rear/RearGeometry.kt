package dev.kb896.aiusage.rear

import android.view.Display
import dev.kb896.aiusage.Panel
import kotlin.math.roundToInt

/**
 * The rear display as it really is, in its own pixels: its size, the lens column at its left
 * ([cutoutPx], the cutout's left inset), its corner radius and its density. The 17 Pro's is
 * 904x572, 296, 97 and 450 ([PRO17], measured); the Pro Max's is 976x596 and the rest not
 * measured. Read once — the rear's size does not change — and the one place the shell takes
 * any of it from.
 *
 * TWO SPACES, and this is the bridge. Everything a finger meets — the portal sheet, the
 * control centre, their layouts and hit tests, the edge gestures — is laid out in PANEL UNITS,
 * the 17 Pro's 904x572, and stretched to the display per axis ([fitX], [fitY]), exactly as
 * the panel is (RearDialView.fitX); the raw touchscreen comes in already in them
 * (RearTouches). Everything that is the display's own — a guest's task bounds, the frame round
 * its edge, the camera strip, captures — is in real pixels from here.
 */
data class RearGeometry(val widthPx: Int, val heightPx: Int, val cutoutPx: Int, val cornerPx: Int, val dpi: Int) {

    /** Real pixels per panel unit across. */
    val fitX: Float get() = widthPx / Panel.WIDTH.toFloat()

    /** Real pixels per panel unit down. */
    val fitY: Float get() = heightPx / Panel.HEIGHT.toFloat()

    /**
     * The lens column's edge in panel units: where a pull's band starts. The lenses keep their
     * pixels on a wider display, so on a Pro Max this is left of the panel's 296.
     */
    val cutoutUnits: Float get() = cutoutPx / fitX

    /**
     * This rear drawn at the panel's size, as the front's preview draws it: 904x572, the lens
     * column and the corner scaled across with it, to the nearest unit.
     */
    fun inPanelUnits(): RearGeometry =
        RearGeometry(Panel.WIDTH, Panel.HEIGHT, cutoutUnits.roundToInt(), (cornerPx / fitX).roundToInt(), dpi)

    /** What one source says about the rear; null (or not above zero) for what it does not say. */
    data class Reading(val width: Int?, val height: Int?, val cutout: Int?, val corner: Int?, val dpi: Int?)

    companion object {
        /** The 17 Pro's rear display, read off the phone (dumpsys display, 2026-10-29). */
        val PRO17 = RearGeometry(Panel.WIDTH, Panel.HEIGHT, Panel.CUTOUT_RIGHT, 97, 450)

        private fun Int?.said() = this?.takeIf { it > 0 }

        /**
         * Whether [display] or [dump] says the rear's size, so that [resolve] is the rear's own
         * and may be kept for good. Neither (both unread, a dump that came back empty): what
         * [resolve] gives is [PRO17]'s, a stand-in to use now and ask again about later.
         */
        fun known(display: Reading?, dump: Reading?): Boolean =
            listOfNotNull(display, dump).any { it.width.said() != null && it.height.said() != null }

        /**
         * The rear from [display] (the window manager's own display, in the frame tasks and
         * layers use) and [dump] (`dumpsys display`'s device line), in that order, field by
         * field; the 17 Pro's for whatever neither says. The size comes whole from one of them
         * or is [PRO17]'s. The lens column and corner only from a source of that same size — a
         * dump in another frame (the panel's natural orientation) has its insets elsewhere —
         * while the density is the same in any frame.
         */
        fun resolve(display: Reading?, dump: Reading?): RearGeometry {
            val sized = listOfNotNull(display, dump).firstOrNull { it.width.said() != null && it.height.said() != null }
                ?: return PRO17.copy(dpi = listOfNotNull(display, dump).firstNotNullOfOrNull { it.dpi.said() } ?: PRO17.dpi)
            val w = sized.width!!
            val h = sized.height!!
            val same = listOfNotNull(display, dump).filter { it.width == w && it.height == h }
            return RearGeometry(
                widthPx = w,
                heightPx = h,
                cutoutPx = same.firstNotNullOfOrNull { it.cutout.said() } ?: PRO17.cutoutPx,
                cornerPx = same.firstNotNullOfOrNull { it.corner.said() } ?: PRO17.cornerPx,
                dpi = listOfNotNull(display, dump).firstNotNullOfOrNull { it.dpi.said() } ?: PRO17.dpi,
            )
        }

        /**
         * What [display] says: its logical size (the frame task bounds and SurfaceFlinger's
         * composition are in), the cutout's left inset in that frame, and its top-left corner's
         * radius. Not its density: Display gives the override (`wm density`), which a guest
         * may have set, not the panel's own.
         */
        fun reading(display: Display): Reading {
            val size = android.graphics.Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            return Reading(
                width = size.x,
                height = size.y,
                cutout = display.cutout?.safeInsetLeft,
                corner = display.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius,
                dpi = null,
            )
        }

        /** The rear as display [Panel.DISPLAY_ID] says it is, for a process with a [context] and nothing to dump. */
        fun of(context: android.content.Context): RearGeometry = resolve(
            runCatching {
                context.getSystemService(android.hardware.display.DisplayManager::class.java)
                    ?.getDisplay(Panel.DISPLAY_ID)?.let(::reading)
            }.getOrNull(),
            null,
        )
    }
}
