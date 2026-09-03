package dev.kb896.aiusage.ui.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import dev.kb896.aiusage.rear.Glass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    /** RadialGradient rejects a zero radius; a glow on an unlaid-out pane must not reach it. */
    @Test fun `glow on a zero-size rect does not throw`() {
        val canvas = Canvas(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
        Pane.glow(canvas, 5f, 5f, 5f, 5f, 2f, progress = 1f, x = 5f, y = 5f)
        Pane.glow(canvas, 0f, 0f, 10f, 0f, 2f, progress = 1f, x = 5f, y = 0f)
    }

    /**
     * A card's lens is clipped to the band that actually bends anything.
     *
     * The clipped draw itself cannot be checked here — the lens pass reaches
     * Glass.refraction, which Robolectric cannot construct — so what is checked is the
     * arithmetic the clip is built from, which is what draw uses: the refraction height
     * the shader is given, plus 2dp so the clip never eats into the band.
     */
    @Test fun `the interior inset is the refraction height plus a margin`() {
        val d = context.resources.displayMetrics.density
        // A small pane: the band is a fraction of the short side.
        val small = 20 * d
        assertEquals(minOf(small * Glass.HEIGHT, Glass.HEIGHT_MAX * d) + 2 * d,
            Pane.interiorInset(small, d), 0.001f)
        // A card: the fraction is past the cap, so the cap is what the shader gets.
        val card = 400 * d
        assertEquals(minOf(card * Glass.HEIGHT, Glass.HEIGHT_MAX * d) + 2 * d,
            Pane.interiorInset(card, d), 0.001f)
    }

    /** Below twice the inset there is no middle left, and a too-small pane draws whole. */
    @Test fun `only a pane bigger than its own band has an interior to clip`() {
        val d = context.resources.displayMetrics.density
        assertTrue("a card is nearly all middle", Pane.clipsInterior(200 * d, 200 * d, d))
        assertFalse("a 20dp pane is band all the way through", Pane.clipsInterior(20 * d, 20 * d, d))
        // The short side is what the band is measured from, so a long thin pane has none.
        assertFalse(Pane.clipsInterior(400 * d, 20 * d, d))
    }
}
