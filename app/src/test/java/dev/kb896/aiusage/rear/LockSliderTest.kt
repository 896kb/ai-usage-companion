package dev.kb896.aiusage.rear

import dev.kb896.aiusage.Panel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockSliderTest {

    /** The band the slider is drawn in — the artwork slot's height. */
    private val h = 70f

    @Test fun `it spans eight tenths of the panel you can actually see`() {
        val usable = Panel.WIDTH - Panel.CUTOUT_RIGHT.toFloat()
        assertEquals(usable * 0.8f, LockSlider.right() - LockSlider.left(), 0.01f)
    }

    /**
     * The cutout is the whole reason this is measured against the usable panel. Eight
     * tenths of the FULL width, centred, would start at 90 — inside the lenses, where
     * the knob would be invisible at rest.
     */
    @Test fun `it starts clear of the camera glass`() {
        assertTrue(
            "the slider begins under the lenses at ${LockSlider.left()}",
            LockSlider.left() > Panel.CUTOUT_RIGHT,
        )
        assertTrue("it runs off the panel", LockSlider.right() <= Panel.WIDTH)
    }

    @Test fun `it is centred in the panel you can see`() {
        val usableMid = Panel.CUTOUT_RIGHT + (Panel.WIDTH - Panel.CUTOUT_RIGHT) / 2f
        assertEquals(usableMid, (LockSlider.left() + LockSlider.right()) / 2f, 0.01f)
    }

    // -- the knob ---------------------------------------------------------------------

    @Test fun `the knob rests at the left and finishes at the right`() {
        val rest = LockSlider.thumbCx(0f, h)
        val done = LockSlider.thumbCx(1f, h)
        assertTrue("does not rest at the left: $rest", rest < LockSlider.left() + h)
        assertTrue("does not finish at the right: $done", done > LockSlider.right() - h)
        assertTrue("travels backwards", done > rest)
    }

    /** The knob is a disc inside the track, so it can never hang out of either end. */
    @Test fun `the knob stays inside the track at both ends`() {
        val r = LockSlider.thumbRadius(h)
        assertTrue(LockSlider.thumbCx(0f, h) - r >= LockSlider.left() - 0.01f)
        assertTrue(LockSlider.thumbCx(1f, h) + r <= LockSlider.right() + 0.01f)
    }

    @Test fun `progress past the ends is still progress`() {
        assertEquals(LockSlider.thumbCx(0f, h), LockSlider.thumbCx(-3f, h), 0.01f)
        assertEquals(LockSlider.thumbCx(1f, h), LockSlider.thumbCx(9f, h), 0.01f)
    }

    // -- taking hold ------------------------------------------------------------------

    /**
     * THE CASE THE USER ASKED FOR BY NAME: a tap at the right-hand end must not unlock.
     * It is not picked up at all, so there is no drag to finish.
     */
    @Test fun `a touch at the far end does not take hold`() {
        assertFalse(
            "a tap on the right grabbed the knob",
            LockSlider.grabs(LockSlider.right() - 10f, 0f, h),
        )
    }

    /** Nor anywhere along the track. The knob has to be picked up where it rests. */
    @Test fun `a touch in the middle of the track does not take hold`() {
        val mid = (LockSlider.left() + LockSlider.right()) / 2f
        assertFalse(LockSlider.grabs(mid, 0f, h))
    }

    @Test fun `a touch on the resting knob takes hold`() {
        assertTrue(LockSlider.grabs(LockSlider.thumbCx(0f, h), 0f, h))
    }

    /** Forgiving, because it is on the back of a phone being held. */
    @Test fun `a touch near the resting knob still takes hold`() {
        val cx = LockSlider.thumbCx(0f, h)
        assertTrue(LockSlider.grabs(cx + LockSlider.thumbRadius(h) + 20f, 0f, h))
        assertTrue(LockSlider.grabs(cx - LockSlider.thumbRadius(h) - 20f, 0f, h))
    }

    /** The grab region travels with the knob, or a drag could not be resumed mid-track. */
    @Test fun `the grab region follows the knob`() {
        assertTrue(LockSlider.grabs(LockSlider.thumbCx(0.5f, h), 0.5f, h))
        assertFalse(LockSlider.grabs(LockSlider.thumbCx(0f, h), 0.5f, h))
    }

    // -- the drag ---------------------------------------------------------------------

    @Test fun `the knob goes where the finger is`() {
        assertEquals(0f, LockSlider.progressFor(LockSlider.thumbCx(0f, h), h), 0.001f)
        assertEquals(0.5f, LockSlider.progressFor(LockSlider.thumbCx(0.5f, h), h), 0.001f)
        assertEquals(1f, LockSlider.progressFor(LockSlider.thumbCx(1f, h), h), 0.001f)
    }

    @Test fun `a finger past either end is clamped`() {
        assertEquals(0f, LockSlider.progressFor(0f, h), 0.001f)
        assertEquals(1f, LockSlider.progressFor(Panel.WIDTH * 2f, h), 0.001f)
    }

    @Test fun `progress rises with the finger, all the way across`() {
        var last = -1f
        var x = LockSlider.left()
        while (x <= LockSlider.right()) {
            val p = LockSlider.progressFor(x, h)
            assertTrue("progress went backwards at $x", p >= last - 0.0001f)
            last = p
            x += 3f
        }
        assertEquals("never arrived", 1f, last, 0.001f)
    }

    // -- committing -------------------------------------------------------------------

    @Test fun `only a finished swipe unlocks`() {
        assertFalse("unlocked without moving", LockSlider.unlocks(0f))
        assertFalse("unlocked halfway", LockSlider.unlocks(0.5f))
        assertFalse("unlocked just short", LockSlider.unlocks(0.9f))
        assertTrue("a completed swipe did not unlock", LockSlider.unlocks(1f))
    }

    /**
     * The knob reaches the end while the finger is still moving, so the last fraction of
     * a unit must not be the difference between unlocking and not.
     */
    @Test fun `arriving counts, even a hair short`() {
        assertTrue(LockSlider.unlocks(LockSlider.COMMIT))
        assertTrue(LockSlider.unlocks(0.995f))
    }

    /** But not so lenient that most of a swipe will do. */
    @Test fun `the margin is a hair and not a shortcut`() {
        assertTrue("the commit threshold is too forgiving", LockSlider.COMMIT > 0.9f)
    }

    // -- a whole gesture --------------------------------------------------------------

    /**
     * The full journey, as the panel will run it: take hold of the knob at rest, drag
     * across in steps, and let go at the end.
     */
    @Test fun `a thumb carried the whole way across unlocks`() {
        var progress = 0f
        assertTrue("could not take hold", LockSlider.grabs(LockSlider.thumbCx(0f, h), progress, h))
        var x = LockSlider.thumbCx(0f, h)
        while (x <= LockSlider.thumbCx(1f, h)) {
            progress = LockSlider.progressFor(x, h)
            x += 11f
        }
        progress = LockSlider.progressFor(LockSlider.thumbCx(1f, h), h)
        assertTrue("a full swipe did not unlock", LockSlider.unlocks(progress))
    }

    /** And the same journey abandoned halfway does not. */
    @Test fun `a thumb that stops halfway does not unlock`() {
        val progress = LockSlider.progressFor(
            (LockSlider.thumbCx(0f, h) + LockSlider.thumbCx(1f, h)) / 2f, h,
        )
        assertFalse(LockSlider.unlocks(progress))
    }

    /**
     * The travel has to be LONG — that is what makes this deliberate rather than lucky.
     * Most of the panel you can see, not a token gap.
     */
    @Test fun `the travel is most of the usable panel`() {
        val usable = Panel.WIDTH - Panel.CUTOUT_RIGHT.toFloat()
        assertTrue(
            "the travel is only ${LockSlider.travel(h)} of $usable",
            LockSlider.travel(h) > usable * 0.6f,
        )
    }
}
