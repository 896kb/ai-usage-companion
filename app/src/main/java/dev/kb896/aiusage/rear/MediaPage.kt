package dev.kb896.aiusage.rear

/**
 * Where everything sits on the media page — the second page, reached by swiping up.
 *
 * A page of its own rather than an expansion of the row, because the two are different
 * things: the row is a strip at the bottom of a readout, and this is the panel being
 * about the music instead. What travels between them is the record, which is why its
 * position here is expressed as a destination for the same disc rather than as a new one.
 *
 * Exists only under the vinyl style. Under a square cover there is no object to carry
 * across the transition and nothing this page would be a bigger view *of* — it would just
 * be a second copy of the row with more numbers on it.
 *
 * Pure, like MediaLayout, and for the same reason: this is arithmetic on a 904x572 panel
 * that nobody is looking at directly, where a number being wrong shows up as something
 * slightly off rather than as a crash.
 */
object MediaPage {

    /**
     * The record, once it has arrived here.
     *
     * Smaller and higher than on the readout page, where it is panel-height and mostly
     * off the bottom. Here the whole disc is on screen with the controls under it, which
     * is the shape of every record player ever made and the reason the disc had to shrink
     * to get here.
     *
     * Its size is DERIVED rather than fixed, from the space between whatever is above it
     * and the stack of type and buttons below — see [disc]. At rest that works out to the
     * numbers this used to hardcode.
     */
    const val DISC_MARGIN = 50f

    /** The top of the type stack: title, artist, transport, bar. Fixed. */
    const val STACK_TOP = 360f

    /** Between the bottom of the record and the top of that stack. */
    const val DISC_GAP = 18f

    /** Small enough to be a token, big enough to still read as a record. */
    const val DISC_MIN_RADIUS = 34f

    /**
     * The record's centre and radius, given whatever is occupying the top of the panel.
     *
     * [topLimit] is where this page may start drawing — normally 0, and the height of a
     * notification strip while one is up.
     *
     * THE RECORD SHRINKS; the controls do not move. A notification used to push this
     * whole page down, which is right on the readout page — everything there is a list
     * and a list can ride down — and wrong here, because the bottom of this page is the
     * transport buttons and pushing them down puts them off the panel. You cannot press
     * what has left the screen, so the one flexible thing on the page gives up the room
     * instead.
     */
    fun disc(topLimit: Float): Pair<Float, Float> {
        val top = topLimit + DISC_MARGIN
        val bottom = STACK_TOP - DISC_GAP
        val radius = ((bottom - top) / 2f).coerceAtLeast(DISC_MIN_RADIUS)
        return (top + radius) to radius
    }

    /** Where the record sits with nothing above it. */
    val DISC_CY: Float get() = disc(0f).first
    val DISC_RADIUS: Float get() = disc(0f).second

    /** Type and controls, stacked under the disc. Baselines and centre lines. */
    const val TITLE_BASELINE = 386f
    const val ARTIST_BASELINE = 414f
    const val TITLE_SIZE = 26f
    const val ARTIST_SIZE = 20f

    /** The transport row, centred under the type. Bigger than the readout row's: this
     *  page is the one you came to on purpose, and there is room. */
    const val TRANSPORT_CY = 468f
    const val TRANSPORT_H = 46f
    const val TRANSPORT_GAP = 58f

    /** The progress bar and its two clocks, along the bottom. */
    const val BAR_CY = 534f
    const val BAR_H = 5f
    const val CLOCK_SIZE = 17f

    /** Between a clock and the end of the bar it labels. */
    const val CLOCK_GAP = 12f

    /**
     * The volume control is the whole area above the transport row.
     *
     * There is no slider any more. A 22-unit column on the back of a phone was a target
     * you had to find before you could use it, which is the wrong shape for the one
     * control here that people reach for without looking. The area is: put a finger
     * anywhere above the buttons and move it.
     *
     * RELATIVE, not absolute — the volume moves by how far the finger travelled, from
     * wherever it already was. Absolute would mean a stray touch near the top slamming
     * the volume to maximum, on a surface nobody is looking at.
     */
    const val VOLUME_AREA_GAP = 18f

    fun volumeAreaBottom(): Float = TRANSPORT_CY - TRANSPORT_H / 2f - VOLUME_AREA_GAP

    /** How much of the range a full sweep of that area covers. */
    const val VOLUME_TRAVEL = 1f

    /** The mute button, beside the ring that now reports the volume. */
    const val MUTE_H = 26f
    const val MUTE_GAP = 16f

    /**
     * The label curved inside a ring, and how big it is.
     *
     * INSIDE, at a radius short of the ring itself. Outside is off the panel at the top
     * and off the bottom edge below, and the gap between the two rings is twenty units —
     * the only room these labels have is the space the rings enclose.
     */
    const val LABEL_RADIUS = 88f
    const val LABEL_SIZE = 15f

    /** What the volume ring is called. Named, because a bare arc around a lens is not
     *  self-describing the way a bar under a track name is. */
    const val VOLUME_LABEL = "VOLUME"

    /**
     * The volume arc: two o'clock to five o'clock on the ring's face.
     *
     * A SEGMENT rather than a whole circle, and it earns the difference. Playback fills a
     * ring because a track has a beginning and an end and going all the way round means
     * finished; volume has neither, and a full circle for it invited the same reading —
     * a ring at three quarters looked like three quarters of something completed.
     *
     * Down the right-hand side, where it lands beside the mute button rather than
     * opposite it. In Android's angles zero is three o'clock and they run clockwise, so
     * an hour on the clock face is thirty degrees and two o'clock is minus thirty.
     */
    const val VOLUME_ARC_START = -30f
    const val VOLUME_ARC_SWEEP = 90f

    /**
     * Where the mute glyph stops showing waves.
     *
     * One threshold, not a scale. The icon is a three-state readable-from-across-a-room
     * summary — silent, some, plenty — and a fourth state would be a distinction nobody
     * can make out at 26 units.
     */
    const val VOLUME_LOUD = 0.5f

    /**
     * The horizontal centre of this page, and the margins its full-width parts keep.
     *
     * Centred on the USABLE half of the panel for the same reason the record is: the left
     * 296 units are camera glass with no display behind them.
     */
    fun centreX(panelWidth: Float, cutoutRight: Float): Float =
        (cutoutRight + panelWidth) / 2f

    /** The progress bar's span, inside the margins, leaving room for both clocks. */
    fun barLeft(cutoutRight: Float, clockWidth: Float): Float =
        cutoutRight + MARGIN + clockWidth + CLOCK_GAP

    fun barRight(panelWidth: Float, clockWidth: Float): Float =
        panelWidth - MARGIN - clockWidth - CLOCK_GAP


    /**
     * A duration as a clock, dropping the hours when there are none.
     *
     * "3:45" rather than "0:03:45". A leading zero-hour is noise on the overwhelming
     * majority of tracks, and the field is not fixed-width — it is right- or left-aligned
     * against a bar, not tabulated against anything.
     */
    fun clock(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val seconds = total % 60L
        val minutes = (total / 60L) % 60L
        val hours = total / 3600L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    /** The widest clock a track of this length can produce, in characters. */
    fun clockWidth(durationMs: Long): Int = clock(durationMs).length

    const val MARGIN = 34f
}
