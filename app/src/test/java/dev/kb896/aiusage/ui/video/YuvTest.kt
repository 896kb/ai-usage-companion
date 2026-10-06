package dev.kb896.aiusage.ui.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class YuvTest {
    private val w = 6
    private val h = 4

    /** A frame as a decoder lays it out: Y with padded rows, U and V interleaved (stride 2). */
    private fun planes(): List<Yuv.Plane> {
        val yStride = w + 2
        val y = ByteBuffer.allocate(yStride * h)
        for (r in 0 until h) for (c in 0 until w) y.put(r * yStride + c, (10 * r + c).toByte())
        val cw = w / 2; val ch = h / 2; val cStride = w + 4
        val uv = ByteArray(cStride * ch)
        for (r in 0 until ch) for (c in 0 until cw) {
            uv[r * cStride + 2 * c] = (100 + 10 * r + c).toByte()       // U
            uv[r * cStride + 2 * c + 1] = (200 - 10 * r - c).toByte()   // V
        }
        val u = ByteBuffer.wrap(uv)
        val v = ByteBuffer.wrap(uv, 1, uv.size - 1).slice()
        return listOf(Yuv.Plane(y, yStride, 1), Yuv.Plane(u, cStride, 2), Yuv.Plane(v, cStride, 2))
    }

    @Test fun `a frame packs to plain I420, row by row`() {
        val out = ByteArray(Yuv.size(w, h))
        Yuv.pack(planes(), w, h, out)
        assertEquals((10 * 3 + 5).toByte(), out[3 * w + 5])           // Y at row 3, column 5
        assertEquals((100 + 10 * 1 + 2).toByte(), out[w * h + 1 * (w / 2) + 2])            // U row 1, col 2
        assertEquals((200 - 10 * 1 - 2).toByte(), out[w * h + (w / 2) * (h / 2) + 1 * (w / 2) + 2])  // V
    }

    @Test fun `unpacking into an encoder's planes and packing again gives the same frame`() {
        val packed = ByteArray(Yuv.size(w, h))
        Yuv.pack(planes(), w, h, packed)
        val target = planes().map { p -> Yuv.Plane(ByteBuffer.allocate(p.buffer.capacity()), p.rowStride, p.pixelStride) }
        // The interleaved target: U and V share memory, as an encoder's NV12 input does.
        val uv = ByteArray(target[1].buffer.capacity())
        val shared = listOf(target[0], Yuv.Plane(ByteBuffer.wrap(uv), target[1].rowStride, 2),
            Yuv.Plane(ByteBuffer.wrap(uv, 1, uv.size - 1).slice(), target[2].rowStride, 2))
        Yuv.unpack(packed, w, h, shared)
        val again = ByteArray(Yuv.size(w, h))
        Yuv.pack(shared, w, h, again)
        assertArrayEquals(packed, again)
    }

    @Test fun `played backwards, the last frame comes first at time 0`() {
        assertEquals(0L, ReverseExport.reversedUs(ptsUs = 3_488_578, lastUs = 3_488_578))
        assertEquals(3_488_578L, ReverseExport.reversedUs(ptsUs = 0, lastUs = 3_488_578))
    }

    @Test fun `each stretch of the clip runs from its key frame to the next`() {
        val starts = listOf(0L, 500_000L, 1_000_000L)
        assertEquals(listOf(1_000_000L to Long.MAX_VALUE, 500_000L to 1_000_000L, 0L to 500_000L),
            ReverseExport.stretchesBackwards(starts))
    }
}
