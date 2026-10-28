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

    // A guest the sheet's Panel hid on display 0 behind the keyguard.

    @Test fun `a guest hidden on the front while it is locked is behind, not gone`() {
        assertEquals(Tick.GUEST_BEHIND, GuestGate.tick(guest, alive = true, onRear = false, top = us, ours = us,
            hiddenOnFront = true, onFront = true, frontLocked = { true }))
        assertEquals(Tick.GUEST_BEHIND, GuestGate.tick(guest, alive = true, onRear = false, top = null, ours = us,
            hiddenOnFront = true, onFront = true, frontLocked = { true }))
    }

    @Test fun `the front unlocked over a hidden guest exposes it`() {
        assertEquals(Tick.GUEST_EXPOSED, GuestGate.tick(guest, alive = true, onRear = false, top = us, ours = us,
            hiddenOnFront = true, onFront = true, frontLocked = { false }))
    }

    @Test fun `a hidden guest that died is gone`() {
        assertEquals(Tick.GUEST_GONE, GuestGate.tick(guest, alive = false, onRear = false, top = us, ours = us,
            hiddenOnFront = true, onFront = false, frontLocked = { true }))
    }

    @Test fun `a hidden guest moved somewhere other than the front is gone`() {
        assertEquals(Tick.GUEST_GONE, GuestGate.tick(guest, alive = true, onRear = false, top = us, ours = us,
            hiddenOnFront = true, onFront = false, frontLocked = { true }))
    }

    @Test fun `something else on the rear over a hidden guest is the usual recovery`() {
        assertEquals(Tick.USUAL, GuestGate.tick(guest, alive = true, onRear = false, top = "com.xiaomi.subscreencenter", ours = us,
            hiddenOnFront = true, onFront = true, frontLocked = { true }))
    }

    @Test fun `a guest on the front that Panel did not hide is gone, keyguard or not`() {
        assertEquals(Tick.GUEST_GONE, GuestGate.tick(guest, alive = true, onRear = false, top = us, ours = us,
            hiddenOnFront = false, onFront = true, frontLocked = { throw AssertionError("not asked") }))
    }

    @Test fun `the keyguard is only read for a hidden guest on the front`() {
        val never = { throw AssertionError("not asked") }
        assertEquals(Tick.GUEST_ON_TOP, GuestGate.tick(guest, alive = true, onRear = true, top = guest, ours = us, frontLocked = never))
        assertEquals(Tick.GUEST_ON_TOP, GuestGate.tick(guest, alive = true, onRear = true, top = guest, ours = us,
            hiddenOnFront = true, onFront = false, frontLocked = never))
    }
}
