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
}
