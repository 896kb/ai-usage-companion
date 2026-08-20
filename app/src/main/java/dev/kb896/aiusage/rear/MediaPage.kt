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
     * The volume column, standing to the right of the record.
     *
     * Vertical, because it is the one control here that is not a transport button and
     * should not read as one — and because the space to the right of a disc is a tall
     * thin gap, which is the shape a vertical bar wants.
     */
    const val VOLUME_W = 22f

    /** How far inside the record's own span the column starts. */
    const val VOLUME_INSET = 10f

    /** The cap under the column: a speaker whose waves follow the level. */
    const val VOLUME_ICON_H = 26f
    const val VOLUME_ICON_GAP = 20f

    /**
     * The column's top and bottom, tied to the record's.
     *
     * Tied rather than fixed, so it shrinks with the disc when a notification takes the
     * top of the panel — a full-height column beside a shrunken record would look like
     * the record had fallen over rather than made room. The cap sits in the space below
     * the column, inside the record's own span.
     */
    fun volumeSpan(discCy: Float, discRadius: Float): Pair<Float, Float> {
        val top = discCy - discRadius + VOLUME_INSET
        val bottom = discCy + discRadius - VOLUME_ICON_H - VOLUME_ICON_GAP
        return top to bottom.coerceAtLeast(top + VOLUME_W)
    }

    /**
     * Where the level stops being "low" and starts being "high".
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
     * Where the volume column stands: measured from the panel's right edge.
     *
     * From the EDGE rather than from the record, which is what it used to be. Tying it to
     * the disc walked the column inward every time a notification shrank the disc — a
     * control that moves under the finger because something unrelated arrived is worse
     * than one that stays put. Measuring from the edge also puts it where a volume
     * control belongs: out of the way, against the side.
     */
    fun volumeLeft(panelWidth: Float): Float = panelWidth - VOLUME_MARGIN - VOLUME_W

    /** Half the gap it used to keep. It is a control, not a column of type, and it does
     *  not need the margin the text on this page keeps. */
    const val VOLUME_MARGIN = 45f

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
