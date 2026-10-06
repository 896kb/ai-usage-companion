package dev.kb896.aiusage.ui.video

import android.graphics.RectF
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoCropTest {
    private val panel = RectF(0f, 0f, 904f, 572f)

    @Test fun `a rotated portrait video is shown upright`() {
        assertEquals(1080 to 1920, VideoCrop.displaySize(1920, 1080, 90))
        assertEquals(1080 to 1920, VideoCrop.displaySize(1920, 1080, 270))
        assertEquals(1920 to 1080, VideoCrop.displaySize(1920, 1080, 0))
    }

    @Test fun `the whole video covering the region exactly is the full frame`() {
        assertArrayEquals(floatArrayOf(-1f, 1f, -1f, 1f), VideoCrop.ndc(panel, RectF(panel)), 1e-5f)
    }

    @Test fun `a video twice the region's size centred keeps its middle half`() {
        val dest = RectF(-452f, -286f, 1356f, 858f)
        assertArrayEquals(floatArrayOf(-0.5f, 0.5f, -0.5f, 0.5f), VideoCrop.ndc(panel, dest), 1e-5f)
    }

    // ArtPlacement's pan +1 moves the picture down, which shows its top in the region.
    @Test fun `a portrait video panned to its top keeps its top band`() {
        val (w, h) = VideoCrop.displaySize(1920, 1080, 90)        // 1080 x 1920, upright
        val crop = VideoCrop.forPan(panel, w, h, 0f, 1f, 1f)
        assertEquals(-1f, crop[0], 1e-4f); assertEquals(1f, crop[1], 1e-4f)   // full width
        assertEquals(1f, crop[3], 1e-4f)                                      // starts at the top
        val keptHeight = (crop[3] - crop[2]) / 2f                             // share of the height
        assertEquals((572f / 904f) * (1080f / 1920f), keptHeight, 1e-3f)
    }
}
