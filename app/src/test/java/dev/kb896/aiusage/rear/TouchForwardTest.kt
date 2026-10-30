package dev.kb896.aiusage.rear

import android.view.MotionEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchForwardTest {

    // The 17 Pro: 904x572, lenses to x 296, raw axes display pixels times 100, Back from x 842.
    private fun forward() = TouchForward(90399, 57199, 904, 572, 296, 842f, 16f)

    private fun hex(v: Int) = "%08x".format(v)
    private val syn = "0000 0000 00000000"
    private fun slot(s: Int) = "0003 002f ${hex(s)}"
    private fun id(i: Int) = "0003 0039 ${hex(i)}"
    private fun x(px: Int) = "0003 0035 ${hex(px * 100)}"
    private fun y(px: Int) = "0003 0036 ${hex(px * 100)}"
    private val up = "0003 0039 ffffffff"

    private fun TouchForward.run(vararg lines: String, at: Long = 0L, open: Boolean = true) =
        lines.flatMap { feed(it, at) { open } }

    private fun List<TouchForward.Out>.masks() = map { it.masked }

    @Test fun `a finger right of the lenses is the app's, less the lenses`() {
        val f = forward()
        val d = f.run(id(1), x(500), y(100), syn).single()
        assertEquals(MotionEvent.ACTION_DOWN, d.masked)
        assertEquals(204f, d.xs[0], 0.01f)
        assertEquals(100f, d.ys[0], 0.01f)
        val m = f.run(x(510), syn).single()
        assertEquals(MotionEvent.ACTION_MOVE, m.masked)
        assertEquals(214f, m.xs[0], 0.01f)
        assertEquals(MotionEvent.ACTION_UP, f.run(up, syn).single().masked)
    }

    @Test fun `one landing behind the lenses is nobody's, and clamped if an app's wanders there`() {
        val f = forward()
        assertTrue(f.run(id(1), x(100), y(100), syn).isEmpty())
        assertTrue(f.run(x(500), syn).isEmpty())
        assertTrue(f.run(up, syn).isEmpty())
        f.run(id(2), x(400), y(100), syn)
        assertEquals(0f, f.run(x(100), syn).single().xs[0], 0.01f)
    }

    @Test fun `a second finger is a pointer down at its index, and the first's lift a pointer up`() {
        val f = forward()
        f.run(id(1), x(500), y(100), syn)
        val pd = f.run(slot(1), id(2), x(600), y(200), syn).single()
        assertEquals(MotionEvent.ACTION_POINTER_DOWN, pd.masked)
        assertEquals(1, pd.index)
        assertArrayEquals(intArrayOf(0, 1), pd.ids)
        val pu = f.run(slot(0), up, syn).single()
        assertEquals(MotionEvent.ACTION_POINTER_UP, pu.masked)
        assertEquals(0, pu.index)
        // Its id stays with the finger still down, and a new one takes the free id.
        val m = f.run(slot(1), x(610), syn).single()
        assertArrayEquals(intArrayOf(1), m.ids)
        val again = f.run(slot(0), id(3), x(700), y(300), syn).single()
        assertArrayEquals(intArrayOf(0, 1), again.ids)
        assertEquals(0, again.index)
    }

    @Test fun `both fingers moving in a frame are one move`() {
        val f = forward()
        f.run(id(1), x(500), y(100), slot(1), id(2), x(600), y(200), syn)
        val m = f.run(slot(0), x(505), slot(1), x(605), syn).single()
        assertEquals(MotionEvent.ACTION_MOVE, m.masked)
        assertEquals(2, m.ids.size)
    }

    @Test fun `a light tap, landing and lifting in a frame, is a down and an up`() {
        assertEquals(
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP),
            forward().run(id(1), x(500), y(100), up, syn).masks(),
        )
    }

    @Test fun `a landing at the last position, axes unsent, is at that position`() {
        val f = forward()
        f.run(id(1), x(500), y(100), syn)
        f.run(up, syn)
        val d = f.run(id(2), syn).single()
        assertEquals(204f, d.xs[0], 0.01f)
    }

    @Test fun `taken mid-way, a cancel of the fingers it had, once, then nothing till all have lifted`() {
        val f = forward()
        val down = f.run(id(1), x(500), y(560), slot(1), id(2), x(600), y(300), syn)
        assertEquals(2, down.size)
        val c = f.run(slot(0), y(530), syn, open = false).single()
        assertEquals(MotionEvent.ACTION_CANCEL, c.masked)
        assertEquals(2, c.ids.size)
        assertEquals(down[0].downMs, c.downMs)
        assertTrue(f.run(y(500), syn, open = false).isEmpty())
        // Open again, the sheet gone, but these fingers stay what they were.
        assertTrue(f.run(y(480), syn).isEmpty())
        assertTrue(f.run(slot(2), id(3), x(700), y(100), syn).isEmpty())
        assertTrue(f.run(slot(2), up, slot(1), up, slot(0), up, syn).isEmpty())
        assertEquals(MotionEvent.ACTION_DOWN, f.run(slot(0), id(4), x(500), y(100), syn).single().masked)
    }

    @Test fun `landing while not open is not forwarded, a tap neither`() {
        val f = forward()
        assertTrue(f.run(id(1), x(500), y(100), syn, open = false).isEmpty())
        assertTrue(f.run(up, syn, open = false).isEmpty())
        assertTrue(f.run(id(2), x(500), y(100), up, syn, open = false).isEmpty())
    }

    @Test fun `a swipe left off the right edge never reaches the app`() {
        val f = forward()
        assertTrue(f.run(id(1), x(880), y(300), syn, at = 0).isEmpty())
        assertTrue(f.run(x(870), syn, at = 8).isEmpty())
        assertTrue(f.run(x(850), syn, at = 16).isEmpty())
        assertTrue(f.run(x(700), syn, at = 24).isEmpty())
        assertTrue(f.run(up, syn, at = 32).isEmpty())
    }

    @Test fun `a tap on the right edge reaches the app at its lift`() {
        val f = forward()
        assertTrue(f.run(id(1), x(880), y(300), syn, at = 0).isEmpty())
        val out = f.run(up, syn, at = 40)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), out.masks())
        assertEquals(584f, out[0].xs[0], 0.01f)
    }

    @Test fun `a vertical drag on the right edge is the app's, from where it landed`() {
        val f = forward()
        f.run(id(1), x(880), y(300), syn)
        val out = f.run(y(330), syn, at = 20)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE), out.masks())
        assertEquals(300f, out[0].ys[0], 0.01f)
        assertEquals(330f, out[1].ys[0], 0.01f)
    }

    @Test fun `a finger staying on the right edge goes to the app`() {
        val f = forward()
        f.run(id(1), x(880), y(300), syn, at = 0)
        assertTrue(f.run(y(301), syn, at = 100).isEmpty())
        assertEquals(MotionEvent.ACTION_DOWN, f.run(y(302), syn, at = TouchForward.HOLD_MS + 1).first().masked)
    }

    @Test fun `a second finger lets a held one go first`() {
        val f = forward()
        f.run(id(1), x(880), y(300), syn)
        val out = f.run(slot(1), id(2), x(500), y(100), syn, at = 10)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN), out.masks())
        assertEquals(584f, out[0].xs[0], 0.01f)
        assertEquals(584f, out[1].xs[0], 0.01f)
        assertEquals(204f, out[1].xs[1], 0.01f)
    }

    @Test fun `a held finger taken by the sheet is simply gone`() {
        val f = forward()
        f.run(id(1), x(880), y(560), syn)
        assertTrue(f.run(y(530), syn, at = 20, open = false).isEmpty())
        assertTrue(f.run(up, syn, at = 30).isEmpty())
    }

    @Test fun `the reader ending cancels what is open`() {
        val f = forward()
        f.run(id(1), x(500), y(100), syn)
        assertEquals(MotionEvent.ACTION_CANCEL, f.cancel(5).single().masked)
        assertTrue(f.cancel(6).isEmpty())
    }
}
