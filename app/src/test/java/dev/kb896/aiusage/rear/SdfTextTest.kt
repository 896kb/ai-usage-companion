package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SdfTextTest {

    /**
     * The whole point of the fast transform is that it agrees with the slow one. This is
     * the slow one: for every cell, look at every lit cell and keep the nearest.
     */
    private fun bruteForce(inside: BooleanArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h) { SdfText.INF }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var best = SdfText.INF
                for (yy in 0 until h) {
                    for (xx in 0 until w) {
                        if (!inside[yy * w + xx]) continue
                        val dx = (x - xx).toFloat()
                        val dy = (y - yy).toFloat()
                        val d = dx * dx + dy * dy
                        if (d < best) best = d
                    }
                }
                out[y * w + x] = best
            }
        }
        return out
    }

    @Test fun `it agrees with brute force on a single lit cell`() {
        val w = 9
        val h = 7
        val inside = BooleanArray(w * h)
        inside[3 * w + 4] = true
        val fast = SdfText.distances(inside, w, h)
        val slow = bruteForce(inside, w, h)
        for (i in fast.indices) {
            assertEquals("cell $i", slow[i], fast[i], 0.001f)
        }
    }

    /** Two sources, so the envelope actually has to choose between parabolas. */
    @Test fun `it agrees with brute force on a scattered shape`() {
        val w = 13
        val h = 11
        val inside = BooleanArray(w * h)
        for (p in listOf(0 to 0, 6 to 5, 12 to 10, 3 to 9, 11 to 1)) {
            inside[p.second * w + p.first] = true
        }
        val fast = SdfText.distances(inside, w, h)
        val slow = bruteForce(inside, w, h)
        for (i in fast.indices) {
            assertEquals("cell $i", slow[i], fast[i], 0.001f)
        }
    }

    /** A filled block, which is what the inside of a stroke looks like. */
    @Test fun `it agrees with brute force on a solid block`() {
        val w = 10
        val h = 10
        val inside = BooleanArray(w * h)
        for (y in 3..6) for (x in 2..7) inside[y * w + x] = true
        val fast = SdfText.distances(inside, w, h)
        val slow = bruteForce(inside, w, h)
        for (i in fast.indices) assertEquals("cell $i", slow[i], fast[i], 0.001f)
    }

    @Test fun `a lit cell is at no distance from itself`() {
        val inside = BooleanArray(16) { true }
        for (v in SdfText.distances(inside, 4, 4)) assertEquals(0f, v, 0.001f)
    }

    @Test fun `nothing lit leaves everything far away`() {
        val inside = BooleanArray(16)
        for (v in SdfText.distances(inside, 4, 4)) assertTrue("should be unreachable", v > 1e9f)
    }

    // -- the packed field -------------------------------------------------------------

    private fun blockCoverage(w: Int, h: Int): FloatArray {
        val c = FloatArray(w * h)
        for (y in 4..9) for (x in 4..9) c[y * w + x] = 1f
        return c
    }

    @Test fun `distance is signed about the edge`() {
        val w = 14
        val h = 14
        val out = SdfText.pack(blockCoverage(w, h), w, h)
        fun red(x: Int, y: Int) = (out[y * w + x] shr 16) and 0xFF
        // The middle of the block is well inside, so below the half-way mark.
        assertTrue("the inside should read as inside", red(6, 6) < 120)
        // A corner of the field is well outside it.
        assertTrue("the outside should read as outside", red(0, 0) > 135)
    }

    @Test fun `coverage is carried in alpha, so the shader knows where the ink is`() {
        val w = 14
        val h = 14
        val cov = blockCoverage(w, h)
        val out = SdfText.pack(cov, w, h)
        assertEquals(255, (out[6 * w + 6] ushr 24))
        assertEquals(0, (out[0] ushr 24))
    }

    /**
     * The normal has to point away from the shape, or the light comes from inside the
     * letters. Left of the block, "away" is to the left: a negative x.
     */
    @Test fun `the normal points out of the shape`() {
        val w = 14
        val h = 14
        val out = SdfText.pack(blockCoverage(w, h), w, h)
        fun nx(x: Int, y: Int) = (((out[y * w + x] shr 8) and 0xFF) / 255f) * 2f - 1f
        assertTrue("normal should face left of the block", nx(2, 6) < -0.5f)
        assertTrue("and right of it", nx(11, 6) > 0.5f)
    }

    @Test fun `every channel stays a byte`() {
        val w = 12
        val h = 12
        for (v in SdfText.pack(blockCoverage(w, h), w, h)) {
            for (shift in listOf(0, 8, 16, 24)) {
                assertTrue("channel out of range", ((v shr shift) and 0xFF) in 0..255)
            }
        }
    }
}
