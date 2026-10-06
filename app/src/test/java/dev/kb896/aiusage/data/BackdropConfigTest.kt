package dev.kb896.aiusage.data

import org.junit.Assert.assertEquals
import org.junit.Test

class BackdropConfigTest {
    @Test fun `an old config with a picture is on Image`() {
        val old = PanelConfig.decode("""{"backdrop":{"imagePath":"/x/panel-background.png"}}""")
        assertEquals(BackdropKind.IMAGE, old.effectiveBackdrop)
    }

    @Test fun `an old config without a picture is on Color`() {
        assertEquals(BackdropKind.COLOR, PanelConfig.decode("""{"title":"X"}""").effectiveBackdrop)
    }

    @Test fun `a chosen kind wins over the picture`() {
        val c = PanelConfig(backdrop = ArtConfig(imagePath = "/p.png"), backdropKind = BackdropKind.SHADER)
        assertEquals(BackdropKind.SHADER, c.effectiveBackdrop)
    }

    @Test fun `video settings survive a round trip`() {
        val v = VideoConfig(originalUri = "content://a/b", clipPath = "/c.mp4", trimStartMs = 1200, trimEndMs = 5400,
            loop = false, easeIn = true, luminance = List(256) { 0.5f })
        val back = PanelConfig.decode(PanelConfig.encode(PanelConfig(video = v, backdropKind = BackdropKind.VIDEO)))
        assertEquals(v, back.video)
        assertEquals(BackdropKind.VIDEO, back.effectiveBackdrop)
    }

    @Test fun `a video saved with Loop on plays as a loop, with it off as a linear run`() {
        assertEquals(VideoPlayMode.LINEAR_LOOP, VideoConfig(loop = true).mode)
        assertEquals(VideoPlayMode.LINEAR, VideoConfig(loop = false).mode)
    }

    @Test fun `a chosen playback wins over the old switch`() {
        assertEquals(VideoPlayMode.PING_PONG, VideoConfig(loop = false, playback = VideoPlayMode.PING_PONG).mode)
    }

    @Test fun `going backwards needs the reversed copy — without it Yoyo is Linear and Ping-pong is a loop`() {
        assertEquals(VideoPlayMode.LINEAR, VideoConfig(playback = VideoPlayMode.YOYO).playable)
        assertEquals(VideoPlayMode.LINEAR_LOOP, VideoConfig(playback = VideoPlayMode.PING_PONG).playable)
        assertEquals(VideoPlayMode.YOYO, VideoConfig(playback = VideoPlayMode.YOYO, reversePath = "/r.mp4").playable)
    }
}
