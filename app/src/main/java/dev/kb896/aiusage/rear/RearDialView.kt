package dev.kb896.aiusage.rear

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.R
import dev.kb896.aiusage.data.Account
import dev.kb896.aiusage.data.MascotMood
import dev.kb896.aiusage.data.DashboardState
import dev.kb896.aiusage.data.RingSpec
import dev.kb896.aiusage.data.RingWindow
import dev.kb896.aiusage.data.RingMetric
import dev.kb896.aiusage.data.MediaArt
import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.data.Health
import dev.kb896.aiusage.data.UsageWindow
import dev.kb896.aiusage.data.WindowKind

/**
 * Draws only — no parsing, no networking, no placement logic. Task 14 adds bars, the
 * footer and the ACRNM mark to this same view; [onDraw] and [drawRing] are kept
 * separate so that work lands as additions here, not a rewrite.
 */
class RearDialView(context: Context) : View(context) {

    var state: DashboardState = DashboardState.EMPTY
        set(value) { field = value; invalidate() }

    /** Everything the user can change. Arrives in the same payload as [state]. */
    var config: PanelConfig = PanelConfig.DEFAULT
        set(value) { field = value; invalidate() }

    /**
     * Draw only this account, filling the panel with it. Null draws all of them.
     *
     * This is how the per-account preview works, and it is a FILTER rather than a second
     * renderer: the same layout code runs, over a shorter list. A preview that drew its
     * own version of one block would be a second implementation of a layout this project
     * has already had to keep in step twice.
     *
     * It replaced a crop of the full panel, which worked only while every account fit on
     * it. They stop fitting at four — 516px of content into 396px of room — so past three
     * accounts the one being edited might not be rendered at all, and there would be
     * nothing to crop.
     */
    var onlyAccount: Account? = null
        set(value) { field = value; invalidate() }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Panel.Palette.TRACK; strokeCap = Paint.Cap.BUTT
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT
    }
    private val scratch = RectF()

    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Panel.Palette.DIM; textSize = 18f
        typeface = resources.getFont(R.font.departure_mono)
    }
    private val heading = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 22.5f; typeface = label.typeface
    }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 21f; typeface = label.typeface; textAlign = Paint.Align.RIGHT
    }
    private val barTrack = Paint().apply { color = Panel.Palette.TRACK }
    private val barFill = Paint()
    private val ray = resources.getDrawable(R.drawable.acrnm_ray, null)

    /** Masthead type. Green rather than accent-coloured — see drawTitle. */
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 40f; typeface = label.typeface; color = Panel.Palette.WORK
        // Centred: the masthead now sits on the bar column's axis and carries no mark
        // beside it, so it is measured from its middle rather than a left edge.
        textAlign = Paint.Align.CENTER
    }

    /**
     * Plan tag. Right-aligned to the value column so it sits over the percentages
     * rather than crowding the account name, and green — the masthead's colour, marking
     * it as fixed context about the account rather than another live reading.
     */
    /**
     * Time-to-reset column, in the palette's foreground white.
     *
     * It was DIM (#555555), which is the palette's "this is disabled" tone and was
     * barely legible against #1D1F21 on the physical panel — the hierarchy was being
     * carried entirely by colour, and it overshot into invisible. The separation the
     * dimming was after is still there, just done with SIZE instead: 18f against the
     * percentage's 21f. Both readings stay legible, the percentage still leads.
     *
     * Palette.FG rather than pure #FFFFFF, so the panel keeps to the Tomorrow Night set
     * it is drawn from — every other bright element on the display is a palette colour.
     */
    private val timeTag = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 18f; typeface = label.typeface
        textAlign = Paint.Align.RIGHT; color = Panel.Palette.FG
    }

    /** Filtered, unlike the mascot: a user's photo is not pixel art and nearest-neighbour
     *  scaling of one looks like a mistake rather than a style. */
    private val artPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var artKey: String? = null
    private var artBitmap: android.graphics.Bitmap? = null

    /** No anti-aliasing: the mascot is pixel art, and crisp edges are the look. */
    private val mascotPaint = Paint()

    /** Same again for the traced glyphs, and for a second reason — see drawIcon. */
    private val iconPaint = Paint()

    /** The notification strip's ground and its edge. */
    private val noticePaint = Paint()

    /** The second card's type, smaller than the headline's. */
    private val cascadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = CASCADE_TEXT
        typeface = label.typeface
    }

    /** One relayed notification, as much of it as the panel shows. */
    private data class Notice(
        val app: String,
        val pkg: String,
        val title: String,
        val text: String,
        /** The next notification in the same group — see NotificationRelay.cascade. */
        val moreTitle: String = "",
        val moreText: String = "",
    ) {
        val hasMore get() = moreTitle.isNotBlank() || moreText.isNotBlank()
    }

    private var notice: Notice? = null
    private var leaving: Notice? = null

    /** When the CURRENT notice arrived, for its five seconds. */
    private var noticeAt = 0L

    /** When the current item started its turn of the wheel. */
    private var wheelAt = 0L

    /** The strip's own slide: where it was, where it is going, and when that changed. */
    private var slideFrom = 0f
    private var slideTo = 0f
    private var slideAt = 0L

    /** The mini card's, which lives inside the headline's window rather than beside it. */
    private var miniFrom = 0f
    private var miniTo = 0f
    private var miniAt = 0L

    /** App icons, kept because the same few apps notify over and over. */
    private val icons = mutableMapOf<String, android.graphics.drawable.Drawable?>()

    /** The close button's ring and cross. Anti-aliased: it is the one curve here. */
    private val closePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * The X over a dead mascot's eye. Anti-aliased, unlike the body — these are the one
     * diagonal on an otherwise strictly axis-aligned figure, and unsmoothed diagonals at
     * this size read as a stray staircase rather than a cross.
     */
    private val deadEye = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        color = Panel.Palette.BG
    }

    /**
     * Plan tag. Coloured per ACCOUNT, like its heading and its row labels.
     *
     * It used to be a fixed green for both, which made the one element on the row that
     * is not a live reading the only element that did not say which account it belonged
     * to — two identical green "MAX 5X" tags on a panel whose whole colour language is
     * "this hue means this account".
     */
    /** Clock and battery. Colour comes from the config on every frame. */
    private val extras = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 18f; typeface = label.typeface
    }

    private val planTag = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 18f; typeface = label.typeface
        textAlign = Paint.Align.RIGHT
    }

    /** One eased tween per animated quantity, keyed by what it drives. See Tween. */
    private val tweens = mutableMapOf<String, Tween>()
    private val colorTweens = mutableMapOf<String, ColorTween>()
    private var frameNowMs = 0L
    private var animating = false

    /**
     * Current animated value for [key], advancing toward [target] by wall clock.
     * Flags the frame as animating so onDraw knows to schedule another one.
     */
    /**
     * The animated form of a colour, keyed by what it paints.
     *
     * Everything the user can recolour goes through here — accents, mascot bodies and
     * kit, the background, the extras, the ramp. A colour that snapped while the value
     * beside it eased read as two unrelated events, and made a setting being dragged in
     * the app look like the panel had flickered rather than responded.
     */
    private fun colorFor(key: String, target: Int): Int {
        val tween = colorTweens.getOrPut(key) { ColorTween() }
        val v = tween.advance(target, frameNowMs)
        if (!tween.settled(frameNowMs)) animating = true
        return v
    }

    private fun animateTo(key: String, target: Float): Float {
        val tween = tweens.getOrPut(key) { Tween() }
        val v = tween.advance(target, frameNowMs)
        if (!tween.settled(frameNowMs)) animating = true
        return v
    }

    /**
     * Self-scheduling redraw, independent of `state`/`geometry` pushes. Both of
     * Task 16's features (staleLabel, driftOffset) are computed live at draw time
     * from the clock, but onDraw only ever runs when something calls invalidate() —
     * and normally that "something" is UsagePollService pushing a fresh
     * DashboardState roughly once a minute (the `state` setter above invalidates).
     *
     * RearActivity runs in its own `:rear` process (see the manifest), reachable
     * from UsagePollService only via the ACTION_STATE broadcast. If that service's
     * tick loop has died — precisely the condition staleLabel exists to surface —
     * no more broadcasts arrive and invalidate() stops firing from that path. Without
     * an independent trigger the view would freeze on its last frame: the stale
     * marker would never appear, and the burn-in drift would freeze at one fixed
     * offset for as long as the service stays dead, which is unbounded and turns the
     * one failure this task defends against into the worst case for it. This timer
     * is what keeps both alive with no other input.
     */
    private val redrawTick: Runnable = Runnable {
        invalidate()
        postDelayed(redrawTick, REDRAW_INTERVAL_MS)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(redrawTick, REDRAW_INTERVAL_MS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(redrawTick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        // Absolute time, not a per-frame delta. A duration-based tween needs to know
        // how far through its leg it is, and asking the clock directly makes it immune
        // to the thing that used to need clamping: the idle redraw is every 15s, and an
        // integrator handed a 15-second step would fling its value past the target in
        // one frame. A tween handed the same gap simply reports "finished".
        frameNowMs = SystemClock.uptimeMillis()
        animating = false

        // Everything the panel paints — background included — happens inside one clip,
        // so the ripple takes the whole surface rather than eating the readout off a
        // background that stays put. That was the bug: the background was painted before
        // the clip existed, so a finished ripple left a full dark panel behind.
        val rippleNow = SystemClock.elapsedRealtime()
        // ONE value: how far the ambient state has swept, 0 to 1. Both directions are the
        // same circle read the same way — the readout survives OUTSIDE it and the mascot
        // lives inside it — and the direction is only whether the number is rising or
        // falling. It used to be a progress from 0 with the CLIP SENSE FLIPPED depending
        // on which way it was going, which worked and could not be interrupted: reversing
        // mid-sweep meant restarting from nothing in the opposite sense, so a second tap
        // had to be ignored until the first finished.
        val v = ambientSweep(rippleNow)
        val hidden = v >= 0.999f
        val front = if (v > 0.001f && v < 0.999f) android.graphics.Path().apply {
            addCircle(rippleCx, rippleCy, rippleReach() * v, android.graphics.Path.Direction.CW)
        } else null
        canvas.save()
        if (front != null) canvas.clipOutPath(front)

        // Opaque, and the colour is the user's.
        //
        // This was a fixed 0xB4 alpha so the system's rear wallpaper showed through. The
        // compromise cost contrast on the bar tracks, and a user who wants a particular
        // background can now simply choose it. The alpha must stay FULL: at zero the
        // panel stops working entirely — with nothing occluding it, MIUI's
        // SubScreenLauncher resumes, takes the display, and leaves RearActivity's window
        // out of the window list while its task record stays alive, which the respawn
        // watchdog cannot see. Any colour is fine; transparency is not.
        //
        // AMBIENT is the one exception, and it is a deliberate risk: see AMBIENT_ALPHA.
        if (!hidden) {
            // Hue and opacity ease SEPARATELY, and they have to: ColorTween runs through
            // RampColor.lerp, which forces alpha to 0xFF so a ramp stop or an accent can
            // never be saved half-invisible. Alpha therefore gets a Tween of its own —
            // the same curve, the same duration, so switching the background off reads
            // as one movement rather than a colour easing while a transparency snaps.
            val alpha = animateTo(
                "bg:alpha",
                if (config.showBackground) 255f else SEE_THROUGH_ALPHA.toFloat(),
            )
            val hue = colorFor("bg", config.background or (0xFF shl 24))
            canvas.drawColor((hue and 0x00FFFFFF) or (alpha.toInt().coerceIn(0, 255) shl 24))
        }
        // Partial fill, so the system's own rear-screen background shows through while
        // this surface still occludes. RearTheme's translucency does nothing on its own
        // — it was in place for a full build while this line painted Palette.BG at full
        // opacity over the whole surface, hiding everything behind it.
        //
        // DO NOT take this to zero. It was tried, and the panel stops working: with
        // nothing occluding it, MIUI's SubScreenLauncher resumes and takes the display,
        // and RearActivity's window leaves the window list entirely while its process
        // and task record stay alive. The panel then shows MIUI's clock-and-steps
        // widget and none of our marks. That failure is also invisible to the respawn
        // watchdog, which asks isAlive() about the TASK — and the task is still there.
        //
        // The cost of any alpha below 255 is contrast: the bar TRACKS are a near-black
        // #2A2C2E picked against #1D1F21, so over a light background they are the first
        // thing to disappear. Filled bars, rings and type are bright palette colours
        // and hold up.
        //
        // Burn-in mitigation, applied unconditionally. The panel is always-on at the
        // user's explicit request (RearActivity holds FLAG_KEEP_SCREEN_ON with no
        // toggle — MIUI otherwise kills the rear display in about five seconds), so
        // there is no "off" state during which this composition isn't lit. Two fixed
        // rings, four fixed bars, a fixed corner mark, all saturated colour, never
        // otherwise moving, is close to worst-case burn-in geometry for an OLED panel.
        // This translate is the only thing standing between that and irreversible
        // ghosting — it is load-bearing, not cosmetic. Do not gate it behind a flag,
        // do not make it conditional, and do not remove it to "simplify" this method.
        //
        // Applied before drawRing/drawCanvasSide so it moves the *entire* composition,
        // including the ACRNM mark drawn at the end of drawCanvasSide — small, bright,
        // high-contrast and pinned to one corner, the single highest burn-in risk on
        // the panel. A drift that moved the bars but left the mark pinned would miss
        // the worst offender. The drawColor above is unaffected (it fills the canvas's
        // clip, not a shape, so it ignores the transform) which is correct: a uniform
        // wash has nothing to protect against ghosting.
        //
        // SystemClock.elapsedRealtime(), not System.currentTimeMillis(): this must be
        // monotonic so an NTP correction or timezone change can't produce a sudden,
        // meaningless jump in drawn position. Same reasoning as RearActivity's
        // armEvictionTimer() guard, which uses the same clock for the same reason.
        val nowReal = SystemClock.elapsedRealtime()
        val (dx, dy) = driftOffset(nowReal)

        // The rings do NOT translate, and that is a deliberate exception to the
        // paragraph above rather than an oversight.
        //
        // These two rings are drawn concentric with the phone's actual camera lenses.
        // Sliding them ±4px slides them off the lenses, and because the lens is a hard
        // physical edge sitting right there for comparison, a 4px error is glaring in a
        // way the same 4px on a bar is not — the user noticed it and assumed the OS was
        // shifting pixels underneath us.
        //
        // They are still protected, just along the axis that alignment does not care
        // about. Breathing the RADIUS moves which pixels of the annulus are lit while
        // leaving the centre exactly on the lens, because a circle's centre is what
        // "aligned" means here and its radius is free. Rotation would not do: a full
        // circle is invariant under it, so the track — the static part, and the part
        // most at risk — would not move at all.
        val breath = ringBreath(nowReal)
        // Derived from the config that arrived with the state, not held separately —
        // one payload in, one appearance out, with no second channel that can disagree.
        // In single-account mode the ring is centred on the block's own height rather
        // than sitting at its panel position. On the panel the two rings are placed to
        // meet the camera lenses; in a preview there are no lenses, and a ring pinned to
        // where the top lens would be leaves the rows it belongs to floating beside
        // nothing.
        val geometry = PanelGeometry.of(config.ringLayout).let { g ->
            if (onlyAccount == null) g
            else PanelGeometry(
                personal = g.personal.copy(cy = Panel.HEIGHT / 2f),
                work = g.work.copy(cy = Panel.HEIGHT / 2f),
            )
        }
        // Each ring reports on whatever it is configured to, which is not necessarily
        // its own account: pointing both rings at one account's two limits is a
        // legitimate thing to want, and the ring's colour follows the account it reports
        // on rather than the slot it occupies.
        // In single-account mode only that account's ring is drawn, and it takes the
        // first ring position — the preview is showing one account's panel, not its
        // slot in someone else's.
        // Ring N belongs to the Nth account in display order, so reordering the accounts
        // carries the rings with them.
        // The readout — rings, bars, mascots, extras — is drawn inside one clip so the
        // ripple takes all of it together. Clipping each piece would let them vanish at
        // slightly different moments, which reads as the panel breaking rather than
        // closing.
        val order = orderedAccounts()
        val positions = listOf(geometry.personal, geometry.work)
        if (!hidden) order.forEachIndexed { position, accountIndex ->
            val account = Account.entries.getOrNull(accountIndex) ?: return@forEachIndexed
            if (onlyAccount != null && account != onlyAccount) return@forEachIndexed
            // A previewed account takes the first ring position whatever its place in
            // the order: the preview shows that account's panel, not its slot.
            val at = if (onlyAccount != null) 0 else position
            val geo = positions.getOrNull(at) ?: return@forEachIndexed
            drawRing(canvas, geo, config.rings.getOrElse(position) { RingSpec() }, account, breath)
        }

        // The strip pushes the readout down and the RINGS DO NOT MOVE. They are drawn
        // concentric with the phone's actual lenses, so shifting them would put two
        // circles a hundred pixels away from the hardware they trace. Everything else on
        // the canvas side rides down and some of it leaves the bottom of the panel,
        // which is the trade: a notification is asking for attention, and for five
        // seconds it gets it.
        val push = noticeHeight(nowReal) * noticeSlide(nowReal)
        canvas.save()
        canvas.translate(dx, dy + push)
        if (!hidden) drawCanvasSide(canvas, dx, dy + push)
        // The extras belong to the panel as a whole, not to an account, so a
        // single-account preview leaves them out — see onlyAccount.
        if (onlyAccount == null && !hidden) drawExtras(canvas)
        // Same rule as the extras: it acts on the panel rather than on an account, so a
        // single-account preview leaves it out — and a preview is scaled, which would
        // put the touch target in the wrong place even if it were wanted there.
        if (onlyAccount == null && config.showCamera && !hidden) drawCamera(canvas, dx, dy + push)
        else cameraButton = null
        if (onlyAccount == null && config.showClose && !hidden) drawClose(canvas, dx, dy + push)
        else closeButton = null
        canvas.restore()
        canvas.restore()   // the readout clip

        // Over everything, and outside the readout's ripple clip: a notification arriving
        // while the panel is closing should still be readable.
        drawNotice(canvas, noticeSlide(nowReal), nowReal)

        // The mascot is outside the readout's clip because it belongs to the other side
        // of the ripple. The BUTTON is not — it is part of the readout and is swept away
        // with it, drawn up with drawCamera above.
        if (onlyAccount == null && config.showClose) {
            // The mascot is clipped to the EXACT COMPLEMENT of the readout, so one
            // circle carries both: as the ripple eats the panel it uncovers the mascot
            // already walking underneath, and reversing it covers the mascot back up.
            //
            // It used to cross-fade in and out over the top, which is what made it look
            // like it spawned — a figure appearing where nothing was, rather than one
            // that had been there all along and was being revealed.
            if (v > 0.001f) {
                canvas.save()
                if (front != null) canvas.clipPath(front)
                // The ambient background belongs to this side too, or the swept region
                // is briefly fully transparent and then snaps to its alpha at the end.
                canvas.drawColor((config.background and 0x00FFFFFF) or (SEE_THROUGH_ALPHA shl 24))
                drawAmbientWalk(canvas, nowReal)
                canvas.restore()
            }
        } else {
            closeButton = null
            ambient = false
            rippleTo = 0f
        }

        // Next frame only while something is still moving. When everything has settled
        // this stops entirely and the view falls back to the 15s idle tick — an
        // always-on panel must not hold a 60fps loop for a display that is not changing.
        if (animating) postInvalidateOnAnimation()
    }

    /**
     * The mascot colour of whichever account is shown first.
     *
     * Ordered, not index zero: the accounts can be rearranged, and "first" here has to
     * mean the same thing it means everywhere else on this panel.
     */
    private fun firstMascotColor(): Int {
        val index = config.orderedAccounts(Account.entries.size).firstOrNull() ?: 0
        return colorFor(
            "ambient:body",
            config.styleFor(index).mascotBody ?: MascotRenderer.BODY_GREEN,
        )
    }

    /** This account's configured accent, falling back to its built-in. */
    private fun accentOf(account: Account): Int =
        colorFor("accent:${account.name}",
            config.styleFor(account.ordinal).accent ?: account.accent)

    /** Its configured label, falling back to its built-in. */
    private fun labelOf(account: Account): String =
        config.styleFor(account.ordinal).label?.takeIf { it.isNotBlank() } ?: account.label

    /** @param breath radial burn-in offset, added to the ring's radius. See onDraw. */
    private fun drawRing(canvas: Canvas, g: RingGeometry, spec: RingSpec, account: Account, breath: Float = 0f) {
        val status = state.statuses.firstOrNull { it.account == account }
        val kind = when (spec.window) {
            RingWindow.SESSION -> WindowKind.SESSION
            RingWindow.WEEKLY_ALL -> WindowKind.WEEKLY_ALL
            RingWindow.WEEKLY_SCOPED -> WindowKind.WEEKLY_SCOPED
        }
        val window = status?.snapshot?.windows?.firstOrNull { it.kind == kind }

        // NONE empties the ring rather than switching it off. A ring that vanishes
        // between two frames reads as a rendering fault: the panel is always on, nobody
        // is watching the moment a setting changes, and the first thing they see is a
        // ring that used to be there. So "off" is said in the motion the ring already
        // makes — it unwinds to empty and dims away as it goes. The same Tween at the
        // same 1800ms, started by the same config change, so the sweep and the fade
        // land together rather than reading as two events.
        val presence = animateTo("ring:${account.name}:presence",
            if (spec.metric == RingMetric.NONE) 0f else 1f)
        if (presence <= 0f) return

        track.strokeWidth = g.stroke
        track.alpha = (255 * presence).toInt()
        val r = g.radius + breath
        scratch.set(g.cx - r, g.cy - r, g.cx + r, g.cy + r)
        canvas.drawArc(scratch, 0f, 360f, false, track)

        // No data, or data we can't vouch for: dim ring, no arc. Never render
        // a stale or absent reading as if it were live.
        if (status == null || window == null || status.health == Health.AUTH) return

        arc.strokeWidth = g.stroke
        // Coloured while the data is good, dimmed once a fetch has actually failed.
        // The tag beside the account label says which failure it was, so grey means
        // "this is history" rather than the unexplained grey it used to mean.
        // The ring is a CLOCK, not a gauge: it sweeps toward the 5-hour reset, filling
        // as the window runs out of time. Utilisation for the same window is the 5H bar
        // on the canvas side — the two were the other way round until the user swapped
        // them, and a ring around a lens reads far more naturally as a dial than as a
        // percentage.
        //
        // Accent-coloured, never the ramp, and that follows directly from what it now
        // plots. On the bars, a full bar means a limit is nearly spent and the
        // green-to-red ramp says so. A full ring means the window is about to RESET,
        // which is good news; ramping it to red would invert the panel's one colour
        // language. This is the same reasoning that used to apply to the 5H bar back
        // when the bar was the thing showing time.
        // A ring showing UTILISATION is a gauge and takes the ramp, exactly like a bar;
        // a ring showing TIME is a clock, fills as the window runs out, and must not be
        // ramped — a full clock means a reset is imminent, which is good news, and
        // turning it red would invert the panel's one colour language.
        val target = when (spec.metric) {
            RingMetric.TIME_TO_RESET -> windowElapsed(window)
            RingMetric.UTILIZATION -> window.utilization
            // Not a reading the ring reports — the value it retreats to.
            RingMetric.NONE -> 0f
        }
        // Keyed by the ACCOUNT alone, not by what its ring currently shows. A Tween
        // opens on its first target instead of animating up from zero, so folding the
        // window and the metric into the key meant every change to what a ring reports
        // built a fresh tween and snapped — including the change to NONE, where the
        // unwind is the whole point.
        val shown = animateTo("ring:${account.name}", target).coerceIn(0f, 1f)
        arc.color = when {
            status.health != Health.OK -> Panel.Palette.DIM
            spec.metric == RingMetric.UTILIZATION -> RampColor.at(shown, config.rampColors)
            else -> accentOf(account)
        }
        arc.alpha = (255 * presence).toInt()
        canvas.drawArc(scratch, -90f, 360f * shown, false, arc)
    }

    /** Drawn after the rings, inside onDraw: bars, the reset footer and the mark. */
    private fun drawCanvasSide(canvas: Canvas, dx: Float, dy: Float) {
        // In single-account mode: the account's own rows and mascot, and nothing else.
        //
        // The masthead, the clock and battery, and the maker's mark all describe the
        // PANEL rather than any account on it. Drawing them into a preview of one
        // account would make them look like part of that account's block, and would ask
        // the eye to ignore two thirds of what it is being shown.
        if (onlyAccount == null) drawTitle(canvas)
        drawBars(canvas)
        drawMascots(canvas)
        if (onlyAccount == null) drawMark(canvas, dx, dy)
    }

    /**
     * Masthead: the Claude Code mark and "CLAUDE USAGE", across the top of the canvas
     * side. Replaces the "7-DAY WINDOWS" section label that used to sit here — the
     * bars are self-describing (each row is named, and the 5-hour figures are on the
     * rings), so that label spent a line of a 572px panel restating the obvious.
     *
     * Fixed green, never health-tinted. Everything else on this panel changes colour to
     * mean something — the ramp on the rings and bars, DIM when a fetch failed — so a
     * masthead that also changed colour would read as a fifth status signal. It is
     * branding, and it stays put.
     *
     * The mark is tinted to the same green from the same constant, not a second literal:
     * title and logo drifting apart is exactly what a copied hex value causes.
     */
    /**
     * Clock and battery, on the masthead line, flanking the title.
     *
     * Placed there because it is the only band on the panel with slack in it: the bar
     * rows are packed to fit and the ring column is the lens area. Both are optional and
     * neither shifts anything when off.
     *
     * Dim rather than accent-coloured. They are context, not readings — the panel is
     * about usage, and a bright clock would compete with the numbers it sits above.
     */
    /**
     * Clock and battery, stacked in the top-right corner.
     *
     * Both on the right, one above the other, rather than flanking the title. The
     * masthead is centred on the bar column, so anything on the left of it sat over the
     * ring column and read as belonging to the rings. Stacked in one corner they are
     * plainly a separate block of context, and the second line costs nothing: the band
     * above the first account heading is empty.
     */
    /**
     * Ambient mode: the readout rippled away, leaving the mascot walking the bottom.
     *
     * The panel is still drawing, still polling, still alive. This is the difference
     * between quiet and gone, and it matters here more than most places: an app that
     * dismisses its own panel cannot put it back — placement needs Shizuku and a task
     * that still exists.
     */
    private var ambient = false

    /**
     * When the current ripple started, or 0 when nothing is animating.
     *
     * Wall clock rather than a frame counter: this view is destroyed and recreated
     * constantly by the placement machinery, and a ripple measured in frames would
     * restart from nothing every respawn.
     */
    private var rippleStart = 0L
    private var rippleFrom = 0f
    private var rippleTo = 0f

    /**
     * Where the current ripple radiates from — the point that was touched.
     *
     * Not a fixed origin. Closing, the finger is on the close button and the two are the
     * same thing; opening, it is on the mascot, which is somewhere else entirely and
     * moving. A ripple that always came out of the button would, on the way back, appear
     * to originate from a spot nobody had touched.
     */
    private var rippleCx = CLOSE_CX
    private var rippleCy = CLOSE_CY

    /** Where the close button is, in the view's own coordinates. Null when hidden. */
    var closeButton: android.graphics.RectF? = null
        private set

    /**
     * Where the ripple is, 0 at the button and 1 covering the panel, or null when the
     * panel is simply drawn as it is.
     *
     * Returns null in both settled states — fully shown and fully ambient — so the
     * drawing code only pays for the clip while something is actually moving.
     */
    /**
     * How far the ambient state has swept, 0 to 1.
     *
     * Interruptible by construction. A tap sets a new target and the sweep continues
     * FROM WHERE IT IS — closing halfway and changing your mind reverses from halfway,
     * rather than being ignored until the first animation finishes or snapping back to
     * start again. The remaining distance is what sets the duration, so a small reversal
     * is quick and a full one is not.
     */
    private fun ambientSweep(nowMs: Long): Float {
        if (rippleStart == 0L) return rippleTo
        val span = (nowMs - rippleStart).toFloat() / RIPPLE_MS
        if (span >= 1f) { rippleStart = 0L; return rippleTo }
        animating = true
        // Eased with the same curve as everything else that moves on this panel, so a
        // ripple and a bar settling read as one machine rather than two.
        return rippleFrom + (rippleTo - rippleFrom) * Tween.easeInOut(span.coerceIn(0f, 1f))
    }

    /**
     * Where the camera button is, in the view's own coordinates, or null when it is not
     * shown. See [drawCamera] for why the drift is subtracted out of it.
     */
    var cameraButton: android.graphics.RectF? = null
        private set

    /** Set by RearActivity. Nothing on the front screen listens. */
    var onCameraTap: (() -> Unit)? = null

    /**
     * The strip the transport buttons live in, or null when the row is not up.
     *
     * ONE rectangle for all three, plus where each glyph's centre is inside it. Not
     * three rectangles: see MediaLayout.Row.centres for what three padded rectangles on
     * 35-unit centres actually did.
     */
    private var mediaStrip: android.graphics.RectF? = null
    private var mediaCentres: List<Float> = emptyList()

    /** In drawing order, matching [mediaCentres]. */
    private val mediaCommands = listOf(
        RearActivity.MEDIA_PREVIOUS, RearActivity.MEDIA_PLAY_PAUSE, RearActivity.MEDIA_NEXT,
    )

    /**
     * Which transport button a touch belongs to, or null if it missed the strip.
     *
     * The strip keeps the usual slop — the whole point is that this is pressed without
     * looking — but WITHIN it the nearest centre wins outright, so the padding can be as
     * generous as the surface needs without one button eating another.
     */
    private fun mediaAt(x: Float, y: Float): String? {
        val strip = mediaStrip ?: return null
        if (!near(strip, x, y)) return null
        val i = mediaCentres.indices.minByOrNull { kotlin.math.abs(mediaCentres[it] - x) }
            ?: return null
        return mediaCommands[i]
    }

    /** Set by RearActivity, which forwards the press to the main process. */
    var onMediaTap: ((String) -> Unit)? = null

    private fun near(r: android.graphics.RectF?, x: Float, y: Float): Boolean {
        val b = r ?: return false
        return x >= b.left - CAMERA_SLOP && x <= b.right + CAMERA_SLOP &&
            y >= b.top - CAMERA_SLOP && y <= b.bottom + CAMERA_SLOP
    }

    @Suppress("ClickableViewAccessibility")   // the rear panel has no accessibility path
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        val onCamera = !ambient && near(cameraButton, event.x, event.y)
        // In ambient mode the whole panel is the way back, not just the button. The
        // mascot is walking and the button is a 16-unit disc on the back of a phone
        // being held — asking someone to hit either is asking them to look at it, which
        // is the one thing this surface cannot be used for.
        // In ambient, ONLY the mascot brings the panel back. Tapping anywhere was
        // forgiving but it made the whole rear display inert to everything else, and the
        // mascot is a deliberate target: it is the one thing drawn there, and it is
        // where the button turned into.
        val onClose =
            if (ambient) near(mascotButton, event.x, event.y)
            else near(closeButton, event.x, event.y)
        // The transport buttons, which exist only while the row is up and never in
        // ambient mode — the readout has been swept away, and with it the slot they
        // live in. Nothing here needs a showMedia check: the strip is null unless the
        // row was actually drawn.
        val onMedia = if (ambient) null else mediaAt(event.x, event.y)
        val hit = onCamera || onMedia != null || (config.showClose && onClose)

        return when (event.actionMasked) {
            // Claim the gesture on the way down if it started on a target, so a slight
            // drag still counts — a finger on the back of a phone is not steady, and
            // without claiming it there is no ACTION_UP to act on.
            android.view.MotionEvent.ACTION_DOWN -> hit
            android.view.MotionEvent.ACTION_UP -> {
                when {
                    onCamera -> onCameraTap?.invoke()
                    // Before the close branch: a transport press is a press on the
                    // readout, not a request to sweep it away.
                    onMedia != null -> onMediaTap?.invoke(onMedia)
                    hit -> {
                        val now = android.os.SystemClock.elapsedRealtime()
                        ambient = !ambient
                        // Continue from wherever the sweep is, not from the start. The
                        // duration shrinks with the distance left, so turning it around
                        // near the end is near-instant rather than a full crossing.
                        rippleFrom = ambientSweep(now)
                        rippleTo = if (ambient) 1f else 0f
                        // From the finger, whichever direction we are going.
                        rippleCx = event.x
                        rippleCy = event.y
                        rippleStart = now
                        invalidate()
                    }
                }
                hit
            }
            else -> false
        }
    }

    /**
     * A camera, in the corner the artwork does not reach.
     *
     * Bottom-right specifically: the mark region ends at BAR_RIGHT and the lens column
     * owns the left, so this is the one part of the panel with nothing in it. It is also
     * the furthest point from the rings, which matters because it is the only thing here
     * that can be pressed by accident.
     *
     * The glyph is traced from the same Terminess Nerd Font as the clock and the
     * battery, by the same generator. It was hand-drawn from rectangles first, and sat
     * beside two traced icons looking exactly like what it was.
     */
    private fun drawCamera(canvas: Canvas, dx: Float, dy: Float) {
        val h = CAMERA_H
        val w = h * PanelIcons.CAMERA.aspect
        // Vertically centred on the bottom artwork, and then inset from the right edge
        // by exactly the gap that centring leaves BELOW it.
        //
        // Not the artwork's own bottom margin, which is what this used first: the
        // artwork is 70 units tall and the camera 30, so centring the smaller one on the
        // larger leaves 46 units under it while the artwork keeps 26. Matching the
        // artwork's number therefore looked cramped — 26 beside it, 46 below it, on a
        // glyph small enough for the difference to read as a mistake.
        val centreY = Panel.HEIGHT - MARK_BOTTOM - MARK_H / 2f
        val bottom = centreY + h / 2f
        val margin = Panel.HEIGHT - bottom
        val right = Panel.WIDTH - margin
        extras.color = colorFor("camera", config.cameraTint)
        // Positioned by its own left edge rather than by a square cell: drawIcon centres
        // within a cell of side h, which is right for the clock and battery sitting in a
        // column and wrong for something pinned to the panel's right margin.
        // drawIcon centres its glyph in a square cell of side h, which is right for the
        // clock and battery stacked in a column and wrong for something pinned to the
        // panel's right margin. Undo the centring so the glyph's own right edge lands on
        // CONTENT_RIGHT, where every other right-aligned thing on the panel ends.
        drawIcon(canvas, PanelIcons.CAMERA, right - w - (h - w) / 2f, bottom - h, h)

        // Recorded WITHOUT the burn-in drift the canvas is currently translated by.
        // Touches arrive in the view's own coordinates, which the drift never moves — it
        // shifts what is painted, not where the window is. Storing the drifted rect would
        // walk the target a few pixels away from the glyph on a ten-minute cycle, which
        // is the kind of bug that gets blamed on the finger.
        cameraButton = android.graphics.RectF(
            right - w - dx, bottom - h - dy, right - dx, bottom - dy,
        )
    }

    /**
     * How far the ripple must travel to clear the panel: the distance from the button to
     * the farthest corner. Anything shorter leaves a wedge of readout behind.
     */
    private fun rippleReach(): Float {
        // The farthest CORNER from wherever it started. Anything shorter leaves a wedge
        // of the old state standing in the corner opposite the touch, and which corner
        // that is now depends on where the finger landed.
        var far = 0f
        for (x in floatArrayOf(0f, Panel.WIDTH.toFloat())) {
            for (y in floatArrayOf(0f, Panel.HEIGHT.toFloat())) {
                far = maxOf(far, kotlin.math.hypot(x - rippleCx, y - rippleCy))
            }
        }
        return far
    }

    /**
     * The close button: a filled disc with an X knocked out of it.
     *
     * Drawn INSIDE the readout's clip, with the rest of the panel, so the ripple sweeps
     * it away and back exactly as it does everything else. It used to sit outside and
     * cross-fade on its own — the reasoning being that the ripple came out of it and so
     * must not eat it, which sounded right and looked wrong: one element dissolving
     * while the surface around it was wiped reads as two different effects.
     *
     * Filled rather than outlined. Two thin strokes on a dark panel, at a size meant to
     * be hit without looking, is not a target; the solid shape is what makes it findable
     * and the cross then reads as a hole in it rather than as more line work.
     */
    private fun drawClose(canvas: Canvas, dx: Float, dy: Float) {
        // Drift subtracted, for the same reason the camera's is: touches arrive in the
        // view's coordinates, which the burn-in translate never moves.
        closeButton = android.graphics.RectF(
            CLOSE_CX - CLOSE_R - dx, CLOSE_CY - CLOSE_R - dy,
            CLOSE_CX + CLOSE_R - dx, CLOSE_CY + CLOSE_R - dy,
        )

        closePaint.style = Paint.Style.FILL
        closePaint.color = colorFor("close", config.closeColor)
        canvas.drawCircle(CLOSE_CX, CLOSE_CY, CLOSE_R, closePaint)

        // The X in the panel's own background, so the disc reads as missing material
        // rather than as a second colour laid on top.
        val arm = CLOSE_R * 0.44f
        closePaint.style = Paint.Style.STROKE
        closePaint.strokeWidth = CLOSE_STROKE
        closePaint.strokeCap = Paint.Cap.SQUARE
        closePaint.color = colorFor("bg", config.background or (0xFF shl 24))
        canvas.drawLine(CLOSE_CX - arm, CLOSE_CY - arm, CLOSE_CX + arm, CLOSE_CY + arm, closePaint)
        canvas.drawLine(CLOSE_CX + arm, CLOSE_CY - arm, CLOSE_CX - arm, CLOSE_CY + arm, closePaint)
    }

    /**
     * The mascot, walking the full width of the panel, with nothing else on it.
     *
     * WalkRig rather than a clip, for the same reason the idle state uses it: it is the
     * only mascot that travels, and travelling is the entire point of leaving it here.
     * It spans the whole panel because there is no bar to stay on any more.
     */
    /** Where the walking mascot is, for the tap that brings the panel back. */
    var mascotButton: android.graphics.RectF? = null
        private set

    private fun drawAmbientWalk(canvas: Canvas, nowMs: Long) {
        // The walk is a continuous animation, so it has to ask for the next frame the
        // way every other moving thing here does. Without this the panel stopped
        // redrawing the moment the ripple settled — `animating` was only ever set while
        // the sweep was in flight — and the mascot froze mid-stride with nothing else on
        // screen to make it obvious the whole surface had stalled.
        animating = true

        val stand = MascotFrames.CLIPS.firstOrNull { it.name == MascotMood.STAND }
        val h = MASCOT_HEIGHT * (stand?.scale ?: 1f)
        val w = h * WalkRig.ASPECT
        // The full width, now that the close button has moved off this row.
        val left = CONTENT_EDGE
        val top = Panel.HEIGHT - MARK_BOTTOM - h
        val span = Panel.WIDTH - MARK_BOTTOM - left - w
        mascotButton = WalkRig.boundsAt(nowMs, left, top, h, span)
        WalkRig.draw(
            canvas, nowMs,
            left = left,
            top = top,
            h = h,
            span = span,
            paint = mascotPaint,
            // The FIRST account's mascot colour, not the close button's. This is the
            // same animal that walks the top bar when the panel is up — it should not
            // change species on its way behind the readout.
            bodyColor = firstMascotColor(),
        )
    }

    /**
     * Show another app's notification.
     *
     * A new one arriving while one is up does NOT restart the old one's clock — it
     * replaces it, and the two change places on the wheel. That is what [leaving] is
     * for: through the turn there are two notices on screen, one going and one coming.
     */
    fun showNotification(
        app: String,
        pkg: String,
        title: String,
        text: String,
        moreTitle: String = "",
        moreText: String = "",
    ) {
        val now = SystemClock.elapsedRealtime()
        // Only a REPLACEMENT turns the wheel. The first notice of a run arrives into an
        // empty strip that is itself sliding in, and turning at the same time would have
        // it enter twice over.
        if (notice != null) { leaving = notice; wheelAt = now }
        notice = Notice(app, pkg, title, text, moreTitle, moreText)
        noticeAt = now
        invalidate()
    }

    /** What is playing, as much of it as the panel shows. */
    private data class Media(
        val title: String,
        val artist: String,
        val playing: Boolean,
        /** Where the cover was written, or empty. See MediaWatcher.writeArt. */
        val artPath: String = "",
        /** The timeline. 0 duration means there is none — see [elapsed]. */
        val durationMs: Long = 0L,
        val positionMs: Long = 0L,
        val positionAtMs: Long = 0L,
        val speed: Float = 1f,
    ) {
        /** See MediaLayout.elapsed, which is where the arithmetic lives. */
        fun elapsed(nowMs: Long): Long = MediaLayout.elapsed(
            positionMs, positionAtMs, speed, playing, durationMs, nowMs,
        )
    }

    private var media: Media? = null

    /** Where the artwork/controls drum is and where it is heading — see [mediaSwap]. */
    private var mediaFrom = 0f
    private var mediaTo = 0f
    private var mediaAt = 0L

    /** Same three fields again for the play/pause glyph, which turns on its own clock:
     *  a track can be paused without the row going anywhere. */
    private var playFrom = 0f
    private var playTo = 0f
    private var playAt = 0L

    /**
     * What the main process says is playing.
     *
     * [active] false covers both "nothing is playing" and "the feature is off", which
     * are the same thing to the panel: give the slot back to the artwork.
     */
    fun showMedia(
        active: Boolean,
        title: String,
        artist: String,
        playing: Boolean,
        artPath: String = "",
        durationMs: Long = 0L,
        positionMs: Long = 0L,
        positionAtMs: Long = 0L,
        speed: Float = 1f,
    ) {
        val now = SystemClock.uptimeMillis()
        val next =
            if (active && config.showMedia) {
                Media(title, artist, playing, artPath,
                    durationMs, positionMs, positionAtMs, speed)
            } else {
                null
            }

        // The ROW's drum turns only when the slot changes hands. A track advancing while
        // the controls are already up is new text in a row that is already there, and
        // sliding the whole slot for it would say the artwork had come and gone.
        val wasShown = media != null
        if (next != null != wasShown) {
            mediaFrom = mediaSwap(now)
            mediaTo = if (next != null) 1f else 0f
            mediaAt = now
        }

        // The GLYPH's state is only REPORTED here, not acted on — see PlayGate, which
        // holds a verdict back until the player has stopped changing its mind.
        val reported = next?.playing == true
        if (next != null && !wasShown) {
            // Arriving: adopt it outright and open the glyph on it, no tween and no wait.
            playGate.snap(reported)
            playFrom = if (reported) 1f else 0f
            playTo = playFrom
            playAt = 0L
        } else {
            playGate.report(reported, now)
        }

        media = next
        invalidate()
    }

    /**
     * Commit the transport state once it has held still, and only then move the glyph.
     *
     * Skipping a track is not one event. The player reports the old track stopping, then
     * a moment with nothing playing while the next one loads, then playing again — so a
     * glyph driven by every report flicked from pause to play and back on every press of
     * next or previous. Half a second of quiet is the difference between that and someone
     * actually pausing.
     *
     * The cost is honest and small: a real pause takes half a second to show. The button
     * has already been pressed by then, and a button that twitches under the finger is
     * worse than one that answers deliberately.
     */
    private fun settlePlaying(nowMs: Long) {
        // Keep the frame clock open while a verdict is pending, or it would arrive
        // whenever something else next happened to redraw rather than on time.
        if (playGate.waiting()) animating = true
        if (!playGate.commit(nowMs)) return
        // Retargeted from wherever the glyph is, so a change arriving mid-travel reverses
        // instead of snapping — the same rule the ambient ripple follows.
        playFrom = playSwap(nowMs)
        playTo = if (playGate.shown) 1f else 0f
        playAt = nowMs
    }

    private val playGate = PlayGate(MEDIA_SETTLE_MS)

    /** 0 while the artwork holds the slot, 1 while the controls do. */
    private fun mediaSwap(nowMs: Long): Float = slide(mediaFrom, mediaTo, mediaAt, nowMs)

    /** 0 on the play glyph, 1 on the pause glyph. */
    private fun playSwap(nowMs: Long): Float = slide(playFrom, playTo, playAt, nowMs)

    /** One eased leg, shared by the two drums above. */
    private fun slide(from: Float, to: Float, startMs: Long, nowMs: Long): Float {
        if (startMs == 0L) return to
        val t = (nowMs - startMs).toFloat() / MEDIA_MS
        if (t >= 1f) return to
        animating = true
        return from + (to - from) * Tween.easeInOut(t.coerceIn(0f, 1f))
    }

    /**
     * How far the strip is down, 0 to 1, on the same eased curve as everything else.
     *
     * Driven by whether a notice is live rather than by an explicit animation: the strip
     * is a consequence of there being something to say, so it retracts by itself the
     * moment the last one expires.
     */
    /**
     * How tall the strip needs to be right now.
     *
     * A quarter of the panel, plus a line for each of the group's other notifications.
     * Taken as the MAX of the arriving and departing notices while the wheel turns, or
     * the taller of the two would be cut off by a container sized for the shorter.
     */
    /** How far through a replacement the wheel is; 1 when nothing is being replaced. */
    private fun wheelTurn(nowMs: Long): Float =
        if (leaving == null) 1f
        else Tween.easeInOut(((nowMs - wheelAt).toFloat() / WHEEL_MS).coerceIn(0f, 1f))

    private fun heightOf(n: Notice?): Float =
        NOTICE_H + if (n?.hasMore == true) CASCADE_H else 0f

    /**
     * How tall the strip is right now, INTERPOLATED across a replacement.
     *
     * So a notification from another app — which has no sibling of its own to show —
     * collapses the second card as part of the same turn rather than snapping the strip
     * shorter when the wheel finishes. The height is part of the change, not a
     * consequence of it having happened.
     */
    private fun noticeHeight(nowMs: Long): Float = NOTICE_H + CASCADE_H * miniSlide(nowMs)

    /**
     * How far the mini card is out, 0 to 1.
     *
     * It arrives AFTER the headline and leaves BEFORE it, by [MINI_LAG] at each end,
     * rather than moving with it. Two cards starting and stopping on the same frame read
     * as one object changing size; staggered, the second one reads as a consequence of
     * the first — which is what it is.
     *
     * Leaving early is possible only because the dismissal is on a clock: the notice
     * expires at a known moment, so the mini can begin retracting before anything has
     * happened. A user-dismissed card could not do this.
     */
    private fun miniSlide(nowMs: Long): Float {
        val n = notice
        val age = nowMs - noticeAt
        val want = n != null && n.hasMore &&
            age >= MINI_LAG &&
            age <= NOTICE_MS - MINI_LAG
        val target = if (want) 1f else 0f
        if (target != miniTo) {
            miniFrom = miniNow(nowMs)
            miniTo = target
            miniAt = nowMs
        }
        val value = miniNow(nowMs)
        if (value != miniTo) animating = true
        // While waiting for the delay to elapse there is nothing to redraw for, but the
        // frame that starts it has to be asked for or the card never appears until
        // something else happens to invalidate.
        if (n != null && n.hasMore && age < MINI_LAG) animating = true
        return value
    }

    private fun miniNow(nowMs: Long): Float {
        if (miniAt == 0L) return miniTo
        val t = ((nowMs - miniAt).toFloat() / SLIDE_MS).coerceIn(0f, 1f)
        return miniFrom + (miniTo - miniFrom) * Tween.easeInOut(t)
    }

    private fun noticeSlide(nowMs: Long): Float {
        if (notice != null && nowMs - noticeAt > NOTICE_MS) notice = null
        if (leaving != null && nowMs - wheelAt > WHEEL_MS) leaving = null

        // Its own timing rather than the shared Tween, which runs at 1.8s. That is right
        // for a bar easing to a new reading — news arriving gently — and far too slow for
        // a panel that has to be in front of you before you have finished glancing at it.
        val target = if (notice != null) 1f else 0f
        if (target != slideTo) {
            slideFrom = noticeSlideNow(nowMs)
            slideTo = target
            slideAt = nowMs
        }
        val value = noticeSlideNow(nowMs)
        if (value != slideTo) animating = true
        return value
    }

    private fun noticeSlideNow(nowMs: Long): Float {
        if (slideAt == 0L) return slideTo
        val t = ((nowMs - slideAt).toFloat() / SLIDE_MS).coerceIn(0f, 1f)
        return slideFrom + (slideTo - slideFrom) * Tween.easeInOut(t)
    }

    /**
     * The notification strip: title and body on the left, the app's icon on the right.
     *
     * Only right of the camera cutout. The left of this panel is lens glass with the
     * rings drawn around it — a banner there would be half on a surface that cannot show
     * it, and the rings must not move, being aligned to physical hardware.
     *
     * Items enter from the top and leave through the BOTTOM, both at once when one
     * replaces another: a drum turning behind a window rather than one card fading into
     * the next. Sending the outgoing one back up the way it came would read as
     * "cancelled"; continuing downward says the next thing is already here.
     */
    private fun drawNotice(canvas: Canvas, slide: Float, nowMs: Long) {
        if (slide <= 0.001f) return
        val total = noticeHeight(nowMs)
        val top = -total + total * slide
        val turn = wheelTurn(nowMs)
        val r = cornerRadius() / CORNER_DIVISOR
        val m = miniSlide(nowMs)

        val miniTop = top + NOTICE_H
        // The step must CLEAR the curve it steps around. MINI_INSET_LEFT is 34 and the
        // radius here is about 32 — correct on this display and one bad panel away from
        // wrong, since the radius is read from the hardware and the inset is a constant.
        // Taking the larger of the two makes the relationship hold wherever it runs
        // instead of holding by two units of luck.
        val miniLeft = NOTICE_LEFT + maxOf(MINI_INSET_LEFT, r + MINI_CORNER_CLEARANCE)

        // ---- the mini card, drawn FIRST so it sits UNDER the headline ----------
        //
        // Stuck to the card above with no gap, running to the same right edge, but
        // stepped IN on the left — so the two make one shape with a shoulder rather than
        // two cards floating separately. It carries the same single rounded corner the
        // card above would have had, which is now the bottom of the whole assembly.
        //
        // It is TUCKED UP behind the headline by a full corner radius, and its contents
        // are padded down by the same amount, so nothing visible moves. The card is
        // taller than the band it shows in and the extra is hidden.
        //
        // That is the fix for a junction that read as clipped. The headline's bottom-left
        // corner is rounded and the mini's top-left was square, sitting exactly on the
        // headline's bottom edge — two cards of similar width met at a seam where one
        // curved away and the other did not, and the eye took the mini for the headline
        // cut off rather than for a second card. With the square corners hidden behind an
        // opaque card there is no seam to read: the mini simply continues out from under.
        //
        // Order matters and is the whole mechanism. Drawn after the headline, the tuck
        // would cover the card it is meant to hide behind.
        if (m > 0.001f) {
            // Slid up behind the headline and clipped to the band it has emerged into, so
            // it comes out from under the card above rather than growing in place — the
            // difference between something arriving and something inflating.
            val cardTop = miniTop - r - (1f - m) * CASCADE_H
            val miniBottom = cardTop + r + CASCADE_H
            val mini = android.graphics.Path().apply {
                addRoundRect(
                    miniLeft, cardTop, Panel.WIDTH.toFloat(), miniBottom,
                    floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, r, r),
                    android.graphics.Path.Direction.CW,
                )
            }
            canvas.save()
            canvas.clipRect(miniLeft, cardTop, Panel.WIDTH.toFloat(), miniTop + CASCADE_H * m)
            canvas.clipPath(mini)
            noticePaint.style = Paint.Style.FILL
            noticePaint.color =
                colorFor("notice:bg", config.notificationGround or (0xFF shl 24))
            noticePaint.alpha = 255
            canvas.drawRect(
                miniLeft, cardTop, Panel.WIDTH.toFloat(), miniBottom, noticePaint)

            // Padded down by the tuck, which is what keeps the text where it was. Its own
            // wheel turns with the card above it, so a replacement moves both.
            val rowTop = cardTop + r
            leaving?.takeIf { it.hasMore }
                ?.let { drawMiniRow(canvas, it, rowTop + turn * CASCADE_H, CASCADE_H) }
            notice?.takeIf { it.hasMore }
                ?.let { drawMiniRow(canvas, it, rowTop - (1f - turn) * CASCADE_H, CASCADE_H) }
            canvas.restore()

            // Its outline OMITS THE TOP EDGE, and starts at the visible top rather than
            // the tucked one: the card above draws a line along its own bottom, and a
            // second one a pixel below it read as a doubled border rather than as two
            // sections meeting.
            canvas.save()
            canvas.clipRect(miniLeft, rowTop, Panel.WIDTH.toFloat(), miniTop + CASCADE_H * m)
            val edge = android.graphics.Path().apply {
                moveTo(miniLeft, rowTop)
                lineTo(miniLeft, miniBottom - r)
                quadTo(miniLeft, miniBottom, miniLeft + r, miniBottom)
                lineTo(Panel.WIDTH.toFloat(), miniBottom)
            }
            outline(canvas, edge, slide)
            canvas.restore()
        }

        // ---- the headline card, OVER it ---------------------------------------
        //
        // Rounded at the BOTTOM-LEFT only. That is the one corner of it sitting in open
        // panel — its top and right edges run off the screen, where the display's own
        // rounding already shapes them. Matching the hardware's radius, scaled down,
        // makes it read as part of the screen rather than a rectangle laid on it: the
        // display's 97 is a corner on a 572-tall screen and a quarter-circle on a
        // 143-tall card.
        //
        // The bottom-left curve stays, ALWAYS, and it was a mistake to fade it. The
        // reasoning was that a card attached below puts this corner in the middle of a
        // stepped shape, where a curve is a notch. That would be true if the mini card
        // covered this edge — but it is stepped in by MINI_INSET_LEFT, which clears the
        // curve, so the corner remains an exposed outside corner of the assembly and
        // wants rounding exactly as much as it did alone.
        val card = android.graphics.Path().apply {
            addRoundRect(
                NOTICE_LEFT, top, Panel.WIDTH.toFloat(), top + NOTICE_H,
                floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, r, r),
                android.graphics.Path.Direction.CW,
            )
        }
        canvas.save()
        canvas.clipPath(card)
        noticePaint.style = Paint.Style.FILL
        noticePaint.color = colorFor("notice:bg", config.notificationGround or (0xFF shl 24))
        noticePaint.alpha = 255
        canvas.drawRect(
            NOTICE_LEFT, top, Panel.WIDTH.toFloat(), top + NOTICE_H, noticePaint)

        // Position only. Nothing fades: this is a window onto a drum, and a drum's rows
        // do not become transparent as they turn — they pass the edge and are gone.
        leaving?.let { drawNoticeRow(canvas, it, top + turn * NOTICE_H) }
        notice?.let { drawNoticeRow(canvas, it, top - (1f - turn) * NOTICE_H) }
        canvas.restore()
        outline(canvas, card, slide)
    }

    /**
     * The display's own corner radius, asked of the display rather than assumed.
     *
     * The rear panel reports 97. Hardcoding that would be right for this phone and
     * silently wrong for the next one, and the API has existed since API 31 — there is
     * no reason to guess. Falls back to the measured value only if the display declines
     * to say, which it does on some emulated ones.
     */
    private fun cornerRadius(): Float {
        cornerRadius?.let { return it }
        val fromWindow = rootWindowInsets
            ?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)
            ?.radius
        return (fromWindow?.toFloat() ?: FALLBACK_CORNER).also { cornerRadius = it }
    }

    private var cornerRadius: Float? = null

    /** The hairline that makes a card read as laid OVER the panel rather than cut into it. */
    private fun outline(canvas: Canvas, shape: android.graphics.Path, slide: Float) {
        noticePaint.color = colorFor("notice", config.notificationTint)
        noticePaint.alpha = (90 * slide).toInt()
        noticePaint.style = Paint.Style.STROKE
        noticePaint.strokeWidth = 1.5f
        noticePaint.isAntiAlias = true
        canvas.drawPath(shape, noticePaint)
        noticePaint.alpha = 255
        noticePaint.style = Paint.Style.FILL
    }

    /** The mini card's contents: the next notification in the group, no icon. */
    private fun drawMiniRow(canvas: Canvas, item: Notice, y: Float, h: Float) {
        val left = NOTICE_LEFT + MINI_INSET_LEFT + CASCADE_PAD
        val width = Panel.WIDTH - left - NOTICE_PAD
        val mid = y + h / 2f

        cascadePaint.color = colorFor("notice", config.notificationTint)
        if (item.moreText.isBlank()) {
            cascadePaint.alpha = 210
            canvas.drawText(noticeClip(item.moreTitle, cascadePaint, width),
                left, mid + CASCADE_TEXT / 3f, cascadePaint)
        } else {
            cascadePaint.alpha = 230
            canvas.drawText(noticeClip(item.moreTitle, cascadePaint, width),
                left, mid - 2f, cascadePaint)
            cascadePaint.alpha = 165
            canvas.drawText(noticeClip(item.moreText, cascadePaint, width),
                left, mid + CASCADE_TEXT + 3f, cascadePaint)
        }
        cascadePaint.alpha = 255
    }

    /** One row of the wheel, at [y]. Clipped by the strip, never faded. */
    private fun drawNoticeRow(canvas: Canvas, item: Notice, y: Float) {
        val alpha = 255
        val left = NOTICE_LEFT + NOTICE_PAD
        val right = Panel.WIDTH - NOTICE_PAD

        val icon = icons.getOrPut(item.pkg) {
            runCatching { context.packageManager.getApplicationIcon(item.pkg) }.getOrNull()
        }
        // Bigger, and held further off the right edge than the text is.
        //
        // The strip is clipped by the DISPLAY'S own top-right corner, whose arc centre
        // sits at (807, 97) with a radius of 97. At the text's margin the icon's corner
        // came within five pixels of that arc — geometrically clear and visibly bitten,
        // because the icon is a rounded square and the eye reads the gap, not the maths.
        // At this inset it clears by fifteen.
        val size = NOTICE_H * ICON_FRACTION
        val iconLeft = Panel.WIDTH - ICON_INSET - size
        if (icon != null) {
            icon.setBounds(
                iconLeft.toInt(), (y + (NOTICE_H - size) / 2f).toInt(),
                (iconLeft + size).toInt(), (y + (NOTICE_H + size) / 2f).toInt(),
            )
            icon.alpha = alpha
            icon.draw(canvas)
        }

        val textRight = iconLeft - NOTICE_PAD
        label.color = colorFor("notice", config.notificationTint)
        label.alpha = (alpha * 0.6f).toInt()
        label.textAlign = Paint.Align.LEFT
        canvas.drawText(noticeClip(item.app.uppercase(), label, textRight - left),
            left, y + NOTICE_H * 0.30f, label)

        heading.color = colorFor("notice", config.notificationTint)
        heading.alpha = alpha
        heading.textAlign = Paint.Align.LEFT
        canvas.drawText(noticeClip(item.title, heading, textRight - left),
            left, y + NOTICE_H * 0.56f, heading)

        // The headline block sits in the top NOTICE_H of the strip whatever the strip's
        // total height, so the cascade below it starts at a fixed offset rather than a
        // fraction — a fraction would push the title around as the group grew.
        if (item.text.isNotBlank()) {
            label.alpha = alpha
            canvas.drawText(noticeClip(item.text, label, textRight - left),
                left, y + NOTICE_H * 0.80f, label)
        }

        label.alpha = 255
        heading.alpha = 255
    }

    /** [text] cut to whatever fits [width], with an ellipsis when it had to be cut. */
    private fun noticeClip(text: String, paint: Paint, width: Float): String {
        if (paint.measureText(text) <= width) return text
        var n = text.length
        while (n > 1 && paint.measureText(text.take(n) + "…") > width) n--
        return text.take(n) + "…"
    }

    private fun drawExtras(canvas: Canvas) {
        var baseline = TITLE_BASELINE
        if (config.showClock) {
            // Each extra takes its own colour, eased separately. They shared one until
            // the panel had three of them, at which point "the extras colour" stopped
            // describing anything — a clock and a battery are different readings and
            // there is no reason they should agree.
            extras.color = colorFor("clock", config.clockTint)
            val now = java.util.Calendar.getInstance()
            val text = String.format(
                "%02d:%02d",
                now.get(java.util.Calendar.HOUR_OF_DAY),
                now.get(java.util.Calendar.MINUTE),
            )
            // Icon on the RIGHT, text to its left. Both icons then share one edge at
            // CONTENT_RIGHT, so the two lines end on a common vertical rather than each
            // ending wherever its own text happens to stop.
            drawIcon(canvas, PanelIcons.CLOCK, CONTENT_RIGHT - EXTRA_ICON_H,
                baseline - EXTRA_ICON_H + 2f, EXTRA_ICON_H)
            extras.textAlign = Paint.Align.RIGHT
            canvas.drawText(text, CONTENT_RIGHT - EXTRA_ICON_H - 8f, baseline, extras)
            baseline += EXTRA_LINE_H
        }
        if (config.showDate) {
            val now = java.util.Calendar.getInstance()
            // Day of the week, then the day of the month. Not the month name: on a panel
            // read at a glance, "which day is it" is the question, and the month is the
            // one part of a date nobody needs reminding of.
            val text = String.format(
                "%s %02d",
                DAYS[now.get(java.util.Calendar.DAY_OF_WEEK) - 1],
                now.get(java.util.Calendar.DAY_OF_MONTH),
            )
            extras.color = colorFor("date", config.dateTint)
            // Icon on the right like its neighbours. It carries no information "TUE 20"
            // does not — but a column of three rows where one lacks the mark the other
            // two share reads as a rendering fault, and the alignment is worth more here
            // than the argument against ornament.
            drawIcon(canvas, PanelIcons.CALENDAR, CONTENT_RIGHT - EXTRA_ICON_H,
                baseline - EXTRA_ICON_H + 2f, EXTRA_ICON_H)
            extras.textAlign = Paint.Align.RIGHT
            canvas.drawText(text, CONTENT_RIGHT - EXTRA_ICON_H - 8f, baseline, extras)
            baseline += EXTRA_LINE_H
        }
        if (config.showBattery) {
            extras.color = colorFor("battery", config.batteryTint)
            // Read from the sticky ACTION_BATTERY_CHANGED broadcast rather than
            // registering a receiver: this view is destroyed and recreated constantly,
            // and a receiver would have to be unregistered on a lifecycle this view does
            // not own. A sticky read is a snapshot with nothing to leak.
            val level = batteryPercent()
            if (level >= 0) {
                val iconX = CONTENT_RIGHT - EXTRA_ICON_H
                val iconY = baseline - EXTRA_ICON_H + 2f
                extras.textAlign = Paint.Align.RIGHT
                canvas.drawText("$level%", iconX - 8f, baseline, extras)
                // The charging glyph replaces the outline entirely rather than being
                // overlaid: it already contains the bolt, and two glyphs stacked would
                // read as one badly drawn one.
                if (charging()) {
                    drawIcon(canvas, PanelIcons.CHARGING, iconX, iconY, EXTRA_ICON_H)
                } else {
                    // Outline, then filled from the bottom to the actual charge — a full
                    // battery drawn at 12% is only ever right once.
                    drawIcon(canvas, PanelIcons.BATTERY, iconX, iconY, EXTRA_ICON_H)
                    // The fill follows the GLYPH inside the cell, not the cell itself,
                    // or it would spill into the padding the square adds either side.
                    val glyphW = EXTRA_ICON_H * PanelIcons.BATTERY.aspect
                    val glyphX = iconX + (EXTRA_ICON_H - glyphW) / 2f
                    val inset = EXTRA_ICON_H * 0.16f
                    val top = iconY + inset
                    val bottom = iconY + EXTRA_ICON_H - inset
                    val fillTop = bottom - (bottom - top) * (level / 100f)
                    canvas.drawRect(glyphX + inset * 0.6f, fillTop,
                        glyphX + glyphW - inset * 0.6f, bottom, extras)
                }
            }
        }
    }

    /**
     * Draws an icon centred in a fixed SQUARE cell of side [h] at [x],[y].
     *
     * The cell is the point. These glyphs are not monospaced — the clock is 0.947 of its
     * height and the battery 0.579, which at h=20 is a 7px difference — so drawing each
     * at its natural width left the two lines' text ending at different x and nothing
     * lining up. A square cell gives every icon the same footprint whatever its shape,
     * and the readings beside them share one right edge.
     */
    /**
     * An icon at [h] tall, centred in a square cell of that height at [x], [y].
     *
     * The aspect comes from the icon itself. It used to be picked by an if-chain here —
     * CLOCK's aspect for the clock, BATTERY's for everything else — so the first icon
     * added after that was written came out battery-shaped: tall and narrow, on a
     * camera. The pair now travels together and cannot be mismatched. See PanelIcons.Icon.
     */
    /**
     * A traced glyph, at [h] units tall.
     *
     * Drawn WITHOUT anti-aliasing, on a paint of its own. The tracer emits one rectangle
     * per pixel row of the source glyph, so a solid shape arrives as a stack of abutting
     * bars — and an anti-aliased edge on each of them blends with the background at every
     * shared boundary. The result is a solid glyph rendered with a hairline of panel
     * showing through between every row: the play triangle came out looking woven. It is
     * most obvious on the transport glyphs because they are the only FILLED ones, which
     * is why it survived three outline icons unnoticed.
     *
     * Pixel art, drawn like the mascot: no anti-aliasing, crisp edges, no seams.
     */
    private fun drawIcon(canvas: Canvas, icon: PanelIcons.Icon, x: Float, y: Float, h: Float) {
        val w = h * icon.aspect
        val left = x + (h - w) / 2f          // centred in the cell, not left-aligned in it
        iconPaint.color = extras.color
        for (r in icon.rects) {
            // SNAPPED TO WHOLE PIXELS, which is the whole reason this loop is not two
            // lines. A traced row is one source pixel tall, and at 24 units from a
            // 19-row glyph that is 1.26 device pixels: the rasteriser gives the leftover
            // fraction partial coverage, so the rows came out alternating full and
            // three-quarter brightness. The play triangle was visibly banded — measured
            // at (122,129,247) and (92,98,187) on consecutive rows.
            //
            // Rounding each edge fixes it without a fudge factor. Row k's bottom and row
            // k+1's top are the same expression, so they round to the same pixel: no
            // gap, no overlap, no partial coverage. Rows land on 1 or 2 pixels depending
            // where they fall, which is exactly what nearest-neighbour scaling of pixel
            // art is supposed to do.
            canvas.drawRect(
                kotlin.math.round(left + r.x * w),
                kotlin.math.round(y + r.y * h),
                kotlin.math.round(left + (r.x + r.w) * w),
                kotlin.math.round(y + (r.y + r.h) * h),
                iconPaint,
            )
        }
    }

    private fun charging(): Boolean {
        val intent = context.registerReceiver(
            null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
        ) ?: return false
        val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
        return status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
            status == android.os.BatteryManager.BATTERY_STATUS_FULL
    }

    private fun batteryPercent(): Int {
        val intent = context.registerReceiver(
            null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
        ) ?: return -1
        val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
        return if (level < 0 || scale <= 0) -1 else level * 100 / scale
    }

    private fun drawTitle(canvas: Canvas) {
        // Centred over the BAR COLUMN, not the whole canvas side — the same rule the
        // maker's mark follows, so the masthead and the artwork share one axis instead
        // of each being centred on something different.
        title.color = colorFor("title", config.titleColor)
        canvas.drawText(config.title, (BAR_LEFT + BAR_RIGHT) / 2f, TITLE_BASELINE, title)
    }


    /**
     * 7-day window bars, one block per account. A window row is only drawn when the
     * account actually reported it — an account with no snapshot yet gets no rows at
     * all (not zeroed bars), and an account with no WEEKLY_SCOPED window (e.g. it has
     * never used a scoped model) simply has no third row. `w.label` is rendered
     * verbatim: the third window's name is whatever the live response resolved
     * (`scope.model.display_name`), never a hardcoded model name.
     */
    /** Account indices in the configured display order. */
    private fun orderedAccounts(): List<Int> = config.orderedAccounts(Account.entries.size)

    private fun drawBars(canvas: Canvas) {
        sessionBars.clear()
        val left = CONTENT_LEFT
        val right = CONTENT_RIGHT

        // Layout metrics scale with the type. These were originally tuned for 12/15/14pt
        // text; when the sizes went to 18/22.5/21 the rows kept their old pitch and
        // crowded. Keep them proportional to the fonts rather than as loose literals.
        val barH = 20f
        val rowPitch = ROW_PITCH
        val headingAdvance = 30f

        // Measure both account blocks before drawing anything, so the leftover space
        // can be shared out evenly instead of accumulating at the bottom.
        val blocks = orderedAccounts()
            .mapNotNull { Account.entries.getOrNull(it) }
            .filter { onlyAccount == null || it == onlyAccount }
            .map { account ->
            val status = state.statuses.firstOrNull { it.account == account }
            // The session window is included now. It used to be filtered out because the
            // rings already show its UTILISATION — but this row shows its TIME, which
            // nothing else on the panel did: the footer carried a single countdown for
            // the personal account only, so the work account's window was invisible.
            // The two accounts' 5h windows start independently, on each account's first
            // use, so one countdown can never stand for both.
            // Rows in the configured order, and ONLY the configured ones.
            //
            // Ordered by looking each configured kind up rather than by sorting the
            // response: a window the API stops sending simply does not appear, and a
            // window the config omits is hidden rather than pushed to the bottom. The
            // config is a whitelist, not a preference.
            val windows = status?.snapshot?.windows.orEmpty()
            val rows = config.barsFor(account.ordinal).mapNotNull { wire ->
                windows.firstOrNull { it.kind.wire == wire }
            }
            Triple(account, status, rows)
        }
        val contentH = blocks.fold(0f) { acc, (_, _, rows) ->
            acc + headingAdvance + rows.size * rowPitch
        }

        // The region the bars own: below the masthead, above the ACRNM mark.
        // The bars start below whatever the extras occupy. Stacked, the battery's
        // baseline lands exactly on the first account heading — the two collided by
        // arithmetic rather than by accident, so the region has to know they are there.
        val extraLines = (if (config.showClock) 1 else 0) + (if (config.showBattery) 1 else 0)
        val regionTop = if (onlyAccount != null) SINGLE_ACCOUNT_TOP
            else TITLE_BASELINE + 26f + (extraLines - 1).coerceAtLeast(0) * EXTRA_LINE_H
        // Without a masthead above or a mark below, one account has the whole height.
        val regionBottom = if (onlyAccount != null) Panel.HEIGHT - SINGLE_ACCOUNT_TOP
            else Panel.HEIGHT - MARK_BOTTOM - MARK_H
        // One gap above the first block, one between, one below the last — so the
        // blocks sit evenly rather than hugging the top.
        // One account centres in the space it has; several share it evenly. Centring is
        // what puts the block level with the ring beside it — the two are read as one
        // row, and a block starting at the top with a ring in the middle is not that.
        val gap = if (onlyAccount != null) ((regionBottom - regionTop - contentH) / 2f).coerceAtLeast(0f)
            else ((regionBottom - regionTop - contentH) / (blocks.size + 1)).coerceAtLeast(0f)

        var y = regionTop + gap

        for ((account, status, rows) in blocks) {
            heading.color = if (status?.health == Health.OK) accentOf(account) else Panel.Palette.DIM
            canvas.drawText(labelOf(account), left, y, heading)
            // Plan bracket, right-aligned to the value column. Static information, so
            // it is never ramp- or health-coloured: it says what you are entitled to,
            // not how much of it is left. Only the tier is shown — the profile response
            // also carries a name, an email and an org, none of which belong on a
            // panel that faces outward from the back of the phone.
            status?.planTier?.let {
                planTag.color = if (status.health == Health.OK) accentOf(account) else Panel.Palette.DIM
                canvas.drawText(it, right, y, planTag)
            }
            val tagX = left + heading.measureText(labelOf(account)) + 14f
            // Kept alongside the dimming: grey alone says "something is wrong" without
            // saying what, which is what made an all-grey panel read as broken rather
            // than informative. The tag names the actual failure.
            healthTag(status?.health)?.let { tag ->
                label.color = Panel.Palette.DIM
                canvas.drawText(tag, tagX, y, label)
            }
            y += headingAdvance

            for (w in rows) {
                // Row labels take the account accent while the data is good, and only
                // go grey when it cannot be vouched for — same rule as the heading and
                // the bar. Previously they were permanently DIM, which read as
                // "disabled" even on a healthy account.
                label.color = if (status?.health == Health.OK) accentOf(account) else Panel.Palette.DIM
                canvas.drawText(w.label, left, y + barH - 4f, label)
                val barLeft = BAR_LEFT
                val barRight = BAR_RIGHT
                canvas.drawRect(barLeft, y, barRight, y + barH, barTrack)
                // EVERY bar is utilisation, including the session row. It used to plot
                // time elapsed through the 5h window, which meant the same bar shape
                // carried two different meanings on one panel — and disagreed with the
                // ring drawn around the lens for that very window, which has always
                // shown utilisation. The countdown is not lost: it stays in the value
                // column, which is the only place a reset time appears at all.
                //
                // Keyed per account AND per window kind so each bar keeps its own
                // momentum — one shared spring would drag every row along with
                // whichever value moved.
                val fraction = animateTo("bar:${account.name}:${w.kind.name}", w.utilization)
                // Same "dim when we can't vouch for it" rule as the rings: a
                // stale/error/auth account still shows its last-known bar widths,
                // just uniformly dimmed rather than accent/warn/crit colored.
                // Same continuous ramp as the rings, so the whole panel speaks one
                // colour language: green through amber to red as a window fills.
                // Replaced the earlier accent-until-75%-then-escalate rule, which made
                // a bar at 60% look identical to one at 10%.
                // One rule for every bar, now that they all plot the same quantity.
                // The session row used to be special-cased to the account accent, which
                // was right while it plotted TIME — filling up then meant a reset was
                // near, which is good news, and the green-to-red ramp would have
                // inverted that meaning. Plotting utilisation, it means what every other
                // bar means, so it is coloured the same way.
                //
                // The ramp reads the animated value, like the ring, so the colour
                // travels with the bar rather than snapping ahead of it.
                barFill.color =
                    // NOT run through colorFor. `fraction` is already the eased value,
                    // so this colour is animating with the bar's own width; easing it a
                    // second time would make a bar's colour lag behind its own length.
                    if (status?.health != Health.OK) Panel.Palette.DIM
                    else RampColor.at(fraction, config.rampColors)
                canvas.drawRect(
                    barLeft, y,
                    barLeft + (barRight - barLeft) * fraction.coerceIn(0f, 1f),
                    y + barH, barFill,
                )
                // Percentage for EVERY row, including the session one, which used to put
                // its countdown here — the value column meant two different things
                // depending on the row, exactly the inconsistency the bars had.
                //
                // The number counts with the bar: springing the bar while the figure
                // jumps straight to its new value would make them disagree for the whole
                // animation.
                value.color = barFill.color
                canvas.drawText("${(fraction * 100).toInt()}%", PCT_RIGHT, y + barH - 4f, value)
                // Time-to-reset in its own column, so every window says when it turns
                // over rather than only the session one.
                canvas.drawText(remaining(w.resetsAt), TIME_RIGHT, y + barH - 4f, timeTag)
                // The mascot stands on the FIRST SHOWN bar, not on the session bar.
                //
                // It anchored on WindowKind.SESSION, so hiding the 5-hour row left the
                // mascot with nothing to stand on and it vanished — the account looked
                // broken rather than configured. Whichever row is first is the one it
                // stands on, and there is always at least one (see barsFor).
                if (account !in sessionBars) {
                    sessionBars[account] = SessionBar(barLeft, barRight, y, fraction)
                }
                y += rowPitch
            }
            y += gap
        }
    }

    /**
     * One mascot per account, standing on that account's 5-hour bar.
     *
     * Which clip plays is NOT decided here — it arrives on the payload as
     * AccountStatus.mascot, chosen by MascotMood in the app process. This view is
     * destroyed and recreated constantly by the placement machinery, so it cannot hold
     * the usage history that decision needs.
     *
     * Feet sit on the bar's top edge, so the figure reads as standing on the progress it
     * describes. Drawn AFTER the bars for that reason — it should overlap them rather
     * than hide behind the track.
     *
     * Positions are FIXED along the bar, one per account, rather than tracking the
     * fill. Riding the fill was tried and reads badly here: at 0% both figures pile up
     * against the left edge on top of the row label, and two accounts at similar usage
     * put them at the same place on stacked rows, which looks like a mistake rather
     * than data. Fixed offsets keep them staggered and legible at every value, and the
     * bar underneath already shows the progress.
     */
    /** The walk IS the stand rig, so it takes that clip's derived scale and lands the
     *  same size as every other mascot rather than being tuned by hand. */
    private val walkScale by lazy {
        MascotFrames.CLIPS.firstOrNull { it.name == MascotMood.STAND }?.scale ?: 1f
    }

    private fun drawMascots(canvas: Canvas) {
        val now = System.currentTimeMillis()
        for ((index, entry) in sessionBars.entries.withIndex()) {
            val account = entry.key
            if (!config.styleFor(account.ordinal).showMascot) continue
            val bar = entry.value

            // The idle state walks its bar instead of standing on it. Handled before the
            // clip lookup because it has no clip: WalkRig animates the timeline, and
            // MascotFrames ships no "walk" for it to resolve to.
            if (accountMascot(account) == MascotMood.WALK) {
                val h = MASCOT_HEIGHT * walkScale
                val w = h * WalkRig.ASPECT
                WalkRig.draw(
                    canvas,
                    // Half a cycle apart, for the same reason MASCOT_STOPS are staggered:
                    // the phase comes from the wall clock, so without an offset both
                    // accounts would step, hop and jump in perfect unison — which reads
                    // as one animation drawn twice rather than two mascots.
                    //
                    // Half of the there-and-back cycle is exactly one pass, so the offset
                    // puts the accounts on opposite legs of it: one walks out while the
                    // other walks home, and they pass each other in the middle.
                    nowMs = now + index * WalkRig.DURATION_MS,
                    left = bar.left,
                    // Span is the bar minus the figure's own width, so the far end of the
                    // jump lands ON the bar's right edge rather than past it.
                    span = (bar.right - bar.left - w).coerceAtLeast(0f),
                    top = bar.top - h,
                    h = h,
                    paint = mascotPaint,
                    rise = ROW_PITCH,
                    // The idle state is the walk, so this is the mascot almost always on
                    // screen — it went through WalkRig, which had its colours compiled
                    // in, and stayed green whatever the account was set to.
                    bodyColor = colorFor("body:${account.name}",
                        config.styleFor(account.ordinal).mascotBody ?: WalkRig.BODY_COLOR),
                )
                continue
            }

            // Fall back to standing for anything unresolvable. A payload restored from
            // disk can name a clip this build no longer ships, and silently drawing
            // nothing would look like the mascot feature had broken.
            val clip = MascotFrames.CLIPS.firstOrNull { it.name == accountMascot(account) }
                ?: MascotFrames.CLIPS.firstOrNull { it.name == MascotMood.STAND }
                ?: continue
            val h = MASCOT_HEIGHT * clip.scale
            val w = h * clip.aspect
            val at = MASCOT_STOPS.getOrElse(index) { MASCOT_STOPS.last() }
            val x = (bar.left + at * (bar.right - bar.left) - w / 2f)
                .coerceIn(bar.left, bar.right - w)
            val top = bar.top - h
            // Per-clip frame time: the confetti burst reads as frantic at the pace that
            // suits a walk or a lift, so it keeps the original 125ms while the rest run
            // at 83ms.
            MascotRenderer.draw(
                canvas, clip, now, x, top, h, mascotPaint, deadEye,
                accent = mascotAccentOf(account),
                bodyColor = colorFor("body:${account.name}",
                    config.styleFor(account.ordinal).mascotBody ?: MascotRenderer.BODY_GREEN),
                dead = accountMascot(account) == MascotMood.DEAD,
            )
        }
        if (sessionBars.isNotEmpty()) animating = true
    }

    /**
     * The artwork slot: the maker's mark, the user's own image, or the media controls.
     *
     * One region, one occupant. The controls do not sit beside the artwork or shrink it
     * — they take the whole slot for as long as something is playing and hand it back
     * when it stops. Anything else divides 278 units of a 904-unit panel into two things
     * that are each too small to read.
     */
    private fun drawMark(canvas: Canvas, dx: Float, dy: Float) {
        val region = android.graphics.RectF(
            BAR_LEFT, Panel.HEIGHT - MARK_BOTTOM - MARK_H,
            BAR_RIGHT, (Panel.HEIGHT - MARK_BOTTOM).toFloat(),
        )

        val swap = mediaSwap(frameNowMs)
        if (swap <= 0.001f) {
            mediaStrip = null
            drawArt(canvas, region)
            return
        }

        // A drum, exactly like the notification wheel, and for the same reason: the two
        // occupants of this slot are alternatives rather than states of one thing, so
        // one leaves as the other arrives. A cross-fade would have the mark and the
        // track name both half-visible over each other in the middle, which on a
        // 70-unit strip is a smudge.
        canvas.save()
        // Clipped to the WIDER band, or the cover would be cut off at the mark's edge
        // as it slides — and to the strip above it, which the progress bar lives in.
        canvas.clipRect(mediaClip(region))
        val h = region.height()
        if (swap < 0.999f) {
            canvas.save()
            // The artwork leaves through the BOTTOM of the clip, which now extends below
            // the band for the progress bar — travelling only the band's height would
            // leave a sliver of it standing in that strip for the whole crossing.
            canvas.translate(0f, swap * mediaClip(region).height())
            drawArt(canvas, region)
            canvas.restore()
        }
        canvas.save()
        canvas.translate(0f, -(1f - swap) * h)
        drawMediaRow(canvas, region, swap >= 0.999f, dx, dy)
        canvas.restore()
        canvas.restore()
    }

    /**
     * The band the media row occupies: the artwork slot, plus the corner the close
     * button used to sit in.
     *
     * Wider than the slot the mark uses. The mark is a logo and belongs on the bar
     * column it is aligned to; the media row is three things side by side and was
     * cramped into 278 units, clipping most track titles at thirteen characters. The
     * space left of the bar column was doing nothing once the close button moved, and it
     * is exactly where a cover wants to be.
     */
    /**
     * The cover, as a square or as a record.
     *
     * The vinyl is not decoration that happens to move. It turns while the track plays
     * and stops dead when it does not, which makes it a second reading of the transport
     * state — visible from further away than a 30-unit glyph, on a panel that is mostly
     * looked at from across a room.
     */
    private fun drawCover(
        canvas: Canvas,
        cover: android.graphics.Bitmap,
        square: android.graphics.RectF,
        m: Media,
        tint: Int,
    ) {
        // Cropped to fill, never letterboxed: covers are square already, and the odd one
        // that is not should lose its edges rather than sit in a box. Same placement the
        // panel's custom artwork uses, at neutral pan and zoom — there is nothing to crop
        // by hand here.
        val dest = ArtPlacement.destination(square, cover.width, cover.height, 0f, 0f, 1f)

        if (config.mediaArt == MediaArt.SQUARE) {
            canvas.save()
            canvas.clipRect(square)
            canvas.drawBitmap(cover, null, dest, artPaint)
            canvas.restore()
            return
        }

        val cx = square.centerX()
        val cy = square.centerY()
        val radius = square.height() / 2f

        canvas.save()
        // Clip FIRST, rotate second. The circle is inscribed in the square, so a square
        // bitmap turned about their shared centre covers the circle at every angle —
        // there is no corner that can swing into view.
        canvas.clipPath(
            android.graphics.Path().apply {
                addCircle(cx, cy, radius, android.graphics.Path.Direction.CW)
            },
        )
        canvas.rotate(vinylAngle(m), cx, cy)
        canvas.drawBitmap(cover, null, dest, artPaint)
        canvas.restore()

        // A groove and a spindle, in the panel's own background rather than in black, so
        // the record reads as material removed from the disc rather than as two dark
        // rings painted on a picture.
        val hole = colorFor("bg", config.background or (0xFF shl 24))
        closePaint.style = Paint.Style.STROKE
        closePaint.strokeWidth = VINYL_GROOVE
        closePaint.color = (hole and 0x00FFFFFF) or (VINYL_GROOVE_ALPHA shl 24)
        canvas.drawCircle(cx, cy, radius * VINYL_GROOVE_AT, closePaint)

        // The label edge, then the spindle hole itself. Tinted rather than background:
        // it is the one part of a record that is not the sleeve art.
        closePaint.strokeWidth = VINYL_GROOVE
        closePaint.color = (tint and 0x00FFFFFF) or (VINYL_LABEL_ALPHA shl 24)
        canvas.drawCircle(cx, cy, radius * VINYL_LABEL_AT, closePaint)

        closePaint.style = Paint.Style.FILL
        closePaint.color = hole
        canvas.drawCircle(cx, cy, radius * VINYL_SPINDLE, closePaint)
    }

    /**
     * Where the record has turned to, in degrees.
     *
     * Accumulated rather than computed from the clock, because it has to STOP when the
     * music does and pick up from where it stopped. A rotation derived from wall time
     * would keep turning through a pause behind the scenes and jump on resume, which is
     * the one thing this is meant to communicate.
     */
    private fun vinylAngle(m: Media): Float {
        val now = frameNowMs
        val since = if (vinylAt == 0L) 0L else now - vinylAt
        vinylAt = now
        if (m.playing) {
            animating = true
            vinylTurn = (vinylTurn + since * VINYL_DEG_PER_MS) % 360f
        }
        return vinylTurn
    }

    private var vinylTurn = 0f
    private var vinylAt = 0L

    /**
     * How far through the track we are, as a hairline under the row.
     *
     * Drawn only when there IS a timeline. A live stream, a radio session or a player
     * that simply does not report one gives a duration of zero, and an empty bar sitting
     * above every one of those would be a reading that is never true rather than a
     * reading that happens to be at the start.
     *
     * Position is carried forward from the last report rather than waiting to be told
     * again — see Media.elapsed. That is what makes this move at all: the player speaks
     * only when something changes.
     */
    private fun drawMediaProgress(
        canvas: Canvas,
        m: Media,
        band: android.graphics.RectF,
        tint: Int,
    ) {
        val fraction = MediaLayout.progress(
            m.elapsed(SystemClock.elapsedRealtime()), m.durationMs,
        ) ?: return
        val top = band.bottom + MediaLayout.PROGRESS_GAP
        val bottom = top + MediaLayout.PROGRESS_H

        // The track it runs in, at the same dim the artist line uses — the two are the
        // same class of thing, context rather than reading, and giving the bar a ground
        // of its own would make it a third colour on a row that has two.
        noticePaint.style = Paint.Style.FILL
        noticePaint.color = (tint and 0x00FFFFFF) or (MEDIA_TRACK_ALPHA shl 24)
        canvas.drawRect(band.left, top, band.right, bottom, noticePaint)

        noticePaint.color = tint
        noticePaint.alpha = 255
        canvas.drawRect(
            band.left, top, band.left + (band.right - band.left) * fraction, bottom,
            noticePaint,
        )
        // While something is playing this has to keep redrawing, or it advances only when
        // the player next happens to say something.
        if (m.playing) animating = true
    }

    /**
     * A line of the media row, scrolled when it does not fit.
     *
     * NOT ellipsised, which is what this replaced. An ellipsis is right for a
     * notification — that strip is up for five seconds and the first few words are the
     * whole point — but a track plays for minutes, and a title cut at thirteen
     * characters is a title you never get to read. There is time here to show the rest,
     * so the rest is shown.
     *
     * A ticker rather than a ping-pong: the text runs left and a second copy follows a
     * gap behind it, so the line never rewinds and is never empty. It dwells at the
     * start of each lap, because a line that begins moving the instant it appears is one
     * you have to wait a full lap to read from the beginning.
     *
     * Phase comes from the frame clock alone — no stored offset, nothing to reset when
     * the track changes or `:rear` is recreated, and two lines of different lengths
     * drift apart by themselves instead of marching in step.
     *
     * The continuous redraw costs nothing extra in practice: the mascots hold the frame
     * clock open whenever there are bars to draw. It stops being true only if every
     * mascot is switched off, which is why this sets the flag itself rather than
     * assuming someone else has.
     */
    private fun marquee(
        canvas: Canvas,
        text: String,
        x: Float,
        baseline: Float,
        width: Float,
        band: android.graphics.RectF,
    ) {
        val w = extras.measureText(text)
        if (w <= width) {
            canvas.drawText(text, x, baseline, extras)
            return
        }
        animating = true
        val span = w + MARQUEE_GAP
        val cycle = (MARQUEE_DWELL_MS + span / MARQUEE_SPEED * 1000f).toLong()
        val t = (frameNowMs % cycle).toFloat()
        val offset =
            if (t < MARQUEE_DWELL_MS) 0f
            else (t - MARQUEE_DWELL_MS) / 1000f * MARQUEE_SPEED

        canvas.save()
        // Clipped to the text column, so a long title runs out of sight rather than
        // through the buttons. Full band height: the clip is about width, and trimming
        // it vertically would only risk shaving a descender.
        canvas.clipRect(x, band.top, x + width, band.bottom)
        canvas.drawText(text, x - offset, baseline, extras)
        // The chaser. Only ever one — MARQUEE_GAP is wider than any column this is given,
        // so a third copy cannot be on screen while the first still is.
        canvas.drawText(text, x - offset + span, baseline, extras)
        canvas.restore()
    }

    private fun mediaRegion(mark: android.graphics.RectF) = android.graphics.RectF(
        CONTENT_EDGE, mark.top, mark.right, mark.bottom,
    )

    /**
     * What the media row is clipped to: its content band plus the strip above it.
     *
     * Separate from [mediaRegion] rather than folded into it. The band's HEIGHT is what
     * makes the cover square and centres the two lines of text, so growing it to make
     * room for the progress bar would have quietly stretched both.
     */
    private fun mediaClip(mark: android.graphics.RectF) = android.graphics.RectF(
        CONTENT_EDGE, mark.top, mark.right, mark.bottom + MediaLayout.PROGRESS_TOTAL,
    )

    /**
     * Track and artist on the left, three transport glyphs on the right.
     *
     * No album art. It would have to be pushed across the process boundary as a bitmap
     * on every track change — the one thing the notification path was careful not to do
     * — and at 70 units square it would cost half the width that the title needs to be
     * readable at all.
     *
     * The glyphs are FILLED where the clock, battery and calendar are outlines. Those
     * are labels; these are the only things on this panel meant to be pressed, and a
     * solid shape is a target where a hairline is a note.
     */
    private fun drawMediaRow(
        canvas: Canvas,
        region: android.graphics.RectF,
        settled: Boolean,
        dx: Float,
        dy: Float,
    ) {
        val m = media ?: return
        settlePlaying(frameNowMs)
        val tint = colorFor("media", config.mediaTint)
        val band = mediaRegion(region)

        drawMediaProgress(canvas, m, band, tint)

        // The cover first, because where it ends is where the text begins.
        //
        // A missing cover is ordinary — plenty of tracks have none, and a podcast or a
        // browser tab rarely does — so the row closes up rather than leaving a hole. The
        // text simply starts at the band's left edge instead.
        val cover = coverArt(m.artPath)
        var textLeft = band.left
        if (cover != null) {
            val square = android.graphics.RectF(
                band.left, band.top, band.left + band.height(), band.bottom)
            drawCover(canvas, cover, square, m, tint)
            textLeft = square.right + MediaLayout.COVER_GAP
        }

        val row = MediaLayout.of(textLeft, region.right, region.centerY())
        val gh = MediaLayout.GLYPH_H
        val (prev, play, next) = Triple(row.prev, row.play, row.next)

        extras.color = tint
        drawIcon(canvas, PanelIcons.PREV, prev, row.glyphTop, gh)
        drawIcon(canvas, PanelIcons.NEXT, next, row.glyphTop, gh)

        // The middle glyph is its own little drum, clipped to one cell: pause rises out
        // of the top as play drops off the bottom. It is the same gesture as the row
        // arriving, one size down, so a track being paused and the controls appearing
        // read as the same machine.
        val turn = playSwap(frameNowMs)
        canvas.save()
        canvas.clipRect(play, region.top, play + gh, region.bottom)
        if (turn < 0.999f) {
            drawIcon(canvas, PanelIcons.PLAY, play, row.glyphTop + turn * gh, gh)
        }
        if (turn > 0.001f) {
            drawIcon(canvas, PanelIcons.PAUSE, play, row.glyphTop - (1f - turn) * gh, gh)
        }
        canvas.restore()

        val hasArtist = m.artist.isNotBlank()
        var baseline = MediaLayout.firstBaseline(region.centerY(), hasArtist)

        extras.textAlign = Paint.Align.LEFT
        extras.textSize = MediaLayout.TEXT_SIZE
        extras.color = tint
        marquee(canvas, m.title, textLeft, baseline, row.textWidth, band)
        if (hasArtist) {
            baseline += MediaLayout.LINE
            // Dimmed against the title rather than given a colour of its own. Which of
            // the two lines is the track is the only thing this row has to say at a
            // glance, and a second hue would make that a question about the palette.
            extras.color = (tint and 0x00FFFFFF) or (MediaLayout.ARTIST_ALPHA shl 24)
            marquee(canvas, m.artist, textLeft, baseline, row.textWidth, band)
        }
        extras.textSize = 18f

        // Targets only once the row has ARRIVED. A button that can be pressed while it
        // is still sliding is a button in a place it is about to leave, and the whole
        // travel is a fifth of a second — there is nothing to gain by accepting a press
        // during it and a mis-tap to lose.
        if (!settled) {
            mediaStrip = null
            mediaCentres = emptyList()
            return
        }
        // Full region height, not the glyph's 24 units. Same reasoning as CAMERA_SLOP:
        // this is glass on the back of a phone being held, and the glyph is what says
        // the target is there rather than what the target is.
        //
        // The canvas offset is taken BACK OUT, for the reason drawCamera gives at
        // length: touches arrive in the view's own coordinates, which neither the
        // burn-in drift nor a notification's push ever moves. Storing the shifted strip
        // would walk the targets away from the glyphs — a few pixels on a ten-minute
        // cycle, and a third of the panel for the five seconds a notification is up.
        mediaStrip = android.graphics.RectF(
            prev - MediaLayout.GLYPH_GAP / 2f - dx,
            region.top - dy,
            region.right - dx,
            region.bottom - dy,
        )
        mediaCentres = row.centres.map { it - dx }
    }

    /**
     * The cover, decoded once and cached against the file's identity.
     *
     * Same trick and same reason as [customArt]: there is ONE cover file and it is
     * overwritten in place, so the path never changes and caching on the path alone
     * would pin the first album forever. Keyed on size and modification time instead,
     * which is what actually changes when a track does.
     */
    private fun coverArt(path: String): android.graphics.Bitmap? {
        if (path.isEmpty()) return null
        val file = java.io.File(path)
        val key = "$path:${file.lastModified()}:${file.length()}"
        if (key != coverKey) {
            coverKey = key
            val decoded = try {
                android.graphics.BitmapFactory.decodeFile(path)
            } catch (_: Exception) {
                null
            }
            // Decode first, release after, and only on success — a failed decode must
            // not destroy the image a draw in flight is still holding.
            if (decoded != null) {
                coverBitmap?.recycle()
                coverBitmap = decoded
            }
        }
        return coverBitmap
    }

    private var coverKey: String? = null
    private var coverBitmap: android.graphics.Bitmap? = null

    /** ACRNM maker's mark, or the user's image. White mask, tinted at draw time — never
     *  edit the drawable. */
    private fun drawArt(canvas: Canvas, region: android.graphics.RectF) {
        val custom = customArt()
        if (custom != null) {
            drawCustomArt(canvas, custom, region)
            return
        }
        ray.setTint(Panel.Palette.PERSONAL)
        ray.setBounds(region.left.toInt(), region.top.toInt(),
            region.right.toInt(), region.bottom.toInt())
        ray.draw(canvas)
    }

    /**
     * The user's image, loaded once and cached against its path.
     *
     * Decoded here rather than pushed through the payload: a bitmap cannot travel in a
     * broadcast of any size worth having, and this process can read the file directly —
     * it is in the app's own storage, and :rear is the same app.
     *
     * A file that will not decode returns null and the built-in mark is drawn. A panel
     * that renders nothing at all because an image went missing would look like the
     * panel had broken.
     */
    private fun customArt(): android.graphics.Bitmap? {
        val path = config.art.imagePath ?: return null

        // Keyed on the file's IDENTITY, not just its name.
        //
        // There is one art slot, so choosing a new image overwrites panel-art.png and
        // the path never changes. Caching on the path alone therefore pins the first
        // image forever in any process that lives long enough — which the panel does not
        // (:rear is destroyed and recreated constantly, re-decoding by accident) but the
        // app's preview does. The bug was invisible on the back and permanent on the
        // front, until an app restart.
        val file = java.io.File(path)
        val key = "$path:${file.lastModified()}:${file.length()}"
        if (key != artKey) {
            artKey = key
            // Decode FIRST, then release the old one — and only if the new one arrived.
            // Recycling up front would destroy a working image whenever a decode failed,
            // and would free a bitmap a draw in flight might still be holding.
            val decoded = try {
                android.graphics.BitmapFactory.decodeFile(path)
            } catch (_: Exception) {
                null
            }
            if (decoded != null) {
                artBitmap?.recycle()
                artBitmap = decoded
            }
        }
        return artBitmap
    }

    /**
     * Draws [bitmap] inside [region], panned and zoomed, and CLIPPED to it.
     *
     * The clip is the point. This region is the only part of the panel with no data in
     * it, so it is the only part an arbitrary image may occupy — without the clip, a
     * zoomed image would cover the bars and the rings, and the panel would stop being a
     * readout.
     *
     * Zoom is relative to "fills the region" (aspect preserved, cropping the overflow),
     * so 1.0 is always sensible whatever the image's proportions. Pan is a fraction of
     * the slack at the current zoom, so it cannot pull the image off its own region
     * however far it is dragged.
     */
    private fun drawCustomArt(
        canvas: Canvas,
        bitmap: android.graphics.Bitmap,
        region: android.graphics.RectF,
    ) {
        // Shared with the cropper, so what was chosen there is what appears here.
        val dest = ArtPlacement.destination(
            region, bitmap.width, bitmap.height,
            config.art.panX, config.art.panY, config.art.zoom,
        )
        canvas.save()
        canvas.clipRect(region)
        canvas.drawBitmap(bitmap, null, dest, artPaint)
        canvas.restore()
    }

    /**
     * Re-tints the mascot's HARDWARE per account: headband, dumbbells and flag pole.
     *
     * The work account's kit takes the second accent, the personal account's stays
     * Anthropic orange, so a glance at which figure is which needs no label. Applied at
     * draw time rather than baked into MascotFrames because the frame data is shared by
     * both accounts — baking it would mean generating and holding two copies of every
     * clip to change one colour.
     *
     * Only the accent is swapped. The body stays the palette green in both, because the
     * green is the panel's own language and two differently-coloured mascots would read
     * as two different characters rather than one character on two accounts.
     */
    private fun accentFor(account: Account, color: Int): Int =
        if (account == Account.WORK && color == ACCENT_ORANGE) ACCENT_WORK else color

    /** The mascot's hardware colour: configured, else the account's built-in. */
    private fun mascotAccentOf(account: Account): Int =
        colorFor("kit:${account.name}",
            config.styleFor(account.ordinal).mascotAccent
                ?: if (account == Account.WORK) MascotRenderer.ACCENT_WORK
                    else MascotRenderer.ACCENT_ORANGE)

    private fun accountMascot(account: Account): String =
        state.statuses.firstOrNull { it.account == account }?.mascot ?: MascotMood.WALK

    /**
     * Geometry of one account's 5h bar, captured by drawBars for the mascots to stand
     * on. Recorded during the bar pass rather than recomputed, so the figure's feet and
     * the bar it stands on cannot drift apart when the row layout changes.
     */
    private class SessionBar(val left: Float, val right: Float, val top: Float, val fill: Float)

    private val sessionBars = LinkedHashMap<Account, SessionBar>()

    /**
     * Short marker shown beside an account whose numbers cannot be vouched for.
     * null when the account is healthy, so nothing is drawn in the common case.
     */
    private fun healthTag(health: Health?): String? = when (health) {
        null -> null
        Health.OK -> null
        Health.AUTH -> "SIGN IN"
        Health.STALE -> "STALE"
        Health.ERROR -> "ERROR"
    }

    /**
     * How far through the 5-hour session window we are, 0..1. Drives the rings.
     *
     * Derived from resetsAt because that is the only anchor the API gives us — there is
     * no window-start field — and the window's length is fixed at five hours, which is
     * what the row is labelled.
     *
     * Returns 0 when resetsAt is null: no session is running, and an empty ring reads
     * correctly as "nothing counting down", where a full one would read as "about to
     * reset".
     */
    /**
     * How far through its own window we are, 0..1 — a clock, not a gauge.
     *
     * The window's LENGTH has to come from its kind, because the API reports only when a
     * window resets and never how long it ran. Using the 5-hour length for a 7-day
     * window would peg the ring at full for six and a half days and then sweep it in the
     * last five hours, which is not wrong by a little.
     *
     * A window with no reset time reads as 0 rather than as full: an unused window has
     * not started, and drawing it complete would claim a reset is imminent.
     */
    private fun windowElapsed(window: UsageWindow): Float {
        val resetsAt = window.resetsAt ?: return 0f
        val length = when (window.kind) {
            WindowKind.SESSION -> SESSION_WINDOW_MS
            WindowKind.WEEKLY_ALL, WindowKind.WEEKLY_SCOPED -> WEEKLY_WINDOW_MS
        }
        val remaining = resetsAt - System.currentTimeMillis()
        return ((length - remaining).toFloat() / length).coerceIn(0f, 1f)
    }

    /**
     * Compact time until a window turns over.
     *
     * Days are a separate unit rather than rolled into hours: a 7-day window is ~168
     * hours out, and "167h02m" is both wider than the column and unreadable at a
     * glance. Two units is always enough — "3d17h", "4h51m", "22m".
     *
     * resetsAt is nullable: a window that has never been used has nothing to reset.
     */
    private fun remaining(resetsAt: Long?): String {
        if (resetsAt == null) return "—"
        val ms = resetsAt - System.currentTimeMillis()
        if (ms <= 0) return "now"
        val mins = ms / 60_000
        val hours = mins / 60
        val days = hours / 24
        return when {
            days > 0 -> "${days}d${hours % 24}h"
            hours > 0 -> "${hours}h${(mins % 60).toString().padStart(2, '0')}m"
            else -> "${mins}m"
        }
    }

    companion object {
        /**
         * How often [redrawTick] reschedules itself. Chosen to be frequent enough
         * that the ±4px/10-minute drift reads as a slow wander rather than a single
         * jump (40 steps per cycle) and that a fresh `⟳ Nm` reading never lags more
         * than this far behind the real minute it crossed — but far from tight
         * enough to matter for battery or CPU on a canvas this simple. Not something
         * StalenessTest can cover: it drives a real Handler/Looper tied to this
         * View's window, which is UI glue, not the pure clock math in staleLabel/
         * driftOffset — same split this file already draws between companion
         * functions (tested) and onDraw/drawRing/drawBars/etc. (not).
         */
        /**
         * Masthead baseline, and the size of the square the mark is drawn into.
         * The mark's box is bottom-aligned to the text baseline so the two sit on one
         * line regardless of the font's own metrics.
         */
        /**
         * Opacity of the background fill, 0..255.
         *
         * Not a style knob — it trades two things off against each other. At 255 the
         * panel is the Tomorrow Night ground the palette was designed against, and
         * nothing shows through. At 0 the system wallpaper shows through completely and
         * the panel stops working entirely (see onDraw for what that failure looks
         * like). This sits between: enough coverage to keep occluding the sub-screen
         * launcher and to keep the near-black bar tracks legible, while the wallpaper
         * still reads through it.
         */

        /**
         * The canvas side's two vertical rules. Everything on the right of the panel
         * lines up on one or the other: labels and the masthead at CONTENT_LEFT, bars
         * and the maker's mark between BAR_LEFT and BAR_RIGHT.
         *
         * These were four separate literals spread across drawBars, drawTitle and
         * drawMark. Sharing them is what makes "the mark is the same width as the bars"
         * a fact of the layout rather than a coincidence that survives until someone
         * retunes one of the margins.
         */
        private const val CONTENT_LEFT = Panel.CUTOUT_RIGHT + 34f
        private const val CONTENT_RIGHT = Panel.WIDTH - 34f
        /**
         * 112, not 92. The column was sized for "5H"/"7D"/"FABLE"; once the labels
         * gained their scope and window ("ALL 5H", "FABLE 7D") the longest of them ran
         * straight into the bar. Measure against the LONGEST label, which is the scoped
         * one — and that one is only as long as the model name the API returns, so
         * leave slack for a longer name than FABLE.
         */
        private const val BAR_LEFT = CONTENT_LEFT + 112f
        /**
         * Each row is four columns: label, bar, percentage, time-to-reset. The last two
         * are derived backwards from the panel edge so the bar absorbs the remaining
         * width — adding the time column had to take its space from somewhere, and the
         * bar is the only element that reads fine at any length.
         *
         * Widths are sized against the worst case each column can hold: "100%" at 21f
         * for the percentage, "3d17h" at 18f for the time.
         */
        private const val TIME_RIGHT = CONTENT_RIGHT
        private const val PCT_RIGHT = CONTENT_RIGHT - 82f
        private const val BAR_RIGHT = PCT_RIGHT - 68f

        /**
         * Maker's mark. Spans exactly the bar column (BAR_LEFT..BAR_RIGHT) at the
         * user's request, so its edges line up vertically with every bar above it.
         *
         * The height is derived from the drawable's own 405x102 viewport rather than
         * chosen, so the fan is never stretched: fixing the width to the bar column and
         * also hand-picking a height is exactly how an aspect-ratio distortion gets
         * baked in unnoticed.
         */
        private const val MARK_H = (BAR_RIGHT - BAR_LEFT) * 102f / 405f
        private const val MARK_BOTTOM = 26

        /**
         * Mascot size and gait. Raising the frame rate raises the redraw rate of the
         * WHOLE view, which is the real cost on an always-on display — 83ms is 12fps.
         */
        /**
         * Where each account's mascot stands along its bar, as a fraction. Staggered on
         * purpose: the two bars are directly above one another, so equal positions would
         * line the figures up in a column and read as a rendering artefact.
         */
        private val MASCOT_STOPS = floatArrayOf(0.45f, 0.80f)

        /**
         * Mascot hardware accents. The orange is Anthropic's brand colour and is what
         * MascotFrames bakes in; the blue is the second accent, swapped in for the work
         * account at draw time. Neither belongs in Panel.Palette — that is the Tomorrow
         * Night set the whole panel is drawn from, and these two are brand colours that
         * appear on exactly one element each.
         */
        private const val ACCENT_ORANGE = 0xFFD97757.toInt()
        private const val ACCENT_WORK = 0xFF006EFF.toInt()

        /**
         * Vertical pitch between rows. Shared with the mascot, whose jump is allowed
         * exactly one row of air — see the walk branch in drawMascots. The figure is
         * already taller than a row and overlaps the one above it; the timeline's own
         * reach is another row and a half on top of that, which would carry it across
         * the account heading and into the other account's block.
         */
        private const val ROW_PITCH = 33f

        /** Nominal mascot height in panel units; each clip scales it by its own factor. */
        const val MASCOT_HEIGHT = 62f
        // 1.5x the original 125ms pace, at the user's request. Every clip shares it, so
        // the 8-frame and 36-frame animations stay in step rather than one appearing to
        // play in slow motion.
        private const val MASCOT_FRAME_MS = 83L

        /** The session window's fixed length — see sessionElapsed. */
        private const val SESSION_WINDOW_MS = 5f * 60f * 60f * 1000f

        /** The 7-day windows' length, for the same reason SESSION_WINDOW_MS exists: the
         *  API says when a window resets, never how long it ran. */
        private const val WEEKLY_WINDOW_MS = 7f * 24f * 60f * 60f * 1000f

        /** Icon height for the clock and battery, matched to the extras' text size. */
        /**
         * How long the ripple takes to cross the panel.
         *
         * Slower than a bar settling. A bar easing is news arriving and should be
         * glanceable; this is the whole readout leaving, and at 1.8s it looked like a
         * glitch rather than a decision.
         */
        private const val RIPPLE_MS = 1200f

        /**
         * How opaque the panel stays when it is meant to be see-through — ambient mode,
         * and the background switched off.
         *
         * NOT zero, and this is the one number in this file that must never become it.
         * At zero the panel stops working entirely: with nothing occluding it, MIUI's
         * SubScreenLauncher resumes, takes the display, and RearActivity's window leaves
         * the window list while its process and task record stay alive — a failure the
         * respawn watchdog cannot see, because it asks whether the TASK is alive and the
         * task is fine. See the block comment on the background fill.
         *
         * Low enough to see the system's own rear screen through, high enough to still
         * be a surface. The panel ran at 0xB4 for a whole build, so the mechanism
         * tolerates partial alpha; how far down it tolerates is not established, and
         * this is the value to raise first if the panel ever vanishes into MIUI's
         * launcher after a close, or after the background is switched off.
         */
        private const val SEE_THROUGH_ALPHA = 0x60

        private const val CLOSE_STROKE = 3f


        /**
         * Just clear of the display cutout, on the artwork's centre line.
         *
         * The left 296 units of this panel are a CUTOUT — the physical camera glass the
         * rings are drawn around. There is no display there and, crucially, no digitizer:
         * a button at x=43 was drawn perfectly and could not be pressed, because nothing
         * on that side of the panel can be. Anything interactive has to live right of
         * Panel.CUTOUT_RIGHT.
         *
         * Inset from the cutout by the same margin the camera keeps from the right edge,
         * and centred on the artwork's line like the camera is, so the two buttons and
         * the mark share one row across the panel.
         */
        /**
         * The left edge of everything that can be drawn OR pressed.
         *
         * The panel's left 296 units are the cutout — camera glass, no display and no
         * digitizer — so this is the cutout plus the margin the rest of the panel keeps
         * from its edges.
         */
        private const val CONTENT_EDGE = (Panel.CUTOUT_RIGHT + MARK_BOTTOM).toFloat()

        private const val CLOSE_R = 16f
        private const val CLOSE_CX = CONTENT_EDGE + CLOSE_R

        /**
         * TOP left, not bottom left, and it moved there to make room.
         *
         * It sat on the artwork's centre line, which put it in the only clear space on
         * the bottom band — the space the media row's cover now occupies. There is
         * nothing else in the top-left corner: the rings are drawn inside the cutout and
         * end at 278, and the masthead is centred on the bar column and cannot reach
         * left of 377 at its longest permitted title (see TitleLimits, which caps it at
         * 16 characters). That leaves this corner empty and reachable.
         *
         * Mirrors the bottom margin so the button sits the same distance from its corner
         * as the camera does from the opposite one.
         */
        private const val CLOSE_CY = MARK_BOTTOM + CLOSE_R


        /** Camera button height in panel units; its width follows the glyph's aspect. */
        private const val CAMERA_H = 30f

        /**
         * How far outside the drawn glyph a touch still counts.
         *
         * Generous, and it has to be: 30 panel units on a 904-unit panel is a few
         * millimetres of glass on the back of a phone being held rather than looked at.
         * The target is what the finger needs; the glyph is only what says it is there.
         */
        private const val CAMERA_SLOP = 26f

        /** How long a notification holds the strip before it retracts. */
        private const val NOTICE_MS = 5_000L

        /** How long one item takes to replace another on the wheel. */
        private const val WHEEL_MS = 300f

        /** How long the strip takes to arrive or leave. */
        private const val SLIDE_MS = 260f

        /**
         * How long the artwork takes to hand the slot over, and the play glyph to
         * become the pause glyph.
         *
         * Shorter than the notification's SLIDE_MS. That strip is 143 units tall and
         * crosses a third of the panel; this travels 70 and is a button changing under
         * a finger that has just pressed it. The same duration over a fifth of the
         * distance reads as lag.
         */
        private const val MEDIA_MS = 200f

        /**
         * The scrolling media line: how fast, how far apart the two copies run, and how
         * long it holds still at the start of each lap.
         *
         * Slow on purpose. This is read at arm's length, over someone's shoulder, on a
         * panel nobody is staring at — 40 units a second is about two characters, which
         * is a reading pace rather than a ticker's.
         *
         * The gap must exceed the widest column this can be given (243 units, the band
         * with no cover in it), or the chasing copy arrives before the first has left
         * and the line reads as doubled.
         */
        /**
         * How long the transport state must hold before the glyph believes it.
         *
         * See settlePlaying. Long enough to cover a player's load gap on a skip, short
         * enough that a deliberate pause still feels answered.
         */
        private const val MEDIA_SETTLE_MS = 500L

        /**
         * The vinyl: how fast it turns, and where its groove, label and spindle sit as
         * fractions of the radius.
         *
         * 100 degrees a second — a little over 16rpm, well under a record's 33. A real
         * speed on a 70-unit disc is a blur rather than a record, and this is meant to be
         * read from across a room as "it is playing" rather than measured.
         */
        private const val VINYL_DEG_PER_MS = 0.1f
        private const val VINYL_GROOVE = 1.5f
        private const val VINYL_GROOVE_AT = 0.80f
        private const val VINYL_GROOVE_ALPHA = 0x80
        private const val VINYL_LABEL_AT = 0.34f
        private const val VINYL_LABEL_ALPHA = 0xAA
        private const val VINYL_SPINDLE = 0.10f

        /** The unfilled part of the progress bar, against the row's tint. */
        private const val MEDIA_TRACK_ALPHA = 0x33

        private const val MARQUEE_SPEED = 40f
        private const val MARQUEE_GAP = MediaLayout.MARQUEE_GAP
        private const val MARQUEE_DWELL_MS = 1_800f

        /** How far the mini card's step must stay clear of the headline's curve. */
        private const val MINI_CORNER_CLEARANCE = 4f

        /** A quarter of the panel, which is what the strip was asked for. */
        private const val NOTICE_H = Panel.HEIGHT / 4f
        /**
         * The cards' left edge: clear of the cutout, not flush against it.
         *
         * Flush, the card ran straight into the lower ring, which is drawn around a
         * physical lens right up to the cutout boundary — two unrelated things touching
         * with no gap read as one object with a seam. Ten units is enough to say they
         * are separate without wasting width the text wants.
         */
        private val NOTICE_LEFT = Panel.CUTOUT_RIGHT + 10f

        private const val NOTICE_PAD = 26f

        /**
         * The mini card: how tall it is, how far it sits below the headline card, and
         * how far it is inset on each side.
         *
         * Inset MORE on the right than the left. The headline card runs to the panel
         * edge, so a mini card ending at the same place would look like a continuation
         * of it rather than a separate thing standing underneath.
         */
        private const val CASCADE_H = 76f

        /**
         * How long after the headline the mini card follows, and how long before it it
         * retracts. Same figure at both ends, so the stagger is symmetrical.
         */
        private const val MINI_LAG = 250L

        /**
         * How far the mini card steps in from the card above.
         *
         * The step is the only thing separating them, since they are flush against each
         * other and share a right edge — so it has to be enough to read as a shoulder
         * rather than as a rendering seam.
         */
        private const val MINI_INSET_LEFT = 34f
        private const val CASCADE_PAD = 18f
        private const val CASCADE_TEXT = 15f

        /** The app icon: how far its right edge sits from the panel edge, and its size. */
        private const val ICON_INSET = 48f
        private const val ICON_FRACTION = 0.56f

        /** What this phone's rear display reports, for when the display will not say. */
        private const val FALLBACK_CORNER = 97f

        /**
         * How much smaller the strip's corner is than the screen's.
         *
         * The strip is roughly a quarter of the panel's height, so a corner sized for
         * the whole screen is proportionally four times too round on it.
         */
        private const val CORNER_DIVISOR = 3f

        private const val EXTRA_ICON_H = 20f

        /** Line pitch when both extras are shown. */
        /** Short weekday names, Sunday first — Calendar.DAY_OF_WEEK is 1-based there. */
        private val DAYS = arrayOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")

        private const val EXTRA_LINE_H = 26f

        /** Breathing room above and below a single-account preview, replacing the
         *  masthead and the maker's mark it does not draw. */
        private const val SINGLE_ACCOUNT_TOP = 24f

        private const val TITLE_BASELINE = 54f
/**
         * How long a value takes to travel from its old reading to its new one.
         *
         * This replaced a damped spring, at the user's request, and the reason is worth
         * keeping: the spring was underdamped (zeta 0.56) so that a change would
         * overshoot by about a tenth and settle back, which reads as physical. But a bar
         * is a bounded quantity. Heading to 94% it overshot past 100%, clamped flat
         * against the top, and crept back down; heading to 0% it went negative and
         * clamped at empty. The overshoot that makes a spring feel alive makes a
         * PERCENTAGE look wrong — it reports a value the data never held.
         *
         * A cubic in-out cannot pass its target, so there is nothing to clamp.
         */


        private const val REDRAW_INTERVAL_MS = 15_000L

        /** Accent until it matters, then the ramp's upper stops. Mirrors waybar's battery rule. */

        /**
         * Slow sub-pixel wander so a permanently-lit panel doesn't ghost. 10-minute
         * period, ±4px, applied to the whole composition including the ACRNM mark —
         * see onDraw for why this is load-bearing rather than cosmetic now that the
         * panel is always-on unconditionally.
         *
         * `now` should be a monotonic clock (SystemClock.elapsedRealtime()) at the
         * call site — see onDraw's KDoc. This function itself is clock-agnostic; it
         * just needs a steadily-increasing Long, verified by StalenessTest.
         *
         * ±4px and a 10-minute period are the brief's numbers, written for the
         * original opt-in/default-off toggle design. They still hold here — see
         * the analysis behind them, now that the panel runs this unconditionally, 24/7.
         * Do not change them without re-checking the ring geometry margins in
         * PanelGeometry.DEFAULT first: this
         * function's contract (StalenessTest's `dx in -4f..4f && dy in -4f..4f`)
         * pins the amplitude, and PanelGeometry's default ring placement has as
         * little as ~1-2px of margin from the panel edge at this amplitude already.
         */
        /**
         * Radial burn-in offset for the rings, in px.
         *
         * ±3 against a 25px stroke, so about a quarter of the annulus is fresh at each
         * extreme — comparable to what the ±4px translation achieved for a shape of
         * this size, without moving the centre off the lens.
         *
         * A 7-minute period, deliberately coprime-ish with driftOffset's 10, so the two
         * mitigations do not lock into a common phase and leave some pixel pair lit
         * together every cycle.
         */
        @JvmStatic
        fun ringBreath(now: Long): Float {
            val period = 420_000.0
            return (Math.sin((now % period.toLong()) / period * 2 * Math.PI) * 3).toFloat()
        }

        /**
         * The burn-in walk: where the whole composition sits this minute.
         *
         * ROUNDED TO WHOLE PIXELS, and that is load-bearing rather than tidy. This is a
         * translate applied to the entire canvas, so a fractional offset puts every edge
         * on the panel a fraction of a pixel off its grid — and the traced glyphs are
         * stacks of one-pixel rows, which the rasteriser then renders with partial
         * coverage. The transport buttons came out banded, measured at (93,99,189) and
         * (104,111,212) on alternating rows of a solid shape, and snapping the
         * rectangles themselves did nothing because the translate under them was still
         * fractional.
         *
         * Costs nothing. This exists to keep a static image from sitting on the same
         * OLED pixels for hours; whether it moves in whole pixels or fractions of one is
         * irrelevant to that, and whole pixels are what a panel drawn from pixel art
         * wants underneath it.
         */
        @JvmStatic
        fun driftOffset(now: Long): Pair<Float, Float> {
            val period = 600_000.0
            val phase = (now % period.toLong()) / period * 2 * Math.PI
            return Math.round(Math.sin(phase) * 4).toFloat() to
                Math.round(Math.cos(phase) * 4).toFloat()
        }
    }
}
