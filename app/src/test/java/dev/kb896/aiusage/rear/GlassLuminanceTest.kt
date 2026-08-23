package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassLuminanceTest {

    private fun grid(vararg v: Float) = FloatArray(25) { v.getOrElse(it) { v.last() } }

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
        val cells = FloatArray(25)
        cells[0] = 1f                                  // top-left cell only
        assertEquals(1f, GlassLuminance.average(cells, 0f, 0f, 10f, 10f, 100f, 100f), 0.001f)
        assertEquals(0f, GlassLuminance.average(cells, 50f, 50f, 60f, 60f, 100f, 100f), 0.001f)
    }

    @Test fun `a pane over the whole panel reads the mean`() {
        val cells = FloatArray(25) { if (it < 5) 1f else 0f }   // the top row lit
        assertEquals(
            0.2f, GlassLuminance.average(cells, 0f, 0f, 100f, 100f, 100f, 100f), 0.001f,
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

    @Test fun `a bright backdrop lifts the glass toward white`() {
        val lit = GlassLuminance.reactive(base, 1f)
        assertTrue("should have lightened", (lit shr 16 and 0xFF) > (base shr 16 and 0xFF))
        assertTrue("should have thickened", (lit ushr 24) > (base ushr 24))
    }

    @Test fun `a dark backdrop sinks it and thins it`() {
        val dim = GlassLuminance.reactive(base, 0f)
        assertTrue("should have darkened", (dim shr 16 and 0xFF) <= (base shr 16 and 0xFF))
        assertTrue("should have thinned", (dim ushr 24) < (base ushr 24))
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

    // -- the glint ----------------------------------------------------------------------

    /** A dark panel makes the edges catch more light; a bright one calms them. */
    @Test fun `the bevel answers the backdrop`() {
        assertTrue("should glint harder in the dark", GlassLuminance.bevel(0f) > 1f)
        assertTrue("should settle in the light", GlassLuminance.bevel(1f) < 1f)
        assertEquals("a neutral backdrop is the authored look",
            1f, GlassLuminance.bevel(0.5f), 0.001f)
    }

    @Test fun `the glint never inverts`() {
        for (i in 0..20) {
            assertTrue("a negative bevel would light it from inside",
                GlassLuminance.bevel(i / 20f) >= 0f)
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
