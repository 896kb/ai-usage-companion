package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameFadeTest {

    @Test fun `it starts out of sight`() {
        val f = FrameFade()
        assertEquals(0f, f.level(0), 0f)
        assertTrue(f.gone(0))
    }

    @Test fun `shown, it comes in over IN_MS on an ease-out cubic`() {
        val f = FrameFade()
        f.show(1_000)
        assertFalse(f.gone(1_000))
        assertEquals(0f, f.level(1_000), 0f)
        // Halfway: 1 - (1/2)^3.
        assertEquals(0.875f, f.level(1_000 + FrameFade.IN_MS / 2), 1e-3f)
        assertEquals(1f, f.level(1_000 + FrameFade.IN_MS), 0f)
        assertEquals(1f, f.level(1_000 + FrameFade.IN_MS + 5_000), 0f)
        assertFalse(f.gone(1_000 + FrameFade.IN_MS + 5_000))
    }

    @Test fun `hidden, it goes over OUT_MS on an ease-in cubic, and is then gone`() {
        val f = FrameFade()
        f.show(0); f.level(FrameFade.IN_MS)
        val at = 10_000L
        f.hide(at)
        assertEquals(1f, f.level(at), 0f)
        assertEquals(1f - 0.125f, f.level(at + FrameFade.OUT_MS / 2), 1e-3f)
        assertFalse(f.gone(at + FrameFade.OUT_MS - 1))
        assertEquals(0f, f.level(at + FrameFade.OUT_MS), 0f)
        assertTrue(f.gone(at + FrameFade.OUT_MS))
    }

    @Test fun `a show mid fade-out turns back from where it is, without a jump`() {
        val f = FrameFade()
        f.show(0)
        f.hide(1_000)
        val at = 1_000 + FrameFade.OUT_MS / 2
        val was = f.level(at)
        f.show(at)
        assertEquals(was, f.level(at), 1e-4f)
        assertTrue(f.level(at + 20) > was)
        // It has less to cover than from nothing, so it is whole sooner.
        assertEquals(1f, f.level(at + FrameFade.IN_MS), 0f)
        assertFalse(f.gone(at + 5))
    }

    @Test fun `a hide mid fade-in turns back from where it is, without a jump`() {
        val f = FrameFade()
        f.show(0)
        val at = FrameFade.IN_MS / 3
        val was = f.level(at)
        f.hide(at)
        assertEquals(was, f.level(at), 1e-4f)
        assertTrue(f.level(at + 20) < was)
        assertTrue(f.gone(at + FrameFade.OUT_MS))
    }

    @Test fun `asking again for where it is going changes nothing`() {
        val f = FrameFade()
        f.show(0)
        f.show(100) // a keep-alive tick's show, mid fade-in
        assertEquals(FrameFade().apply { show(0) }.level(150), f.level(150), 0f)
        f.hide(1_000)
        f.hide(1_100)
        assertTrue(f.gone(1_000 + FrameFade.OUT_MS))
    }

    @Test fun `it only ever rises while shown, and only ever falls while hidden`() {
        val f = FrameFade()
        f.show(0)
        val up = (0..FrameFade.IN_MS.toInt() step 5).map { f.level(it.toLong()) }
        assertTrue(up.zipWithNext().all { (a, b) -> b >= a })
        f.hide(1_000)
        val down = (1_000..1_000 + FrameFade.OUT_MS.toInt() step 5).map { f.level(it.toLong()) }
        assertTrue(down.zipWithNext().all { (a, b) -> b <= a })
    }
}
