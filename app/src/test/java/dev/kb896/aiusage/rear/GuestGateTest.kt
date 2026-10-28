package dev.kb896.aiusage.rear

import dev.kb896.aiusage.rear.GuestGate.Tick
import org.junit.Assert.assertEquals
import org.junit.Test

class GuestGateTest {

    private val us = "dev.kb896.aiusage"
    private val guest = "org.mozilla.firefox"

    @Test fun `no guest, the keep-alive does its usual job`() {
        assertEquals(Tick.USUAL, GuestGate.tick(guest = null, alive = false, onRear = false, top = "com.x", ours = us))
    }

    @Test fun `a guest on top is left alone, with its frame`() {
        assertEquals(Tick.GUEST_ON_TOP, GuestGate.tick(guest, alive = true, onRear = true, top = guest, ours = us))
    }

    @Test fun `the panel over a live guest is fine — Resume brings it back`() {
        assertEquals(Tick.GUEST_BEHIND, GuestGate.tick(guest, alive = true, onRear = true, top = us, ours = us))
    }

    @Test fun `a dark rear changes nothing`() {
        assertEquals(Tick.GUEST_BEHIND, GuestGate.tick(guest, alive = true, onRear = true, top = null, ours = us))
    }

    @Test fun `a guest gone, or moved off the rear, ends it`() {
        assertEquals(Tick.GUEST_GONE, GuestGate.tick(guest, alive = false, onRear = false, top = us, ours = us))
        assertEquals(Tick.GUEST_GONE, GuestGate.tick(guest, alive = true, onRear = false, top = us, ours = us))
    }

    @Test fun `something else on top of the rear is the usual recovery`() {
        assertEquals(Tick.USUAL, GuestGate.tick(guest, alive = true, onRear = true, top = "com.xiaomi.subscreencenter", ours = us))
    }
}
