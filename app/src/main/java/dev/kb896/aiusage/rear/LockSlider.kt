package dev.kb896.aiusage.rear

import dev.kb896.aiusage.Panel

/**
 * The unlock slider: the one thing the panel will listen to while it is locked.
 *
 * WHY THE PANEL LOCKS AT ALL. The rear display now lights itself when a notification or
 * a charger arrives, and the phone those things arrive at is quite often in a pocket.
 * Every control on this panel is then under a thigh: the volume strip, the transport
 * buttons, the page swipe. A screen that turns itself on has to assume nobody asked it
 * to, and the only safe assumption is that whatever touches it next is not a decision.
 *
 * SO IT ASKS FOR A GESTURE NOBODY MAKES BY ACCIDENT. Not a tap — cloth taps. Not a tap
 * on the right-hand end — that is a tap with extra steps. A thumb has to land on the
 * knob where it rests, at the left, and carry it the whole way across without letting
 * go. That is three things in sequence (land in one place, travel a long way, arrive at
 * another) and a pocket produces none of them.
 *
 * The measurements are all fractions of the panel, and the one that matters is that the
 * travel is LONG. A short slider is a tap with tolerance; the width is what makes this
 * a deliberate act rather than a lucky one.
 */
object LockSlider {

    /**
     * How much of the usable panel the slider spans.
     *
     * Eight tenths, centred, at the user's request — and the reason it is worth stating
     * as a fraction of the USABLE panel rather than of the whole one is the cutout. The
     * left 296 units of this display are camera glass: the pixels are live and nothing
     * drawn on them can be seen. A slider spanning 80% of 904 would begin underneath the
     * lenses, so the knob would start life invisible and the first third of the travel
     * would happen somewhere the user cannot watch it.
     */
    const val WIDTH_FRACTION = 0.8f

    /** The panel that can actually be seen and touched: everything right of the lenses. */
    const val USABLE_LEFT = Panel.CUTOUT_RIGHT.toFloat()
    const val USABLE_WIDTH = Panel.WIDTH - Panel.CUTOUT_RIGHT.toFloat()

    fun left(): Float = USABLE_LEFT + USABLE_WIDTH * (1f - WIDTH_FRACTION) / 2f
    fun right(): Float = left() + USABLE_WIDTH * WIDTH_FRACTION

    /**
     * How far in from the track's ends the knob's centre sits at rest and at the finish.
     *
     * The knob is a disc inside the track rather than a block on top of it, so its centre
     * can never reach the track's own edge — half of it would hang outside. Everything
     * about where the knob IS goes through [thumbCx], so this number is stated once.
     */
    fun thumbRadius(height: Float): Float = height / 2f - INSET

    /** Where the knob's centre is for a given progress, 0 at rest and 1 at the finish. */
    fun thumbCx(progress: Float, height: Float): Float {
        val r = thumbRadius(height)
        val from = left() + INSET + r
        val to = right() - INSET - r
        return from + (to - from) * progress.coerceIn(0f, 1f)
    }

    /** The travel available to the knob, in units. Zero-safe for absurd heights. */
    fun travel(height: Float): Float {
        val r = thumbRadius(height)
        return (right() - INSET - r) - (left() + INSET + r)
    }

    /**
     * Whether a touch at [x] counts as taking hold of the knob.
     *
     * Generous, and deliberately so — this is the one gesture standing between someone
     * and their own phone, and a grab region sized to the drawn disc would have them
     * stabbing at a 30-unit target on the back of a device they are holding. The
     * generosity is safe because it only decides where a drag may BEGIN; the length of
     * the travel is what makes the gesture hard to perform by accident, and that is
     * unaffected by how forgivingly it is picked up.
     *
     * Note what this does NOT allow: taking hold anywhere along the track. The knob has
     * to be picked up where it rests. Otherwise the gesture degrades into "touch the left
     * third, then touch the right", which is two taps, and cloth manages two taps.
     */
    fun grabs(x: Float, progress: Float, height: Float): Boolean =
        Math.abs(x - thumbCx(progress, height)) <= thumbRadius(height) + GRAB_SLOP

    /**
     * Where the knob should sit for a finger at [x], as progress 0..1.
     *
     * Absolute rather than relative: the knob goes under the finger and stays there. A
     * relative mapping would let someone start the drag, lift, and resume from wherever
     * the knob had got to, which is the "two taps" failure again in slow motion.
     */
    fun progressFor(x: Float, height: Float): Float {
        val t = travel(height)
        if (t <= 0.0001f) return 0f
        val r = thumbRadius(height)
        return ((x - (left() + INSET + r)) / t).coerceIn(0f, 1f)
    }

    /**
     * Whether letting go here unlocks the panel.
     *
     * Not an exact 1. The knob reaches the end while the finger is still travelling, and
     * asking for the last pixel means a gesture that visibly finished did not count —
     * which reads as the panel refusing rather than as the user missing. The margin is
     * small enough that nothing short of arriving satisfies it.
     */
    fun unlocks(progress: Float): Boolean = progress >= COMMIT

    /** How far in the knob sits from the track it runs in. */
    const val INSET = 5f

    /** Extra reach around the knob when picking it up. See [grabs]. */
    const val GRAB_SLOP = 34f

    /** How much of the travel counts as having arrived. */
    const val COMMIT = 0.97f

    /**
     * How long the knob takes to fall back after a drag that did not finish.
     *
     * Slower than it was pushed. A knob that snaps back the instant it is released reads
     * as a rejection; one that falls reads as a thing with weight that was not carried
     * far enough — which is the truth, and is also the instruction.
     */
    const val SPRING_MS = 260f
}
