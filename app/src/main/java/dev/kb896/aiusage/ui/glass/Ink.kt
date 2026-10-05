package dev.kb896.aiusage.ui.glass

/**
 * Colours keyed on what is behind a control, not on the system theme.
 *
 * ---------------------------------------------------------------------------------
 * The values below are read out of the catalog app of AndroidLiquidGlass (the
 * `backdrop` library) by Kyant0, https://github.com/Kyant0/AndroidLiquidGlass, cited
 * per constant by file and line, and used under the Apache License 2.0:
 *
 *   Copyright 2025 Kyant
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 * ---------------------------------------------------------------------------------
 *
 * The catalog keys light and dark on isSystemInDarkTheme(). Here the backdrop is a
 * photograph, so one screen has light and dark regions at once; each control reads its
 * own luminance cell and takes the catalog's light or dark value for it.
 */
object Ink {
    const val LIGHT = 0.5f

    /**
     * NEVER LIGHT, on the front: nothing here reacts to what is behind it any more — not
     * the text, not the tracks, the switch's on colour, the accent or the dim behind a
     * dialog. Every branch below takes its dark-background answer, the one the silk asks
     * for, wherever the control happens to sit on it.
     */
    @Suppress("UNUSED_PARAMETER")
    fun isLight(luminance: Float) = false

    /**
     * TEXT IS WHITE, whatever is behind it. It used to flip to black over a light part
     * of the background, so a card's words changed colour as it scrolled across the
     * silk; the luminance is still taken, and still decides the glass's own tint and the
     * tracks, but not the text.
     */
    @Suppress("UNUSED_PARAMETER")
    fun primary(luminance: Float): Int = 0xFFFFFFFF.toInt()

    /** White at 60%, for the quieter text — and like [primary], whatever is behind it. */
    @Suppress("UNUSED_PARAMETER")
    fun secondary(luminance: Float): Int = 0x99FFFFFF.toInt()

    /** The toggle's on colour. LiquidToggle.kt:56-57. */
    fun on(luminance: Float): Int =
        if (isLight(luminance)) 0xFF34C759.toInt() else 0xFF30D158.toInt()

    /** The slider fill and the segmented selection. LiquidSlider.kt:59-60. */
    fun accent(luminance: Float): Int =
        if (isLight(luminance)) 0xFF0088FF.toInt() else 0xFF0091FF.toInt()

    /** An inactive track. LiquidToggle.kt:59-60: grey at 0.2 light, 0.36 dark. */
    fun track(luminance: Float): Int =
        if (isLight(luminance)) 0x33787878 else 0x5C787880

    /** The dialog scrim. DialogContent.kt:46-47. */
    fun scrimAlpha(luminance: Float): Float = if (isLight(luminance)) 0.23f else 0.56f
    fun scrimColor(luminance: Float): Int =
        if (isLight(luminance)) 0xFF29293A.toInt() else 0xFF121212.toInt()

    /** [colour] moved [t] of the way toward [toward], per channel, alpha kept. */
    fun mix(colour: Int, toward: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val a = (colour shr shift) and 0xFF
            val b = (toward shr shift) and 0xFF
            return Math.round(a + (b - a) * t).coerceIn(0, 255)
        }
        return (colour and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
