package dev.kb896.aiusage.ui.glass

import android.content.Context
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
class GlassSegmentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `it starts on the given option and clamps`() {
        val s = GlassSegmented(context, listOf("A", "B", "C"), selected = 7)
        assertEquals(2, s.selected)
    }

    @Test fun `select reports a change once and ignores a repeat`() {
        val s = GlassSegmented(context, listOf("A", "B", "C"), 0)
        val seen = ArrayList<Int>()
        s.onChange = { seen += it }
        s.select(2); s.select(2)
        assertEquals(listOf(2), seen)
        assertEquals(2, s.selected)
    }

    @Test fun `a tap on the middle third selects the middle option`() {
        val s = GlassSegmented(context, listOf("A", "B", "C"), 0)
        s.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        s.layout(0, 0, 300, s.measuredHeight)
        assertEquals(1, s.optionAt(150f))
        assertEquals(2, s.optionAt(299f))
    }

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

    /** Hosted and laid out: the control needs a width before a drag means anything. */
    private fun hosted(host: Host, selected: Int = 0): GlassSegmented {
        val s = GlassSegmented(context, listOf("A", "B", "C"), selected)
        host.addView(s)
        s.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.UNSPECIFIED)
        s.layout(0, 0, 300, s.measuredHeight)
        return s
    }

    /**
     * The report: a scroll that happens to start on the control used to change the choice.
     *
     * The control takes the DOWN, the ScrollView decides the gesture is vertical and takes
     * it back with an ACTION_CANCEL — which fell into the UP branch and was read as a tap.
     */
    @Test fun `a vertical drag that starts on the control does not change the choice`() {
        val host = Host(context)
        val s = hosted(host)
        val seen = ArrayList<Int>()
        s.onChange = { seen += it }
        send(s, MotionEvent.ACTION_DOWN, 250f, 20f)
        send(s, MotionEvent.ACTION_MOVE, 250f, 20f + slop * 3f)
        send(s, MotionEvent.ACTION_CANCEL, 250f, 20f + slop * 3f)
        assertEquals(0, s.selected)
        assertTrue("a cancelled gesture reports nothing", seen.isEmpty())
        assertEquals("the selection goes back to the option it still is", 0f, s.dragTarget, 0f)
    }

    @Test fun `a sideways drag takes the gesture from the scroller`() {
        val host = Host(context)
        val s = hosted(host)
        send(s, MotionEvent.ACTION_DOWN, 50f, 20f)
        send(s, MotionEvent.ACTION_MOVE, 50f + slop * 3f, 20f)
        assertTrue(host.disallowed)
    }

    @Test fun `a vertical drag leaves the gesture to the scroller`() {
        val host = Host(context)
        val s = hosted(host)
        send(s, MotionEvent.ACTION_DOWN, 50f, 20f)
        send(s, MotionEvent.ACTION_MOVE, 50f, 20f + slop * 3f)
        assertFalse("nothing may be disallowed until the drag is known to be sideways",
            host.disallowed)
    }

    /** A cancel arriving mid-drag is not a choice: the pane goes back, nothing reports. */
    @Test fun `a cancel mid-drag puts the selection back`() {
        val host = Host(context)
        val s = hosted(host)
        val seen = ArrayList<Int>()
        s.onChange = { seen += it }
        send(s, MotionEvent.ACTION_DOWN, 50f, 20f)
        send(s, MotionEvent.ACTION_MOVE, 250f, 20f)
        assertEquals("dragged onto the last option", 2f, s.dragTarget, 0f)
        send(s, MotionEvent.ACTION_CANCEL, 250f, 20f)
        assertEquals(0, s.selected)
        assertTrue(seen.isEmpty())
        assertEquals(0f, s.dragTarget, 0f)
    }
}
