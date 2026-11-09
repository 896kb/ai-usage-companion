package dev.kb896.aiusage.rear

import android.graphics.Matrix
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlassPainterRotatedTest {

    @Test fun `a pane under a rotated canvas samples what is really under it`() {
        val canvas = Matrix().apply { setRotate(37f, 600f, 228f); postTranslate(12f, -5f) }
        val rect = RectF(590f, 40f, 610f, 260f)
        val out = Matrix()
        assertTrue(GlassPainter.sourceToPane(canvas, rect, out))
        // A point in the pane's own space, where the root draws it, and back.
        val q = floatArrayOf(7f, 101f)
        val root = floatArrayOf(q[0] + rect.left, q[1] + rect.top)
        canvas.mapPoints(root)
        out.mapPoints(root)
        assertEquals(q[0], root[0], 0.01f)
        assertEquals(q[1], root[1], 0.01f)
    }

    @Test fun `only a rotation or skew takes the new path`() {
        assertTrue(GlassPainter.axisAligned(Matrix().apply { setTranslate(3f, 4f); postScale(2f, 2f) }))
        assertFalse(GlassPainter.axisAligned(Matrix().apply { setRotate(10f) }))
        assertFalse(GlassPainter.axisAligned(Matrix().apply { setRotate(90f) }))
    }
}
