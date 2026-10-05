package dev.kb896.aiusage.ui.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.view.View
import androidx.test.core.app.ApplicationProvider
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.data.PanelTheme
import dev.kb896.aiusage.rear.GlassLuminance
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlassChipTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    // The backdrop is an object, so a bitmap installed here would still be installed for
    // whatever runs next in this sandbox, and the theme is read from a store that outlives
    // the test too. Both go back to what an untouched run would see.
    @After fun reset() {
        Backdrop.release()
        ConfigStore(context).save(PanelConfig())
    }

    @Test fun `the chip carries its colour and repaints when it changes`() {
        val chip = GlassChip(context)
        chip.colour = 0xFF123456.toInt()
        assertEquals(0xFF123456.toInt(), chip.colour)
        assertEquals(0xD9123456.toInt(), GlassChip.shown(0xFF123456.toInt()))
    }

    /**
     * The rim pass must not re-run the lens.
     *
     * Drawing the whole chip cannot be checked here — the lens pass reaches
     * Glass.refraction, and Robolectric cannot construct a RuntimeShader — so this draws
     * only the pass the fix changed. With a backdrop installed and `lens = false`, the
     * shader is never touched and the pane still reports what is behind it: pure green,
     * not the no-backdrop fallback. Before the fix this call ran the refraction and
     * repainted the wallpaper over the colour the chip had just drawn.
     */
    @Test fun `the rim pass reads the backdrop without touching the lens`() {
        ConfigStore(context).save(PanelConfig(theme = PanelTheme.GLASS))
        Backdrop.installForTest(
            Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF00FF00.toInt()) })
        val chip = GlassChip(context)
        chip.colour = 0xFFFF0000.toInt()
        assertTrue(chip.glass)
        chip.measure(View.MeasureSpec.makeMeasureSpec(20, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(20, View.MeasureSpec.EXACTLY))
        chip.layout(0, 0, 20, 20)

        val canvas = Canvas(Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888))
        val lum = Pane.draw(canvas, chip, 0f, 0f, 20f, 20f, 10f,
            tint = 0, clear = false, rim = false, lens = false)
        assertEquals(GlassLuminance.of(0xFF00FF00.toInt()), lum, 0.001f)
        assertFalse("the fallback would mean it never saw the backdrop",
            lum == Pane.FALLBACK_LUMINANCE)
    }
}
