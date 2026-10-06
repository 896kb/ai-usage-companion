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
}
