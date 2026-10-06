package dev.kb896.aiusage.ui.video

import org.junit.Assert.assertEquals
import org.junit.Test

class TrimRangeTest {
    @Test fun `the start cannot pass within half a second of the end`() {
        assertEquals(4500L to 5000L, TrimRange.moveStart(0, 5000, 4900, 10_000))
    }

    @Test fun `the end cannot pass within half a second of the start`() {
        assertEquals(2000L to 2500L, TrimRange.moveEnd(2000, 8000, 2100, 10_000))
    }

    @Test fun `a selection never exceeds a minute — the other handle follows`() {
        assertEquals(30_000L to 90_000L, TrimRange.moveEnd(0, 60_000, 90_000, 120_000))
        assertEquals(0L to 60_000L, TrimRange.moveStart(20_000, 80_000, 0, 120_000))
    }

    @Test fun `handles stay inside the video`() {
        assertEquals(0L to 3000L, TrimRange.moveStart(1000, 3000, -400, 3000))
        assertEquals(1000L to 3000L, TrimRange.moveEnd(1000, 2000, 9999, 3000))
    }

    @Test fun `a video shorter than half a second is kept whole`() {
        assertEquals(0L to 300L, TrimRange.moveStart(0, 300, 200, 300))
        assertEquals(0L to 300L, TrimRange.moveEnd(0, 300, 100, 300))
    }
}
