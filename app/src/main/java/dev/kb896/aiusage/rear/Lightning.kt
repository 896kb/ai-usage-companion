package dev.kb896.aiusage.rear

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * The storm's lightning: how it changes the light on the glass at a moment — 0 none, 1 the
 * scene twice as bright, below 0 a little darker (to -0.35).
 *
 * THE LOOK OF A STORM SEEN THROUGH A WINDOW, after rocksdanister's Rain wallpaper, in our own
 * maths: the light is MULTIPLIED, so what is bright flares and what is dark stays dark, and
 * a strike is not a pulse but a stutter — a fast, irregular flicker inside a swell, dipping a
 * little below the ordinary light between its flashes.
 *
 * About every seven seconds and never on a beat: each six-second stretch has a strike at a
 * random moment in it, most of the time, so gaps run from next to nothing to over ten.
 *
 * A pure function of the clock, so the back and the preview strike together and nothing has
 * to remember when the last one was.
 */
object Lightning {

    private const val SLOT_MS = 6_000L
    /** How many stretches have a strike in them: with SLOT_MS, about one every seven seconds. */
    private const val CHANCE = 0.85f
    /** How dark a flicker's trough goes. */
    private const val DIP = 0.35f

    fun flash(nowMs: Long): Float {
        val slot = Math.floorDiv(nowMs, SLOT_MS)
        // This stretch's strike, or the last one's still flickering across the boundary.
        val a = strike(slot, nowMs)
        val b = strike(slot - 1, nowMs)
        return (if (Math.abs(a) >= Math.abs(b)) a else b).coerceIn(-DIP, 1f)
    }

    private fun strike(slot: Long, nowMs: Long): Float {
        if (unit(slot, 1) >= CHANCE) return 0f
        val length = 700f + unit(slot, 2) * 500f
        val at = slot * SLOT_MS + (unit(slot, 3) * (SLOT_MS - 1_300L)).toLong()
        val d = (nowMs - at).toFloat()
        if (d < 0f || d > length) return 0f
        // The swell: nothing at either end, full in the middle, steep-sided.
        val swell = max(0.0, sin(PI * d / length)).pow(4.0).toFloat()
        // The stutter: a flicker whose own speed wanders, so no two strikes stutter alike.
        val s = d / 1000f
        val phase = unit(slot, 4) * 6.2832f
        val wander = 2.5f + unit(slot, 5) * 2f
        val flicker = sin(34f * s + wander * sin(41f * s + phase) + phase)
        return swell * (if (flicker >= 0f) flicker else flicker * DIP)
    }

    /** A number in [0, 1) for this stretch and [salt], the same every time it is asked. */
    private fun unit(slot: Long, salt: Int): Float {
        var x = slot * 0x9E3779B97F4A7C15uL.toLong() + salt * 0xBF58476D1CE4E5B9uL.toLong()
        x = (x xor (x ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
        x = (x xor (x ushr 27)) * 0x94D049BB133111EBuL.toLong()
        x = x xor (x ushr 31)
        return ((x ushr 40).toFloat() / (1L shl 24).toFloat())
    }
}
