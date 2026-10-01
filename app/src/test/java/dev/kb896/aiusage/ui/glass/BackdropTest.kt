package dev.kb896.aiusage.ui.glass

import android.graphics.Bitmap
import dev.kb896.aiusage.data.ArtConfig
import dev.kb896.aiusage.data.PanelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackdropTest {
    @Test fun `the gradient lifts the colour toward white at the top and drops it toward black at the bottom`() {
        val stops = Backdrop.gradientStops(0xFF1D1F21.toInt())
        assertEquals(Ink.mix(0xFF1D1F21.toInt(), 0xFFFFFFFF.toInt(), 0.08f), stops[0])
        assertEquals(Ink.mix(0xFF1D1F21.toInt(), 0xFF000000.toInt(), 0.12f), stops[1])
    }

    @Test fun `paint makes a window-sized bitmap either way`() {
        val flat = Backdrop.paint(120, 300, PanelConfig(background = 0xFF336699.toInt()), image = null)
        assertEquals(120, flat.width); assertEquals(300, flat.height)
        val photo = Bitmap.createBitmap(40, 10, Bitmap.Config.ARGB_8888)
        val pictured = Backdrop.paint(120, 300,
            PanelConfig(backdrop = ArtConfig(imagePath = "/nowhere.png")), image = photo)
        assertEquals(120, pictured.width); assertEquals(300, pictured.height)
    }

    @Test fun `the wallpaper is the slate gradient whatever the panel shows`() {
        val photo = Bitmap.createBitmap(40, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFFF0000.toInt()) }
        val flat = Backdrop.paint(120, 300, PanelConfig(background = 0xFF336699.toInt()), image = null)
        val pictured = Backdrop.paint(120, 300, PanelConfig(), image = photo)
        // Robolectric's canvas does not shade a gradient exactly, so the pixel is compared
        // between the two paints, not with the stop: the photograph makes no difference.
        assertEquals(flat.getPixel(60, 150), pictured.getPixel(60, 150))
        assertNotEquals(0xFFFF0000.toInt(), pictured.getPixel(60, 150))
    }

    @Test fun `a missing image file decodes to nothing rather than throwing`() {
        assertNull(Backdrop.decodeNear("/definitely/not/here.png", 100, 100))
    }

    @Test fun `luminance is zero and safe before anything is built`() {
        assertEquals(0f, Backdrop.luminance(0f, 0f, 10f, 10f), 0f)
    }
}
