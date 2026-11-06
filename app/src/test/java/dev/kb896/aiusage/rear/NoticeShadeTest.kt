package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeShadeTest {
    private val left = 296f
    private fun item(n: Int) = NoticeShade.Item("k$n", "p", "App", "Title $n", "Text")
    private fun open(count: Int): NoticeShade = NoticeShade(left).apply {
        items((1..count).map(::item))
        beginPull(10f, 30f, 0)
        endPull(400f, 100)
    }

    @Test fun `the pull is the top edge's right half, the control centre keeps the left`() {
        val mid = left + (904f - left) / 2f
        assertTrue(NoticeShade.startsPull(mid + 1f, 20f, left))
        assertTrue(NoticeShade.startsPull(900f, 20f, left))
        assertFalse(NoticeShade.startsPull(mid - 1f, 20f, left))
        assertFalse(NoticeShade.startsPull(mid + 1f, 60f, left))
        assertTrue(ControlCenter.startsPull(mid - 1f, 20f, left))
        assertFalse(ControlCenter.startsPull(mid + 1f, 20f, left))
    }

    @Test fun `past halfway it opens, short of it it springs back`() {
        assertEquals(1f, open(1).progress(1000), 0.001f)
        val short = NoticeShade(left).apply { beginPull(10f, 30f, 0); movePull(60f, 200) }
        val effects = short.endPull(60f, 1000)
        assertEquals(0f, short.progress(2000), 0.001f)
        assertTrue(effects.single() is NoticeShade.Effect.ShutAt)
    }

    @Test fun `a row swiped far enough is dismissed and then gone`() {
        val s = open(2)
        val y = NoticeShade.TOP + 10f
        s.fingerDown(500f, y, 1000)
        s.fingerMove(700f, y, 1050)
        assertEquals(listOf<NoticeShade.Effect>(NoticeShade.Effect.Dismissed("k1")), s.fingerUp(700f, y, 1060))
        assertEquals(1, s.count)
        assertEquals(listOf("k2"), s.rows(2000).map { it.item.key })
    }

    @Test fun `a short swipe comes back`() {
        val s = open(1)
        val y = NoticeShade.TOP + 10f
        s.fingerDown(500f, y, 1000)
        s.fingerMove(560f, y, 1050)
        assertTrue(s.fingerUp(560f, y, 1060).isEmpty())
        assertEquals(0f, s.rows(2000).single().dx, 0.001f)
    }

    @Test fun `a tap beside the rows shuts it, a tap on one does not`() {
        val s = open(1)
        s.fingerDown(500f, NoticeShade.TOP + 10f, 1000)
        assertTrue(s.fingerUp(500f, NoticeShade.TOP + 10f, 1010).isEmpty())
        s.fingerDown(500f, 500f, 1100)
        assertTrue(s.fingerUp(500f, 500f, 1110).single() is NoticeShade.Effect.ShutAt)
    }

    @Test fun `a long list scrolls before a drag up shuts it`() {
        val s = open(12)
        s.fingerDown(500f, 500f, 1000)
        s.fingerMove(500f, 300f, 1100)
        assertEquals(1f, s.progress(1100), 0.001f)
        assertTrue(s.rows(1100).first().top < NoticeShade.TOP)
        s.fingerUp(500f, 300f, 1200)
        assertEquals(1f, s.progress(2000), 0.001f)
    }

    @Test fun `a short list shuts on a drag up`() {
        val s = open(1)
        s.fingerDown(500f, 500f, 1000)
        s.fingerMove(500f, 300f, 1100)
        assertTrue(s.progress(1100) < 1f)
        assertTrue(s.fingerUp(500f, 300f, 1200).single() is NoticeShade.Effect.ShutAt)
    }

    @Test fun `an update leaves a row on its way out where it was`() {
        val s = open(3)
        val y = NoticeShade.TOP + 10f
        s.fingerDown(500f, y, 1000); s.fingerMove(700f, y, 1050); s.fingerUp(700f, y, 1060)
        s.items(listOf(item(2), item(3)))
        assertEquals(listOf("k1", "k2", "k3"), s.rows(1070).map { it.item.key })
    }

    @Test fun `a finger landing as it settles shut is not its own, so nothing can hold it up`() {
        val s = open(1)
        val effects = s.close(1000)
        assertFalse(s.shown(1050))
        assertTrue(effects.single() is NoticeShade.Effect.ShutAt)
        assertTrue(s.isShut(1000 + NoticeShade.SETTLE_MS))
    }

    @Test fun `open at rest, a finger is its own`() {
        assertTrue(open(1).shown(1000))
    }

    @Test fun `the next row rises as one is swiped away`() {
        val s = open(2)
        val y = NoticeShade.TOP + 10f
        val rest = s.rows(1000)[1].top
        s.fingerDown(500f, y, 1000)
        s.fingerMove(700f, y, 1050)
        assertTrue(s.rows(1050)[1].top < rest)
    }

    @Test fun `a row let go keeps fading from where the swipe had it`() {
        val s = open(1)
        val y = NoticeShade.TOP + 10f
        s.fingerDown(500f, y, 1000)
        s.fingerMove(700f, y, 1050)
        val held = s.rows(1050).single().alpha
        assertTrue(held < 1f)
        s.fingerUp(700f, y, 1050)
        var last = held
        for (t in 1050L..1050L + NoticeShade.FLY_MS step 20) {
            val a = s.rows(t).firstOrNull()?.alpha ?: 0f
            assertTrue("alpha $a at $t over $last", a <= last + 0.001f)
            last = a
        }
    }

    @Test fun `a drag up from the bottom edge shuts a long list without scrolling it`() {
        val s = open(12)
        s.fingerDown(500f, dev.kb896.aiusage.Panel.HEIGHT - 10f, 1000)
        s.fingerMove(500f, 300f, 1100)
        assertTrue(s.progress(1100) < 1f)
        assertEquals(NoticeShade.TOP, s.rows(1100).first().top, 0.001f)
        assertTrue(s.fingerUp(500f, 300f, 1200).single() is NoticeShade.Effect.ShutAt)
    }

    @Test fun `the clear button sits at the bottom and clears them all to the right, bottom first, a tenth apart`() {
        val s = open(3)
        val c = s.clear(1000)!!
        // At the bottom, however few rows.
        assertEquals(dev.kb896.aiusage.Panel.HEIGHT - NoticeShade.BOTTOM - NoticeShade.CLEAR_D / 2f, c.cy, 0.001f)
        assertEquals(c.cy, open(1).clear(1000)!!.cy, 0.001f)
        s.fingerDown(c.cx, c.cy, 1000)
        val effect = s.fingerUp(c.cx, c.cy, 1010).single() as NoticeShade.Effect.ClearedAll
        assertEquals(listOf("k1", "k2", "k3"), effect.keys)
        assertEquals(0, s.count)
        // The bottom one already sliding right and fading, the top one not yet; none moving up.
        val early = s.rows(1010 + 50)
        assertTrue(early[2].dx > 0f && early[2].alpha < 1f)
        assertEquals(0f, early[0].dx, 0.001f)
        assertEquals(NoticeShade.TOP + 2 * (NoticeShade.ROW_H + NoticeShade.GAP), early[2].top, 0.001f)
        assertEquals(1010 + 2 * NoticeShade.CHAIN_MS + NoticeShade.RISE_MS, effect.doneAtMs)
        assertTrue(s.rows(effect.doneAtMs).isEmpty())
        assertFalse(s.showsEmpty())
    }

    @Test fun `with a long list the clear button stays on screen`() {
        val s = open(12)
        val c = s.clear(1000)!!
        assertTrue(c.cy + c.size / 2f <= dev.kb896.aiusage.Panel.HEIGHT)
    }
}
