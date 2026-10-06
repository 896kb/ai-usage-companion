package dev.kb896.aiusage.rear

import dev.kb896.aiusage.data.GlassReactivity
import dev.kb896.aiusage.data.GlassStyle

/**
 * A piece of glass's tint under a chosen [GlassStyle]: its tint, adjusted for the
 * brightness behind the piece ([local]) or behind the whole panel ([mean]) — or for
 * neither — as GlassLuminance.reactive adjusts the built-in ones.
 */
object GlassTint {
    fun of(style: GlassStyle, local: Float, mean: Float): Int = when (style.reactivity) {
        GlassReactivity.NONE -> style.tint()
        GlassReactivity.GLOBAL -> GlassLuminance.reactive(style.tint(), mean)
        GlassReactivity.SHARD -> GlassLuminance.reactive(style.tint(), local)
    }
}
