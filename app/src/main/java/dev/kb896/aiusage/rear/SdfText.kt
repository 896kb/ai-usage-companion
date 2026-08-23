package dev.kb896.aiusage.rear

/**
 * A signed distance field for a piece of text, so it can be made of glass.
 *
 * WHY A FIELD AND NOT A GLYPH. Everything else on this panel refracts through a rounded
 * rectangle, and the shader that does it computes the rectangle's distance field in a
 * dozen lines of arithmetic because a rounded rectangle HAS a closed form. Letterforms do
 * not. To bend light through the shape of a "7" you have to know, for every pixel, how far
 * it is from the nearest edge of a seven and which way that edge faces — and the only way
 * to know that is to measure it.
 *
 * So the field is measured once, into a texture: distance in red, the surface normal in
 * green and blue, the glyph's own coverage in alpha. That is the reference's own layout,
 * and its clock is a texture prepared at build time because a clock face is four glyphs
 * that never change shape.
 *
 * THIS ONE CANNOT BE PREPARED, which is the whole difficulty. A charge reading is any of
 * a hundred strings and it changes while you watch it. So the field is built on the fly —
 * which is affordable only because it is built when the NUMBER changes rather than when
 * the frame does, and a percentage changes at most a hundred times in a charge.
 *
 * The measuring is Felzenszwalb and Huttenlocher's distance transform: exact Euclidean
 * distance in one pass per axis, linear in the number of pixels. The naive alternative —
 * for each pixel, search for the nearest lit one — is quadratic, and at the size type has
 * to be drawn here that is the difference between two milliseconds and several seconds.
 */
object SdfText {

    /**
     * How far from an edge the field still carries a gradient, in pixels.
     *
     * This is the width of the bevel: the shader turns distance into how hard it bends,
     * so a wide spread is a soft thick edge and a narrow one a sharp thin one. Too narrow
     * and the letters look like they were cut out of paper; too wide and neighbouring
     * strokes reach into each other and the counters fill in.
     */
    const val SPREAD = 12f

    /**
     * The exact squared distance transform of one row.
     *
     * [f] holds the cost at each cell — zero where the shape is, infinite where it is not
     * — and comes back holding the squared distance to the nearest zero. The algorithm
     * walks a lower envelope of parabolas, one rooted at each cell: [v] tracks which
     * parabolas are on the envelope and [z] where consecutive ones cross.
     */
    fun transform(f: FloatArray, d: FloatArray, v: IntArray, z: FloatArray, n: Int) {
        var k = 0
        v[0] = 0
        z[0] = -INF
        z[1] = INF
        for (q in 1 until n) {
            // Where this parabola meets the one currently on top. If that is to the LEFT
            // of where the top one started, the top one is now entirely under this one and
            // is dropped — repeatedly, which is what keeps the whole pass linear.
            var s = intersect(f, q, v[k])
            while (s <= z[k]) {
                k--
                s = intersect(f, q, v[k])
            }
            k++
            v[k] = q
            z[k] = s
            z[k + 1] = INF
        }
        k = 0
        for (q in 0 until n) {
            while (z[k + 1] < q) k++
            val dx = (q - v[k]).toFloat()
            d[q] = dx * dx + f[v[k]]
        }
    }

    private fun intersect(f: FloatArray, q: Int, vk: Int): Float {
        val a = f[q] + (q * q).toFloat()
        val b = f[vk] + (vk * vk).toFloat()
        return (a - b) / (2f * q - 2f * vk)
    }

    /** Large enough to stand in for infinity and still survive being squared. */
    const val INF = 1e10f

    /**
     * Squared Euclidean distance from every cell to the nearest cell where [inside] is
     * true, over a [w] by [h] grid.
     */
    fun distances(inside: BooleanArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h) { if (inside[it]) 0f else INF }
        val n = maxOf(w, h)
        val f = FloatArray(n)
        val d = FloatArray(n)
        val v = IntArray(n)
        val z = FloatArray(n + 1)
        // One pass along each axis, reusing the same scratch. That separability is the
        // whole trick: the exact two-dimensional Euclidean transform is the one-dimensional
        // one applied to the rows and then to the columns of its own output.
        for (y in 0 until h) {
            for (x in 0 until w) f[x] = out[y * w + x]
            transform(f, d, v, z, w)
            for (x in 0 until w) out[y * w + x] = d[x]
        }
        for (x in 0 until w) {
            for (y in 0 until h) f[y] = out[y * w + x]
            transform(f, d, v, z, h)
            for (y in 0 until h) out[y * w + x] = d[y]
        }
        return out
    }

    /**
     * The field, packed the way the shader reads it.
     *
     * Red is the signed distance mapped into 0..1 with the edge at a half, green and blue
     * are the normal on the same mapping, and alpha is the glyph's own coverage — which is
     * what stops the shader drawing a rectangle of bent light around the words.
     */
    fun pack(coverage: FloatArray, w: Int, h: Int, spread: Float = SPREAD): IntArray {
        val inside = BooleanArray(w * h) { coverage[it] >= 0.5f }
        val outer = distances(inside, w, h)
        val inner = distances(BooleanArray(w * h) { !inside[it] }, w, h)
        // Signed: negative within the letterform, positive outside it. Distances come back
        // squared, because the transform never needs the root and taking it per cell is
        // the one part of this that would be worth avoiding.
        val sd = FloatArray(w * h) {
            val o = Math.sqrt(outer[it].toDouble()).toFloat()
            val i = Math.sqrt(inner[it].toDouble()).toFloat()
            (o - i) / spread
        }
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                // Central differences, clamped at the border. The gradient of a distance
                // field IS the surface normal — it points away from the nearest edge —
                // which is why this is measured rather than traced from the outline.
                val gx = sd[idx + if (x + 1 < w) 1 else 0] - sd[idx - if (x > 0) 1 else 0]
                val gy = sd[idx + if (y + 1 < h) w else 0] - sd[idx - if (y > 0) w else 0]
                val len = Math.sqrt((gx * gx + gy * gy).toDouble()).toFloat()
                val nx = if (len > 1e-6f) gx / len else 0f
                val ny = if (len > 1e-6f) gy / len else 0f
                out[idx] = (byteOf(coverage[idx]) shl 24) or
                    (byteOf((sd[idx].coerceIn(-1f, 1f) + 1f) / 2f) shl 16) or
                    (byteOf((nx + 1f) / 2f) shl 8) or
                    byteOf((ny + 1f) / 2f)
            }
        }
        return out
    }

    private fun byteOf(v: Float): Int = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
}
