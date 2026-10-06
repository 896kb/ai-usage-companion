package dev.kb896.aiusage.rear

import dev.kb896.aiusage.data.VideoPlayMode
import dev.kb896.aiusage.data.VideoPlayMode.LINEAR
import dev.kb896.aiusage.data.VideoPlayMode.LINEAR_LOOP
import dev.kb896.aiusage.data.VideoPlayMode.PING_PONG
import dev.kb896.aiusage.data.VideoPlayMode.YOYO
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoStateTest {
    private fun d(
        isVideo: Boolean = true, hasClip: Boolean = true, attached: Boolean = true,
        displayAwake: Boolean = true, visible: Boolean = true, aod: Boolean = false, paused: Boolean = false,
        locked: Boolean = false, mode: VideoPlayMode = LINEAR_LOOP, preview: Boolean = false,
        fresh: Boolean = false, changed: Boolean = false,
    ) = VideoState.decide(isVideo, hasClip, attached, displayAwake, visible, aod, paused, locked, mode, preview, fresh, changed)

    @Test fun `no video or no clip lets go of everything, the last frame too`() {
        assertEquals(VideoAction.DROP, d(isVideo = false))
        assertEquals(VideoAction.DROP, d(hasClip = false))
    }

    @Test fun `a dark display, a detached panel or one nobody can see sleeps the decoder`() {
        assertEquals(VideoAction.SLEEP, d(attached = false))
        assertEquals(VideoAction.SLEEP, d(displayAwake = false))
        assertEquals(VideoAction.SLEEP, d(visible = false))
        assertEquals(VideoAction.SLEEP, d(visible = false, preview = true))
    }

    @Test fun `always-on and the double tap's dim stop a loop where it is`() {
        for (m in listOf(LINEAR, LINEAR_LOOP, PING_PONG)) {
            assertEquals(VideoAction.FREEZE, d(mode = m, aod = true))
            assertEquals(VideoAction.FREEZE, d(mode = m, paused = true))
        }
    }

    @Test fun `a loop carries on, even when the panel comes back, and starts over only when changed`() {
        assertEquals(VideoAction.LOOP, d())
        assertEquals(VideoAction.LOOP, d(locked = true, fresh = true))
        assertEquals(VideoAction.RESTART_LOOP, d(changed = true))
    }

    @Test fun `ping-pong goes on forward and back, and starts over only when changed`() {
        assertEquals(VideoAction.PING_PONG, d(mode = PING_PONG))
        assertEquals(VideoAction.PING_PONG, d(mode = PING_PONG, locked = true, fresh = true))
        assertEquals(VideoAction.RESTART_PING_PONG, d(mode = PING_PONG, changed = true))
    }

    @Test fun `a linear run waits behind the slider and plays from the start when the panel opens`() {
        assertEquals(VideoAction.FREEZE, d(mode = LINEAR, locked = true))
        assertEquals(VideoAction.FREEZE, d(mode = LINEAR, locked = true, fresh = true))
        assertEquals(VideoAction.PLAY_ONCE, d(mode = LINEAR, fresh = true))
        assertEquals(VideoAction.KEEP, d(mode = LINEAR))
    }

    @Test fun `yoyo runs to its end while the panel is open, and back to its start when it is not`() {
        assertEquals(VideoAction.TO_END, d(mode = YOYO))
        assertEquals(VideoAction.TO_END, d(mode = YOYO, fresh = true))
        assertEquals(VideoAction.TO_START, d(mode = YOYO, locked = true))
        assertEquals(VideoAction.TO_START, d(mode = YOYO, aod = true))
        assertEquals(VideoAction.TO_START, d(mode = YOYO, paused = true))
    }

    @Test fun `the front's preview shows each way going round`() {
        assertEquals(VideoAction.LOOP, d(mode = LINEAR, preview = true, locked = true, fresh = true))
        assertEquals(VideoAction.LOOP, d(mode = LINEAR_LOOP, preview = true))
        assertEquals(VideoAction.PING_PONG, d(mode = YOYO, preview = true, locked = true))
        assertEquals(VideoAction.PING_PONG, d(mode = PING_PONG, preview = true))
        assertEquals(VideoAction.RESTART_PING_PONG, d(mode = YOYO, preview = true, changed = true))
        assertEquals(VideoAction.RESTART_LOOP, d(mode = LINEAR, preview = true, changed = true))
    }

    @Test fun `a player just opened with nothing to do shows its frame rather than nothing`() {
        assertEquals(VideoAction.FREEZE, VideoState.afterOpen(VideoAction.KEEP, created = true))
        assertEquals(VideoAction.KEEP, VideoState.afterOpen(VideoAction.KEEP, created = false))
        assertEquals(VideoAction.LOOP, VideoState.afterOpen(VideoAction.LOOP, created = true))
    }

    @Test fun `the preview eases each pass of what plays once on the back, and nothing else`() {
        assertEquals(true to false, VideoState.passEase(preview = true, mode = LINEAR, easeIn = true, easeOut = false))
        assertEquals(true to true, VideoState.passEase(preview = true, mode = YOYO, easeIn = true, easeOut = true))
        assertEquals(false to false, VideoState.passEase(preview = true, mode = LINEAR_LOOP, easeIn = true, easeOut = true))
        assertEquals(false to false, VideoState.passEase(preview = true, mode = PING_PONG, easeIn = true, easeOut = true))
        assertEquals(false to false, VideoState.passEase(preview = false, mode = LINEAR, easeIn = true, easeOut = true))
    }
}
