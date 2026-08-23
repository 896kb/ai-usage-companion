package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassCardTest {

    /** The rear panel's own rounding, and the notification card's. */
    private val screen = 97f
    private val card = 22f

    /**
     * The property the whole object exists for, checked the way the panel does it: put
     * the card's corner circle where the margin says, and see whether it stays inside the
     * screen's.
     */
    private fun reach(margin: Float, screen: Float = this.screen, card: Float = this.card): Float {
        val between = Math.sqrt(2.0).toFloat() * (screen - margin - card)
        return between + card
    }

    @Test fun `the derived margin keeps the corner inside the screen`() {
        val m = GlassCard.margin(screen, card)
        assertTrue("corner reaches ${reach(m)} against a limit of $screen", reach(m) <= screen)
    }

    /** And it is the LEAST such margin, near enough — not a shrug with a large number. */
    @Test fun `it is not more generous than it needs to be`() {
        val m = GlassCard.margin(screen, card)
        assertTrue("margin $m is doing more than clearing the corner",
            reach(m) > screen - GlassCard.CLEARANCE * 2f)
    }

    /**
     * The case that prompted this: a margin chosen for how it looked put the card's
     * corner eleven units outside the display's cut.
     */
    @Test fun `the margin that was there before would have been clipped`() {
        assertTrue("14 should not have been enough on this panel", reach(14f) > screen)
        assertTrue("the fix must therefore move it", GlassCard.margin(screen, card) > 14f)
    }

    @Test fun `a rounder screen needs a bigger margin`() {
        assertTrue(GlassCard.margin(150f, card) > GlassCard.margin(97f, card))
    }

    /** A rounder card hugs the curve, so it can sit closer in. */
    @Test fun `a rounder card needs a smaller margin`() {
        assertTrue(GlassCard.margin(screen, 60f) < GlassCard.margin(screen, 22f))
    }

    /** A square screen cuts no corners, so only the design's own margin applies. */
    @Test fun `a square screen falls back to the floor`() {
        assertEquals(GlassCard.MIN, GlassCard.margin(0f, card), 0.001f)
        assertEquals(GlassCard.MIN, GlassCard.margin(card, card), 0.001f)
    }

    /** Never below the floor, however square things get. */
    @Test fun `the floor holds`() {
        var s = 0f
        while (s <= 200f) {
            assertTrue("went under the floor at screen radius $s",
                GlassCard.margin(s, card) >= GlassCard.MIN - 0.001f)
            s += 7f
        }
    }
}
