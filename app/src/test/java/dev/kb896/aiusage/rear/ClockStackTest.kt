package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockStackTest {
    private val slot = 50f
    private val colon = 20f
    private val span = 6 * slot + 2 * colon
    private val cx = 600f
    private val base = 330f
    private val size = 92f
    private val top = 64f
    private val bottom = 464f

    private fun shifts(v: Float, seconds: Float = 1f): FloatArray {
        val s = if (seconds >= 1f) span else 4 * slot + colon + (colon + 2 * slot) * seconds
        return FloatArray(16).also { ClockStack.shifts("12:34:56", cx, s, slot, colon, seconds, base, size, top, bottom, v, it) }
    }

    /** Where figure [i] stands at [v]: its slot's middle across, its baseline down. */
    private fun at(i: Int, v: Float, seconds: Float = 1f): Pair<Float, Float> {
        val s = if (seconds >= 1f) span else 4 * slot + colon + (colon + 2 * slot) * seconds
        var x = cx - s / 2f
        for (j in 0 until i) x += (if ("12:34:56"[j] == ':') colon else slot) * (if (j >= 5) seconds else 1f)
        val w = (if ("12:34:56"[i] == ':') colon else slot) * (if (i >= 5) seconds else 1f)
        val d = shifts(v, seconds)
        return (x + w / 2f + d[2 * i]) to (base + d[2 * i + 1])
    }

    @Test fun `across, nothing moves`() {
        assertTrue(shifts(0f).all { it == 0f })
    }

    @Test fun `on end, hours over minutes over seconds, each pair centred`() {
        val (h1x, h1y) = at(0, 1f)
        val (h2x, h2y) = at(1, 1f)
        val (m1x, m1y) = at(3, 1f)
        val (s1x, s1y) = at(6, 1f)
        assertEquals(h1y, h2y, 0.001f)
        assertEquals(cx, (h1x + h2x) / 2f, 0.001f)
        assertEquals(h1x, m1x, 0.001f)
        assertEquals(h1x, s1x, 0.001f)
        assertTrue(h1y < m1y && m1y < s1y)
    }

    @Test fun `the stack stands in the middle of its band`() {
        val (_, h) = at(0, 1f)
        val (_, s) = at(6, 1f)
        val middle = (h + s) / 2f - ClockStack.CENTRE * size
        assertEquals((top + bottom) / 2f, middle, 0.5f)
    }

    @Test fun `without seconds the two rows take the middle`() {
        val (_, h) = at(0, 1f, seconds = 0f)
        val (_, m) = at(3, 1f, seconds = 0f)
        assertEquals((top + bottom) / 2f, (h + m) / 2f - ClockStack.CENTRE * size, 0.5f)
    }

    @Test fun `half way, half way there`() {
        val (x0, y0) = at(4, 0f)
        val (x1, y1) = at(4, 1f)
        val (xh, yh) = at(4, 0.5f)
        assertEquals((x0 + x1) / 2f, xh, 0.001f)
        assertEquals((y0 + y1) / 2f, yh, 0.001f)
    }

    @Test fun `a lone hour figure stands in the middle of its row`() {
        // " 9:05:30": the blank has no width in the line, and the 9 is centred on end.
        val figures = " 9:05:30"
        val s = 5 * slot + 2 * colon
        val out = FloatArray(16)
        ClockStack.shifts(figures, cx, s, slot, colon, 1f, base, size, top, bottom, 1f, out)
        val lineX = cx - s / 2f + slot / 2f // the 9's slot, the blank before it taking nothing
        assertEquals(cx, lineX + out[2], 0.001f)
    }

    @Test fun `the last row is the seconds, or the minutes without them`() {
        val with = ClockStack.lastRowBaseline(size, top, bottom, 1f)
        val without = ClockStack.lastRowBaseline(size, top, bottom, 0f)
        assertTrue(with > without)
        val (_, s) = at(6, 1f)
        assertEquals(s, with, 0.001f)
        val (_, m) = at(3, 1f, seconds = 0f)
        assertEquals(m, without, 0.001f)
    }

    @Test fun `AM and PM take a row under the last, and the stack centres with it`() {
        val withMark = ClockStack.markerBaseline(size, top, bottom, secondsScale = 1f, marker = 1f)
        val seconds = ClockStack.lastRowBaseline(size, top, bottom, 1f, marker = 1f)
        assertEquals(size * ClockStack.ROW, withMark - seconds, 0.001f)
        // Four rows centred: hours' middle and AM/PM's middle either side of the band's middle.
        val out = FloatArray(16)
        ClockStack.shifts("12:34:56", cx, span, slot, colon, 1f, base, size, top, bottom, 1f, out, marker = 1f)
        val hours = base + out[1]
        assertEquals((top + bottom) / 2f, (hours + withMark) / 2f - ClockStack.CENTRE * size, 0.5f)
        // Without seconds, straight under the minutes.
        assertEquals(
            size * ClockStack.ROW,
            ClockStack.markerBaseline(size, top, bottom, 0f, 1f) - ClockStack.lastRowBaseline(size, top, bottom, 0f, marker = 1f), 0.001f,
        )
    }
}
