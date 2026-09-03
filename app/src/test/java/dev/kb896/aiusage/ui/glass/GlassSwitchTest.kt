package dev.kb896.aiusage.ui.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlassSwitchTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `it measures the catalog's 64 by 28`() {
        val s = GlassSwitch(context)
        s.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val d = context.resources.displayMetrics.density
        assertEquals((64 * d).toInt(), s.measuredWidth)
        assertEquals((28 * d).toInt(), s.measuredHeight)
    }

    @Test fun `a click toggles and reports, a silent set does not report`() {
        val s = GlassSwitch(context)
        val seen = ArrayList<Boolean>()
        s.onCheckedChange = { seen += it }
        assertFalse(s.isChecked)
        s.performClick()
        assertTrue(s.isChecked)
        assertEquals(listOf(true), seen)
        s.setCheckedSilently(false)
        assertFalse(s.isChecked)
        assertEquals(listOf(true), seen)
    }

    @Test fun `a switch with no size draws without throwing`() {
        val s = GlassSwitch(context)
        s.layout(0, 0, 0, 0)
        s.draw(Canvas(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)))
    }

    @Test fun `a click listener sees the state the click produced`() {
        val s = GlassSwitch(context)
        var seen: Boolean? = null
        s.setOnClickListener { seen = s.isChecked }
        s.performClick()
        assertEquals(true, seen)
    }
}
