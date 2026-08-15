package dev.kb896.aiusage.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MascotMoodTest {

    @Test fun `an untouched window celebrates`() {
        assertEquals(MascotMood.CONFETTI, MascotMood.of(0f, null))
        assertEquals(MascotMood.CONFETTI, MascotMood.of(0f, 0f))
    }

    @Test fun `a nearly spent window flies the flag`() {
        assertEquals(MascotMood.FLAG, MascotMood.of(0.90f, 0.10f))
        assertEquals(MascotMood.FLAG, MascotMood.of(1.0f, null))
    }

    @Test fun `burning through it with room left lifts`() {
        assertEquals(MascotMood.MUSCLE, MascotMood.of(0.40f, 0.30f))
    }

    /**
     * The spec's "and total < 90%" qualifier. Checking the burst before the high-water
     * mark would hide the warning at exactly the moment it matters most — the user is
     * burning hard AND nearly out.
     */
    @Test fun `a burst above the high mark still flies the flag`() {
        assertEquals(MascotMood.FLAG, MascotMood.of(0.95f, 0.80f))
    }

    @Test fun `steady use in the middle just walks`() {
        assertEquals(MascotMood.STAND, MascotMood.of(0.40f, 0.39f))
        assertEquals(MascotMood.STAND, MascotMood.of(0.40f, 0.40f))
    }

    /** Exactly 5% is not MORE than 5%. */
    @Test fun `the burst threshold is exclusive`() {
        assertEquals(MascotMood.STAND, MascotMood.of(0.35f, 0.30f))
        assertEquals(MascotMood.MUSCLE, MascotMood.of(0.3501f, 0.30f))
    }

    /**
     * With no history the honest reading is "unknown", not "no burn". Inventing a
     * baseline from a single sample would fire the lifting animation on the first poll
     * after every restart.
     */
    @Test fun `no history cannot produce a burst`() {
        assertEquals(MascotMood.STAND, MascotMood.of(0.40f, null))
    }

    /** Usage can fall when a window rolls over; that is not a burst. */
    @Test fun `a falling window does not lift`() {
        assertEquals(MascotMood.STAND, MascotMood.of(0.10f, 0.80f))
    }
}
