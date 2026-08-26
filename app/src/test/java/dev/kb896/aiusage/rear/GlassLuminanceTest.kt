package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassLuminanceTest {

    private val n = GlassLuminance.SIDE * GlassLuminance.SIDE

    private fun grid(vararg v: Float) = FloatArray(n) { v.getOrElse(it) { v.last() } }

    // -- luminance --------------------------------------------------------------------

    @Test fun `black is nothing and white is everything`() {
        assertEquals(0f, GlassLuminance.of(0xFF000000.toInt()), 0.001f)
        assertEquals(1f, GlassLuminance.of(0xFFFFFFFF.toInt()), 0.001f)
    }

    /**
     * The weights are the whole reason this is not an average. The eye is about seven
     * times more sensitive to green than to blue, so an unweighted mean would call a
     * saturated blue as bright as a green that is plainly much brighter.
     */
    @Test fun `green reads far brighter than blue at the same value`() {
        val green = GlassLuminance.of(0xFF00FF00.toInt())
        val blue = GlassLuminance.of(0xFF0000FF.toInt())
        assertTrue("green should dominate", green > blue * 5f)
        assertEquals(0.7152f, green, 0.001f)
        assertEquals(0.0722f, blue, 0.001f)
    }

    /** Alpha is not brightness. A transparent white is still white. */
    @Test fun `alpha is ignored`() {
        assertEquals(
            GlassLuminance.of(0xFFFFFFFF.toInt()),
            GlassLuminance.of(0x00FFFFFF), 0.001f,
        )
    }

    // -- averaging over a region ------------------------------------------------------

    @Test fun `a pane over one cell reads that cell`() {
        val cells = FloatArray(n)
        cells[0] = 1f                                  // top-left cell only
        // Half a cell, whatever a cell is: the point is a pane INSIDE one cell, and
        // hardcoding ten units made that true at a five-wide grid and false at sixteen.
        val cell = 100f / GlassLuminance.SIDE
        assertEquals(
            1f, GlassLuminance.average(cells, 0f, 0f, cell / 2f, cell / 2f, 100f, 100f), 0.001f,
        )
        assertEquals(
            0f, GlassLuminance.average(cells, 50f, 50f, 50f + cell / 2f, 50f + cell / 2f, 100f, 100f),
            0.001f,
        )
    }

    @Test fun `a pane over the whole panel reads the mean`() {
        // One row of the grid lit, whatever the grid's size: the mean over the whole
        // panel is that row's share of it.
        val side = GlassLuminance.SIDE
        val cells = FloatArray(n) { if (it < side) 1f else 0f }
        assertEquals(
            1f / side, GlassLuminance.average(cells, 0f, 0f, 100f, 100f, 100f, 100f), 0.001f,
        )
    }

    /** A pane hanging off the panel is still a pane. It must not throw or read nothing. */
    @Test fun `a pane past the edges is clamped, not dropped`() {
        val cells = grid(0.5f)
        assertEquals(
            0.5f,
            GlassLuminance.average(cells, -50f, -50f, 500f, 500f, 100f, 100f), 0.001f,
        )
    }

    @Test fun `a degenerate panel does not divide by zero`() {
        assertEquals(0f, GlassLuminance.average(grid(1f), 0f, 0f, 1f, 1f, 0f, 0f), 0.001f)
    }

    // -- the response curve -----------------------------------------------------------

    @Test fun `middling backdrops barely move it`() {
        assertEquals(0f, GlassLuminance.response(0.5f), 0.001f)
        assertTrue(
            "a slightly light backdrop should be quiet",
            Math.abs(GlassLuminance.response(0.6f)) < 0.05f,
        )
    }

    @Test fun `the ends run the whole way`() {
        assertEquals(-1f, GlassLuminance.response(0f), 0.001f)
        assertEquals(1f, GlassLuminance.response(1f), 0.001f)
    }

    @Test fun `it is signed, and squaring does not lose the sign`() {
        assertTrue("a dark backdrop should read negative", GlassLuminance.response(0.2f) < 0f)
        assertTrue("a light one positive", GlassLuminance.response(0.8f) > 0f)
    }

    @Test fun `it is monotonic`() {
        var last = -2f
        for (i in 0..20) {
            val v = GlassLuminance.response(i / 20f)
            assertTrue("response went backwards at $i", v > last)
            last = v
        }
    }

    // -- the tint ---------------------------------------------------------------------

    private val base = 0xB0203040.toInt()

    /**
     * A pane is read AGAINST its backdrop, so it has to move away from it. Matching it —
     * pale over white, dark over black — is a pane that vanishes at exactly the two ends
     * of the range where the measurement exists to help.
     */
    @Test fun `a bright backdrop darkens the glass`() {
        val lit = GlassLuminance.reactive(base, 1f)
        assertTrue("should have darkened", (lit shr 16 and 0xFF) < (base shr 16 and 0xFF))
        assertTrue("should have thickened", (lit ushr 24) > (base ushr 24))
    }

    @Test fun `a dark backdrop lightens it`() {
        val dim = GlassLuminance.reactive(base, 0f)
        assertTrue("should have lightened", (dim shr 16 and 0xFF) > (base shr 16 and 0xFF))
        assertTrue("should have thickened", (dim ushr 24) > (base ushr 24))
    }

    /** Neither end may be the quiet one. Both are where the pane has work to do. */
    @Test fun `both ends tint, and the middle barely does`() {
        val over = GlassLuminance.clearTint(1f) ushr 24
        val under = GlassLuminance.clearTint(0f) ushr 24
        val mid = GlassLuminance.clearTint(0.5f) ushr 24
        assertEquals("the ends should tint equally hard", over, under)
        assertTrue("the ends should tint", over > 0x20)
        assertTrue("the middle should not", mid < 0x08)
    }

    /** And they must tint in OPPOSITE directions, or "equally hard" means nothing. */
    @Test fun `the ends tint opposite ways`() {
        assertEquals("over white, toward black", 0x00, GlassLuminance.clearTint(1f) shr 16 and 0xFF)
        assertEquals("over black, toward white", 0xFF, GlassLuminance.clearTint(0f) shr 16 and 0xFF)
    }

    /**
     * A card is a surface to read type against at both ends of the range. Whatever the
     * backdrop does, it may not turn the card into a pane of nothing.
     */
    @Test fun `it stays a surface whatever is behind it`() {
        for (i in 0..20) {
            val a = GlassLuminance.reactive(base, i / 20f) ushr 24
            assertTrue("the card vanished at ${i / 20f}", a > 0x60)
            assertTrue("the card went opaque at ${i / 20f}", a <= 0xFF)
        }
    }

    @Test fun `every channel stays in range`() {
        for (c in listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0x40123456)) {
            for (i in 0..10) {
                val out = GlassLuminance.reactive(c, i / 10f)
                for (shift in listOf(0, 8, 16, 24)) {
                    val v = (out shr shift) and 0xFF
                    assertTrue("channel $shift out of range: $v", v in 0..255)
                }
            }
        }
    }
}
