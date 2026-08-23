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
 * BUILT AS A TWO-STOP TAB BAR, which is where the shape comes from. The reference's
 * bottom tabs are a capsule container with a padded capsule indicator that slides between
 * positions; this is that component with two positions — where the thumb rests and where
 * it has to arrive. So the thumb is half the track rather than a small knob, which suits
 * the gesture: a wide slab pushed across is a deliberate act, and a small knob at the end
 * of a long empty groove looks like something to flick.
 */
object LockSlider {

    /**
     * How much of the usable panel the slider spans.
     *
     * Eight tenths, centred, at the user's request — and the reason it is worth stating
     * as a fraction of the USABLE panel rather than of the whole one is the cutout. The
     * left 296 units of this display are camera glass: the pixels are live and nothing
     * drawn on them can be seen. A slider spanning 80% of 904 would begin underneath the
     * lenses, so the thumb would start life invisible and the first third of the travel
     * would happen somewhere the user cannot watch it.
     */
    const val WIDTH_FRACTION = 0.8f

    /** The panel that can actually be seen and touched: everything right of the lenses. */
    const val USABLE_LEFT = Panel.CUTOUT_RIGHT.toFloat()
    const val USABLE_WIDTH = Panel.WIDTH - Panel.CUTOUT_RIGHT.toFloat()

    fun left(): Float = USABLE_LEFT + USABLE_WIDTH * (1f - WIDTH_FRACTION) / 2f
    fun right(): Float = left() + USABLE_WIDTH * WIDTH_FRACTION

    /**
     * How far the indicator is held inside the container, on every side.
     *
     * The reference's tab bar is a 64dp capsule with 4dp of padding around a 56dp
     * indicator — the container's own glass stays visible all the way round, which is
     * what makes the indicator look like it is INSIDE something rather than covering it.
     */
    const val PAD = 5f

    /** The indicator's height, given the band it lives in. */
    fun thumbHeight(bandHeight: Float): Float = bandHeight - PAD * 2f

    /**
     * The indicator's width: half the track's usable span, because there are two stops.
     *
     * Two stops rather than a scale of them, and that is the whole difference from a tab
     * bar: this control has one journey with a beginning and an end, not a row of places
     * to be. Half the track each.
     */
    fun thumbWidth(): Float = (right() - left() - PAD * 2f) / 2f

    /** The indicator's left edge for a given progress, 0 at rest and 1 arrived. */
    fun thumbLeft(progress: Float): Float =
        left() + PAD + thumbWidth() * progress.coerceIn(0f, 1f)

    /** How far the indicator travels, which is exactly its own width. */
    fun travel(): Float = thumbWidth()

    /**
     * Whether a touch at [x] counts as taking hold of the indicator.
     *
     * Note what this does NOT allow: taking hold anywhere along the track. The indicator
     * has to be picked up where it is. Otherwise the gesture degrades into "touch the
     * left half, then touch the right", which is two taps, and cloth manages two taps.
     */
    fun grabs(x: Float, progress: Float): Boolean {
        val l = thumbLeft(progress)
        return x >= l - GRAB_SLOP && x <= l + thumbWidth() + GRAB_SLOP
    }

    /**
     * Where the indicator should sit for a finger at [x], as progress 0..1.
     *
     * Measured from where the finger GRABBED rather than from the panel, so the
     * indicator does not jump to centre itself under the thumb on the first move. The
     * caller passes the progress the gesture started at and the distance travelled.
     */
    fun progressFrom(startProgress: Float, dx: Float): Float {
        val t = travel()
        if (t <= 0.0001f) return 0f
        return (startProgress + dx / t).coerceIn(0f, 1f)
    }

    /**
     * Whether letting go here unlocks the panel.
     *
     * Not an exact 1. The indicator reaches the end while the finger is still travelling,
     * and asking for the last pixel means a gesture that visibly finished did not count —
     * which reads as the panel refusing rather than as the user missing.
     */
    fun unlocks(progress: Float): Boolean = progress >= COMMIT

    /** Extra reach around the indicator when picking it up. See [grabs]. */
    const val GRAB_SLOP = 24f

    /** How much of the travel counts as having arrived. */
    const val COMMIT = 0.97f

    /**
     * How long the indicator takes to fall back after a drag that did not finish.
     *
     * Slower than it was pushed. One that snaps back the instant it is released reads as
     * a rejection; one that falls reads as a thing with weight that was not carried far
     * enough — which is the truth, and is also the instruction.
     */
    const val SPRING_MS = 260f
}
