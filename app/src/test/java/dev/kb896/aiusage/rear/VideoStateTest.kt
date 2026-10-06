package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoStateTest {
    private fun d(
        isVideo: Boolean = true, hasClip: Boolean = true, attached: Boolean = true,
        displayAwake: Boolean = true, aod: Boolean = false, paused: Boolean = false, locked: Boolean = false,
        loop: Boolean = true, preview: Boolean = false, fresh: Boolean = false,
    ) = VideoState.decide(isVideo, hasClip, attached, displayAwake, aod, paused, locked, loop, preview, fresh)

    @Test fun `no video or no clip lets go of everything, the last frame too`() {
        assertEquals(VideoAction.DROP, d(isVideo = false))
        assertEquals(VideoAction.DROP, d(hasClip = false))
    }

    @Test fun `a dark display or a detached panel sleeps the decoder and keeps the last frame`() {
        assertEquals(VideoAction.SLEEP, d(attached = false))
        assertEquals(VideoAction.SLEEP, d(displayAwake = false))
    }

    @Test fun `always-on and the double tap's dim freeze the frame where it is`() {
        assertEquals(VideoAction.FREEZE, d(aod = true))
        assertEquals(VideoAction.FREEZE, d(paused = true))
        assertEquals(VideoAction.FREEZE, d(paused = true, loop = false))
    }

    @Test fun `a looping video carries on, and starts over when the panel comes back`() {
        assertEquals(VideoAction.LOOP, d())
        assertEquals(VideoAction.LOOP, d(locked = true))
        assertEquals(VideoAction.RESTART_LOOP, d(fresh = true))
        assertEquals(VideoAction.RESTART_LOOP, d(fresh = true, locked = true))
    }

    @Test fun `a non-looping video waits on its frame behind the slider`() {
        assertEquals(VideoAction.FREEZE, d(loop = false, locked = true))
        assertEquals(VideoAction.FREEZE, d(loop = false, locked = true, fresh = true))
    }

    @Test fun `a non-looping video plays its run from the start when the panel comes back open`() {
        assertEquals(VideoAction.PLAY_ONCE, d(loop = false, fresh = true))
        assertEquals(VideoAction.KEEP, d(loop = false))
    }

    @Test fun `the front's preview always loops, untouched by the panel's state`() {
        assertEquals(VideoAction.LOOP, d(loop = false, preview = true, locked = true, fresh = true))
    }
}
