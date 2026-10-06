package dev.kb896.aiusage.ui

import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CropGestureTest {
    private val region = RectF(0f, 0f, 904f, 572f)

    private fun drag(g: CropGesture, dx: Float, dy: Float) {
        val t = SystemClock.uptimeMillis()
        g.onTouch(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 400f, 300f, 0))
        g.onTouch(MotionEvent.obtain(t, t + 16, MotionEvent.ACTION_MOVE, 400f + dx, 300f + dy, 0))
        g.onTouch(MotionEvent.obtain(t, t + 32, MotionEvent.ACTION_UP, 400f + dx, 300f + dy, 0))
    }

    @Test fun `a drag moves the pan by its share of the hidden part`() {
        var changes = 0
        val g = CropGesture(ApplicationProvider.getApplicationContext(), { region }, { 1080 to 1920 }, { changes++ })
        // 1080x1920 covering 904x572 at zoom 1: 1607 tall, 517.5 hidden above and below.
        drag(g, 0f, 103.5f)
        assertEquals(0.2f, g.panY, 1e-3f)
        assertEquals(0f, g.panX, 1e-6f)          // no slack sideways: nothing to move
        assertEquals(1, changes)
    }

    @Test fun `the pan stops at the edge`() {
        val g = CropGesture(ApplicationProvider.getApplicationContext(), { region }, { 1080 to 1920 }, {})
        drag(g, 0f, 5000f)
        assertEquals(1f, g.panY, 1e-6f)
    }

    @Test fun `with nothing loaded a drag changes nothing`() {
        val g = CropGesture(ApplicationProvider.getApplicationContext(), { region }, { null }, {})
        drag(g, 50f, 50f)
        assertEquals(0f, g.panX, 0f); assertEquals(0f, g.panY, 0f)
    }
}
