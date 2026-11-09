package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackLockTest {

    @Test fun `locked only with a secure lock, the phone locked, and no code given here`() {
        val l = BackLock()
        assertTrue(l.locked(BackLock.Kind.PIN, phoneLocked = true))
        assertFalse(l.locked(BackLock.Kind.PIN, phoneLocked = false))
        assertFalse(l.locked(BackLock.Kind.NONE, phoneLocked = true))
        // Not known yet: locked, as the safe side.
        assertTrue(l.locked(null, phoneLocked = true))
        l.unlocked()
        assertFalse(l.locked(BackLock.Kind.PIN, phoneLocked = true))
    }

    @Test fun `going dark locks it again`() {
        val l = BackLock()
        l.unlocked()
        l.dark()
        assertTrue(l.locked(BackLock.Kind.PIN, phoneLocked = true))
    }

    @Test fun `the phone locking again locks it, the phone staying locked does not`() {
        val l = BackLock()
        l.locked(BackLock.Kind.PIN, phoneLocked = true)
        l.unlocked()
        // Still locked as it was when the code was given: stays open.
        assertFalse(l.locked(BackLock.Kind.PIN, phoneLocked = true))
        // Unlocked on the front, then locked again: the code is wanted again.
        l.seen(false)
        assertTrue(l.locked(BackLock.Kind.PIN, phoneLocked = true))
    }

    @Test fun `an entry takes characters up to its most, and goes back`() {
        val e = BackLock.Entry(max = 3)
        assertTrue(e.add('1')); assertTrue(e.add('2')); assertTrue(e.add('3'))
        assertFalse(e.add('4'))
        assertEquals("123", e.text)
        e.back()
        assertEquals("12", e.text)
        e.clear()
        assertEquals(0, e.length)
    }

    @Test fun `the lock type is read off the user's own block`() {
        val dump = """
            Current lock settings service state:

            User State:
              User 0
                SID: 19d81ad5a7c884f9
                Quality: 0
                CredentialType: PIN
              User 10
                CredentialType: PATTERN
              User 999
                CredentialType: PASSWORD
        """.trimIndent() // "User State:" at the margin, each user two in, their lines four: as dumpsys has them
        assertEquals(BackLock.Kind.PIN, BackLock.kindOf(dump))
        assertEquals(BackLock.Kind.PATTERN, BackLock.kindOf(dump, 10))
        assertEquals(BackLock.Kind.PASSWORD, BackLock.kindOf(dump, 999))
        assertNull(BackLock.kindOf("nothing here"))
    }

    @Test fun `the keypad is twelve round keys spread from the lenses to the edge, under the line`() {
        val k = PortalSheet.keypad(296f)
        assertEquals(12, k.keys.size)
        assertTrue(k.keys.all { it.left >= 296f && it.top > k.titleY && it.bottom <= PortalSheet.FULL && it.right <= 904f })
        assertTrue(k.keys.all { kotlin.math.abs(it.width - it.height) < 0.01f })
        assertTrue(k.keys[0].height > 110f)
        // The same gap across as down, the block centred under the line.
        val across = k.keys[1].left - k.keys[0].right
        val down = k.keys[3].top - k.keys[0].bottom
        assertEquals(across, down, 0.01f)
        assertEquals(k.centerX, (k.keys[0].left + k.keys[2].right) / 2, 0.01f)
        assertEquals('1', PortalSheet.keyDigit(0))
        assertEquals('0', PortalSheet.keyDigit(PortalSheet.KEY_ZERO))
        assertNull(PortalSheet.keyDigit(PortalSheet.KEY_OK))
        val five = k.keys[4]
        assertEquals(4, PortalSheet.keyAt(k, five.centerX, five.centerY))
        assertEquals(-1, PortalSheet.keyAt(k, 10f, 10f))
    }
}

class BackLockInputsTest {
    @Test fun `a pattern joins dots, takes the one passed over, and never one twice`() {
        // 1 to 3 along the top passes over 2.
        assertEquals(listOf(0, 1, 2), PortalSheet.patternAdd(listOf(0), 2))
        // 1 to 9 across passes over 5.
        assertEquals(listOf(0, 4, 8), PortalSheet.patternAdd(listOf(0), 8))
        // Already passed: not taken again; a knight's move passes over nothing.
        assertEquals(listOf(0, 4, 8), PortalSheet.patternAdd(listOf(0, 4, 8), 4))
        assertEquals(listOf(0, 5), PortalSheet.patternAdd(listOf(0), 5))
        // The middle already in: skipped over without taking it again.
        assertEquals(listOf(1, 0, 2), PortalSheet.patternAdd(listOf(1, 0), 2))
        assertEquals("1235789", PortalSheet.patternCode(listOf(0, 1, 2, 4, 6, 7, 8)))
    }

    @Test fun `the pattern's dots stand in the keypad's square, a finger finds them`() {
        val g = PortalSheet.patternGrid(296f)
        assertEquals(9, g.centers.size)
        val (x, y) = g.centers[4]
        assertEquals(4, PortalSheet.cellAt(g, x, y))
        assertEquals(4, PortalSheet.cellAt(g, x + g.reach - 1f, y))
        assertEquals(-1, PortalSheet.cellAt(g, 10f, 10f))
        assertTrue(g.square.left > 296f && g.square.bottom <= PortalSheet.FULL && g.centers[0].first < 420f)
    }

    @Test fun `the keyboard has letters, capitals and symbols, all right of the lenses`() {
        for (page in listOf(PortalSheet.KB_LOWER, PortalSheet.KB_UPPER, PortalSheet.KB_SYMBOLS)) {
            val k = PortalSheet.keyboard(296f, page)
            assertTrue(k.keys.all { it.box.left >= 296f && it.box.right <= 904f && it.box.bottom <= PortalSheet.FULL })
            assertTrue(k.keys.any { it.type == PortalSheet.KbType.OK })
            assertTrue(k.keys.any { it.type == PortalSheet.KbType.BACK })
        }
        assertTrue(PortalSheet.keyboard(296f, PortalSheet.KB_UPPER).keys.any { it.ch == 'Q' })
        assertTrue(PortalSheet.keyboard(296f, PortalSheet.KB_SYMBOLS).keys.any { it.ch == '@' })
        val k = PortalSheet.keyboard(296f, PortalSheet.KB_LOWER)
        val a = k.keys.first { it.ch == 'a' }
        assertEquals('a', k.keys[PortalSheet.kbKeyAt(k, a.box.centerX, a.box.centerY)].ch)
    }
}
