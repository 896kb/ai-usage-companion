package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoStateTest {
    private fun d(
        isVideo: Boolean = true, hasClip: Boolean = true, attached: Boolean = true,
        displayAwake: Boolean = true, visible: Boolean = true, aod: Boolean = false, paused: Boolean = false,
        locked: Boolean = false, loop: Boolean = true, preview: Boolean = false, fresh: Boolean = false,
        changed: Boolean = false,
    ) = VideoState.decide(isVideo, hasClip, attached, displayAwake, visible, aod, paused, locked, loop, preview, fresh, changed)

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

    @Test fun `a looping video carries on, even when the panel comes back`() {
        assertEquals(VideoAction.LOOP, d())
        assertEquals(VideoAction.LOOP, d(locked = true))
        assertEquals(VideoAction.LOOP, d(fresh = true))
        assertEquals(VideoAction.LOOP, d(fresh = true, locked = true))
    }

    @Test fun `a looping video starts over only when it was changed`() {
        assertEquals(VideoAction.RESTART_LOOP, d(changed = true))
        assertEquals(VideoAction.RESTART_LOOP, d(fresh = true, changed = true))
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

    @Test fun `a panel nobody can see — the front app in the background — sleeps the decoder`() {
        assertEquals(VideoAction.SLEEP, d(visible = false))
        assertEquals(VideoAction.SLEEP, d(visible = false, preview = true))
    }

    @Test fun `a player just opened with nothing to do shows its frame rather than nothing`() {
        assertEquals(VideoAction.FREEZE, VideoState.afterOpen(VideoAction.KEEP, created = true))
        assertEquals(VideoAction.KEEP, VideoState.afterOpen(VideoAction.KEEP, created = false))
        assertEquals(VideoAction.LOOP, VideoState.afterOpen(VideoAction.LOOP, created = true))
    }

    @Test fun `the preview's loop runs each pass eased, as the back plays its one run`() {
        assertEquals(true to true, VideoState.loopEase(preview = true, loop = false, easeIn = true, easeOut = true))
        assertEquals(false to true, VideoState.loopEase(preview = true, loop = false, easeIn = false, easeOut = true))
    }

    @Test fun `a real loop has no ends to ease`() {
        assertEquals(false to false, VideoState.loopEase(preview = true, loop = true, easeIn = true, easeOut = true))
        assertEquals(false to false, VideoState.loopEase(preview = false, loop = true, easeIn = true, easeOut = true))
    }
}
