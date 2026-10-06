package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoLuminanceTest {
    @Test fun `a fully opaque video reads as its own frame`() {
        val cells = List(4) { 0.8f }
        assertEquals(0.8f, VideoLuminance.blend(cells, 1f, 0xFF000000.toInt())[0], 1e-5f)
    }

    @Test fun `a thinned video reads partly as the colour behind it`() {
        // A bright clip at 30% over black: mostly black.
        val out = VideoLuminance.blend(List(4) { 1f }, 0.3f, 0xFF000000.toInt())
        assertEquals(0.3f, out[0], 1e-5f)
        // Over white, the rest is white.
        assertEquals(1f, VideoLuminance.blend(List(4) { 1f }, 0.3f, 0xFFFFFFFF.toInt())[2], 1e-4f)
    }
}
