package dev.kb896.aiusage.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaColorsTest {
    @Test fun `each part of the media page starts in the one media colour it had`() {
        val c = PanelConfig(mediaColor = 0xFF112233.toInt())
        for (tint in listOf(c.mediaPlaybackTint, c.mediaVolumeTint, c.mediaButtonsTint, c.mediaArtistTint, c.mediaTitleTint)) {
            assertEquals(0xFF112233.toInt(), tint)
        }
    }

    @Test fun `a part given its own colour keeps it, and the others stay as they were`() {
        val c = PanelConfig(mediaColor = 0xFF112233.toInt(), mediaVolumeColor = 0xFFAA0000.toInt())
        assertEquals(0xFFAA0000.toInt(), c.mediaVolumeTint)
        assertEquals(0xFF112233.toInt(), c.mediaPlaybackTint)
    }
}
