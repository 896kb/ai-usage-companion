package dev.kb896.aiusage.rear

/**
 * A video background's brightness cells as the panel shows them: the clip's own frame at
 * its opacity over the colour behind — as the picture's are, which are measured from the
 * picture drawn over its ground. A bright clip thinned over a dark ground reads mostly dark.
 */
object VideoLuminance {
    fun blend(cells: List<Float>, opacity: Float, background: Int): FloatArray {
        val o = opacity.coerceIn(0f, 1f)
        val ground = GlassLuminance.of(background or (0xFF shl 24))
        return FloatArray(cells.size) { cells[it] * o + ground * (1f - o) }
    }
}
