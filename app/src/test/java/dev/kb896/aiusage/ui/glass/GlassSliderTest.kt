package dev.kb896.aiusage.ui.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.SeekBar
import androidx.test.core.app.ApplicationProvider
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.data.PanelTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlassSliderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @After fun reset() { ConfigStore(context).save(PanelConfig()) }

    @Test fun `under ORIGINAL it is the platform seek bar`() {
        ConfigStore(context).save(PanelConfig())
        val ours = GlassSlider(context)
        assertFalse(ours.glass)
        assertNotNull(ours.thumb)
        assertEquals(SeekBar(context).paddingLeft, ours.paddingLeft)
    }

    @Test fun `under GLASS it draws its own thumb and track`() {
        ConfigStore(context).save(PanelConfig(theme = PanelTheme.GLASS))
        val ours = GlassSlider(context)
        assertTrue(ours.glass)
        assertNull(ours.thumb)
        assertNull(ours.progressDrawable)
        val d = context.resources.displayMetrics.density
        assertEquals((32 * d).toInt(), ours.paddingLeft)
    }

    @Test fun `under GLASS a slider with no size draws without throwing`() {
        ConfigStore(context).save(PanelConfig(theme = PanelTheme.GLASS))
        val s = GlassSlider(context)
        s.layout(0, 0, 0, 0)
        s.draw(Canvas(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)))
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun `under GLASS a slider made in code is tall enough and draws something`() {
        ConfigStore(context).save(PanelConfig(theme = PanelTheme.GLASS))
        val d = context.resources.displayMetrics.density
        val s = GlassSlider(context).apply { max = 100; progress = 100 }
        val w = (300 * d).toInt()
        for (spec in listOf(
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            android.view.View.MeasureSpec.makeMeasureSpec((400 * d).toInt(), android.view.View.MeasureSpec.AT_MOST),
        )) {
            s.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY), spec)
            assertTrue("height ${s.measuredHeight} for $spec", s.measuredHeight >= (48 * d).toInt())
        }
        s.layout(0, 0, s.measuredWidth, s.measuredHeight)
        assertFalse("will not draw", s.willNotDraw())
        val bmp = Bitmap.createBitmap(s.measuredWidth, s.measuredHeight, Bitmap.Config.ARGB_8888)
        s.draw(Canvas(bmp))
        var lit = 0
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) if (bmp.getPixel(x, y) ushr 24 != 0) lit++
        assertTrue("nothing drawn", lit > 0)
    }
}

