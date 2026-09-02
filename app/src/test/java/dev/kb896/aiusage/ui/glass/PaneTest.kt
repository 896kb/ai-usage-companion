package dev.kb896.aiusage.ui.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaneTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** With no backdrop built, drawing must not touch a RuntimeShader and must not throw. */
    @Test fun `draws a flat fallback when there is no backdrop`() {
        val view = View(context)
        val canvas = Canvas(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
        val lum = Pane.draw(canvas, view, 0f, 0f, 10f, 10f, 4f, 0x14FFFFFF)
        assertEquals(Pane.FALLBACK_LUMINANCE, lum, 0f)
    }
}
