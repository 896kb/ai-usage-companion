package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl
import dev.kb896.aiusage.data.GlassStyle
import dev.kb896.aiusage.data.PanelTheme
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The notifications over a guest or the panel, whichever page it is on, drawn and driven by the
 * shell service, as ShellControlCenter is the control centre: [touch] takes the top edge's right half and every finger while it is down,
 * and the sheet is a ShellOverlay with the live blur and the blocker. What is on it is a
 * NoticeShade's rows. The list is the app's (NotificationRelay through BackNotices, whole, with
 * every change); a row swiped away goes back by [onDismissed].
 *
 * [pop] is the other half: one notification as it arrives, a banner over the top of the rear that
 * slides in and leaves by itself, on a trusted layer of its own so touches go through it to the app.
 *
 * THREADING as ShellControlCenter's: [lock] guards the model, [touch] runs on the reader thread,
 * the overlay's render thread reads under [lock] once a frame, [clock] runs begin() and end().
 */
class ShellNotices(
    private val layerStack: () -> Int,
    private val geometry: () -> RearGeometry,
    private val onDismissed: (String) -> Unit,
    private val onProgress: (Float) -> Unit,
) {
    private val lock = Any()
    private val model by lazy { NoticeShade(geometry().cutoutUnits) }
    private var owns = false
    private var downY = 0f
    @Volatile private var up = false
    private var beginPending = false
    @Volatile private var overlay: ShellOverlay? = null
    private val making = Any()
    @Volatile private var face: PanelType.Face? = null
    @Volatile private var look = NoticeCard.Look(PanelTheme.GLASS, 0xFFFFFFFF.toInt(), 0xFF1D1F21.toInt(), SheetTint.of(true, null))
    private var shutCheck: ScheduledFuture<*>? = null
    @Volatile private var icons: Map<String, Bitmap> = emptyMap()
    private val iconCache = HashMap<String, Bitmap>()

    private val clock = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "notices-clock").apply { isDaemon = true }
    }

    /** Up, or on its way: for the touch reader to read with no lock. */
    val isUp: Boolean get() = up

    /** The panel's face; nothing opens before it is known. The first one makes the sheet. */
    fun face(f: PanelType.Face) {
        face = f
        overlayOrNull()?.changed()
    }

    /** The panel's theme, Glass settings' style, and a notification's colours (PanelConfig.notificationTint, notificationGround). */
    fun theme(theme: PanelTheme, style: GlassStyle?, text: Int, ground: Int) {
        look = NoticeCard.Look(theme, text, ground, SheetTint.of(theme.isGlass, style))
        overlay?.changed()
    }


    /** The whole list, newest first, as [encode] wrote it. Any thread. */
    fun items(bundle: Bundle?): List<NoticeShade.Item> {
        val list = decode(bundle)
        synchronized(iconCache) {
            for (item in list) if (item.pkg !in iconCache) {
                bundle?.getByteArray(ICON + item.pkg)?.let { bytes ->
                    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()?.let { iconCache[item.pkg] = it }
                }
            }
            icons = HashMap(iconCache)
        }
        synchronized(lock) { model.items(list) }
        overlay?.changed()
        return list
    }

    /** See ShellControlCenter.touch: the same, for the top edge's other half. */
    fun touch(e: RearTouches.Event, actions: List<EdgeGestures.Action>, opens: () -> Boolean): List<EdgeGestures.Action> {
        val effects = ArrayList<NoticeShade.Effect>()
        val rest = ArrayList<EdgeGestures.Action>()
        var begin = false
        var heal = false
        synchronized(lock) {
            val now = e.atMs
            if (e is RearTouches.Down) {
                downY = e.y
                if (up && !beginPending && overlay?.up != true) {
                    Log.w(TAG, "notices: their layers are gone — shut")
                    up = false; model.reset(); stopTimers()
                }
                owns = up && model.shown(now)
                if (owns) model.fingerDown(e.x, e.y, now)
                // Shut, with its layers still up and no check left to come: whatever led here,
                // the blocker must not stay over the rear. The next finger takes it away.
                if (up && !beginPending && model.isShut(now)) heal = true
            }
            for (a in actions) when (a) {
                is EdgeGestures.Action.NoticePullStart -> if (!owns && opens() && overlay != null && face != null) {
                    model.beginPull(downY, a.y, now)
                    if (!up) { up = true; begin = true; beginPending = true }
                }
                is EdgeGestures.Action.NoticePullMove -> model.movePull(a.y, now)
                is EdgeGestures.Action.NoticePullEnd -> effects += model.endPull(a.y, now)
                is EdgeGestures.Action.PullEnd, is EdgeGestures.Action.SlideEnd, EdgeGestures.Action.Cancel -> rest += a
                else -> if (!owns) rest += a
            }
            if (owns) when (e) {
                is RearTouches.Move -> model.fingerMove(e.x, e.y, now)
                is RearTouches.Up -> { effects += model.fingerUp(e.x, e.y, now); owns = false }
                else -> Unit
            }
        }
        if (begin) { open(); banner.gone() }
        if (heal) {
            Log.w(TAG, "notices: shut, with the blocker still up — taken away")
            runCatching { clock.execute { endIfShut() } }
        }
        if (synchronized(lock) { up }) overlay?.changed()
        effects.forEach(::carryOut)
        return rest
    }

    /** Shut at once, no settle (the rear going dark, the guest going), the banner with it. */
    fun closeNow(why: String, sync: Boolean = false) {
        banner.gone()
        val o = synchronized(lock) {
            if (!up) return
            Log.i(TAG, "notices: shut — $why")
            up = false
            model.reset()
            stopTimers()
            overlay
        } ?: return
        if (sync) runCatching { o.end() } else runCatching { clock.execute { runCatching { o.end() } } }
    }

    private fun open() {
        val o = overlay
        if (o == null) { synchronized(lock) { up = false; beginPending = false; model.reset() }; return }
        val queued = runCatching {
            clock.execute {
                val ok = synchronized(lock) { up } && runCatching { o.begin() }.getOrElse {
                    Log.w(TAG, "notices: begin threw: ${it.message}"); false
                }
                synchronized(lock) {
                    beginPending = false
                    if (!ok) {
                        if (up) Log.i(TAG, "notices: could not come up — the pull does nothing")
                        up = false; model.reset(); stopTimers()
                    }
                }
                if (!ok) runCatching { o.end() }
            }
        }.isSuccess
        if (!queued) synchronized(lock) { up = false; beginPending = false; model.reset() }
    }

    private fun overlayOrNull(): ShellOverlay? {
        overlay?.let { return it }
        synchronized(making) {
            overlay?.let { return it }
            val made = runCatching { ShellOverlay(layerStack(), geometry(), "notices", "notices", Drawing()) }
                .onFailure { Log.w(TAG, "notices: no sheet: ${it.message}") }.getOrNull() ?: return null
            made.onProgress = onProgress
            overlay = made
            return made
        }
    }

    private fun carryOut(effect: NoticeShade.Effect) {
        when (effect) {
            is NoticeShade.Effect.Dismissed -> {
                runCatching { onDismissed(effect.key) }.onFailure { Log.w(TAG, "notices: dismiss: ${it.message}") }
                // The last one gone, there is nothing to stay open for.
                runCatching {
                    clock.schedule({
                        val shut = synchronized(lock) {
                            if (up && model.count == 0) model.close(SystemClock.uptimeMillis()) else emptyList()
                        }
                        shut.forEach(::carryOut)
                        overlay?.changed()
                    }, NoticeShade.FLY_MS, TimeUnit.MILLISECONDS)
                }
            }
            is NoticeShade.Effect.ClearedAll -> {
                for (key in effect.keys) {
                    runCatching { onDismissed(key) }.onFailure { Log.w(TAG, "notices: dismiss: ${it.message}") }
                }
                // Shut once the last has risen away.
                runCatching {
                    clock.schedule({
                        val shut = synchronized(lock) {
                            if (up && model.count == 0) model.close(SystemClock.uptimeMillis()) else emptyList()
                        }
                        shut.forEach(::carryOut)
                        overlay?.changed()
                    }, (effect.doneAtMs - SystemClock.uptimeMillis()).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                }
            }
            is NoticeShade.Effect.ShutAt -> synchronized(lock) {
                shutCheck?.cancel(false)
                val delay = (effect.atMs - SystemClock.uptimeMillis()).coerceAtLeast(0L) + SHUT_GRACE_MS
                shutCheck = runCatching { clock.schedule({ endIfShut() }, delay, TimeUnit.MILLISECONDS) }.getOrNull()
            }
        }
    }

    private fun endIfShut() {
        val o = synchronized(lock) {
            if (!up || !model.isShut(SystemClock.uptimeMillis())) return
            up = false
            model.reset()
            stopTimers()
            overlay
        } ?: return
        runCatching { o.end() }.onFailure { Log.w(TAG, "notices: end failed: ${it.message}") }
    }

    private fun stopTimers() {
        shutCheck?.cancel(false); shutCheck = null
    }

    /** One line, cut to [width]. */
    private fun fit(text: String, paint: TextPaint, width: Float): String =
        TextUtils.ellipsize(text.replace('\n', ' '), paint, width, TextUtils.TruncateAt.END).toString()

    private fun newCard() = NoticeCard({ face }, { geometry().let { it.cornerPx / it.fitX } }, { icons[it] })

    private inner class Drawing : ShellOverlay.Content {
        private var rows: List<NoticeShade.Row> = emptyList()
        private var clear: NoticeShade.Clear? = null
        private var empty = false
        private var moving = false

        override fun frame(): Float = synchronized(lock) {
            val now = SystemClock.uptimeMillis()
            rows = model.rows(now)
            clear = model.clear(now)
            empty = model.showsEmpty()
            moving = model.moving(now) || (clear?.alpha ?: 1f) < 1f
            model.progress(now)
        }

        override fun painter(): ShellOverlay.Painter {
            val card = newCard()
            val clearCard = newCard()
            val emptyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
            var emptyFace: PanelType.Face? = null
            val rect = RectF()
            return ShellOverlay.Painter { canvas, progress ->
                val f = face ?: return@Painter false
                // As the control centre's contents: nothing for the first of the pull, then arriving.
                val arrive = ((progress - 0.3f) / 0.7f).coerceIn(0f, 1f)
                val left = geometry().cutoutUnits + 10f
                val right = NoticeShade.RIGHT
                canvas.save()
                canvas.translate(0f, -(1f - arrive) * 48f)
                if (empty) {
                    if (f !== emptyFace) { f.set(emptyPaint, PanelType.BODY); emptyFace = f }
                    emptyPaint.textSize = 22.5f * f.scale
                    emptyPaint.color = look.text
                    emptyPaint.alpha = (153 * arrive).toInt()
                    canvas.drawText("No notifications", (left + right) / 2f, 150f, emptyPaint)
                }
                for (r in rows) {
                    val alpha = arrive * r.alpha
                    if (alpha <= 0.004f || r.height < 1f) continue
                    if (r.top > dev.kb896.aiusage.Panel.HEIGHT || r.top + r.height < 0f) continue
                    canvas.save()
                    // Whole as it goes, not squashed: its place closes under it while it fades.
                    canvas.translate(r.dx, r.top)
                    rect.set(left, 0f, right, NoticeShade.ROW_H)
                    card.draw(canvas, rect, r.item, alpha, square = false, screenTop = r.top - (1f - arrive) * 48f, look = look)
                    canvas.restore()
                }
                clear?.let { c ->
                    clearCard.drawClear(canvas, NoticeShade.Clear(c.cx, c.cy, c.size, c.alpha * arrive), look)
                }
                canvas.restore()
                moving
            }
        }
    }

    /** [item] as it arrives: the banner over the top of the app. Any thread. */
    fun pop(item: NoticeShade.Item) = banner.show(item)

    private val banner = Banner()

    /**
     * The banner: in, held, out by itself, on a thread of its own that lives only while it shows.
     * Trusted and so touch-transparent: the app under it goes on being used.
     */
    private inner class Banner {
        private val state = Any()
        private var item: NoticeShade.Item? = null
        private var shownAt = 0L
        private var thread: Thread? = null

        fun show(next: NoticeShade.Item) {
            if (face == null) return
            synchronized(state) {
                val now = SystemClock.uptimeMillis()
                // Already fully in: this one takes its place without going out first.
                shownAt = if (item != null && now - shownAt in IN_MS until IN_MS + HOLD_MS) now - IN_MS else now
                item = next
                if (thread?.isAlive != true) {
                    thread = Thread({ run() }, "notice-banner").apply { isDaemon = true; start() }
                }
            }
        }

        fun gone() {
            synchronized(state) {
                item = null
                thread?.interrupt()
                thread = null
            }
        }

        private fun run() {
            var sc: SurfaceControl? = null
            var surface: Surface? = null
            try {
                val rear = geometry()
                val height = Math.round(BANNER_H * rear.fitY)
                sc = ShellLayer.create("notice-banner", layerStack(), ShellLayer.Z_BANNER, rear.widthPx, height, trusted = true, visible = false, alpha = 0f)
                surface = Surface(sc)
                val card = newCard()
            val clearCard = newCard()
                val rect = RectF()
                var drawn: NoticeShade.Item? = null
                var drawnLook: NoticeCard.Look? = null
                var placedY = Float.NaN
                var alpha = -1f
                while (!Thread.currentThread().isInterrupted) {
                    val (now, it, at) = synchronized(state) { Triple(SystemClock.uptimeMillis(), item, shownAt) }
                    val t = now - at
                    if (it == null || t >= IN_MS + HOLD_MS + OUT_MS) break
                    val shown = when {
                        t < IN_MS -> { val p = 1f - t.toFloat() / IN_MS; 1f - p * p * p }
                        t < IN_MS + HOLD_MS -> 1f
                        else -> { val p = (t - IN_MS - HOLD_MS).toFloat() / OUT_MS; 1f - p * p }
                    }
                    val y = -(1f - shown) * BANNER_H
                    val l = look
                    // DRAWN ONCE, where it rests, and the layer moved: so the blur under it, which is
                    // the layer's, goes with the card rather than staying where the card will be.
                    if (it !== drawn || l !== drawnLook) {
                        // Where the panel's own strip stood: off the top and right edges under
                        // ORIGINAL, a pane inside the glass's margin under GLASS.
                        val left = rear.cutoutUnits + 10f
                        if (l.theme.isGlass) rect.set(left, NoticeCard.MARGIN, dev.kb896.aiusage.Panel.WIDTH - NoticeCard.MARGIN, NoticeShade.ROW_H)
                        else rect.set(left, 0f, dev.kb896.aiusage.Panel.WIDTH.toFloat(), NoticeShade.ROW_H)
                        val c = runCatching { surface.lockHardwareCanvas() }.getOrNull() ?: surface.lockCanvas(null)
                        try {
                            c.drawColor(0, PorterDuff.Mode.CLEAR)
                            c.scale(rear.fitX, rear.fitY)
                            card.draw(c, rect, it, 1f, square = !l.theme.isGlass, screenTop = rect.top, look = l)
                        } finally { surface.unlockCanvasAndPost(c) }
                        // A LITTLE BLUR under the card, cut to it: what is behind it, text above all,
                        // no longer reads through it. SurfaceFlinger's, so it costs this nothing a frame.
                        SurfaceControl.Transaction().use { tx ->
                            tx.setCrop(sc, android.graphics.Rect(
                                Math.round(rect.left * rear.fitX), Math.round(rect.top * rear.fitY),
                                Math.round(rect.right * rear.fitX), Math.round(rect.bottom * rear.fitY),
                            ))
                            ShellOverlay.roundCorners(tx, sc, if (l.theme.isGlass) NoticeCard.RADIUS * rear.fitX else 0f)
                            ShellOverlay.blurUnder(tx, sc, BANNER_BLUR_PX)
                            tx.apply()
                        }
                        drawn = it
                        drawnLook = l
                    }
                    if (y != placedY) {
                        SurfaceControl.Transaction().use { tx -> tx.setPosition(sc, 0f, y * rear.fitY).apply() }
                        placedY = y
                    }
                    if (alpha != shown) {
                        SurfaceControl.Transaction().use { tx -> tx.setAlpha(sc, shown).setVisibility(sc, shown > 0f).apply() }
                        alpha = shown
                    }
                    Thread.sleep(if (shown < 1f) FRAME_MS else IDLE_MS)
                }
            } catch (_: InterruptedException) {
            } catch (e: Throwable) {
                Log.w(TAG, "notice banner stopped: ${e.message}")
            } finally {
                runCatching { surface?.release() }
                ShellLayer.remove(sc)
                synchronized(state) { if (thread === Thread.currentThread()) { thread = null; item = null } }
            }
        }
    }

    companion object {
        private const val TAG = "AiUsageRear"
        private const val SHUT_GRACE_MS = 32L
        private val BANNER_H = NoticeShade.ROW_H
        /** The blur under the banner's card, SurfaceFlinger px: enough to quiet text behind it. */
        private const val BANNER_BLUR_PX = 30
        private const val IN_MS = 260L
        private const val HOLD_MS = 4_000L
        private const val OUT_MS = 220L
        private const val FRAME_MS = 16L
        private const val IDLE_MS = 100L

        private const val KEYS = "keys"
        private const val PKGS = "pkgs"
        private const val APPS = "apps"
        private const val TITLES = "titles"
        private const val TEXTS = "texts"
        private const val ICON = "icon:"

        /** The list for the binder, each app's icon once. */
        fun encode(items: List<NoticeShade.Item>, icons: Map<String, ByteArray>): Bundle = Bundle().apply {
            putStringArray(KEYS, items.map { it.key }.toTypedArray())
            putStringArray(PKGS, items.map { it.pkg }.toTypedArray())
            putStringArray(APPS, items.map { it.app }.toTypedArray())
            putStringArray(TITLES, items.map { it.title }.toTypedArray())
            putStringArray(TEXTS, items.map { it.text }.toTypedArray())
            for (pkg in items.map { it.pkg }.distinct()) icons[pkg]?.let { putByteArray(ICON + pkg, it) }
        }

        fun decode(b: Bundle?): List<NoticeShade.Item> {
            val keys = b?.getStringArray(KEYS) ?: return emptyList()
            val pkgs = b.getStringArray(PKGS) ?: return emptyList()
            val apps = b.getStringArray(APPS) ?: return emptyList()
            val titles = b.getStringArray(TITLES) ?: return emptyList()
            val texts = b.getStringArray(TEXTS) ?: return emptyList()
            return keys.indices.mapNotNull { i ->
                NoticeShade.Item(keys[i], pkgs.getOrNull(i) ?: return@mapNotNull null, apps.getOrNull(i).orEmpty(), titles.getOrNull(i).orEmpty(), texts.getOrNull(i).orEmpty())
            }
        }
    }
}
