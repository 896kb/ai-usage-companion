package dev.kb896.aiusage.ui.video

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoFilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `each export gets a file of its own, so the back sees a new path`() {
        assertNotEquals(VideoFiles.newClip(context).path, VideoFiles.newClip(context).path)
    }

    @Test fun `keeping one clip deletes the others`() {
        val a = VideoFiles.newClip(context).apply { writeText("a") }
        val b = VideoFiles.newClip(context).apply { writeText("b") }
        VideoFiles.keepOnly(context, b.path)
        assertFalse(a.exists()); assertTrue(b.exists())
    }

    @Test fun `a discarded export leaves nothing behind`() {
        val partial = VideoFiles.newClip(context).apply { writeText("half") }
        VideoFiles.discard(partial)
        assertFalse(partial.exists())
    }

    @Test fun `an original that is gone cannot be opened`() {
        assertFalse(VideoFiles.canOpen(context, "content://dev.kb896.nothing/video/1"))
        assertFalse(VideoFiles.canOpen(context, null))
    }
}
