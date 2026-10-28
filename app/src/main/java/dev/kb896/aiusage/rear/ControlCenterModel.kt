package dev.kb896.aiusage.rear

/**
 * The control centre the shell holds over a guest (ShellControlCenter), as a state: how far
 * it is pulled, the brightness and the figure, the automatic button and the four tiles —
 * driven by the top-edge pull (EdgeGestures' TopPull*), the fingers on it once it is shown
 * (ControlCenterFinger), the brightness as read and the tiles' states as the app reports
 * them. It behaves as the panel's own (RearDialView's control centre: controlCenterTouch,
 * controlCenterProgress, brightShown, autoShownNow, quickShown, brightFigureGrow), on the
 * same numbers (ControlCenter); what the shell must then do comes back as [Effect]s.
 *
 * Pure: every call takes the time ([atMs], [nowMs]: SystemClock.uptimeMillis). Not
 * thread-safe; the shell holds a lock around it.
 */
class ControlCenterModel {

    sealed interface Effect {
        /** Write the rear brightness, 1 to 255 (which puts it on manual). */
        data class Brightness(val value: Int) : Effect
        /** Switch automatic brightness. */
        data class Auto(val on: Boolean) : Effect
        /** Switch a tile, by its name (ControlCenterPainter.QUICK_NAMES) — the app's to do. */
        data class Quick(val which: String, val on: Boolean) : Effect
        /** It is settling shut: at [atMs] it is down, and its layers may go ([isShut] says for sure). */
        data class ShutAt(val atMs: Long) : Effect
    }

    // ---- how far down ----

    /** Open, or settling there. */
    private var open = false
    /** A finger has it (the opening pull, or a drag on it), [dy] from where it landed. */
    private var dragging = false
    private var dy = 0f
    /** A settle: from, to, since when (0: none), for how long. */
    private var from = 0f
    private var to = 0f
    private var settleAt = 0L
    private var settleMs = ControlCenter.SETTLE_MAX_MS

    // ---- the opening pull ----

    private var pulling = false
    private var pullDownY = 0f
    private var velocity = 0f
    private var lastY = 0f
    private var lastAt = 0L

    // ---- the brightness ----

    /** The level, 0 to 1; -1 until read. */
    private var level = -1f
    private var unavailable = false
    private var brightDragging = false
    private var brightFrom = 0f
    private var changedAt = 0L
    private var sentAt = 0L
    /** The pour from the last level shown to a new reading. */
    private var shownFrom = -1f
    private var shownAt = 0L
    /** The figure's growth in and out. */
    private var growFrom = 0f
    private var growTo = 0f
    private var growAt = 0L

    // ---- the button and the tiles ----

    private val auto = Switch()
    private val tiles = Array(4) { Switch() }
    private var reachable = false
    private var pressed = ControlCenterLayout.NONE

    /** On or off, and its colour easing between them over ControlCenter.SWITCH_MS. */
    private class Switch {
        var on = false
            private set
        private var from = 0f
        private var at = 0L
        fun set(value: Boolean, nowMs: Long) {
            if (on == value) return
            from = shown(nowMs); on = value; at = nowMs
        }
        fun shown(nowMs: Long): Float {
            val to = if (on) 1f else 0f
            if (at == 0L) return to
            val t = ((nowMs - at) / ControlCenter.SWITCH_MS).coerceIn(0f, 1f)
            return from + (to - from) * Tween.easeInOut(t)
        }
    }

    /** How far down it is now, 0 to 1: under a finger, settling, or at rest. */
    fun progress(nowMs: Long): Float {
        if (dragging) return ControlCenter.progress(open, dy)
        if (settleAt == 0L) return to
        val t = ((nowMs - settleAt) / settleMs.toFloat()).coerceIn(0f, 1f)
        // EASED OUT: it leaves at the finger's speed and slows into place — see settleMs.
        val e = 1f - (1f - t) * (1f - t)
        return from + (to - from) * e
    }

    /** Whether a finger landing now is the control centre's: it is down at all, or being pulled. */
    fun shown(nowMs: Long): Boolean = pulling || dragging || progress(nowMs) > 0.01f

    /** Shut, settled, and nothing in hand: its layers can go. */
    fun isShut(nowMs: Long): Boolean = !pulling && !dragging && !open && progress(nowMs) <= 0f

    /** Shut at once, nothing in hand (the rear went dark, the service is going). */
    fun reset() {
        open = false; dragging = false; pulling = false; dy = 0f
        from = 0f; to = 0f; settleAt = 0L
        brightDragging = false
        pressed = ControlCenterLayout.NONE
    }

    // ---- the pull from the top edge, through EdgeGestures ----

    /** A pull from the top edge, landed at [downY], is under way, the finger at [y]. */
    fun beginPull(downY: Float, y: Float, atMs: Long) {
        pulling = true; dragging = true; open = false
        settleAt = 0L; from = 0f; to = 0f
        pullDownY = downY
        dy = y - downY
        velocity = 0f; lastY = y; lastAt = atMs
        pressed = ControlCenterLayout.NONE
    }

    fun movePull(y: Float, atMs: Long) {
        if (!pulling) return
        dy = y - pullDownY
        track(y, atMs)
    }

    /** The pull let go at [y]: open past halfway or on a flick down, else back shut. */
    fun endPull(y: Float, atMs: Long): List<Effect> {
        if (!pulling) return emptyList()
        pulling = false
        dy = y - pullDownY
        val v = if (atMs - lastAt > ControlCenterFinger.STILL_MS) 0f else velocity
        val at = ControlCenter.progress(false, dy)
        dragging = false
        return settle(ControlCenter.release(false, dy, v), at, v, atMs)
    }

    private fun track(y: Float, atMs: Long) {
        val ms = (atMs - lastAt).coerceAtLeast(1L)
        velocity = 0.6f * ((y - lastY) / ms) + 0.4f * velocity
        lastY = y; lastAt = atMs
    }

    /** Go [toOpen] from [at], carrying on at [v] (down positive) if it was moving that way. */
    private fun settle(toOpen: Boolean, at: Float, v: Float, atMs: Long): List<Effect> {
        open = toOpen
        from = at
        to = if (toOpen) 1f else 0f
        val toward = if (toOpen) v else -v
        settleMs = ControlCenter.settleMs(Math.abs(to - at) * ControlCenter.TRAVEL, toward)
        settleAt = atMs
        return if (toOpen) emptyList() else listOf(Effect.ShutAt(atMs + settleMs))
    }

    // ---- a finger on it, once shown ----

    fun finger(a: ControlCenterFinger.Action, atMs: Long): List<Effect> = when (a) {
        is ControlCenterFinger.Action.Down -> {
            // From wherever a settle had got to: on to where it was going.
            if (settleAt != 0L) { open = progress(atMs) > 0.5f; settleAt = 0L; to = if (open) 1f else 0f }
            pressed = if (a.target == ControlCenterLayout.AUTO || a.target in 0 until 4) a.target else ControlCenterLayout.NONE
            emptyList()
        }
        is ControlCenterFinger.Action.Pull -> {
            pressed = ControlCenterLayout.NONE
            dragging = true
            dy = a.dy
            emptyList()
        }
        is ControlCenterFinger.Action.Release -> {
            // Let go: past halfway it goes. Taken away (lost): it stays as it was.
            val at = ControlCenter.progress(open, a.dy)
            val toOpen = if (a.lifted) ControlCenter.release(open, a.dy, a.velocity) else open
            dragging = false
            dy = 0f
            settle(toOpen, at, if (a.lifted) a.velocity else 0f, atMs)
        }
        is ControlCenterFinger.Action.Bright -> bright(a.dy, atMs)
        ControlCenterFinger.Action.BrightEnd -> {
            pressed = ControlCenterLayout.NONE
            val was = brightDragging
            brightDragging = false
            changedAt = atMs
            if (was && !unavailable && level >= 0f) listOf(Effect.Brightness(ControlCenter.setting(level))) else emptyList()
        }
        is ControlCenterFinger.Action.Tap -> {
            pressed = ControlCenterLayout.NONE
            tap(a.target, atMs)
        }
        ControlCenterFinger.Action.Back -> {
            pressed = ControlCenterLayout.NONE
            settle(false, progress(atMs), 0f, atMs)
        }
        ControlCenterFinger.Action.Cancel -> {
            pressed = ControlCenterLayout.NONE
            emptyList()
        }
    }

    private fun bright(d: Float, atMs: Long): List<Effect> {
        pressed = ControlCenterLayout.NONE
        if (!brightDragging) {
            brightDragging = true
            brightFrom = level.coerceAtLeast(ControlCenter.MIN_LEVEL)
            // Set by hand: no longer automatic (the service puts it on manual).
            auto.set(false, atMs)
        }
        changedAt = atMs
        if (unavailable) return emptyList()
        level = ControlCenter.drag(brightFrom, d)
        shownFrom = level; shownAt = 0L
        if (atMs - sentAt < ControlCenter.SEND_MS) return emptyList()
        sentAt = atMs
        return listOf(Effect.Brightness(ControlCenter.setting(level)))
    }

    private fun tap(target: Int, atMs: Long): List<Effect> {
        // Off the controls a pull that never moved springs back shut; open, it stays.
        if (!open) settle(false, progress(atMs), 0f, atMs)
        return when (target) {
            ControlCenterLayout.CELL -> { changedAt = atMs; emptyList() }
            ControlCenterLayout.AUTO -> {
                if (unavailable) emptyList() else {
                    auto.set(!auto.on, atMs)
                    listOf(Effect.Auto(auto.on))
                }
            }
            in 0 until 4 -> {
                // Silent is the app's own; the others need Shizuku.
                if (target != 0 && !reachable) emptyList() else {
                    val on = !tiles[target].on
                    tiles[target].set(on, atMs)
                    listOf(Effect.Quick(ControlCenterPainter.QUICK_NAMES[target], on))
                }
            }
            else -> emptyList()
        }
    }

    // ---- what the phone says ----

    /** The rear brightness as read, 1 to 255 (anything else: it cannot be), and whether it is automatic. */
    fun reading(value: Int, auto: Boolean, nowMs: Long) {
        if (!brightDragging) this.auto.set(auto, nowMs)
        if (value in 1..255) {
            if (!brightDragging) {
                // From wherever the cell is showing now, to the new reading.
                val now = if (level < 0f) -1f else shownLevel(nowMs)
                level = ControlCenter.level(value)
                if (now >= 0f && Math.abs(now - level) > 0.005f) { shownFrom = now; shownAt = nowMs }
            }
            unavailable = false
        } else {
            unavailable = true
            if (level < 0f) level = 0.5f
        }
    }

    /** The tiles as the app reports them: silent, Bluetooth, mobile data, Wi-Fi; and whether the last three can be switched. */
    fun quickStates(states: BooleanArray, reachable: Boolean, nowMs: Long) {
        this.reachable = reachable
        for (i in 0 until 4) tiles[i].set(states.getOrElse(i) { false }, nowMs)
    }

    // ---- what is drawn ----

    /** The level the cell shows: the finger's while it drags; otherwise pouring to the last reading. */
    private fun shownLevel(nowMs: Long): Float {
        val target = level.coerceAtLeast(ControlCenter.MIN_LEVEL)
        if (brightDragging || shownFrom < 0f) { shownFrom = target; shownAt = 0L; return target }
        if (shownAt == 0L) return target
        val t = ((nowMs - shownAt) / ControlCenter.POUR_MS).coerceIn(0f, 1f)
        if (t >= 1f) { shownFrom = target; shownAt = 0L; return target }
        return shownFrom + (target - shownFrom) * Tween.easeInOut(t)
    }

    /** How far the figure is grown in: in while it changes, out two seconds after. */
    private fun figureGrow(nowMs: Long): Float {
        val want = if (ControlCenter.figureShown(nowMs, changedAt)) 1f else 0f
        val t0 = ((nowMs - growAt) / ChargeOverlay.SLIDE_MS).coerceIn(0f, 1f)
        val current = growFrom + (growTo - growFrom) * Tween.easeInOut(t0)
        if (want != growTo) { growFrom = current; growTo = want; growAt = nowMs }
        val t = ((nowMs - growAt) / ChargeOverlay.SLIDE_MS).coerceIn(0f, 1f)
        return growFrom + (growTo - growFrom) * Tween.easeInOut(t)
    }

    /** The control centre as it is to be drawn at [nowMs]. */
    fun look(nowMs: Long): ControlCenterPainter.Look = ControlCenterPainter.Look(
        level = shownLevel(nowMs),
        figureGrow = figureGrow(nowMs),
        unavailable = unavailable,
        auto = if (unavailable) 0f else auto.shown(nowMs),
        tiles = FloatArray(4) { i -> if (i == 0 || reachable) tiles[i].shown(nowMs) else 0f },
        reachable = reachable,
        pressed = pressed,
    )
}
