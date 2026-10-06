package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoStateTest {
    private fun d(
        isVideo: Boolean = true, hasClip: Boolean = true, attached: Boolean = true,
        displayAwake: Boolean = true, aod: Boolean = false, locked: Boolean = false,
        loop: Boolean = true, preview: Boolean = false, justUnlocked: Boolean = false,
    ) = VideoState.decide(isVideo, hasClip, attached, displayAwake, aod, locked, loop, preview, justUnlocked)

    @Test fun `no video, no clip, detached or dark releases the decoder`() {
        assertEquals(VideoAction.RELEASE, d(isVideo = false))
        assertEquals(VideoAction.RELEASE, d(hasClip = false))
        assertEquals(VideoAction.RELEASE, d(attached = false))
        assertEquals(VideoAction.RELEASE, d(displayAwake = false))
    }

    @Test fun `always-on pauses, looping or not`() {
        assertEquals(VideoAction.PAUSE, d(aod = true))
        assertEquals(VideoAction.PAUSE, d(aod = true, loop = false, locked = true))
    }

    @Test fun `a looping video plays whether locked or not`() {
        assertEquals(VideoAction.LOOP, d(locked = true))
        assertEquals(VideoAction.LOOP, d(locked = false))
    }

    @Test fun `a non-looping video waits on its first frame behind the slider`() {
        assertEquals(VideoAction.HOLD_FIRST, d(loop = false, locked = true))
    }

    @Test fun `unlocking plays a non-looping video once, and later checks leave it be`() {
        assertEquals(VideoAction.PLAY_ONCE, d(loop = false, justUnlocked = true))
        assertEquals(VideoAction.KEEP, d(loop = false))
    }

    @Test fun `the front's preview always loops`() {
        assertEquals(VideoAction.LOOP, d(loop = false, preview = true, locked = true))
    }
}
