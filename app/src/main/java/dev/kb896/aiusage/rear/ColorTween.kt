package dev.kb896.aiusage.rear

/**
 * One animated colour, eased between values exactly as [Tween] eases numbers.
 *
 * Colours were the one thing on the panel that still changed by snapping. A bar's value
 * eased over 1.8s while its colour cut instantly, which reads as two unrelated events
 * rather than one — and when a setting is being dragged in the app, an instant repaint
 * makes the panel look like it flickered rather than responded.
 *
 * Not internal: the settings previews use it too, so a colour transition shown in the
 * app is the same transition the panel performs.
 *
 * Kept separate from Tween rather than made generic: Tween's whole job is arithmetic on
 * one float, and a colour is four channels that must be interpolated together and
 * rounded per channel. Sharing the timing is what matters, and that is one constant.
 */
class ColorTween {

    /** NaN-equivalent: no colour yet, so the first value is adopted rather than eased
     *  from black — every panel respawn would otherwise fade in from nothing. */
    private var from = 0
    private var to = 0
    private var startMs = 0L
    private var seeded = false

    var value = 0
        private set

    fun advance(target: Int, nowMs: Long): Int {
        if (!seeded) {
            seeded = true
            from = target; to = target; value = target
            return value
        }
        if (target != to) {
            from = value        // retarget from where it IS, so a change mid-fade continues
            to = target
            startMs = nowMs
        }
        if (from == to) { value = to; return value }
        val t = ((nowMs - startMs).toFloat() / Tween.DURATION_MS).coerceIn(0f, 1f)
        value = RampColor.lerp(from, to, Tween.easeInOut(t))
        return value
    }

    fun settled(nowMs: Long): Boolean = from == to || nowMs - startMs >= Tween.DURATION_MS
}
