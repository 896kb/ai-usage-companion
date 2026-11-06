package dev.kb896.aiusage.rear

import dev.kb896.aiusage.Panel
import kotlin.math.abs

/**
 * The notifications as a pull-down list, over a guest or the panel: pulled from the top edge's right half, as
 * the control centre is from its left (ControlCenter). Swiped sideways, a row is dismissed: it fades
 * as it goes, and the rows under it rise into its place under the finger already. Dragged up, the
 * list scrolls and, once it can't, shuts; from the bottom edge it shuts at once, however long the
 * list; a tap beside the rows shuts it too.
 *
 * Pure: times are handed in, and what it means is said by [Effect]s. In panel units. Not
 * thread-safe: ShellNotices holds the lock.
 *
 * @param left where the usable area starts (RearGeometry.cutoutUnits): the rows stand right of it.
 */
class NoticeShade(private val left: Float) {

    data class Item(val key: String, val pkg: String, val app: String, val title: String, val text: String)

    sealed interface Effect {
        data class Dismissed(val key: String) : Effect
        /** It will be settled shut at [atMs]. */
        data class ShutAt(val atMs: Long) : Effect
        /** All of [keys] dismissed at once, by the clear button; the last of them gone at [doneAtMs]. */
        data class ClearedAll(val keys: List<String>, val doneAtMs: Long) : Effect
    }

    /** The clear button as it stands: a circle of [size] at [cx], [cy]. */
    class Clear(val cx: Float, val cy: Float, val size: Float, val alpha: Float)

    /** One row as it stands now. */
    class Row(val item: Item, val top: Float, val height: Float, val dx: Float, val alpha: Float)

    /** On its way out: swiped, flying sideways from [fromDx]; or [up], cleared, sliding off to the right from [atMs] in its turn. */
    private class Leaving(val fromDx: Float, val dir: Float, val atMs: Long, val up: Boolean = false)

    private var items: List<Item> = emptyList()
    private val leaving = HashMap<String, Leaving>()

    private var pulling = false
    private var pullFrom = 0f
    private var pullAt = 0f
    private var lastPullY = 0f
    private var lastPullMs = 0L
    private var pullSpeed = 0f

    private var settleFrom = 0f
    private var settleTo = 0f
    private var settleAt = 0L

    private var scroll = 0f
    /** How far a drag up past the list's end has taken it towards shut. */
    private var closing = 0f

    private enum class Finger { NONE, UNDECIDED, SWIPE, VERTICAL }
    private var finger = Finger.NONE
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var lastMs = 0L
    private var speed = 0f
    private var fingerRow: String? = null
    /** The finger landed on the bottom edge: dragged up, it shuts the list rather than scroll it. */
    private var fromBottom = false
    /** The finger landed on the clear button. */
    private var onClear = false
    /** When the clear button was last pressed, 0 for not since it opened: the button and the empty line stay away after. */
    private var clearedAt = 0L
    private var swipeKey: String? = null
    private var swipeDx = 0f
    private var springKey: String? = null
    private var springFrom = 0f
    private var springAt = 0L

    /** The list as the app has it now. A row on its way out stays where it was until it has gone. */
    fun items(next: List<Item>) {
        val out = items.withIndex().filter { it.value.key in leaving }
        val merged = next.filter { it.key !in leaving }.toMutableList()
        for ((i, item) in out) merged.add(i.coerceAtMost(merged.size), item)
        items = merged
        if (swipeKey != null && merged.none { it.key == swipeKey }) { swipeKey = null; swipeDx = 0f }
        if (fingerRow != null && merged.none { it.key == fingerRow }) fingerRow = null
        scroll = scroll.coerceIn(0f, maxScroll())
    }

    val count: Int get() = items.count { it.key !in leaving }

    fun beginPull(downY: Float, y: Float, now: Long) {
        pulling = true
        pullFrom = downY
        pullAt = y
        lastPullY = y
        lastPullMs = now
        pullSpeed = 0f
        settleTo = 0f
        scroll = 0f
        closing = 0f
    }

    fun movePull(y: Float, now: Long) {
        if (!pulling) return
        pullAt = y
        if (now > lastPullMs) pullSpeed = (y - lastPullY) / (now - lastPullMs)
        lastPullY = y
        lastPullMs = now
    }

    fun endPull(y: Float, now: Long): List<Effect> {
        if (!pulling) return emptyList()
        movePull(y, now)
        pulling = false
        val from = ControlCenter.progress(false, pullAt - pullFrom)
        return settle(from, ControlCenter.release(false, pullAt - pullFrom, pullSpeed), now)
    }

    /** Shut, by its settle. */
    fun close(now: Long): List<Effect> = settle(progress(now), false, now)

    fun reset() {
        pulling = false
        settleFrom = 0f; settleTo = 0f; settleAt = 0L
        scroll = 0f; closing = 0f
        finger = Finger.NONE
        fingerRow = null; swipeKey = null; swipeDx = 0f; springKey = null
        onClear = false; clearedAt = 0L
        items = items.filter { it.key !in leaving }
        leaving.clear()
    }

    private fun settle(from: Float, open: Boolean, now: Long): List<Effect> {
        settleFrom = from
        settleTo = if (open) 1f else 0f
        settleAt = now
        closing = 0f
        return if (open) emptyList() else listOf(Effect.ShutAt(now + SETTLE_MS))
    }

    /** How far open, 0 to 1. */
    fun progress(now: Long): Float {
        if (pulling) return ControlCenter.progress(false, pullAt - pullFrom)
        if (closing > 0f) return ControlCenter.progress(true, -closing)
        val t = ((now - settleAt).toFloat() / SETTLE_MS).coerceIn(0f, 1f)
        val eased = 1f - (1f - t) * (1f - t) * (1f - t)
        return settleFrom + (settleTo - settleFrom) * eased
    }

    /**
     * Open or being pulled: a finger landing now is its own. Not while it settles shut: a finger
     * held through that would still be down when it got there, and the check that takes the
     * layers away would find it not shut yet and never look again, leaving the blocker over a
     * panel nobody could then touch.
     */
    fun shown(now: Long): Boolean = pulling || (settleTo > 0f && progress(now) > 0f)

    fun isShut(now: Long): Boolean = !pulling && finger == Finger.NONE && progress(now) <= 0f

    /** Something still moves of itself. */
    fun moving(now: Long): Boolean =
        pulling || now - settleAt < SETTLE_MS || leaving.isNotEmpty() ||
            (springKey != null && now - springAt < SPRING_MS)

    fun fingerDown(x: Float, y: Float, now: Long) {
        finger = Finger.UNDECIDED
        downX = x; downY = y; lastY = y; lastMs = now; speed = 0f
        fromBottom = y >= Panel.HEIGHT - BOTTOM_EDGE
        onClear = clear(now)?.let { c ->
            val r = c.size / 2f + CLEAR_SLOP
            (x - c.cx) * (x - c.cx) + (y - c.cy) * (y - c.cy) <= r * r
        } ?: false
        if (onClear) fingerRow = null
        fingerRow = if (fromBottom) null else rows(now).firstOrNull { x >= rowLeft && x <= RIGHT && y >= it.top && y <= it.top + it.height }?.item?.key
    }

    fun fingerMove(x: Float, y: Float, now: Long) {
        if (finger == Finger.UNDECIDED) {
            val dx = abs(x - downX)
            val dy = abs(y - downY)
            if (maxOf(dx, dy) < EdgeGestures.SLOP) return
            finger = if (dx > dy && fingerRow != null) Finger.SWIPE else Finger.VERTICAL
            if (finger == Finger.SWIPE) { swipeKey = fingerRow; springKey = null }
        }
        when (finger) {
            Finger.SWIPE -> swipeDx = x - downX
            Finger.VERTICAL -> {
                if (now > lastMs) speed = (y - lastY) / (now - lastMs)
                drag(y - lastY)
            }
            else -> Unit
        }
        lastY = y
        lastMs = now
    }

    /** [d] down positive: the list scrolls first, and only past its end does it go towards shut. */
    private fun drag(d: Float) {
        if (fromBottom) {
            closing = (closing - d).coerceAtLeast(0f)
            return
        }
        if (d < 0f) {
            val into = minOf(-d, maxScroll() - scroll)
            scroll += into
            closing += -d - into
        } else {
            val back = minOf(d, closing)
            closing -= back
            scroll = (scroll - (d - back)).coerceAtLeast(0f)
        }
    }

    fun fingerUp(x: Float, y: Float, now: Long): List<Effect> {
        val was = finger
        finger = Finger.NONE
        return when (was) {
            Finger.SWIPE -> {
                val key = swipeKey
                val dx = swipeDx
                swipeKey = null
                swipeDx = 0f
                if (key == null) return emptyList()
                if (abs(dx) >= DISMISS_DX) {
                    leaving[key] = Leaving(dx, if (dx < 0f) -1f else 1f, now)
                    listOf(Effect.Dismissed(key))
                } else {
                    springKey = key; springFrom = dx; springAt = now
                    emptyList()
                }
            }
            Finger.VERTICAL -> if (closing > 0f) {
                val from = progress(now)
                settle(from, ControlCenter.release(true, -closing, speed), now)
            } else emptyList()
            Finger.UNDECIDED -> when {
                onClear -> clearAll(now)
                fingerRow == null -> close(now)
                else -> emptyList()
            }
            Finger.NONE -> emptyList()
        }
    }

    /**
     * The rows as they stand: those gone past their way out are let go here. A row's way out is
     * one measure, how far it has gone of [travel]: it fades by it, and its place closes by it,
     * so the rows under it rise as the finger takes it and go on rising as it flies.
     */
    fun rows(now: Long): List<Row> {
        leaving.entries.removeAll { (key, l) ->
            (now - l.atMs >= (if (l.up) RISE_MS else FLY_MS)).also { gone -> if (gone) items = items.filter { it.key != key } }
        }
        val out = ArrayList<Row>(items.size)
        var y = TOP - scroll
        for (item in items) {
            val l = leaving[item.key]
            var dx = when {
                item.key == swipeKey -> swipeDx
                item.key == springKey -> {
                    val t = ((now - springAt).toFloat() / SPRING_MS).coerceIn(0f, 1f)
                    springFrom * (1f - t) * (1f - t)
                }
                else -> 0f
            }
            if (l != null && l.up) {
                // Cleared: sliding off to the right in its turn, fading as a swiped one does; its place kept.
                val t = ((now - l.atMs).toFloat() / RISE_MS).coerceIn(0f, 1f)
                val slid = travel * t * t * (3f - 2f * t)
                out += Row(item, y, ROW_H, slid, 1f - slid / travel)
                y += ROW_H + GAP
                continue
            }
            if (l != null) {
                val t = ((now - l.atMs).toFloat() / FLY_MS).coerceIn(0f, 1f)
                val eased = 1f - (1f - t) * (1f - t)
                dx = l.fromDx + (l.dir * travel - l.fromDx) * eased
            }
            val gone = (abs(dx) / travel).coerceIn(0f, 1f)
            val alpha = 1f - gone
            val scale = 1f - gone
            val h = ROW_H * scale
            out += Row(item, y, h, dx, alpha)
            y += h + GAP * scale
        }
        return out
    }

    /**
     * Every row dismissed, by the clear button: they slide off to the right one after another,
     * [CHAIN_MS] apart, the bottom one on screen first and up from there. Those off screen go at
     * once, unseen.
     */
    private fun clearAll(now: Long): List<Effect> {
        val shown = rows(now).filter { it.item.key !in leaving }
        if (shown.isEmpty()) return emptyList()
        clearedAt = now
        swipeKey = null; springKey = null
        var k = 0
        var last = now
        for (r in shown.asReversed()) {
            val seen = r.top < Panel.HEIGHT && r.top + r.height > 0f
            val at = if (seen) now + CHAIN_MS * k++ else now
            leaving[r.item.key] = Leaving(0f, 0f, at, up = true)
            last = maxOf(last, at)
        }
        return listOf(Effect.ClearedAll(shown.map { it.item.key }, last + RISE_MS))
    }

    /**
     * The clear button, at the bottom of the screen however few rows there are, over them when
     * the list runs that far. Null with nothing to clear; it fades away once pressed.
     */
    fun clear(now: Long): Clear? {
        val fade = if (clearedAt == 0L) 1f else 1f - ((now - clearedAt).toFloat() / CLEAR_FADE_MS).coerceIn(0f, 1f)
        if (fade <= 0f || items.none { it.key !in leaving || clearedAt != 0L }) return null
        val top = Panel.HEIGHT - BOTTOM - CLEAR_D
        return Clear((rowLeft + RIGHT) / 2f, top + CLEAR_D / 2f, CLEAR_D, fade)
    }

    /** "No notifications" shows: none left, and not because they were all just cleared. */
    fun showsEmpty(): Boolean = items.isEmpty() && clearedAt == 0L

    private val rowLeft: Float get() = left + MARGIN
    /** How far a row goes to be gone: off the right edge, from where it stands. */
    private val travel: Float get() = Panel.WIDTH - rowLeft
    val rowsLeft: Float get() = rowLeft
    private fun maxScroll(): Float =
        (TOP + items.size * (ROW_H + GAP) + CLEAR_D + BOTTOM - Panel.HEIGHT).coerceAtLeast(0f)

    companion object {
        /** A finger at [x], [y] starts the pull: the top edge's right half, past the control centre's. */
        fun startsPull(x: Float, y: Float, left: Float): Boolean =
            y <= ControlCenter.EDGE && x > left + (Panel.WIDTH - left) / 2f

        const val MARGIN = 16f
        /** The first row at the glass's margin (GlassCard.MIN), so its corner meets the display's. */
        const val TOP = 14f
        const val BOTTOM = 22f
        /** The panel's own notification height (RearDialView.NOTICE_H): a quarter of it. */
        const val ROW_H = 143f
        const val GAP = 10f
        val RIGHT: Float get() = Panel.WIDTH - MARGIN
        const val DISMISS_DX = 150f
        const val SETTLE_MS = 240L
        const val SPRING_MS = 180L
        const val FLY_MS = 240L
        /** The clear button: its size, and how far round it a finger still lands on it. */
        const val CLEAR_D = 56f
        const val CLEAR_SLOP = 8f
        const val CLEAR_FADE_MS = 150L
        /** Cleared, each row slides off to the right over this, the next one up this long after. */
        const val RISE_MS = 320L
        const val CHAIN_MS = 100L
        /** Where a drag up shuts the list rather than scroll it: as the control centre's top edge. */
        const val BOTTOM_EDGE = ControlCenter.EDGE
    }
}
