package dev.kb896.aiusage.rear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockRetryTest {

    private val never: () -> Boolean = { throw AssertionError("not asked") }

    @Test fun `a task seen and gone a moment is closed, a trampoline's gap is not`() {
        assertFalse(LockRetry.closedItself(started = true, sawTask = true, goneForMs = LockRetry.SEEN_GONE_MS - 1))
        assertTrue(LockRetry.closedItself(started = true, sawTask = true, goneForMs = LockRetry.SEEN_GONE_MS))
    }

    @Test fun `a task never seen after a start that took is closed only after a while`() {
        assertFalse(LockRetry.closedItself(started = true, sawTask = false, goneForMs = LockRetry.NEVER_SEEN_MS - 1))
        assertTrue(LockRetry.closedItself(started = true, sawTask = false, goneForMs = LockRetry.NEVER_SEEN_MS))
    }

    @Test fun `a start that was refused is nothing closing`() {
        assertFalse(LockRetry.closedItself(started = false, sawTask = false, goneForMs = 60_000))
    }

    @Test fun `only with the front locked`() {
        assertTrue(LockRetry.applies(closed = true, confined = false, refused = false, retried = false) { true })
        assertFalse(LockRetry.applies(closed = true, confined = false, refused = false, retried = false) { false })
    }

    @Test fun `not for one not closed, already confined, refused a display, or retried, and the lock not read for them`() {
        assertFalse(LockRetry.applies(closed = false, confined = false, refused = false, retried = false, frontLocked = never))
        assertFalse(LockRetry.applies(closed = true, confined = true, refused = false, retried = false, frontLocked = never))
        assertFalse(LockRetry.applies(closed = true, confined = false, refused = true, retried = false, frontLocked = never))
        assertFalse(LockRetry.applies(closed = true, confined = false, refused = false, retried = true, frontLocked = never))
    }
}
