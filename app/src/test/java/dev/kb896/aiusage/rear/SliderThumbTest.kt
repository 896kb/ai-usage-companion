package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SliderThumbTest {

    private val track = 278f      // the usage bars' own span
    private val thumb = 32f

    private fun centre(p: Float) = SliderThumb.centre(track, thumb, p)

    /**
     * THE BUG THIS EXISTS FOR. Packing the thumb inside the track means its centre only
     * travels from half a thumb in to half a thumb short, so a bar at nothing shows its
     * thumb already advanced and a bar at everything shows it stopping short.
     */
    @Test fun `the thumb is centred on the value, not packed inside the track`() {
        // The old, wrong geometry, for contrast: centre from thumb/2 to track - thumb/2.
        val packedAtZero = thumb / 2f
        assertTrue(
            "the thumb at zero is still sitting where the packed one did",
            centre(0f) < packedAtZero,
        )
        val packedAtOne = track - thumb / 2f
        assertTrue(
            "the thumb at full is still stopping where the packed one did",
            centre(1f) > packedAtOne,
        )
    }

    /** At the ends it overhangs by a quarter of its width — the reference's clamp. */
    @Test fun `it overhangs each end by a quarter of itself`() {
        assertEquals(-thumb * SliderThumb.OVERHANG, SliderThumb.left(track, thumb, 0f), 0.01f)
        assertEquals(
            track + thumb * SliderThumb.OVERHANG,
            SliderThumb.left(track, thumb, 1f) + thumb, 0.01f,
        )
    }

    /**
     * Never more than that — while the thumb FITS.
     *
     * Conditional on purpose. A thumb wider than its own track has to stick out further
     * than a quarter of itself whatever it does, so the invariant is about instruments
     * that are the shape of instruments; the degenerate case has its own test below.
     */
    @Test fun `it never comes off the instrument`() {
        for (t in listOf(40f, 120f, 278f, 600f)) {
            for (w in listOf(8f, 32f, 90f)) {
                if (w > t) continue
                var p = 0f
                while (p <= 1f) {
                    val l = SliderThumb.left(t, w, p)
                    assertTrue("left edge ran off at $p (track $t thumb $w)",
                        l >= -w * SliderThumb.OVERHANG - 0.01f)
                    assertTrue("right edge ran off at $p (track $t thumb $w)",
                        l + w <= t + w * SliderThumb.OVERHANG + 0.01f)
                    p += 0.05f
                }
            }
        }
    }

    /** In the middle it is exactly on the value, with no clamp involved. */
    @Test fun `mid travel is the value itself`() {
        assertEquals(track * 0.5f, centre(0.5f), 0.01f)
        assertEquals(track * 0.25f, centre(0.25f), 0.01f)
        assertEquals(track * 0.75f, centre(0.75f), 0.01f)
    }

    /** Monotonic: a rising reading never moves the thumb backwards. */
    @Test fun `it only ever moves forward`() {
        var last = Float.NEGATIVE_INFINITY
        var p = 0f
        while (p <= 1f) {
            val c = centre(p)
            assertTrue("went backwards at $p", c >= last - 0.0001f)
            last = c
            p += 0.01f
        }
    }

    /** A value that has not settled cannot throw the thumb off the panel. */
    @Test fun `out of range readings are clamped`() {
        assertEquals(centre(0f), centre(-2f), 0.001f)
        assertEquals(centre(1f), centre(5f), 0.001f)
    }

    /**
     * A thumb wider than its track sits still in the middle rather than throwing.
     *
     * coerceIn does not pick a side on an empty range, it raises — so before this was
     * handled, a ring whose arc grew short enough would have taken the panel down on that
     * frame. There is no travel to draw in that case, so there is nothing to animate.
     */
    @Test fun `a thumb wider than its track is centred and still`() {
        // The range only inverts once the thumb is more than TWICE the track: the clamp
        // is [-w/4, track - 3w/4], which crosses when track < w/2. Ninety on forty.
        val l0 = SliderThumb.left(40f, 90f, 0f)
        val l1 = SliderThumb.left(40f, 90f, 1f)
        assertEquals("it should not move when there is nowhere to move", l0, l1, 0.001f)
        assertEquals("and it should be centred", (40f - 90f) / 2f, l0, 0.001f)
    }

    /** A thumb merely CROWDED by its track still travels, just not far. */
    @Test fun `a crowded thumb still moves`() {
        val l0 = SliderThumb.left(40f, 60f, 0f)
        val l1 = SliderThumb.left(40f, 60f, 1f)
        assertTrue("a crowded thumb should still have some travel", l1 > l0)
    }
}
