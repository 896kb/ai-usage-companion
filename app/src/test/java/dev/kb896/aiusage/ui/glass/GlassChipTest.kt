package dev.kb896.aiusage.ui.glass

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlassChipTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `the chip carries its colour and repaints when it changes`() {
        val chip = GlassChip(context)
        chip.colour = 0xFF123456.toInt()
        assertEquals(0xFF123456.toInt(), chip.colour)
        assertEquals(0xD9123456.toInt(), GlassChip.shown(0xFF123456.toInt()))
    }
}
