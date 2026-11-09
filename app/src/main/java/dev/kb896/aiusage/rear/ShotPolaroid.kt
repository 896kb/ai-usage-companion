package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RectF
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl

/**
 * A screenshot of the back, shown as taken: a white flash, then the picture itself shrinking from
 * the whole rear into a polaroid — a white frame round it, deeper at the bottom, tipped a little —
 * as it flows up to the top right of the glass, where it waits a few seconds with a bin under it,
 * then slides off that way and fades (the owner, 2026-11-09). The bin is the notifications' clear
 * button (NoticeCard.drawClear) with a bin for its cross: tapped, the polaroid falls away at once and
 * the screenshot leaves the gallery ([binAt], [bin]).
 *
 * One layer of its own over everything of ours, trusted (it takes no finger), and under the bin a
 * blocker as big as it, untrusted, so its tap reaches nothing below; both left out of screenshots and
 * recordings, as the recording's pill is. Drawn on a thread of its own; gone when it is done.
 */
class ShotPolaroid(
    private val layerStack: Int,
    private val rear: RearGeometry,
    /** The notifications' look, for the bin drawn as their clear button. */
    private val look: () -> NoticeCard.Look?,
) {

    @Volatile private var playing: Thread? = null
    /** When the bin was tapped; 0 while it was not. */
    @Volatile private var binnedAt = 0L
    /** The bin can be tapped and the card dragged: the polaroid is at rest. */
    @Volatile private var binReady = false
    /**
     * Dragged to the right by a finger (the rear's pixels), and whether one holds it; let go short of
     * [SWIPE_FRACTION] of its width it springs back from [springFrom] (at [springAt]), past it (or
     * flicked) it is swiped away from [swipeFrom] (at [swipedAt]) — gone, not binned.
     */
    @Volatile private var dragDx = 0f
    @Volatile private var dragging = false
    @Volatile private var springFrom = 0f
    @Volatile private var springAt = 0L
    @Volatile private var swipeFrom = 0f
    @Volatile private var swipedAt = 0L
    /** When it leaves of itself, ms from its start: put off while a finger holds it. */
    @Volatile private var leaveAt = LEAVE_AT
    @Volatile private var startedAt = 0L
    private val card = NoticeCard({ null }, { 0f }, { null })

    /** [shot] (the rear's picture, its own size) played, then recycled. A second while one plays replaces it. */
    fun play(shot: Bitmap) {
        playing?.interrupt()
        binnedAt = 0L
        binReady = false
        dragDx = 0f; dragging = false; springAt = 0L; swipedAt = 0L
        leaveAt = LEAVE_AT
        playing = Thread({ run(shot) }, "shot-polaroid").apply { isDaemon = true; start() }
    }

    /** Whether a finger at [ux], [uy] (panel units, as the touches come) is on the bin, with a little room. */
    fun binAt(ux: Float, uy: Float): Boolean {
        if (!binReady || binnedAt != 0L) return false
        val (cx, cy) = binCentre()
        val r = BIN_SIZE / 2 + BIN_SLOP
        return (ux - cx) * (ux - cx) + (uy - cy) * (uy - cy) <= r * r
    }

    /** Whether a finger at [ux], [uy] (panel units) is on the card at rest, to drag it. */
    fun cardAt(ux: Float, uy: Float): Boolean {
        if (!binReady || binnedAt != 0L || swipedAt != 0L) return false
        val l = (restCx - cardW / 2) / rear.fitX - CARD_SLOP
        val r = (restCx + cardW / 2) / rear.fitX + CARD_SLOP
        val t = (restCy - cardH / 2) / rear.fitY - CARD_SLOP
        val b = (restCy + cardH / 2) / rear.fitY + CARD_SLOP
        return ux in l..r && uy in t..b
    }

    /** Held and dragged [dux] (panel units) from where the finger landed: right freely, left only a little. */
    fun drag(dux: Float) {
        dragging = true
        springAt = 0L
        dragDx = maxOf(dux * rear.fitX, -LEFT_GIVE * rear.fitX)
    }

    /** Let go, moving at [speed] (panel units a ms, right positive): away if far or fast enough, else back. */
    fun release(speed: Float) {
        if (!dragging) return
        dragging = false
        val now = android.os.SystemClock.uptimeMillis()
        if (dragDx > cardW * SWIPE_FRACTION || speed > SWIPE_SPEED) {
            swipeFrom = dragDx
            swipedAt = now
            binReady = false
        } else {
            springFrom = dragDx
            springAt = now
            dragDx = 0f
            // A moment more to look at it, if it was about to go.
            leaveAt = maxOf(leaveAt, now - startedAt + HELD_MORE_MS)
        }
    }

    /** Binned: it falls away now. */
    fun bin() {
        if (binnedAt == 0L) binnedAt = android.os.SystemClock.uptimeMillis()
        binReady = false
    }

    // -- where things stand, in panel units (the bin, its blocker) and in the rear's pixels (the card)

    private val w get() = rear.widthPx.toFloat()
    private val h get() = rear.heightPx.toFloat()
    private val restW get() = w * REST_SCALE
    private val restH get() = restW * h / w
    private val cardW get() = restW * (1 + 2 * BORDER)
    private val cardH get() = restH + restW * (BORDER + BORDER_BOTTOM)
    /**
     * In from the top and right edges at rest: clear of the glass's rounded corner (as deep as the
     * corner's curve reaches in along its diagonal, from the rear's own radius), plus the margin, plus
     * what the tilt lifts its top right corner by. A margin alone left it cut off by the corner (the
     * owner, 2026-11-09).
     */
    private val inset get() = rear.cornerPx * (1f - 1f / kotlin.math.sqrt(2f)) + MARGIN * rear.fitX +
        cardW / 2 * kotlin.math.abs(kotlin.math.sin(Math.toRadians(TILT.toDouble()))).toFloat()

    /** The card's middle at rest: up at the top right of the glass, clear of its corner. */
    private val restCx get() = w - inset - cardW / 2
    private val restCy get() = inset + cardH / 2

    /** The bin's middle, panel units: under the card, at its middle. */
    private fun binCentre(): Pair<Float, Float> =
        restCx / rear.fitX to (restCy + cardH / 2) / rear.fitY + BIN_GAP + BIN_SIZE / 2

    private fun run(shot: Bitmap) {
        var layer: SurfaceControl? = null
        var blocker: SurfaceControl? = null
        var surface: Surface? = null
        try {
            val sc = ShellLayer.create("shot-polaroid", layerStack, ShellLayer.Z_FRAME, rear.widthPx, rear.heightPx, trusted = true, visible = false, format = PixelFormat.TRANSLUCENT)
            layer = sc
            // One blocker over the card and the bin under it, while they rest: their fingers reach nothing below.
            val (_, by) = binCentre()
            val pad = CARD_SLOP * rear.fitX
            val blLeft = restCx - cardW / 2 - pad
            val blTop = restCy - cardH / 2 - pad
            val blW = (cardW + 2 * pad).toInt()
            val blH = ((by + BIN_SIZE / 2 + BIN_SLOP) * rear.fitY - blTop).toInt()
            val bl = ShellLayer.create("shot-blocker", layerStack, ShellLayer.Z_PANES, blW, blH, trusted = false, visible = false)
            blocker = bl
            Surface(bl).also { s -> runCatching { s.lockHardwareCanvas().also { it.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR); s.unlockCanvasAndPost(it) } }; s.release() }
            SurfaceControl.Transaction().use { t ->
                skip(t, sc); skip(t, bl)
                t.setPosition(bl, blLeft, blTop)
                t.setVisibility(sc, true).apply()
            }
            val s = Surface(sc)
            surface = s
            val start = android.os.SystemClock.uptimeMillis()
            startedAt = start
            var blocking = false
            while (!Thread.currentThread().isInterrupted) {
                val now = android.os.SystemClock.uptimeMillis()
                val t = now - start
                val binned = binnedAt
                val swiped = swipedAt
                if (binned != 0L && now - binned > BIN_MS) break
                if (swiped != 0L && now - swiped > SWIPE_MS) break
                // Held, it stays: its leaving put off for as long as the finger is on it.
                if (dragging) leaveAt = maxOf(leaveAt, t + HELD_MORE_MS)
                if (binned == 0L && swiped == 0L && t > leaveAt + LEAVE_MS) break
                // The bin and the card are for fingers while it rests: the blocker up just then.
                val rest = t in REST_AT until leaveAt && binned == 0L && swiped == 0L
                binReady = rest
                if (rest != blocking) {
                    blocking = rest
                    runCatching { SurfaceControl.Transaction().use { it.setVisibility(bl, rest).apply() } }
                }
                val c = runCatching { s.lockHardwareCanvas() }.getOrNull() ?: break
                try {
                    c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                    frame(c, shot, t, if (binned == 0L) 0f else ((now - binned) / BIN_MS.toFloat()).coerceIn(0f, 1f))
                } finally {
                    s.unlockCanvasAndPost(c)
                }
                Thread.sleep(FRAME_MS)
            }
        } catch (_: InterruptedException) {
        } catch (e: Throwable) {
            Log.w(TAG, "screenshot animation: ${e.message}")
        } finally {
            binReady = false
            surface?.release()
            ShellLayer.remove(layer)
            ShellLayer.remove(blocker)
            shot.recycle()
        }
    }

    private val photo = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF7F5F0.toInt() }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
    private val flash = Paint().apply { color = Color.WHITE }
    private val box = RectF()
    private val pic = RectF()

    /** The picture at [t] ms in; [binned] how far it has fallen away since the bin was tapped (0 to 1). */
    private fun frame(c: android.graphics.Canvas, shot: Bitmap, t: Long, binned: Float) {
        // Shrinking into the polaroid as it flows up to the top right, eased out; waiting; then away
        // up and right, fading — or, binned, shrinking to nothing where it is.
        val shrink = ease(((t - SHRINK_AT) / SHRINK_MS.toFloat()).coerceIn(0f, 1f))
        val leave = if (binned > 0f || swipedAt != 0L) 0f else easeIn(((t - leaveAt) / LEAVE_MS.toFloat()).coerceIn(0f, 1f))
        val gone = easeIn(binned)
        // Across under a finger, springing back, or swiped away off the right and fading.
        val now = android.os.SystemClock.uptimeMillis()
        val swiped = swipedAt
        val swipe = if (swiped == 0L) 0f else easeIn(((now - swiped) / SWIPE_MS.toFloat()).coerceIn(0f, 1f))
        val across = when {
            // Swiped: from where the finger let it go, away the way it leaves of itself (below).
            swiped != 0L -> swipeFrom
            dragging -> dragDx
            springAt != 0L -> springFrom * (1f - ease(((now - springAt) / SPRING_MS.toFloat()).coerceIn(0f, 1f)))
            else -> 0f
        }
        val picW = (w + (restW - w) * shrink) * (1f - gone)
        val picH = picW * h / w
        val side = picW * BORDER * shrink
        val bottom = picW * BORDER_BOTTOM * shrink
        // Leaving, of itself or swiped: up and to the right, tipping further — the one way out.
        val away = maxOf(leave, swipe)
        val cx = w / 2f + (restCx - w / 2f) * shrink + away * cardW + across
        val cy = h / 2f + (restCy - h / 2f) * shrink - away * cardH
        val angle = TILT * shrink + TILT_LEAVE * away
        val alpha = (1f - maxOf(leave, gone, swipe)).coerceIn(0f, 1f)
        val cardW = picW + 2 * side
        val cardH = picH + side + bottom
        c.save()
        c.rotate(angle, cx, cy)
        box.set(cx - cardW / 2, cy - cardH / 2, cx + cardW / 2, cy + cardH / 2)
        if (shrink > 0f) {
            // A soft shadow under it, a little down and right, and the paper.
            shade.alpha = (0x66 * shrink * alpha).toInt()
            val off = 6f * rear.fitX * shrink
            c.drawRoundRect(box.left + off, box.top + off * 1.5f, box.right + off, box.bottom + off * 1.5f, 6f, 6f, shade)
            paper.alpha = (255 * shrink * alpha).toInt()
            c.drawRoundRect(box, 6f * shrink, 6f * shrink, paper)
        }
        pic.set(box.left + side, box.top + side, box.left + side + picW, box.top + side + picH)
        photo.alpha = (255 * alpha).toInt()
        if (picW > 1f) c.drawBitmap(shot, null, pic, photo)
        c.restore()
        // The bin under it while it rests: in as it arrives, out as it leaves or is binned.
        val binIn = ((t - REST_AT + BIN_FADE_MS) / BIN_FADE_MS.toFloat()).coerceIn(0f, 1f)
        val binAlpha = binIn * (1f - maxOf(leave * 2f, binned * 2f, swipe * 2f)).coerceIn(0f, 1f)
        val l = look()
        if (binAlpha > 0.01f && l != null) {
            val (bx, by) = binCentre()
            c.save()
            c.scale(rear.fitX, rear.fitY)
            card.drawClear(c, NoticeShade.Clear(bx, by, BIN_SIZE, binAlpha), l, bin = true)
            c.restore()
        }
        // The flash, over the first moment.
        val f = 1f - (t / FLASH_MS.toFloat()).coerceIn(0f, 1f)
        if (f > 0f) {
            flash.alpha = (FLASH_ALPHA * f).toInt()
            c.drawRect(0f, 0f, w, h, flash)
        }
    }

    private fun skip(t: SurfaceControl.Transaction, sc: SurfaceControl) {
        if (ShootMode.capturesShown()) return // shooting for the site: in the picture
        runCatching { t.javaClass.getMethod("setSkipScreenshot", SurfaceControl::class.java, Boolean::class.javaPrimitiveType).invoke(t, sc, true) }
    }

    private fun ease(x: Float): Float = 1f - (1f - x) * (1f - x) * (1f - x)
    private fun easeIn(x: Float): Float = x * x * x

    private companion object {
        const val TAG = "AiUsageRear"
        const val FRAME_MS = 16L
        const val FLASH_MS = 180L
        const val FLASH_ALPHA = 170
        const val SHRINK_AT = 60L
        const val SHRINK_MS = 560L
        /** At rest from here, and the bin with it; leaving from [LEAVE_AT]. */
        const val REST_AT = SHRINK_AT + SHRINK_MS
        const val LEAVE_AT = REST_AT + 4_000L
        const val LEAVE_MS = 420L
        const val BIN_MS = 300L
        const val BIN_FADE_MS = 200L
        /** Its width at rest, of the rear's, and its margin from the top and right, panel units. */
        const val REST_SCALE = 0.30f
        const val MARGIN = 16f
        /** The frame: its sides and top, and its deeper bottom, of the picture's width. */
        const val BORDER = 0.045f
        const val BORDER_BOTTOM = 0.17f
        /** Tipped this many degrees at rest, and more as it leaves. */
        const val TILT = -4f
        const val TILT_LEAVE = 10f
        /** The bin: the clear button's size, its gap under the card, and how far off it a finger finds it. Panel units. */
        const val BIN_SIZE = 56f
        const val BIN_GAP = 14f
        const val BIN_SLOP = 10f
        /** The card to a finger: how far off it one finds it, how far left it gives, panel units. */
        const val CARD_SLOP = 8f
        const val LEFT_GIVE = 24f
        /** Let go past this much of its width, or faster than this (panel units a ms), and it is swiped away. */
        const val SWIPE_FRACTION = 0.25f
        const val SWIPE_SPEED = 0.5f
        const val SWIPE_MS = 260L
        const val SPRING_MS = 220L
        /** Held, or sprung back, it stays at least this much longer. */
        const val HELD_MORE_MS = 1_000L
    }
}
