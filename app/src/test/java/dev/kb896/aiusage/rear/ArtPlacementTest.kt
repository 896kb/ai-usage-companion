package dev.kb896.aiusage.rear

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// RectF is one of the android.graphics types the plain JVM stub throws "not mocked" for.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArtPlacementTest {

    private val region = RectF(0f, 0f, 278f, 70f)      // the panel's art region

    /** Cover, not fit: the region must never show through behind the image. */
    @Test fun `a portrait photo still covers the region`() {
        val d = ArtPlacement.destination(region, 1000, 2000, 0f, 0f, 1f)
        assertTrue("width ${d.width()}", d.width() >= region.width() - 0.01f)
        assertTrue("height ${d.height()}", d.height() >= region.height() - 0.01f)
    }

    @Test fun `a wide photo still covers the region`() {
        val d = ArtPlacement.destination(region, 4000, 200, 0f, 0f, 1f)
        assertTrue(d.width() >= region.width() - 0.01f)
        assertTrue(d.height() >= region.height() - 0.01f)
    }

    /** At pan 0 the image is centred, whatever its shape. */
    @Test fun `no pan centres the image`() {
        val d = ArtPlacement.destination(region, 1000, 2000, 0f, 0f, 1f)
        assertEquals(region.centerX(), d.centerX(), 0.01f)
        assertEquals(region.centerY(), d.centerY(), 0.01f)
    }

    /**
     * Panning to an extreme brings the image's edge to the region's edge and no further:
     * that is what makes the pan range meaningful whatever the zoom.
     */
    @Test fun `full pan lands the edge exactly on the region`() {
        // +1 brings the image's TOP to the region's top, which is the natural reading:
        // dragging the picture down reveals what was above the frame.
        val down = ArtPlacement.destination(region, 1000, 2000, 0f, 1f, 1f)
        assertEquals(region.top, down.top, 0.01f)
        val up = ArtPlacement.destination(region, 1000, 2000, 0f, -1f, 1f)
        assertEquals(region.bottom, up.bottom, 0.01f)
    }

    /** Out-of-range values are clamped, not obeyed — a config can be edited by hand. */
    @Test fun `pan and zoom are clamped`() {
        val wild = ArtPlacement.destination(region, 1000, 1000, 40f, -40f, 900f)
        val edge = ArtPlacement.destination(region, 1000, 1000, 1f, -1f, ArtPlacement.MAX_ZOOM)
        assertEquals(edge.left, wild.left, 0.01f)
        assertEquals(edge.top, wild.top, 0.01f)
    }

    /** Zooming keeps the image centred on the same point when it is not panned. */
    @Test fun `zoom grows the image about the region's centre`() {
        val one = ArtPlacement.destination(region, 1000, 1000, 0f, 0f, 1f)
        val two = ArtPlacement.destination(region, 1000, 1000, 0f, 0f, 2f)
        assertEquals(one.centerX(), two.centerX(), 0.01f)
        assertEquals(2f, two.width() / one.width(), 0.01f)
    }
}
