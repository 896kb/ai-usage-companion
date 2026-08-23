package dev.kb896.aiusage.rear

import android.content.res.Resources
import android.graphics.Typeface
import dev.kb896.aiusage.R
import dev.kb896.aiusage.data.PanelTheme

/**
 * The face each theme is set in, and the corrections that come with changing it.
 *
 * A FONT IS NOT A COLOUR. Everything on this panel was positioned against Departure
 * Mono: baselines, column widths, the gap that stops a percentage running into a bar.
 * Swapping in a proportional face at the same numbers does not give you the same layout
 * in a different style, it gives you a broken one — so the two things that change with
 * the face travel with it here rather than being scattered through the drawing code.
 *
 * WHAT CHANGES. Outfit's lowercase is tall relative to its cap height and its digits are
 * narrower than a monospaced cell, so at a shared point size it reads SMALLER than
 * Departure Mono while occupying less width. Both are corrected by [scale] rather than
 * by retuning forty text sizes, which is the only way to keep the two themes from
 * drifting apart every time one of them is adjusted.
 */
object PanelType {

    /**
     * The face itself.
     *
     * Cached because this is asked for on every frame by several paints, and
     * `Resources.getFont` is a parse and an allocation — it is not a lookup.
     */
    fun of(res: Resources, theme: PanelTheme): Typeface =
        cache.getOrPut(theme) {
            when (theme) {
                PanelTheme.ORIGINAL -> res.getFont(R.font.departure_mono)
                PanelTheme.GLASS -> res.getFont(R.font.outfit)
            }
        }

    /**
     * The variation axes to set on a paint, or null if the face has none.
     *
     * THIS IS NOT WHAT Typeface.create DOES, and the difference is the whole reason this
     * exists. `Typeface.create(face, 400, false)` picks a weight from a FAMILY — several
     * files, one per weight — and a variable font is one file with an axis. Handed one,
     * it has nothing to choose between and returns the face's default instance, which
     * for Outfit[wght] is Thin. The panel came up drawn in hairlines and the request for
     * Regular had quietly done nothing.
     *
     * Set on the Paint rather than baked into the Typeface because that is where the API
     * for it is: Paint.setFontVariationSettings drives the axis directly.
     */
    fun variation(theme: PanelTheme): String? =
        if (theme == PanelTheme.GLASS) "'wght' $GLASS_WEIGHT" else null

    private val cache = HashMap<PanelTheme, Typeface>(2)

    /**
     * What to multiply a text size by so the two faces read at the same weight.
     *
     * Measured rather than guessed: at a common size Outfit's capitals sit noticeably
     * shorter than Departure Mono's, because a pixel font spends its whole em on the
     * glyph and a proportional one leaves room above and below for ascenders and
     * descenders it actually uses.
     *
     * Applied at the point of drawing, so every size in the panel stays stated once, in
     * ORIGINAL's terms, and the other theme is expressed as a correction to it.
     */
    fun scale(theme: PanelTheme): Float =
        if (theme == PanelTheme.GLASS) GLASS_SCALE else 1f

    /** How much taller Outfit has to be set to match Departure Mono's presence. */
    const val GLASS_SCALE = 1.12f

    /** Where on the variable axis GLASS is cut. 400 is Regular. */
    const val GLASS_WEIGHT = 400

    /**
     * Whether the theme's face is monospaced.
     *
     * Asked by the things that lay out on a character grid — the marquee that scrolls a
     * long title, the clipper that trims a notification to the width it has. Those can
     * count characters under a mono face and must measure under a proportional one.
     */
    fun isMono(theme: PanelTheme): Boolean = theme == PanelTheme.ORIGINAL
}
