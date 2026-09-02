package dev.kb896.aiusage.ui.glass

import android.view.MotionEvent
import android.view.View
import android.view.animation.AnimationUtils

/**
 * Drives a set of [Spring]s from the view's animation frames, and invalidates it.
 *
 * One ticker per control. It posts itself with postOnAnimation only while something is
 * still moving, so a control at rest costs nothing — which is the rule the whole front
 * screen's glass runs on.
 */
class Motion(private val view: View) {
    private val springs = ArrayList<Spring>()
    private var last = 0L
    private var running = false

    /** Runs after every frame's stepping, before the view is invalidated. A control uses it to react to a spring arriving. */
    var onFrame: (() -> Unit)? = null

    fun spring(stiffness: Float, dampingRatio: Float, threshold: Float, initial: Float): Spring =
        Spring(stiffness, dampingRatio, threshold, initial).also { springs += it }

    fun animate(spring: Spring, to: Float) { spring.target = to; start() }

    fun start() {
        if (running) return
        running = true
        last = 0L
        view.postOnAnimation(tick)
    }

    /** Advance every spring by [dt] seconds. True while any is still moving. Tests call this. */
    fun advance(dt: Float): Boolean {
        var moving = false
        for (s in springs) if (s.step(dt)) moving = true
        onFrame?.invoke()
        // The hook may have set new targets — a release after an arrival — so ask again.
        if (!moving) moving = springs.any { !it.settled }
        return moving
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = AnimationUtils.currentAnimationTimeMillis()
            val dt = if (last == 0L) 1f / 60f else (now - last) / 1000f
            last = now
            val moving = advance(dt)
            view.invalidate()
            if (moving) view.postOnAnimation(this) else running = false
        }
    }
}

/**
 * The press of a glass button: a highlight that lands under the finger, follows it, and
 * springs back on release. Ported from catalog/utils/InteractiveHighlight.kt (Apache 2.0):
 * progress spring(0.5, 300), position spring(0.5, 300).
 */
class PressSpring(view: View) {
    val motion = Motion(view)
    private val progressSpring = motion.spring(300f, 0.5f, 0.001f, 0f)
    private val xSpring = motion.spring(300f, 0.5f, 0.5f, 0f)
    private val ySpring = motion.spring(300f, 0.5f, 0.5f, 0f)
    private var startX = 0f
    private var startY = 0f

    val progress: Float get() = progressSpring.value
    val x: Float get() = xSpring.value
    val y: Float get() = ySpring.value

    fun press(x: Float, y: Float) {
        startX = x; startY = y
        xSpring.snap(x); ySpring.snap(y)
        motion.animate(progressSpring, 1f)
    }

    fun move(x: Float, y: Float) { xSpring.snap(x); ySpring.snap(y); motion.start() }

    fun release() {
        motion.animate(progressSpring, 0f)
        motion.animate(xSpring, startX)
        motion.animate(ySpring, startY)
    }

    /** Feed a view's touch stream. Returns false always: the caller still runs its own handling. */
    fun onTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> press(e.x, e.y)
            MotionEvent.ACTION_MOVE -> move(e.x, e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release()
        }
        return false
    }
}

/**
 * A value that is dragged, and a control that squashes while it is held.
 *
 * Ported from catalog/utils/DampedDragAnimation.kt (Apache 2.0). The springs are its:
 * value (1, 1000), velocity (0.5, 300), press (1, 1000), scaleX (0.6, 250), scaleY
 * (0.7, 250). Velocity is the value spring's own, normalised to the range, rather than a
 * pointer velocity tracker — same use, less machinery.
 */
class DampedDrag(
    view: View,
    initial: Float,
    val range: ClosedFloatingPointRange<Float>,
    threshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
) {
    val motion = Motion(view)
    private val valueSpring = motion.spring(1000f, 1f, threshold, initial)
    private val velocitySpring = motion.spring(300f, 0.5f, threshold * 10f, 0f)
    private val pressSpring = motion.spring(1000f, 1f, 0.001f, 0f)
    private val scaleXSpring = motion.spring(250f, 0.6f, 0.001f, initialScale)
    private val scaleYSpring = motion.spring(250f, 0.7f, 0.001f, initialScale)

    private var pendingRelease = false

    init {
        motion.onFrame = {
            if (pendingRelease && valueSpring.settled) { pendingRelease = false; release() }
        }
    }

    private val span get() = range.endInclusive - range.start

    val value: Float get() = valueSpring.value
    val progress: Float get() = if (span == 0f) 0f else (value - range.start) / span
    val targetValue: Float get() = valueSpring.target
    val pressProgress: Float get() = pressSpring.value
    val scaleX: Float get() = scaleXSpring.value
    val scaleY: Float get() = scaleYSpring.value
    val velocity: Float get() = velocitySpring.value

    fun press() {
        motion.animate(pressSpring, 1f)
        motion.animate(scaleXSpring, pressedScale)
        motion.animate(scaleYSpring, pressedScale)
    }

    fun release() {
        motion.animate(pressSpring, 0f)
        motion.animate(scaleXSpring, initialScale)
        motion.animate(scaleYSpring, initialScale)
    }

    fun updateValue(v: Float) {
        motion.animate(valueSpring, v.coerceIn(range))
        motion.animate(velocitySpring, if (span == 0f) 0f else valueSpring.velocity / span)
    }

    fun animateTo(v: Float) {
        // A tap is a press, a move, and a let-go; the let-go has to wait for the move.
        press()
        motion.animate(valueSpring, v.coerceIn(range))
        pendingRelease = true
    }

    fun snap(v: Float) { valueSpring.snap(v.coerceIn(range)) }
}
