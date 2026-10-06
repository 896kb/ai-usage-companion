package dev.kb896.aiusage.ui.video

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import dev.kb896.aiusage.Panel

/**
 * The rear display's pixels, landscape and even (H.264 wants even sides): the size a
 * clip is made at. Read from the display, so a Pro Max gets its own; the 17 Pro's when
 * there is no second display to ask.
 */
object RearSize {
    fun pixels(context: Context): Pair<Int, Int> {
        val rear = context.getSystemService(DisplayManager::class.java)
            ?.displays?.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        val mode = rear?.mode
        val a = mode?.physicalWidth ?: Panel.WIDTH
        val b = mode?.physicalHeight ?: Panel.HEIGHT
        return even(maxOf(a, b)) to even(minOf(a, b))
    }

    private fun even(v: Int) = v - v % 2

    /**
     * The rear display's corner radius as a share of its width — what a frame of any size
     * is rounded by to be the shape of the back. Asked of the display, as the panel asks
     * its window; the 17 Pro's (97 of 904) when it does not say.
     */
    fun cornerShare(context: Context): Float {
        val rear = context.getSystemService(DisplayManager::class.java)
            ?.displays?.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        val mode = rear?.mode
        val width = if (mode != null) maxOf(mode.physicalWidth, mode.physicalHeight) else 0
        return cornerShare(rear?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius, width)
    }

    fun cornerShare(radius: Int?, width: Int): Float =
        if (radius == null || radius <= 0 || width <= 0) FALLBACK_CORNER / Panel.WIDTH else radius.toFloat() / width

    private const val FALLBACK_CORNER = 97f
}
