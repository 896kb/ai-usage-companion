package dev.kb896.aiusage.data

import kotlinx.serialization.Serializable

/**
 * The whole app's look, as one choice.
 *
 * NOT A SKIN OVER ONE SET OF SHAPES. The two themes disagree about what the panel is
 * made of, and that is the point of having them rather than a pile of switches. ORIGINAL
 * is a readout: a pixel face, glyphs traced from a terminal font as rectangles, flat
 * bars, hard edges, everything drawn at exact pixel boundaries because that is what
 * makes small type legible on a 904-unit strip. GLASS is a surface: a proportional face,
 * glyphs as smooth outlines, and elements that refract whatever is behind them.
 *
 * They are therefore not interchangeable at the level of "swap a colour". A glyph in one
 * is a list of rectangles and in the other is a path; type is monospaced in one and
 * proportional in the other, so the same string is a different width. Everything that
 * differs goes through this enum, and the places that ask are the places where the two
 * genuinely part company.
 *
 * ORIGINAL is the default and always will be. It is what the panel has looked like for
 * its whole life, it is what every measurement in this project was taken against, and a
 * theme system that quietly moved everyone to the new thing would be a theme system that
 * broke the panel for the person who never asked for one.
 */
@Serializable
enum class PanelTheme {
    /** Pixel type, traced rectangle glyphs, flat surfaces. The panel as it was built. */
    ORIGINAL,

    /**
     * Proportional type, rounded outline glyphs, refracting surfaces.
     *
     * The refraction is Kyant0's AndroidLiquidGlass, used as AGSL rather than as the
     * library: that project is Compose Multiplatform and this app is Views and Canvas
     * all the way down, but the effect itself is a handful of self-contained shaders and
     * those port straight across. See Glass.kt for the notice and the licence.
     */
    GLASS,
    ;

    val isGlass: Boolean get() = this == GLASS
}
