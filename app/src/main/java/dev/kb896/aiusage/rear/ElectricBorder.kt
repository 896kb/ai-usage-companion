package dev.kb896.aiusage.rear

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * The portal frame's line: vue-bits' Electric Border
 * (https://vue-bits.dev/animations/electric-border), ported. A shape's outline, sampled
 * every [STEP] px, each point pushed off it by octaved value noise that moves with time.
 *
 * TRIMMED FROM THE ORIGINAL, ON PURPOSE. The literal port (tools/spikes/PortalFrameSpike)
 * ran ten octaves on a sine hash at a point every 2 px and managed 16 fps on the rear;
 * the budget is a frame under 8 ms. Six octaves carry nearly all of the visible jitter
 * (the rest sum to under 12% of the amplitude), and a point every 4 px is still under a
 * line's width apart once blurred.
 *
 * Pure — flat x,y arrays — so it is tested on the JVM; PortalFrame makes Paths of them.
 */
object ElectricBorder {

    sealed interface Shape {
        /** Outline length in px. */
        val length: Float
        /** The point a fraction [t] (0..1) of the way round, clockwise from the top. */
        fun at(t: Float, out: FloatArray, index: Int)

        data class Circle(val cx: Float, val cy: Float, val r: Float) : Shape {
            override val length get() = (2 * PI * r).toFloat()
            override fun at(t: Float, out: FloatArray, index: Int) {
                val a = t * 2 * PI - PI / 2
                out[index] = cx + r * cos(a).toFloat()
                out[index + 1] = cy + r * sin(a).toFloat()
            }
        }

        data class Rounded(val left: Float, val top: Float, val right: Float, val bottom: Float, val r: Float) : Shape {
            private val sw get() = right - left - 2 * r
            private val sh get() = bottom - top - 2 * r
            private val arc get() = (PI * r / 2).toFloat()
            override val length get() = 2 * sw + 2 * sh + 4 * arc
            override fun at(t: Float, out: FloatArray, index: Int) {
                var d = t * length
                fun put(x: Float, y: Float) { out[index] = x; out[index + 1] = y }
                fun corner(cx: Float, cy: Float, start: Double, p: Float) {
                    val a = start + p.coerceIn(0f, 1f) * PI / 2
                    put(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat())
                }
                if (d <= sw) return put(left + r + d, top); d -= sw
                if (d <= arc) return corner(right - r, top + r, -PI / 2, d / arc); d -= arc
                if (d <= sh) return put(right, top + r + d); d -= sh
                if (d <= arc) return corner(right - r, bottom - r, 0.0, d / arc); d -= arc
                if (d <= sw) return put(right - r - d, bottom); d -= sw
                if (d <= arc) return corner(left + r, bottom - r, PI / 2, d / arc); d -= arc
                if (d <= sh) return put(left, bottom - r - d); d -= sh
                corner(left + r, top + r, PI, d / arc)
            }
        }
    }

    /** Between outline points, in px. */
    const val STEP = 4f
    /** The original's 0.12, its "chaos". */
    private const val AMPLITUDE = 0.12f
    private const val OCTAVES = 6
    /**
     * How far, at most, a point strays, in px. The original pushes by 60 CSS px times a
     * noise sum that peaks near 0.39 — some 65 rear pixels at 450 dpi, a fifth of the
     * display's height. A third of that reads the same at arm's length and stays off the
     * middle of whatever the guest is showing.
     *
     * It bounds each axis separately; a point's diagonal can reach REACH * sqrt(2).
     */
    const val REACH = 22f

    private fun count(shape: Shape) = (shape.length / STEP).toInt() + 1

    fun outline(shape: Shape): FloatArray {
        val n = count(shape)
        val out = FloatArray(n * 2)
        for (i in 0 until n) shape.at(i / (n - 1f), out, i * 2)
        return out
    }

    /** The outline at [time] seconds, displaced; same length as [outline]. */
    fun bolt(shape: Shape, time: Float): FloatArray {
        val out = outline(shape)
        val n = out.size / 2
        val peak = peak()
        for (i in 0 until n) {
            val t = i / (n - 1f)
            out[i * 2] += octaved(t * 8f, time, 0) / peak * REACH
            out[i * 2 + 1] += octaved(t * 8f, time, 1) / peak * REACH
        }
        return out
    }

    /** The noise sum's largest possible size, so [REACH] is a true bound. */
    private fun peak(): Float {
        var a = AMPLITUDE; var sum = 0f
        for (i in 0 until OCTAVES) { if (i > 0) sum += a; a *= 0.7f }
        return sum
    }

    private fun octaved(x: Float, time: Float, seed: Int): Float {
        var y = 0f
        var amplitude = AMPLITUDE
        var frequency = 10f
        for (i in 0 until OCTAVES) {
            // The original's baseFlatness 0: the first, broadest octave is flat.
            if (i > 0) y += amplitude * noise(frequency * x + seed * 100, time * frequency * 0.3f)
            frequency *= 1.6f
            amplitude *= 0.7f
        }
        return y
    }

    /** Value noise in -1..1 with smoothstep between lattice points. */
    private fun noise(x: Float, y: Float): Float {
        val i = floor(x); val j = floor(y)
        val fx = x - i; val fy = y - j
        val a = hash(i, j); val b = hash(i + 1, j); val c = hash(i, j + 1); val d = hash(i + 1, j + 1)
        val ux = fx * fx * (3 - 2 * fx); val uy = fy * fy * (3 - 2 * fy)
        return a * (1 - ux) * (1 - uy) + b * ux * (1 - uy) + c * (1 - ux) * uy + d * ux * uy
    }

    /** An integer hash of a lattice point, -1..1: no sine, the original's one cost. */
    private fun hash(x: Float, y: Float): Float {
        var h = x.toInt() * 374761393 + y.toInt() * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 32767.5f - 1f
    }
}
