package dev.kb896.aiusage.rear

/**
 * A signed distance field with surface normals, packed into RGBA.
 *
 * WHAT THIS IS FOR. The GLASS title is not painted, it is a LENS SHAPED LIKE THE TEXT —
 * the panel behind shows through the letterforms, bent at their edges and lit along one
 * side, the way heavy glass type would be. The shader that does it needs, for every
 * pixel, how far inside the letter it is and which way the surface faces. That cannot be
 * read off a rendered glyph, so it is computed here.
 *
 * The reference ships this as a baked texture for one fixed clock. Ours is a title
 * somebody can type, so it has to be built at runtime — which is why the maths lives in
 * a pure object with no Android in it, and why it is worth testing rather than eyeballing.
 *
 * THE PACKING is the shader's, not a choice:
 *
 *   R    the signed distance, -1..0 mapped to 0..0.5. Zero at the edge, negative inside.
 *   G,B  the outward normal, -1..1 mapped to 0..1.
 *   A    coverage, straight from the glyph.
 *
 * Everything outside a letter is transparent and the shader returns early there, so only
 * the inside of the strokes is ever asked about.
 */
object SdfField {

    /**
     * Build the field from a coverage mask.
     *
     * [alpha] is row-major, 0..1, one entry per pixel. [spread] is how many pixels from
     * an edge the distance is measured over — the depth of the bevel, in effect. Past it
     * the surface is flat and the glass is simply clear.
     */
    fun build(alpha: FloatArray, w: Int, h: Int, spread: Float): IntArray {
        require(alpha.size == w * h) { "mask is ${alpha.size}, expected ${w * h}" }
        val inside = FloatArray(w * h)
        // Distance from each INSIDE pixel to the nearest outside one. Squared first,
        // because the transform below works in squared distances and one sqrt at the end
        // is cheaper than one per step.
        for (i in alpha.indices) inside[i] = if (alpha[i] >= 0.5f) INF else 0f
        val d2 = edt(inside, w, h)
        val dist = FloatArray(w * h) { Math.sqrt(d2[it].toDouble()).toFloat() }

        val out = IntArray(w * h)
        val s = if (spread <= 0f) 1f else spread
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val a = alpha[i]
                if (a <= 0f) { out[i] = 0; continue }

                // Negative inside, zero at the edge — the convention the shader reads.
                val sd = -(dist[i] / s).coerceIn(0f, 1f)

                // The surface normal, from the gradient of the distance field. It points
                // INWARD, since distance grows away from the edge, so it is negated to
                // face out of the letter the way a surface does.
                var nx = -(sample(dist, w, h, x + 1, y) - sample(dist, w, h, x - 1, y))
                var ny = -(sample(dist, w, h, x, y + 1) - sample(dist, w, h, x, y - 1))
                val len = Math.sqrt((nx * nx + ny * ny).toDouble()).toFloat()
                if (len > 1e-5f) { nx /= len; ny /= len } else { nx = 0f; ny = 0f }

                out[i] = pack(a, (sd + 1f) / 2f, (nx + 1f) / 2f, (ny + 1f) / 2f)
            }
        }
        return out
    }

    private fun sample(d: FloatArray, w: Int, h: Int, x: Int, y: Int): Float =
        d[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]

    private fun pack(a: Float, r: Float, g: Float, b: Float): Int {
        fun c(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (c(a) shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    /**
     * Exact Euclidean distance transform, squared, by Felzenszwalb and Huttenlocher.
     *
     * Chosen over a chamfer pass because the normals come from this field's GRADIENT, and
     * a chamfer's error is not smooth — it is worst along diagonals, which on letterforms
     * is most of the interesting edge. An approximate distance looks fine as a distance
     * and produces normals that wobble, which shows up as the light crawling along a
     * stroke rather than sitting on it.
     *
     * Separable: one pass down the columns, one across the rows.
     */
    private fun edt(f: FloatArray, w: Int, h: Int): FloatArray {
        val d = f.copyOf()
        val col = FloatArray(h)
        for (x in 0 until w) {
            for (y in 0 until h) col[y] = d[y * w + x]
            val r = edt1d(col, h)
            for (y in 0 until h) d[y * w + x] = r[y]
        }
        val row = FloatArray(w)
        for (y in 0 until h) {
            for (x in 0 until w) row[x] = d[y * w + x]
            val r = edt1d(row, w)
            for (x in 0 until w) d[y * w + x] = r[x]
        }
        return d
    }

    /** The 1D transform the separable one is built from: a lower envelope of parabolas. */
    private fun edt1d(f: FloatArray, n: Int): FloatArray {
        val d = FloatArray(n)
        val v = IntArray(n)
        val z = FloatArray(n + 1)
        var k = 0
        v[0] = 0
        z[0] = -INF
        z[1] = INF
        for (q in 1 until n) {
            var s: Float
            while (true) {
                s = ((f[q] + q * q) - (f[v[k]] + v[k] * v[k])) / (2f * q - 2f * v[k])
                if (s <= z[k]) k-- else break
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
        return d
    }

    private const val INF = 1e20f
}
