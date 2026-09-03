package dev.kb896.aiusage.ui.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
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

    /** A ViewGroup that remembers being told to keep out, as a ScrollView would be. */
    private class Host(context: Context) : ViewGroup(context) {
        var disallowed = false
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {}
        override fun requestDisallowInterceptTouchEvent(disallow: Boolean) {
            if (disallow) disallowed = true
            super.requestDisallowInterceptTouchEvent(disallow)
        }
    }

    private fun send(v: View, action: Int, x: Float, y: Float) {
        val e = MotionEvent.obtain(0L, 10L, action, x, y, 0)
        try { v.onTouchEvent(e) } finally { e.recycle() }
    }

    private val slop get() = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    @Test fun `it measures the catalog's 64 by 28 with room around it for the pressed thumb`() {
        val s = GlassSwitch(context)
        s.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val d = context.resources.displayMetrics.density
        assertEquals(((64 + 2 * GlassSwitch.PAD_X) * d).toInt(), s.measuredWidth)
        assertEquals(((28 + 2 * GlassSwitch.PAD_Y) * d).toInt(), s.measuredHeight)
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

    /**
     * The report: a scroll that happens to start on a toggle used to toggle it.
     *
     * The switch takes the DOWN, the ScrollView decides the gesture is vertical and takes
     * it back with an ACTION_CANCEL — which fell into the UP branch and was read as a tap.
     */
    @Test fun `a vertical drag that starts on the switch does not toggle it`() {
        val host = Host(context)
        val s = GlassSwitch(context)
        host.addView(s)
        val seen = ArrayList<Boolean>()
        s.onCheckedChange = { seen += it }
        send(s, MotionEvent.ACTION_DOWN, 40f, 20f)
        send(s, MotionEvent.ACTION_MOVE, 40f, 20f + slop * 3f)
        send(s, MotionEvent.ACTION_CANCEL, 40f, 20f + slop * 3f)
        assertFalse(s.isChecked)
        assertTrue("a cancelled gesture reports nothing", seen.isEmpty())
        assertEquals("the thumb goes back to the state it still has", 0f, s.dragTarget, 0f)
    }

    @Test fun `a sideways drag takes the gesture from the scroller`() {
        val host = Host(context)
        val s = GlassSwitch(context)
        host.addView(s)
        send(s, MotionEvent.ACTION_DOWN, 40f, 20f)
        send(s, MotionEvent.ACTION_MOVE, 40f + slop * 3f, 20f)
        assertTrue(host.disallowed)
    }

    @Test fun `a vertical drag leaves the gesture to the scroller`() {
        val host = Host(context)
        val s = GlassSwitch(context)
        host.addView(s)
        send(s, MotionEvent.ACTION_DOWN, 40f, 20f)
        send(s, MotionEvent.ACTION_MOVE, 40f, 20f + slop * 3f)
        assertFalse("nothing may be disallowed until the drag is known to be sideways",
            host.disallowed)
    }

    /** A cancel arriving mid-drag is not a choice: the thumb goes back, nothing reports. */
    @Test fun `a cancel mid-drag puts the thumb back`() {
        val host = Host(context)
        val s = GlassSwitch(context)
        host.addView(s)
        val seen = ArrayList<Boolean>()
        s.onCheckedChange = { seen += it }
        val d = context.resources.displayMetrics.density
        send(s, MotionEvent.ACTION_DOWN, 40f, 20f)
        send(s, MotionEvent.ACTION_MOVE, 40f + 30f * d, 20f)
        assertEquals("dragged past the far end", 1f, s.dragTarget, 0f)
        send(s, MotionEvent.ACTION_CANCEL, 40f + 30f * d, 20f)
        assertFalse(s.isChecked)
        assertTrue(seen.isEmpty())
        assertEquals(0f, s.dragTarget, 0f)
    }
}
