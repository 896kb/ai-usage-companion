package dev.kb896.aiusage.ui.glass

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MotionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val view = View(context)

    private fun settle(motion: Motion, seconds: Float = 2f) {
        var t = 0f
        while (t < seconds && motion.advance(1f / 120f)) t += 1f / 120f
    }

    @Test fun `press runs progress to one and release back to zero`() {
        val p = PressSpring(view)
        p.press(10f, 10f)
        settle(p.motion)
        assertEquals(1f, p.progress, 0.01f)
        p.release()
        settle(p.motion)
        assertEquals(0f, p.progress, 0.01f)
    }

    @Test fun `the highlight follows the finger and returns to where it started`() {
        val p = PressSpring(view)
        p.press(10f, 10f)
        p.move(50f, 20f)
        settle(p.motion)
        assertEquals(50f, p.x, 0.5f)
        p.release()
        settle(p.motion)
        assertEquals(10f, p.x, 0.5f)
    }

    @Test fun `a damped drag squashes on press and springs its value`() {
        val d = DampedDrag(view, initial = 0f, range = 0f..1f, threshold = 0.001f,
            initialScale = 1f, pressedScale = 1.5f)
        d.press()
        settle(d.motion)
        assertEquals(1.5f, d.scaleX, 0.01f)
        assertEquals(1.5f, d.scaleY, 0.01f)
        assertEquals(1f, d.pressProgress, 0.01f)
        d.updateValue(0.7f)
        settle(d.motion)
        assertEquals(0.7f, d.value, 0.01f)
        assertEquals(0.7f, d.progress, 0.01f)
        d.release()
        settle(d.motion)
        assertEquals(1f, d.scaleX, 0.01f)
    }

    @Test fun `animateTo clamps to the range`() {
        val d = DampedDrag(view, 0f, 0f..1f, 0.001f, 1f, 1.5f)
        d.animateTo(3f)
        settle(d.motion)
        assertEquals(1f, d.targetValue, 0f)
        assertTrue(d.value <= 1.001f)
    }

    /**
     * The whole end state of the touch path's own order: release, then animate to the end
     * the drag chose. The velocity's own decay is the drag test below's to guard — here
     * animateTo zeroes it outright, being a move the control makes rather than a finger.
     */
    @Test fun `a drag that ends puts the velocity back with everything else`() {
        val d = DampedDrag(view, 0f, 0f..1f, 0.001f, 1f, 1.5f)
        d.press()
        var v = 0f
        repeat(5) { v += 0.2f; d.updateValue(v); d.motion.advance(1f / 120f) }
        assertTrue("the drag should have built up speed: ${d.velocity}", abs(d.velocity) > 0.1f)
        // The touch path's own order: let go of the press, then animate to the end it chose.
        d.release()
        d.animateTo(1f)
        settle(d.motion)
        assertEquals(0f, d.velocity, 0.001f)
        assertEquals(1f, d.scaleX, 0.01f)
        assertEquals(1f, d.scaleY, 0.01f)
        assertEquals(0f, d.pressProgress, 0.01f)
        assertEquals(1f, d.value, 0.01f)
    }

    /**
     * A tap is the control moving itself, and the catalog animates the velocity to zero
     * for it: the thumb swells and travels, but carries none of a flick's stretch.
     */
    @Test fun `a tap moves the value with no squash on it`() {
        val d = DampedDrag(view, 0f, 0f..1f, 0.001f, 1f, 1.5f)
        d.animateTo(1f)
        d.motion.advance(1f / 120f)
        assertTrue("the tap should be under way: ${d.value}", d.value > 0f)
        assertEquals(0f, d.velocity, 0.001f)
        // More than one frame, and the fastest ones: a frame retargets the velocity after
        // stepping it, so the first frame reads zero whether or not the tap is following
        // the value. It is the frames over the value's own peak speed that tell them apart.
        repeat(20) {
            d.motion.advance(1f / 120f)
            assertEquals("a tap carries no squash at any point: ${d.value}", 0f, d.velocity, 0.001f)
        }
    }

    /**
     * A drag is the finger's, and the squash is its: it builds while the finger moves and
     * is gone once everything has come to rest.
     */
    @Test fun `a drag squashes while it moves and lets go of it afterwards`() {
        val d = DampedDrag(view, 0f, 0f..1f, 0.001f, 1f, 1.5f)
        d.press()
        var v = 0f
        repeat(4) { v += 0.2f; d.updateValue(v); d.motion.advance(1f / 120f) }
        assertTrue("a moving finger squashes it: ${d.velocity}", abs(d.velocity) > 0.1f)
        d.release()
        settle(d.motion)
        assertEquals(0f, d.velocity, 0.001f)
    }

    /**
     * The lift of a flick, as the touch path actually plays it: release() and animateTo()
     * with no frame between them, so the velocity animateTo sends to zero is still the
     * drag's own. Sent, not snapped — dropped in the one frame at the lift, the squash
     * fell from a flick's stretch to the press's plain swell in a single frame, which is
     * a pop where round 1 meant it to decay.
     */
    @Test fun `a released flick eases its squash out instead of dropping it`() {
        val d = DampedDrag(view, 0f, 0f..1f, 0.001f, 1f, 1.5f)
        d.press()
        var v = 0f
        repeat(5) { v += 0.2f; d.updateValue(v); d.motion.advance(1f / 120f) }
        val v0 = d.velocity
        assertTrue("the flick should have built up speed: $v0", abs(v0) > 0.1f)
        d.release()
        d.animateTo(1f)
        d.motion.advance(1f / 120f)
        assertTrue("the squash eases out, it does not come off: ${d.velocity} from $v0",
            abs(d.velocity) > 0.5f * abs(v0))
        settle(d.motion)
        assertEquals(0f, d.velocity, 0.001f)
        assertEquals(1f, d.scaleX, 0.01f)
        assertEquals(1f, d.scaleY, 0.01f)
        assertEquals(0f, d.pressProgress, 0.01f)
        assertEquals(1f, d.value, 0.01f)
    }

    @Test fun `animateTo squashes while the value moves and lets go when it settles`() {
        val d = DampedDrag(view, 0f, 0f..1f, 0.001f, 1f, 1.5f)
        d.animateTo(1f)
        d.motion.advance(1f / 120f)
        assertTrue("scale should be rising: ${d.scaleX}", d.scaleX > 1f)
        settle(d.motion)
        assertEquals(1f, d.value, 0.01f)
        assertEquals(1f, d.scaleX, 0.01f)
        assertEquals(0f, d.pressProgress, 0.01f)
    }
}
