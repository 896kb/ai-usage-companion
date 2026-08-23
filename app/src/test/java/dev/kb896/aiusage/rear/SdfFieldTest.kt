package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SdfFieldTest {

    private val w = 41
    private val h = 41

    /** A filled disc in the middle of the mask — a letterform with one smooth edge. */
    private fun disc(radius: Float): FloatArray {
        val a = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val dx = x - w / 2f
            val dy = y - h / 2f
            a[y * w + x] = if (Math.hypot(dx.toDouble(), dy.toDouble()) <= radius) 1f else 0f
        }
        return a
    }

    private fun r(p: Int) = ((p shr 16) and 0xFF) / 255f
    private fun nx(p: Int) = ((p shr 8) and 0xFF) / 255f * 2f - 1f
    private fun ny(p: Int) = (p and 0xFF) / 255f * 2f - 1f
    private fun alpha(p: Int) = ((p ushr 24) and 0xFF) / 255f

    /** Outside a letter there is nothing to bend, and the shader returns early on it. */
    @Test fun `everything outside the shape is empty`() {
        val f = SdfField.build(disc(10f), w, h, 8f)
        assertEquals(0f, alpha(f[0]), 0f)
        assertEquals(0, f[0])
    }

    /**
     * The distance is NEGATIVE inside and zero at the edge, which is the convention the
     * shader reads: it takes `-sd` as depth, so an inside-positive field would put the
     * bevel on the outside of every stroke and nothing on the letter at all.
     */
    @Test fun `the field is zero at the edge and negative within`() {
        val f = SdfField.build(disc(14f), w, h, 10f)
        val centre = f[(h / 2) * w + w / 2]
        // r encodes (sd + 1) / 2, so 0.5 is sd = 0 and 0 is sd = -1.
        assertTrue("the centre should be deep inside: ${r(centre)}", r(centre) < 0.1f)
        val edge = f[(h / 2) * w + (w / 2 + 13)]
        assertTrue("just inside the edge should be shallow: ${r(edge)}", r(edge) > 0.35f)
    }

    /** Depth is measured over the spread, so a wider spread makes a deeper bevel. */
    @Test fun `the spread sets how far the bevel reaches`() {
        val shallow = SdfField.build(disc(14f), w, h, 4f)
        val deep = SdfField.build(disc(14f), w, h, 14f)
        val i = (h / 2) * w + (w / 2 + 8)
        assertTrue(
            "a wider spread should read as less deep at the same pixel",
            r(deep[i]) > r(shallow[i]),
        )
    }

    /**
     * THE NORMALS FACE OUT. On a disc the normal at any pixel should point away from the
     * centre — that is what makes the light sit on one side of a stroke rather than
     * crawling along it.
     */
    @Test fun `normals point away from the middle`() {
        val f = SdfField.build(disc(14f), w, h, 10f)
        for ((px, py) in listOf(
            w / 2 + 10 to h / 2, w / 2 - 10 to h / 2,
            w / 2 to h / 2 + 10, w / 2 to h / 2 - 10,
        )) {
            val p = f[py * w + px]
            val ox = px - w / 2f
            val oy = py - h / 2f
            val len = Math.hypot(ox.toDouble(), oy.toDouble()).toFloat()
            val dot = nx(p) * (ox / len) + ny(p) * (oy / len)
            assertTrue("normal at ($px,$py) faces inward: dot=$dot", dot > 0.7f)
        }
    }

    /** Coverage is carried through untouched — it is the glyph's own edge. */
    @Test fun `coverage survives`() {
        val a = disc(10f)
        a[(h / 2) * w + w / 2] = 0.5f
        val f = SdfField.build(a, w, h, 8f)
        assertEquals(0.5f, alpha(f[(h / 2) * w + w / 2]), 0.01f)
    }

    /** A mask with nothing in it produces nothing, rather than dividing by zero. */
    @Test fun `an empty mask is empty`() {
        val f = SdfField.build(FloatArray(w * h), w, h, 8f)
        assertTrue(f.all { it == 0 })
    }

    /** A mask that is entirely inside has no edge, so no bevel anywhere. */
    @Test fun `a full mask has no edge to bevel`() {
        val f = SdfField.build(FloatArray(w * h) { 1f }, w, h, 8f)
        val centre = f[(h / 2) * w + w / 2]
        assertEquals("a shape with no edge should read as fully deep", 0f, r(centre), 0.02f)
    }

    /** Zero spread must not divide by zero. */
    @Test fun `a zero spread is survivable`() {
        val f = SdfField.build(disc(10f), w, h, 0f)
        assertTrue(f.any { it != 0 })
    }

    /** The distance is Euclidean, so a diagonal is not overstated. */
    @Test fun `distance is euclidean, not chamfered`() {
        // One pixel set, far from the edges; measure how far the field says its
        // neighbours are along a diagonal versus along an axis.
        val a = FloatArray(w * h) { 1f }
        a[20 * w + 20] = 0f          // a single hole
        val f = SdfField.build(a, w, h, 20f)
        val axis = 1f - r(f[20 * w + 24]) * 2f       // 4 across
        val diag = 1f - r(f[23 * w + 23]) * 2f       // 3,3 = 4.24 away
        assertTrue("a diagonal should read slightly further than an axis: $axis vs $diag",
            diag > axis)
    }
}
