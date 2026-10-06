package dev.kb896.aiusage.ui.video

import org.junit.Assert.assertEquals
import org.junit.Test

class RearSizeTest {
    @Test fun `the corner is a share of the display's width, so any frame can be rounded alike`() {
        assertEquals(97f / 904f, RearSize.cornerShare(radius = 97, width = 904), 1e-6f)
        assertEquals(110f / 976f, RearSize.cornerShare(radius = 110, width = 976), 1e-6f)
    }

    @Test fun `a display that does not say falls back to the 17 Pro's`() {
        assertEquals(97f / 904f, RearSize.cornerShare(radius = null, width = 0), 1e-6f)
        assertEquals(97f / 904f, RearSize.cornerShare(radius = null, width = 976), 1e-6f)
    }
}
