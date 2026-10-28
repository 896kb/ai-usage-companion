package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RearTouchesTest {

    // The 17 Pro's rear touchscreen, measured: 0..90399 x 0..57199, display pixels times 100.
    private fun touches() = RearTouches(90399, 57199)

    private fun hex(v: Int) = "%08x".format(v)
    private val syn = "0000 0000 00000000"
    private fun land(x: Int, y: Int, id: Int = 1) = listOf("0003 0039 ${hex(id)}", "0003 0035 ${hex(x)}", "0003 0036 ${hex(y)}", "0001 014a 00000001", syn)
    private fun move(x: Int, y: Int) = listOf("0003 0035 ${hex(x)}", "0003 0036 ${hex(y)}", syn)
    private val lift = listOf("0003 0039 ffffffff", "0001 014a 00000000", syn)

    private fun RearTouches.run(lines: List<String>, at: Long = 0L) = lines.mapNotNull { feed(it, at) }

    @Test fun `a landing finger is a down, in panel pixels`() {
        val e = touches().run(land(60100, 54500)).single()
        assertTrue(e is RearTouches.Down)
        assertEquals(601f, e.x, 0.5f)
        assertEquals(545f, e.y, 0.5f)
    }

    @Test fun `then moves, then an up where it was last`() {
        val t = touches()
        t.run(land(60100, 54500))
        val m = t.run(move(60000, 40000)).single()
        assertTrue(m is RearTouches.Move)
        assertEquals(400f, m.y, 0.5f)
        val u = t.run(lift).single()
        assertTrue(u is RearTouches.Up)
        assertEquals(400f, u.y, 0.5f)
    }

    @Test fun `a second finger is ignored`() {
        val t = touches()
        t.run(land(60100, 54500))
        val second = listOf("0003 002f 00000001") + land(10000, 10000, id = 2)
        assertEquals(emptyList<RearTouches.Event>(), t.run(second))
    }

    @Test fun `a line that is not an event is nothing`() {
        assertNull(touches().feed("add device 3: /dev/input/event7", 0))
    }

    @Test fun `a finger landing where the last one lifted is still a down`() {
        val t = touches()
        val first = t.run(land(60100, 54500)) + t.run(lift)
        val same = t.run(listOf("0003 0039 00000002", syn)) + t.run(lift)
        val events = first + same
        assertEquals(listOf("Down", "Up", "Down", "Up"), events.map { it::class.simpleName })
        assertEquals(601f, events[2].x, 0.5f)
        assertEquals(545f, events[2].y, 0.5f)
        val third = t.run(land(10000, 10000)).single()
        assertTrue(third is RearTouches.Down)
        assertEquals(100f, third.x, 0.5f)
        assertEquals(100f, third.y, 0.5f)
    }

    @Test fun `a second finger left down after the first lifts is ignored, and the next landing is the new first`() {
        val t = touches()
        val events = t.run(
            land(60100, 54500) +
                listOf("0003 002f 00000001") + land(10000, 10000, id = 2) +
                listOf("0003 002f 00000000", "0003 0039 ffffffff", syn) +
                listOf("0003 002f 00000001") + move(12000, 12000) +
                listOf("0003 002f 00000001", "0003 0039 ffffffff", syn) +
                listOf("0003 002f 00000000") + land(30000, 30000, id = 3),
        )
        assertEquals(listOf("Down", "Up", "Down"), events.map { it::class.simpleName })
        assertEquals(300f, events[2].x, 0.5f)
        assertEquals(300f, events[2].y, 0.5f)
    }

    @Test fun `a line with a device prefix is read`() {
        val e = touches().run(land(60100, 54500).map { "/dev/input/event7: $it" })
        assertTrue(e.single() is RearTouches.Down)
    }
}
