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

    /** A frame of the view's animation, which is what advances the thumb between touches. */
    private fun frame(s: GlassSwitch) { s.motion.advance(1f / 120f) }

    /** Frames until nothing is moving, as the view's own ticker would run them. */
    private fun settle(s: GlassSwitch, seconds: Float = 2f) {
        var t = 0f
        while (t < seconds && s.motion.advance(1f / 120f)) t += 1f / 120f
    }

    private fun laidOut(): GlassSwitch = GlassSwitch(context).apply {
        measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        layout(0, 0, measuredWidth, measuredHeight)
    }

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

    /**
     * The report: after a drag the thumb stayed very wide, or a circle.
     *
     * The squash reads a velocity that was only ever retargeted from updateValue, so the
     * finger lifting froze it at whatever the last drag frame set, and the thumb sat at
     * rest wearing that flick's stretch for good: 50 by 19dp, saturated at the clamp,
     * turning the switch on. Turning it off it was the same freeze on a negative
     * velocity, which — while the squash was still signed — pinched instead of stretched
     * and left a 33 by 29dp thumb with no straight edge on it. That is the circle.
     */
    @Test fun `the thumb is its own size again once a drag has settled`() {
        val host = Host(context)
        val s = laidOut()
        host.addView(s)
        val d = context.resources.displayMetrics.density
        send(s, MotionEvent.ACTION_DOWN, 20f, 20f)
        // A frame between the moves: without one the value spring never steps, and a
        // velocity that was never built cannot be left behind.
        send(s, MotionEvent.ACTION_MOVE, 20f + slop + 2f, 20f); frame(s)
        send(s, MotionEvent.ACTION_MOVE, 20f + 12f * d, 20f); frame(s)
        send(s, MotionEvent.ACTION_MOVE, 20f + 22f * d, 20f); frame(s)
        send(s, MotionEvent.ACTION_UP, 20f + 22f * d, 20f)
        settle(s)
        assertTrue("a drag across the travel turns it on", s.isChecked)
        val (sx, sy) = s.thumbScales()
        assertEquals("the thumb is no wider than itself", 1f, sx, 0.01f)
        assertEquals("nor any flatter", 1f, sy, 0.01f)
    }

    /**
     * A thumb at rest is opaque white, and nothing under it is worth drawing: no bitmap
     * of its own backdrop, and none of the lens that would refract it.
     */
    @Test fun `a switch at rest composes nothing behind its thumb`() {
        val s = laidOut()
        val canvas = Canvas(Bitmap.createBitmap(s.width, s.height, Bitmap.Config.ARGB_8888))
        s.draw(canvas)
        assertFalse("nothing under an opaque thumb is worth a bitmap", s.hasLocalBitmap)
        send(s, MotionEvent.ACTION_DOWN, 40f, 20f)
        settle(s)
        s.draw(canvas)
        assertTrue("a pressed thumb turns to glass and needs one", s.hasLocalBitmap)
    }

    /**
     * The pressed thumb is capped by the clearance it has, not by the view's centre.
     *
     * At rest the thumb's centre sits 34dp into the 88dp view, so a 40dp thumb may grow
     * to 1.7x before its leading edge reaches the view's — measured from the centre the
     * cap would read 2.2x, which a press plus a flick can exceed, and the thumb clipped
     * at either end of its travel.
     */
    @Test fun `the thumb's cap is its clearance at the nearer edge`() {
        val d = context.resources.displayMetrics.density
        assertEquals(1.7f, GlassSwitch.thumbCap(34 * d, 88 * d, 40 * d), 0.01f)
        // Only in the middle of its travel does the thumb have the whole view to grow into.
        assertEquals(2.2f, GlassSwitch.thumbCap(44 * d, 88 * d, 40 * d), 0.01f)
    }
}
