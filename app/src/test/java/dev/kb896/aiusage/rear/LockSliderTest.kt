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


    // -- the indicator ----------------------------------------------------------------

    /**
     * The indicator keeps the reference SLIDER's proportion, not a tab's.
     *
     * Half the track was the literal reading of "a tab bar with two stops" and it made a
     * slab — at that size the thing being dragged is most of the instrument, so it is not
     * being carried across anything.
     */
    @Test fun `the indicator is a small object with a long way to go`() {
        val span = LockSlider.right() - LockSlider.left() - LockSlider.PAD * 2f
        assertEquals(
            LockSlider.thumbHeight(h) * LockSlider.THUMB_ASPECT,
            LockSlider.thumbWidth(h), 0.01f,
        )
        assertTrue("the indicator is most of its own track",
            LockSlider.thumbWidth(h) < span / 2f)
        assertTrue("there is barely anywhere to carry it",
            LockSlider.travel(h) > LockSlider.thumbWidth(h))
    }

    /** It stays inside the container at both stops — that is what the padding is for. */
    @Test fun `it sits inside the container at rest and arrived`() {
        assertEquals(LockSlider.left() + LockSlider.PAD, LockSlider.thumbLeft(0f, h), 0.01f)
        assertEquals(
            LockSlider.right() - LockSlider.PAD,
            LockSlider.thumbLeft(1f, h) + LockSlider.thumbWidth(h), 0.01f,
        )
    }

    /** Held, it swells — the only acknowledgement a control on the back of a phone has. */
    @Test fun `it grows when it is held`() {
        assertTrue("a held indicator should be bigger", LockSlider.PRESSED_SCALE > 1f)
        assertTrue("but not a different object", LockSlider.PRESSED_SCALE < 2f)
    }

    @Test fun `progress past the ends is still progress`() {
        assertEquals(LockSlider.thumbLeft(0f, h), LockSlider.thumbLeft(-3f, h), 0.01f)
        assertEquals(LockSlider.thumbLeft(1f, h), LockSlider.thumbLeft(9f, h), 0.01f)
    }

    // -- taking hold ------------------------------------------------------------------

    /**
     * THE CASE THE USER ASKED FOR BY NAME: a tap at the right-hand end must not unlock.
     * It is not picked up at all, so there is no drag to finish.
     */
    @Test fun `a touch at the far end does not take hold`() {
        assertFalse(
            "a tap on the right grabbed the indicator",
            LockSlider.grabs(LockSlider.right() - 4f, 0f, h),
        )
    }

    @Test fun `a touch on the resting indicator takes hold`() {
        assertTrue(LockSlider.grabs(LockSlider.thumbLeft(0f, h) + 10f, 0f, h))
        assertTrue(LockSlider.grabs(LockSlider.thumbLeft(0f, h) + LockSlider.thumbWidth(h) / 2f, 0f, h))
    }

    /** The grab region travels with it, or a drag could not be resumed mid-track. */
    @Test fun `the grab region follows the indicator`() {
        val midOfArrived = LockSlider.thumbLeft(1f, h) + LockSlider.thumbWidth(h) / 2f
        assertTrue(LockSlider.grabs(midOfArrived, 1f, h))
        assertFalse("the resting position still answers once it has moved",
            LockSlider.grabs(LockSlider.left() + 2f, 1f, h))
    }

    // -- the drag ---------------------------------------------------------------------

    /**
     * Measured from where the finger GRABBED, so the indicator moves with the thumb
     * instead of jumping to centre itself under it on the first move.
     */
    @Test fun `it moves with the finger, not to it`() {
        assertEquals(0f, LockSlider.progressFrom(0f, 0f, h), 0.001f)
        assertEquals(0.5f, LockSlider.progressFrom(0f, LockSlider.travel(h) / 2f, h), 0.001f)
        assertEquals(1f, LockSlider.progressFrom(0f, LockSlider.travel(h), h), 0.001f)
    }

    @Test fun `a drag resumed from halfway carries on from there`() {
        assertEquals(0.75f, LockSlider.progressFrom(0.5f, LockSlider.travel(h) / 4f, h), 0.001f)
    }

    @Test fun `dragging back the way it came is allowed, and clamped`() {
        assertEquals(0f, LockSlider.progressFrom(0.5f, -LockSlider.travel(h), h), 0.001f)
        assertEquals(1f, LockSlider.progressFrom(0.5f, LockSlider.travel(h) * 4f, h), 0.001f)
    }

    // -- committing -------------------------------------------------------------------

    @Test fun `only a finished swipe unlocks`() {
        assertFalse("unlocked without moving", LockSlider.unlocks(0f))
        assertFalse("unlocked halfway", LockSlider.unlocks(0.5f))
        assertFalse("unlocked just short", LockSlider.unlocks(0.9f))
        assertTrue("a completed swipe did not unlock", LockSlider.unlocks(1f))
    }

    /**
     * The indicator reaches the end while the finger is still moving, so the last
     * fraction of a unit must not decide it.
     */
    @Test fun `arriving counts, even a hair short`() {
        assertTrue(LockSlider.unlocks(LockSlider.COMMIT))
        assertTrue(LockSlider.unlocks(0.995f))
    }

    @Test fun `the margin is a hair and not a shortcut`() {
        assertTrue("the commit threshold is too forgiving", LockSlider.COMMIT > 0.9f)
    }

    // -- a whole gesture --------------------------------------------------------------

    /** Take hold where it rests, carry it across, let go at the end. */
    @Test fun `an indicator carried the whole way across unlocks`() {
        val grabAt = LockSlider.thumbLeft(0f, h) + LockSlider.thumbWidth(h) / 2f
        assertTrue("could not take hold", LockSlider.grabs(grabAt, 0f, h))
        val progress = LockSlider.progressFrom(0f, LockSlider.travel(h), h)
        assertTrue("a full swipe did not unlock", LockSlider.unlocks(progress))
    }

    @Test fun `one that stops halfway does not`() {
        assertFalse(LockSlider.unlocks(LockSlider.progressFrom(0f, LockSlider.travel(h) / 2f, h)))
    }

    /** The travel is long — that is what makes this deliberate rather than lucky. */
    @Test fun `the travel is a real distance`() {
        val usable = Panel.WIDTH - Panel.CUTOUT_RIGHT.toFloat()
        assertTrue("the travel is only ${LockSlider.travel(h)} of $usable",
            LockSlider.travel(h) > usable * 0.3f)
    }

    /** The indicator has room inside the container on every side. */
    @Test fun `the container shows around it`() {
        assertTrue(LockSlider.PAD > 0f)
        assertTrue(LockSlider.thumbHeight(h) < h)
        assertEquals(h - LockSlider.PAD * 2f, LockSlider.thumbHeight(h), 0.001f)
    }
}
