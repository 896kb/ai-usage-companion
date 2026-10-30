package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HyperOS's list of apps its freezer leaves alone, a comma-and-space string in a setting, and
 * the rule for it: a guest is on it while it is on the back, and only what we added comes off.
 */
class NoRestrictListTest {
    private val list = "com.a, com.b"

    @Test fun `reads the list, an unset one is empty`() {
        assertEquals(listOf("com.a", "com.b"), NoRestrictList.parse(list))
        assertEquals(emptyList<String>(), NoRestrictList.parse("null"))
        assertEquals(emptyList<String>(), NoRestrictList.parse(""))
        assertEquals(listOf("com.a", "com.b"), NoRestrictList.parse("com.a,com.b,\n"))
    }

    @Test fun `a guest not on it goes on, and is ours to take off`() {
        val s = NoRestrictList.step(list, ours = emptyList(), guest = "com.c")
        assertEquals("com.a, com.b, com.c", s.list)
        assertEquals(listOf("com.c"), s.ours)
    }

    @Test fun `a guest already on it stays, and is never ours`() {
        val s = NoRestrictList.step(list, ours = emptyList(), guest = "com.a")
        assertEquals(null, s.list)
        assertEquals(emptyList<String>(), s.ours)
        val gone = NoRestrictList.step(list, ours = s.ours, guest = null)
        assertEquals(null, gone.list)
    }

    @Test fun `leaving takes off only what we put on`() {
        val s = NoRestrictList.step("com.a, com.b, com.c", ours = listOf("com.c"), guest = null)
        assertEquals("com.a, com.b", s.list)
        assertEquals(emptyList<String>(), s.ours)
    }

    @Test fun `changing guest swaps them`() {
        val s = NoRestrictList.step("com.a, com.c", ours = listOf("com.c"), guest = "com.d")
        assertEquals("com.a, com.d", s.list)
        assertEquals(listOf("com.d"), s.ours)
    }

    @Test fun `looked at again with the same guest, nothing to write`() {
        val s = NoRestrictList.step("com.a, com.c", ours = listOf("com.c"), guest = "com.c")
        assertEquals(null, s.list)
        assertEquals(listOf("com.c"), s.ours)
    }

    @Test fun `a crash's leftovers come off at the next look`() {
        val s = NoRestrictList.step("com.a, com.c, com.d", ours = listOf("com.c", "com.d"), guest = null)
        assertEquals("com.a", s.list)
        assertEquals(emptyList<String>(), s.ours)
    }

    @Test fun `ours but taken off meanwhile by the person is simply forgotten`() {
        val s = NoRestrictList.step("com.a", ours = listOf("com.c"), guest = null)
        assertEquals(null, s.list)
        assertEquals(emptyList<String>(), s.ours)
    }

    @Test fun `the marker reads back what it wrote`() {
        assertEquals(listOf("com.c", "com.d"), NoRestrictList.parse(NoRestrictList.marker(listOf("com.c", "com.d"))))
    }
}
