package dev.kb896.aiusage.ui.video

import java.nio.ByteBuffer

/**
 * One frame's picture as plain I420 bytes — Y, then U, then V, each row tight — and back
 * into the planes an encoder hands out. A decoder's and an encoder's planes pad their rows
 * and may interleave U and V (a pixel stride of 2), each in its own way; the bytes between
 * are the one layout both can be read from and written to.
 */
object Yuv {
    class Plane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int)

    fun size(w: Int, h: Int): Int = w * h + 2 * (w / 2) * (h / 2)

    fun pack(planes: List<Plane>, w: Int, h: Int, out: ByteArray) {
        var o = 0
        for (i in 0..2) {
            val p = planes[i]
            val pw = if (i == 0) w else w / 2
            val ph = if (i == 0) h else h / 2
            for (r in 0 until ph) {
                val row = r * p.rowStride
                if (p.pixelStride == 1) {
                    p.buffer.duplicate().apply { position(row) }.get(out, o, pw)
                } else {
                    for (c in 0 until pw) out[o + c] = p.buffer.get(row + c * p.pixelStride)
                }
                o += pw
            }
        }
    }

    fun unpack(src: ByteArray, w: Int, h: Int, planes: List<Plane>) {
        var o = 0
        for (i in 0..2) {
            val p = planes[i]
            val pw = if (i == 0) w else w / 2
            val ph = if (i == 0) h else h / 2
            for (r in 0 until ph) {
                val row = r * p.rowStride
                if (p.pixelStride == 1) {
                    p.buffer.duplicate().apply { position(row) }.put(src, o, pw)
                } else {
                    for (c in 0 until pw) p.buffer.put(row + c * p.pixelStride, src[o + c])
                }
                o += pw
            }
        }
    }
}
