package dev.kb896.aiusage.rear

/**
 * Where a slider's thumb sits for a given reading.
 *
 * THE THUMB IS CENTRED ON THE VALUE, not packed inside the track. That distinction is
 * the whole of this file and it was got wrong first time here: pinning the thumb between
 * the track's ends means its centre only ever travels from half a thumb in to half a
 * thumb short, so every reading is drawn somewhere it is not. A bar at nothing shows its
 * thumb already advanced; a bar at everything shows it stopping short. The error is
 * largest exactly where it is most obvious, at the two ends.
 *
 * The reference component says it in one line — thumb left is `-width/2 + trackWidth *
 * progress`, which is the centre on the value — and then clamps it, which is the second
 * half of the idea. A thumb centred on zero hangs half of itself off the left of the
 * track, which reads as a control coming apart. Clamped to a QUARTER of its width, it
 * overhangs enough to say "this is the end" and not so much as to leave the instrument.
 */
object SliderThumb {

    /**
     * The thumb's left edge, in the track's own coordinates.
     *
     * [trackWidth] is the full span the reading is measured across and [thumbWidth] the
     * thumb's own. Progress outside 0..1 is treated as its nearest end, so a value that
     * has not settled cannot throw the thumb off the panel.
     */
    fun left(trackWidth: Float, thumbWidth: Float, progress: Float): Float {
        val centred = -thumbWidth / 2f + trackWidth * progress.coerceIn(0f, 1f)
        val lo = -thumbWidth * OVERHANG
        val hi = trackWidth - thumbWidth * (1f - OVERHANG)
        // A THUMB WIDER THAN ITS OWN TRACK inverts that range, and coerceIn THROWS on an
        // empty one rather than picking a side — which would be a crash on the frame a
        // ring's arc got short enough, not a rounding artefact. There is no travel to
        // speak of in that case, so it sits in the middle and stays there.
        if (hi < lo) return (trackWidth - thumbWidth) / 2f
        return centred.coerceIn(lo, hi)
    }

    /** The thumb's centre, which is what the reading actually points at. */
    fun centre(trackWidth: Float, thumbWidth: Float, progress: Float): Float =
        left(trackWidth, thumbWidth, progress) + thumbWidth / 2f

    /**
     * How far a thumb may hang off each end of its track, as a fraction of its width.
     *
     * A quarter, which is the reference's own clamp: it allows the left edge down to
     * `-width/4` and the right edge up to `trackWidth + width/4`. Less and the ends stop
     * reading as ends; more and the thumb looks like it has come off.
     */
    const val OVERHANG = 0.25f
}
