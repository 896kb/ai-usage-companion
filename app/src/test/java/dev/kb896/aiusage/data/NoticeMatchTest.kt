package dev.kb896.aiusage.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Matching the front: which of the back's cards stay, given what is on the front now. */
class NoticeMatchTest {
    @Test fun `cards whose notification left the front go`() {
        assertEquals(listOf(true, false, true), NoticeMatch.keep(listOf("a", "b", "c"), setOf("a", "c", "z")))
    }

    @Test fun `cards from before keys were kept stay`() {
        assertEquals(listOf(true, false), NoticeMatch.keep(listOf("", "b"), emptySet()))
    }
}
