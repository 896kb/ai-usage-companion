package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameVeilTest {

    @Test fun `the frame shows as much as the sheet is shut, times its own fade`() {
        assertEquals(0.5f, FrameVeil.alpha(full = 0.5f, level = 1f, veil = 0f), 0f)
        assertEquals(0.25f, FrameVeil.alpha(full = 0.5f, level = 1f, veil = 0.5f), 1e-6f)
        assertEquals(0f, FrameVeil.alpha(full = 0.5f, level = 1f, veil = 1f), 0f)
        assertEquals(0.125f, FrameVeil.alpha(full = 0.5f, level = 0.5f, veil = 0.5f), 1e-6f)
        // Out of range in, in range out.
        assertEquals(0f, FrameVeil.alpha(full = 0.5f, level = 1f, veil = 1.4f), 0f)
        assertEquals(0.5f, FrameVeil.alpha(full = 0.5f, level = 1f, veil = -0.2f), 0f)
    }

    @Test fun `it follows the sheet both ways`() {
        val v = FrameVeil()
        v.sheet(0.3f); assertEquals(0.3f, v.veil(), 0f)
        v.sheet(1f); assertEquals(1f, v.veil(), 0f)
        v.sheet(0.4f); assertEquals(0.4f, v.veil(), 0f)
        v.sheet(0f); assertEquals(0f, v.veil(), 0f)
    }

    @Test fun `held for a choice, it stays down while the sheet shuts`() {
        val v = FrameVeil()
        v.sheet(1f)
        v.hold()
        v.sheet(0.5f); v.sheet(0f)
        assertEquals(1f, v.veil(), 0f)
    }

    @Test fun `a choice that hid the frame keeps it down until it is shown again`() {
        val v = FrameVeil()
        v.sheet(1f); v.hold(); v.sheet(0f)
        v.hidden()
        v.release(stillShown = false)
        assertEquals(1f, v.veil(), 0f)
        v.shown()
        assertEquals(0f, v.veil(), 0f)
    }

    @Test fun `a choice that left the frame up lets it back at once`() {
        val v = FrameVeil()
        v.sheet(1f); v.hold(); v.sheet(0f)
        v.release(stillShown = true)
        assertEquals(0f, v.veil(), 0f)
    }

    @Test fun `hidden under a part-open sheet, it does not come back as the sheet shuts`() {
        val v = FrameVeil()
        v.sheet(0.6f)
        v.hidden()
        v.sheet(0f)
        assertEquals(0.6f, v.veil(), 0f)
    }

    @Test fun `the control centre over a guest hides it as the sheet does, whichever is further open`() {
        val v = FrameVeil()
        v.controlCenter(0.6f); assertEquals(0.6f, v.veil(), 0f)
        v.sheet(0.2f); assertEquals(0.6f, v.veil(), 0f)
        // One shutting does not bring the frame back while the other is still open.
        v.sheet(0f); v.controlCenter(1f); assertEquals(1f, v.veil(), 0f)
        v.controlCenter(0f); assertEquals(0f, v.veil(), 0f)
    }

    @Test fun `a frame hidden while the control centre is open stays down as it shuts`() {
        val v = FrameVeil()
        v.controlCenter(1f)
        v.hidden()
        v.controlCenter(0f)
        assertEquals(1f, v.veil(), 0f)
        v.shown()
        assertEquals(0f, v.veil(), 0f)
    }
}
