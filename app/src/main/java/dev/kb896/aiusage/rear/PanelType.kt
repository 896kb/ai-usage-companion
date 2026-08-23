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

    /**
     * The masthead's own weight, which is not the panel's.
     *
     * Heavier, because under GLASS the title is not painted — it is a lens shaped like
     * the letters, and a lens needs a stroke wide enough to have an inside. At the body
     * weight the bevel from each edge meets in the middle of every stem and the whole
     * word is edge, which reads as a smear rather than as type.
     */
    fun titleVariation(theme: PanelTheme): String? =
        if (theme == PanelTheme.GLASS) "'wght' $GLASS_TITLE_WEIGHT" else null

    /** ExtraBold. See [titleVariation]. */
    const val GLASS_TITLE_WEIGHT = 800

    /**
     * What a track's name and artist are set in, which is lighter than the panel.
     *
     * The only text here that is somebody ELSE'S — a title as its author wrote it, not a
     * label this app chose — and it is the one string that is genuinely prose. Set at the
     * panel's weight it competes with the readings for a glance it does not deserve: the
     * numbers are why the panel is on.
     */
    /**
     * The heaviest weight the face carries, for the charging figure.
     *
     * Nine hundred is Outfit's top stop. Anything asking for more is clamped to it by the
     * font itself, so this is the end of the axis rather than a number chosen from taste.
     */
    fun heaviestVariation(theme: PanelTheme): String? =
        if (theme == PanelTheme.GLASS) "'wght' $GLASS_HEAVIEST" else null

    private const val GLASS_HEAVIEST = 900

    fun mediaVariation(theme: PanelTheme): String? =
        if (theme == PanelTheme.GLASS) "'wght' $GLASS_MEDIA_WEIGHT" else null

    /** Regular. See [mediaVariation]. */
    const val GLASS_MEDIA_WEIGHT = 400

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

    /**
     * Where on the variable axis the panel's body is cut. 600 is SemiBold.
     *
     * Heavier than a page of prose would be set, because this is not one. The panel is
     * read at arm's length, on the back of a phone, in a glance — and every string on it
     * is a label or a number rather than a sentence. Regular held up at the sizes the
     * masthead uses and went thin everywhere else.
     */
    const val GLASS_WEIGHT = 600

    /**
     * Whether the theme's face is monospaced.
     *
     * Asked by the things that lay out on a character grid — the marquee that scrolls a
     * long title, the clipper that trims a notification to the width it has. Those can
     * count characters under a mono face and must measure under a proportional one.
     */
    fun isMono(theme: PanelTheme): Boolean = theme == PanelTheme.ORIGINAL
}
