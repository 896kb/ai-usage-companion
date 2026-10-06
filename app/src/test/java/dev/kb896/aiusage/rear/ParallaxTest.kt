package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ParallaxTest {
    @Test fun `with one page there is nothing to scroll`() {
        assertEquals(0f, Parallax.scroll(offset = 0f, span = 572f, pages = 1), 0f)
    }

    @Test fun `the first page is one end of the margin, the last the other, however many pages`() {
        assertEquals(-1f, Parallax.scroll(offset = 0f, span = 572f, pages = 3), 1e-6f)
        assertEquals(0f, Parallax.scroll(offset = 572f, span = 572f, pages = 3), 1e-6f)
        assertEquals(1f, Parallax.scroll(offset = 1144f, span = 572f, pages = 3), 1e-6f)
    }

    @Test fun `tilt and scroll add up, never past the margin`() {
        assertEquals(0.3f to 0.9f, Parallax.combine(tiltX = 0.3f, tiltY = 0.4f, scrollY = 0.5f))
        assertEquals(-1f to 1f, Parallax.combine(tiltX = -1.7f, tiltY = 0.8f, scrollY = 0.9f))
    }

    @Test fun `a shift of one is the whole margin of that side`() {
        assertEquals(Parallax.MARGIN * 904f, Parallax.px(shift = 1f, size = 904f), 1e-4f)
        assertEquals(0f, Parallax.px(shift = 0f, size = 904f), 0f)
    }

    @Test fun `held still at any angle, tilt starts and stays centred`() {
        val t = TiltTracker()
        repeat(200) { t.update(roll = 0.5f, pitch = -0.3f, dtMs = 16) }
        assertEquals(0f, t.x, 1e-3f)
        assertEquals(0f, t.y, 1e-3f)
    }

    @Test fun `a quick tilt moves it towards the edge at once, and it drifts back to the centre`() {
        val t = TiltTracker()
        t.update(0f, 0f, 16)
        repeat(10) { t.update(roll = TiltTracker.MAX_TILT, pitch = 0f, dtMs = 16) }   // 160 ms in
        assertTrue("x ${t.x}", t.x > 0.7f)
        repeat(600) { t.update(roll = TiltTracker.MAX_TILT, pitch = 0f, dtMs = 16) }   // ~10 s held there
        assertTrue("x ${t.x}", abs(t.x) < 0.1f)
    }

    @Test fun `tilting past the limit stops at the edge`() {
        val t = TiltTracker()
        t.update(0f, 0f, 16)
        repeat(10) { t.update(roll = 0f, pitch = -TiltTracker.MAX_TILT * 3, dtMs = 16) }
        assertTrue("y ${t.y}", t.y >= -1f && t.y < -0.9f)
    }
}
