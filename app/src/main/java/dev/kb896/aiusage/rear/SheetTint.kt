package dev.kb896.aiusage.rear

import dev.kb896.aiusage.data.GlassStyle

/**
 * The tint of the shell sheet's clear glass, as the panel tints its own: Glass settings'
 * style when one is chosen (GlassTint), else the clear glass (GlassLuminance.clearTint) —
 * the panel's styledTint and clearReactive, over a middling ground. The shell sheet has no
 * picture of what is behind it to react to — SurfaceFlinger blurs the live rear under it,
 * and no picture of it ever reaches this process — so every pane takes the same tint.
 */
object SheetTint {

    /**
     * A clear pane's body: [style]'s when one is chosen, else under [glass] clear glass,
     * else the built-in DISC_TINT, as the panel's clearReactive under ORIGINAL.
     */
    fun of(glass: Boolean, style: GlassStyle?): Int {
        if (style != null) return GlassTint.of(style, MIDDLING, MIDDLING)
        if (!glass) return Glass.DISC_TINT
        return GlassLuminance.clearTint(MIDDLING)
    }

    /** The brightness assumed behind the sheet: neither end, so nothing reacts. */
    const val MIDDLING = 0.5f
}
