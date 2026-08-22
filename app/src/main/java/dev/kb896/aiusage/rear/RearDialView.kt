package dev.kb896.aiusage.rear

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import androidx.core.content.ContextCompat
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
    /**
     * Whether this view answers touches at all.
     *
     * False for the preview on the front screen, and that is a bug fix rather than a
     * tidy-up: the preview is a live RearDialView sitting inside the settings page's
     * scroll view, so every drag past it was handed to the pager. Scrolling the app
     * silently scrolled the preview onto the media page, which then showed a record
     * player where the panel was supposed to be previewed.
     *
     * A preview is a picture of the panel. Nothing on it is meant to be operated, and the
     * buttons it draws point at hardware this process does not have.
     */
    var interactive: Boolean = true

    var config: PanelConfig = PanelConfig.DEFAULT
        set(value) {
            field = value
            // Switching the feature OFF has to act now, not at the next thing the player
            // says. The row and the record are only ever created by a broadcast, so
            // without this they sat there — correctly drawn, from a config that no longer
            // wanted them — until the next track change, which on a paused player is
            // never. Turning it back ON is the other half, and lives on the front screen:
            // MainActivity asks the watcher to re-publish (see MEDIA_SYNC).
            if (!value.showMedia && media != null) {
                showMedia(active = false, title = "", artist = "", playing = false)
            }
            invalidate()
        }

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

    /** The notice that has expired and is riding the strip off the panel. */
    private var outgoing: Notice? = null
    private var outgoingAt = 0L

    /** Notices waiting their turn. See showNotification and [promoteNotices]. */
    private val pending = ArrayDeque<Notice>()

    /**
     * Whether two notices say the same thing.
     *
     * A repeat is not news, and the system delivers one twice on every ranking change —
     * so without this a single message could occupy two slots in the queue and be read
     * out twice in a row.
     */
    private fun sameAs(a: Notice?, b: Notice): Boolean =
        a != null && a.app == b.app && a.title == b.title && a.text == b.text

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

    /**
     * A value that eases to its target over a chosen duration, keyed by name.
     *
     * Separate from [animateTo], which runs on Tween's 1800ms — right for a bar settling
     * to a fresh poll and hopeless for something moving under a finger. A volume ring on
     * Tween's clock would still be catching up two seconds after the drag ended.
     *
     * Retargets from wherever it currently is, so a value changed repeatedly — which is
     * exactly what a volume drag is — follows smoothly instead of restarting on every
     * step.
     */
    private fun ease(
        key: String,
        target: Float,
        durationMs: Float,
        linear: Boolean = false,
        /**
         * How big a change has to be to count as a new movement.
         *
         * Without this, a target that creeps every frame restarts the leg every frame —
         * and an ease-in-out begins each leg at zero velocity, so the follower never
         * accumulates any. Measured: the playback dial sat at one degree while its track
         * ran sixteen seconds, because each frame it was told to start moving and then
         * asked again before it had. Below the threshold the destination is simply
         * updated and the clock left alone, so a finished leg tracks its target exactly.
         *
         * The volume does not need it: it moves in discrete steps and follows linearly,
         * where every restart begins at full speed.
         */
        epsilon: Float = 0f,
    ): Float {
        val leg = legs[key]
        if (leg == null) {
            legs[key] = Leg(target, target, frameNowMs)
            return target
        }
        // The clock is kept as a LONG. It was a Float, packed into the same array as the
        // two values, and that quietly broke the whole thing: uptime on this device is
        // 310 million milliseconds, where a Float can only step 32 at a time. Two frames
        // 16ms apart rounded to the same number, the elapsed time came out as zero, and
        // the eased value simply stopped following — measured sitting at 0.015 while its
        // target moved from 0.166 to 0.02. The animation was never wrong; it was being
        // told no time had passed.
        val t = ((frameNowMs - leg.startMs).toFloat() / durationMs).coerceIn(0f, 1f)
        val value = leg.from + (leg.to - leg.from) * (if (linear) t else Tween.easeInOut(t))
        if (Math.abs(target - leg.to) > epsilon) {
            leg.from = value
            leg.to = target
            leg.startMs = frameNowMs
            animating = true
        } else {
            // A drift rather than a movement: follow it without restarting the clock.
            leg.to = target
        }
        if (t < 1f) animating = true
        return value
    }

    /** One eased value: where it came from, where it is going, and when it set off. */
    private class Leg(var from: Float, var to: Float, var startMs: Long)

    /** Put an eased value exactly on its target, so it continues from there later. */
    private fun snapEase(key: String, value: Float): Float {
        legs[key] = Leg(value, value, frameNowMs)
        return value
    }

    private val legs = mutableMapOf<String, Leg>()

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
        // The charger going in. ACTION_POWER_CONNECTED rather than watching
        // ACTION_BATTERY_CHANGED for a status flip: the former fires once on the event,
        // the latter fires whenever the level or temperature moves and would have to be
        // filtered back down to the same thing.
        ContextCompat.registerReceiver(
            context, powerReceiver,
            android.content.IntentFilter(android.content.Intent.ACTION_POWER_CONNECTED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // GRAVITY, not the accelerometer. The accelerometer carries the hand's own
        // movement as well as gravity, so a surface answering it jitters while the phone
        // is merely being held. Gravity is the low-passed version, and is what a liquid
        // actually responds to.
        //
        // Absent on some devices, in which case the surface stays level — still a sound
        // picture of a liquid, just a still one.
        val sensors = context.getSystemService(android.hardware.SensorManager::class.java)
        val gravity = sensors?.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY)
        if (gravity != null) {
            sensors.registerListener(
                gravityListener, gravity, android.hardware.SensorManager.SENSOR_DELAY_UI)
        }
    }

    private val powerReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
            showCharging()
        }
    }

    /** The gravity vector, as the sensor reports it. See chargeTilt for the axes. */
    private var gravityX = 0f
    private var gravityY = 0f

    private val gravityListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            gravityX = e.values[0]
            gravityY = e.values[1]
            // Only while there is something to move. This fires several times a second
            // for the life of the panel, and a redraw is not free.
            if (chargeAt != 0L) invalidate()
        }
        override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) = Unit
    }

    /**
     * Gravity in the PANEL's own axes, which is not the sensor's frame.
     *
     * The sensor reports a vector pointing AWAY from the ground — Android's convention,
     * and the opposite of the acceleration a falling thing feels. The panel is a landscape
     * surface on the BACK of the phone, so a viewer looking at it sees the device turned
     * about its vertical axis: both in-plane axes flip, and those two inversions cancel
     * against the sensor's. Which is why this is the identity, and why it is written out
     * rather than left as one.
     *
     * IT WAS BRIEFLY FLIPPED ON Y, and that is worth recording because the reasoning was
     * bad in an instructive way. The liquid was piling at the top of the glass, so the
     * vertical axis was declared inverted "by observation" and the sign turned over. The
     * observation was real and the diagnosis was wrong: the pile was at the top because
     * the physics was inflating it — separation displacement was being read back as
     * velocity, so a packed heap pushed itself upward faster than gravity pulled it down.
     * Once that was fixed the flip became the bug, and the liquid climbed for a living.
     *
     * The lesson is not about axes. A symptom seen at the end of a pipeline says nothing
     * about which stage produced it, and "observed rather than derived" is only better
     * than reasoning when the thing observed is the stage in question.
     */
    private fun gravityPanelX(): Float = gravityX

    private fun gravityPanelY(): Float = gravityY

    override fun onDetachedFromWindow() {
        removeCallbacks(redrawTick)
        runCatching { context.unregisterReceiver(powerReceiver) }
        runCatching {
            context.getSystemService(android.hardware.SensorManager::class.java)
                ?.unregisterListener(gravityListener)
        }
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
        // How far a notification strip has pushed the canvas side down. Computed here
        // rather than where it is used, because the record behind the readout rides the
        // same offset and is drawn first.
        val push = noticeHeight(nowReal) * noticeSlide(nowReal)

        // Paging. The media page exists only under the vinyl style and only while
        // something is playing — see MediaPage for why a square cover gets no second
        // page. A preview is never paged: it is a picture of the panel on a phone screen,
        // and a scroll position it cannot be given is a scroll position that would just
        // be wrong.
        settlePresence(frameNowMs)
        val wantsMediaPage = onlyAccount == null && mediaPageAvailable()
        if (wantsMediaPage) {
            pager.pages = 2
            // COME BACK TO THE PAGE YOU WERE ON.
            //
            // The :rear process is destroyed and recreated constantly — every recovery,
            // every re-placement, every time HyperOS decides to — and it holds no state,
            // by design, so that a respawned panel is never briefly wrong. That is right
            // for everything it DRAWS, all of which arrives in one payload. It is wrong
            // for where you were standing: being put back on the readout because the
            // system restarted a process is the panel losing your place for a reason
            // that is nothing to do with you.
            //
            // Restored WITHOUT animating, and only once. A panel that comes up and then
            // visibly scrolls to the media page is showing you a transition you did not
            // make; it should simply already be there.
            if (!pageRestored) {
                pageRestored = true
                if (panelPrefs.getInt(KEY_PAGE, 0) == 1) {
                    pager.goTo(1, frameNowMs)
                    pager.advance(frameNowMs + PAGE_SETTLE_MS.toLong())
                }
            }
        } else if (pager.pages > 1) {
            // The media page can vanish while you are standing on it — the music stops,
            // and the page it was about goes with it. Walk back rather than cutting: the
            // panel drops a page only once it has actually returned to the first one, so
            // what you see is the readout arriving, not the panel blinking.
            pager.settleTo(0, frameNowMs)
            if (pager.restingAt(0)) pager.pages = 1
        }
        val paged = if (onlyAccount == null) pager.advance(frameNowMs) else 0f
        // Written only when it SETTLES, not while a drag is in flight: a scroll that is
        // abandoned halfway never happened, and a file written sixty times a second
        // during a swipe is a lot of I/O to record something that is still changing.
        if (interactive && pageRestored && pager.restingAt(pager.page) &&
            pager.page != pageSaved
        ) {
            pageSaved = pager.page
            panelPrefs.edit().putInt(KEY_PAGE, pageSaved).apply()
        }
        val paging = (paged / Panel.HEIGHT).coerceIn(0f, 1f)

        // The notice pushes the READOUT down, and only while the readout is the page you
        // are on. Off-page it must not: the readout sits a full panel height above, so
        // pushing it 143 units down slid its bottom edge back into view underneath the
        // notification — a strip of the first page visible behind a card on the second.
        //
        // On the media page the notice makes room a different way. See drawMediaPage.
        val readoutPush = push * (1f - paging)
        if (pager.animating()) animating = true

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
        // The record, behind everything on the canvas side. Drawn inside the readout's
        // clip so the ripple sweeps it away with the rest, and under the drift for the
        // same reason the mark is: a 572-unit disc held still on an always-on OLED is
        // the largest burn-in target this panel has ever had.
        if (!hidden) {
            canvas.save()
            // NOT paged. The record is the one thing that does not scroll away — it
            // travels between the two pages instead, shrinking and moving to the middle
            // as the readout leaves over the top of it. That is the whole gesture: the
            // panel turns into a record player rather than showing a second screen.
            // Rides the readout's push while it is behind the readout, and none of it
            // once it has become the media page's record — where the notice shrinks it
            // instead of moving it.
            canvas.translate(dx, dy + readoutPush)
            drawVinyl(canvas, paging, push, geometry)
            canvas.restore()
        }

        val order = orderedAccounts()
        val positions = listOf(geometry.personal, geometry.work)
        // The rings do not slide and they do not fade. They SWEEP: as the page turns,
        // each one travels from what it reports about usage to what it reports about the
        // music — the top one to the playback position, the bottom one to the volume.
        //
        // They are the one part of this panel that could do that, because they are the
        // one part that is a dial rather than a layout. They are also drawn concentric
        // with the phone's actual camera lenses, so moving them was never an option: a
        // ring in motion is a circle visibly not on the lens it traces.
        val playing = media ?: departing
        val mediaRingTint = colorFor("media", config.mediaTint)
        // EASED, which matters for exactly one moment: the end of a track. The reading
        // goes from nearly full to nothing between two frames, and a dial that jumps
        // there reads as the panel having lost its place rather than as a record
        // finishing. Eased, it winds back.
        //
        // The cost during ordinary playback is a lag of the ease's own duration — half a
        // second against a track of several minutes, which is under a third of one per
        // cent of the arc and not visible. Paying that everywhere is far simpler than
        // deciding per frame whether a change was a tick or a jump, and it is the same
        // follower the volume already uses.
        // The position is FROZEN while there is no current track.
        //
        // A skip is not instant: the player drops the session for a moment while it loads
        // the next one, and during that gap the only thing left to read is the track that
        // is leaving. It is still marked as playing, so carrying its position forward ran
        // it past its own end — the dial filled to 100% on every skip and then wound back
        // from there, which is not the dial returning to the beginning, it is the dial
        // going the long way round first.
        //
        // What is left of a departing track is whatever it last reported. It is not
        // playing any more; it is gone.
        // A POSITION STAMPED BEFORE THE TRACK CHANGED belongs to the previous track.
        //
        // Players deliver a track in stages — the same staging that put the wrong sleeve
        // on the record — so the first report carrying a new title can still carry the
        // old position. Measured: a new title arriving with 30 seconds on the clock of a
        // track that had only just begun, which put the dial a sixth of the way round
        // before it wound back. Not every skip does it, which is what made it look
        // intermittent.
        //
        // MediaSession stamps every position with the clock reading it was taken at, so
        // this is decidable rather than guessable: a stamp older than the change is not
        // about this track. It self-corrects the moment the player publishes a fresh one,
        // and gives up after a few seconds in case a player never does.
        val current = media
        val stale = current != null &&
            current.positionAtMs < trackChangedAtReal &&
            nowReal - trackChangedAtReal < POSITION_TRUST_MS
        // A seek THIS PANEL asked for, believed until the player's own reading catches up.
        //
        // Exactly the staged-metadata problem again, from the other end: between sending
        // the seek and the player reporting from its new position, every reading we have
        // is from before the jump. Holding the value we asked for keeps the dial where the
        // finger left it instead of snapping back for a moment and then jumping forward.
        // Decidable rather than guessable, by the same position stamps: a reading taken
        // before the seek is not about where the track is now.
        if (seekedTo != null &&
            (nowReal - seekedAt >= POSITION_TRUST_MS ||
                (current != null && current.positionAtMs >= seekedAt))
        ) {
            seekedTo = null
        }
        val progressRaw = when {
            stale -> 0f
            seekedTo != null -> seekedTo
            current != null ->
                MediaLayout.progress(current.elapsed(nowReal), current.durationMs)
            else -> departing?.let { MediaLayout.progress(it.positionMs, it.durationMs) }
        } ?: 0f
        val scrubbing = scrubTo
        val progressFraction = if (scrubbing != null) {
            // NO easing under the finger, the same rule the volume follows: easing exists
            // to make a value that JUMPS read as a movement, and a value being moved by
            // hand does not jump. Snapping the follower also leaves it sitting exactly
            // where the finger let go, so an abandoned scrub eases back from there rather
            // than from wherever a lagging follower had got to.
            snapEase("media:progress", scrubbing)
        } else {
            ease("media:progress", progressRaw, PLAYBACK_EASE_MS, epsilon = PROGRESS_JUMP)
        }
        // The readout page's hairline reads the SAME follower rather than the position.
        // It is the same fact on the same panel, and two readings of it that disagree
        // during a wind-back would be visible in the moment the pages overlap on a scroll.
        progressShown = progressFraction
        // Eased, because the volume moves in steps under a finger and a dial that jumped
        // between notches would read as a counter rather than as a control.
        val rawVolume = if (muted()) 0f else volumeLevel()
        val volumeFraction =
            if (drag == Drag.VOLUME) {
                // NO easing under the finger. Easing exists to make a value that JUMPS
                // read as a movement; a value already being moved by hand does not jump,
                // and smoothing it only puts the ring behind the finger dragging it. The
                // ease resumes the moment the finger lifts, from wherever the ring is, so
                // a change from anywhere else still arrives softly.
                snapEase("media:volume", rawVolume)
            } else {
                ease("media:volume", rawVolume, VOLUME_EASE_MS, linear = true)
            }
        val mediaFractions = listOf(progressFraction, volumeFraction)
        // How held each dial looks. Eased, so taking hold and letting go are movements
        // rather than a state the ring is suddenly found in.
        // Only the playback dial. The volume applies LIVE — you hear the result, which is
        // feedback enough — so greying it would be saying "not committed yet" about a
        // change that already happened. The grip belongs to the control that only
        // previews, and that is the position.
        val mediaEngaged = listOf(engagement(Drag.SCRUB), 0f)
        // Where the volume goes back to when unmuted. Eased on the same clock as the
        // arc it sits under, so engaging mute is one movement — the reading falls to
        // zero and the ghost of it stays where it was — rather than two.
        val ghostRaw = if (muted()) mutedFrom else 0f
        val mediaGhost = listOf(
            0f,
            // Pinned to the finger while it is the thing being dragged, eased otherwise —
            // the same rule the live arc follows, and for the same reason: easing a value
            // that is being moved by hand only puts it behind the hand.
            if (drag == Drag.VOLUME && muted()) snapEase("media:mutedfrom", ghostRaw)
            else ease("media:mutedfrom", ghostRaw, VOLUME_EASE_MS, linear = true),
        )

        if (!hidden) order.forEachIndexed { position, accountIndex ->
            val account = Account.entries.getOrNull(accountIndex) ?: return@forEachIndexed
            if (onlyAccount != null && account != onlyAccount) return@forEachIndexed
            // A previewed account takes the first ring position whatever its place in
            // the order: the preview shows that account's panel, not its slot.
            val at = if (onlyAccount != null) 0 else position
            val geo = positions.getOrNull(at) ?: return@forEachIndexed
            // Ring one takes the playback position, ring two the volume — both as
            // gauges rather than full circles, with their gaps facing each other. See
            // MediaPage.PLAYBACK_ARC_START and VOLUME_ARC_START.
            val isVolume = at == 1
            drawRing(
                canvas, geo, config.rings.getOrElse(position) { RingSpec() }, account, breath,
                blend = paging,
                mediaFraction = mediaFractions.getOrElse(at) { 0f },
                mediaColor = mediaRingTint,
                mediaStart =
                    if (isVolume) MediaPage.VOLUME_ARC_START
                    else MediaPage.PLAYBACK_ARC_START,
                mediaSweep =
                    if (isVolume) MediaPage.VOLUME_ARC_SWEEP
                    else MediaPage.PLAYBACK_ARC_SWEEP,
                engaged = mediaEngaged.getOrElse(at) { 0f },
                ghostFraction = mediaGhost.getOrElse(at) { 0f },
            )
        }

        // The strip pushes the readout down and the RINGS DO NOT MOVE. They are drawn
        // concentric with the phone's actual lenses, so shifting them would put two
        // circles a hundred pixels away from the hardware they trace. Everything else on
        // the canvas side rides down and some of it leaves the bottom of the panel,
        // which is the trade: a notification is asking for attention, and for five
        // seconds it gets it.
        canvas.save()
        canvas.translate(dx, dy + readoutPush - paged)
        if (!hidden) drawCanvasSide(canvas, dx, dy + readoutPush - paged)
        // The extras belong to the panel as a whole, not to an account, so a
        // single-account preview leaves them out — see onlyAccount.
        if (onlyAccount == null && !hidden) drawExtras(canvas)
        // Same rule as the extras: it acts on the panel rather than on an account, so a
        // single-account preview leaves it out — and a preview is scaled, which would
        // put the touch target in the wrong place even if it were wanted there.
        if (onlyAccount == null && config.showCamera && !hidden) {
            drawCamera(canvas, dx, dy + readoutPush - paged)
        } else {
            cameraButton = null
        }
        // The close button belongs to the READOUT and scrolls away with it. It was
        // briefly pinned across both pages, on the reasoning that it is the panel's one
        // permanent control — but it closes the readout, and on a page that is not the
        // readout it is a button for somewhere else. The way back from the media page is
        // the swipe that got you there.
        if (onlyAccount == null && config.showClose && !hidden) {
            drawClose(canvas, dx, dy + readoutPush - paged)
        } else {
            closeButton = null
        }
        canvas.restore()

        // The media page: everything on the second page except the record, which was
        // drawn behind the readout and has been travelling here the whole time.
        //
        // OUTSIDE the paged translate, which is the whole point and was wrong first
        // time: this page does not scroll, it assembles in place as the readout leaves
        // over the top of it. Nested inside, every part of it was drawn a full panel
        // height above the screen and the page came up as a bare record on black.
        if (!hidden && onlyAccount == null && paging > 0.001f) {
            canvas.save()
            // NOT pushed by the notice. Everything on this page is anchored to the
            // bottom of the panel and pushing it down puts the transport buttons off the
            // screen — which is what happened, and you cannot press what has left. The
            // record gives up the room instead.
            canvas.translate(dx, dy)
            drawMediaPage(canvas, paging, dx, dy, push, geometry, volumeFraction)
            canvas.restore()
        } else if (paging <= 0.001f) {
            volumeBar = null
        }
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

        // LAST, and outside every translate on the way here.
        //
        // Over both pages, unmoved by the notification push and unscrolled by the pager:
        // the charger going in is a fact about the phone rather than about whichever page
        // you happen to be looking at, so it appears where it appears and the panel
        // carries on underneath.
        drawCharge(canvas)

        // Next frame only while something is still moving. When everything has settled
        // this stops entirely and the view falls back to the 15s idle tick — an
        // always-on panel must not hold a 60fps loop for a display that is not changing.
        if (animating) postInvalidateOnAnimation()
    }

    /** When the cell arrived, and what it is filling to. */
    private var chargeAt = 0L
    private var chargeStepAt = 0L
    private var chargeTenth = -1

    /** Which way the liquid currently thinks is down, in radians. See drawCharge. */
    private var chargeDown = 0f

    /** The level the liquid is filling to, and the figure drawn over it. */
    private var chargeLevel = 0f
    private var chargePercent = 0

    private val surface = LiquidSurface()

    /**
     * Show the charging cell, filling to the battery's present level.
     *
     * Public because the thing that knows a charger went in is a broadcast receiver, and
     * because it is worth being able to fire by hand — see tools/charge.sh. Re-showing
     * while one is up restarts it rather than queueing: there is one charger and one
     * answer, and two cells in a row would be the same fact twice.
     */
    fun showCharging(percent: Int = batteryPercent()) {
        if (percent < 0) return
        chargePercent = percent
        chargeLevel = percent / 100f
        chargeAt = SystemClock.elapsedRealtime()
        chargeStepAt = 0L
        chargeTenth = -1
        surface.calm()
        invalidate()
    }

    /**
     * The charging cell: a glass on the right that fills with liquid.
     *
     * NO TOUCH TARGET, and that is deliberate rather than unfinished. It sits over the
     * volume strip, and a thing that appears unbidden for five seconds must not take the
     * surface from underneath a finger already using it. Nothing is registered, so every
     * touch goes straight through to whatever was there.
     */
    private fun drawCharge(canvas: Canvas) {
        if (chargeAt == 0L) return
        val elapsed = (SystemClock.elapsedRealtime() - chargeAt).toFloat()
        if (ChargeOverlay.done(elapsed)) {
            chargeAt = 0L
            surface.calm()
            return
        }
        animating = true

        val w = ChargeOverlay.width(Panel.WIDTH.toFloat())
        val off = ChargeOverlay.offset(elapsed, w)
        val left = Panel.WIDTH - w + off + ChargeOverlay.MARGIN
        val right = Panel.WIDTH + off - ChargeOverlay.MARGIN
        val top = ChargeOverlay.MARGIN
        val bottom = Panel.HEIGHT - ChargeOverlay.MARGIN
        // THE SCREEN'S OWN RADIUS, carried inward.
        //
        // The cell is inset from the panel's edges by MARGIN, and a shape inset inside a
        // rounded rectangle keeps concentric corners at radius minus inset — so this is
        // the display's actual curve continued, not an approximation of it. cornerRadius()
        // reads it from the window insets rather than assuming a number.
        //
        // Floored, because on a display with barely rounded corners the arithmetic gives
        // something square, and a glass with sharp corners reads as a box.
        val r = (cornerRadius() - ChargeOverlay.MARGIN)
            .coerceIn(ChargeOverlay.MIN_CORNER, (right - left) / 2f)
        val tint = colorFor("charge", config.batteryTint)

        val glass = android.graphics.Path().apply {
            addRoundRect(left, top, right, bottom, r, r, android.graphics.Path.Direction.CW)
        }

        canvas.save()
        canvas.clipPath(glass)
        // The glass's own ground, so the liquid has something to be inside rather than
        // floating over whatever the panel was drawing there.
        noticePaint.style = Paint.Style.FILL
        noticePaint.shader = null
        noticePaint.color = (config.background and 0x00FFFFFF) or (CHARGE_GROUND_ALPHA shl 24)
        canvas.drawRect(left, top, right, bottom, noticePaint)

        // ---- the liquid, as a SURFACE ----------------------------------------------
        //
        // Drops were the second attempt and metaballs the third, and both were the wrong
        // shape of answer. Blobs merging and pulling apart is what happens to liquid
        // moving through air; this is a vessel with liquid sitting in it, and however
        // well a heap of circles is packed the eye keeps finding the lumps — because a
        // still liquid in a glass does not have any.
        //
        // What it has is a surface. One smooth line that ripples when disturbed and
        // settles flat when left alone, which is a height field: see LiquidSurface.
        val now = SystemClock.elapsedRealtime()
        val dt = if (chargeStepAt == 0L) 0f else (now - chargeStepAt) / 1000f
        chargeStepAt = now

        val (rawX, rawY) = ChargeOverlay.gravity(gravityPanelX(), gravityPanelY())
        // THE DRAWN DOWN LAGS THE MEASURED ONE, on purpose.
        //
        // The level is worked out from where down is, so following the sensor exactly
        // makes a rotation a teleport: turn the phone over and the water is on the other
        // side on the very next frame. Swinging the direction round instead means the
        // surface tilts through every angle in between, which is the liquid going round
        // the glass rather than reappearing somewhere else.
        val want = Math.atan2(rawX.toDouble(), rawY.toDouble()).toFloat()
        chargeDown =
            if (chargeStepAt == 0L || dt <= 0f) want
            else ChargeOverlay.turnToward(chargeDown, want, dt, ChargeOverlay.TURN_RATE)
        val downX = Math.sin(chargeDown.toDouble()).toFloat()
        val downY = Math.cos(chargeDown.toDouble()).toFloat()
        surface.step(dt, downX, downY)

        val fill = ChargeOverlay.fill(elapsed, chargeLevel)
        // A knock as the level passes each tenth, so the arriving liquid disturbs the
        // surface instead of the level sliding up under a sheet of glass.
        val tenth = (fill * 10f).toInt()
        if (tenth != chargeTenth) {
            chargeTenth = tenth
            surface.splash(0.35f + 0.3f * ((tenth * 7) % 10) / 10f, ChargeOverlay.SPLASH)
        }

        canvas.save()
        canvas.clipPath(glass)
        // Along the surface, which is perpendicular to down. Everything below is built in
        // this frame and then simply drawn — nothing needs to know the angle.
        val acrossX = -downY
        val acrossY = downX
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val cw2 = (right - left)
        val ch2 = (bottom - top)
        // How far the cell reaches along each of the two directions. Generous on purpose:
        // the polygon is clipped to the glass, so overshooting costs nothing and running
        // short would leave a gap at a corner.
        val depth = Math.abs(cw2 * downX) + Math.abs(ch2 * downY)
        val span = Math.abs(cw2 * acrossX) + Math.abs(ch2 * acrossY)
        // Where the flat surface sits: at the top of the cell when empty, the bottom when
        // full, measured along the down axis.
        val level = (0.5f - fill) * depth

        val body = android.graphics.Path()
        for (i in 0 until surface.size) {
            val u = (i / (surface.size - 1f) - 0.5f) * span
            val d = level + surface.heightAt(i)
            val px = cx + acrossX * u + downX * d
            val py = cy + acrossY * u + downY * d
            if (i == 0) body.moveTo(px, py) else body.lineTo(px, py)
        }
        // Close it off well past the far side, so the fill runs to the bottom of the glass
        // whichever way up the glass currently is.
        body.lineTo(cx + acrossX * span / 2f + downX * depth, cy + acrossY * span / 2f + downY * depth)
        body.lineTo(cx - acrossX * span / 2f + downX * depth, cy - acrossY * span / 2f + downY * depth)
        body.close()

        noticePaint.style = Paint.Style.FILL
        noticePaint.shader = null
        noticePaint.color = (tint and 0x00FFFFFF) or (CHARGE_LIQUID_ALPHA shl 24)
        canvas.drawPath(body, noticePaint)

        // The meniscus. A liquid's surface catches the light, and a brighter line along
        // it is most of what separates "filled shape" from "something with a top".
        noticePaint.style = Paint.Style.STROKE
        noticePaint.strokeWidth = CHARGE_SURFACE
        noticePaint.color = (tint and 0x00FFFFFF) or (CHARGE_MENISCUS shl 24)
        val skin = android.graphics.Path()
        for (i in 0 until surface.size) {
            val u = (i / (surface.size - 1f) - 0.5f) * span
            val d = level + surface.heightAt(i)
            val px = cx + acrossX * u + downX * d
            val py = cy + acrossY * u + downY * d
            if (i == 0) skin.moveTo(px, py) else skin.lineTo(px, py)
        }
        canvas.drawPath(skin, noticePaint)
        noticePaint.style = Paint.Style.FILL
        canvas.restore()

        // And the glass's clip, so the rim below is a full stroke rather than a half one
        // cut down its own centre line.
        //
        // ONE restore for one save, and it is worth being careful here: this function has
        // had the pairing wrong in both directions within an hour. First a restore was
        // dropped and the rim was drawn inside the clip; then the metaball rewrite took
        // out a save and left its restore behind, which is not a cosmetic fault — the
        // canvas underflows, onDraw throws, and the panel process dies on the frame the
        // charger is plugged in.
        canvas.restore()

        // The rim, drawn after the clip is lifted so it is a full stroke rather than a
        // half one clipped down its own centre line.
        vinylPaint.style = Paint.Style.STROKE
        vinylPaint.strokeWidth = CHARGE_RIM
        vinylPaint.color = (tint and 0x00FFFFFF) or (CHARGE_RIM_ALPHA shl 24)
        canvas.drawRoundRect(left, top, right, bottom, r, r, vinylPaint)
        vinylPaint.style = Paint.Style.FILL

        // The figure, in the middle of the glass. Over the liquid rather than inside it:
        // a number that the liquid rose past and half-covered would be unreadable at
        // exactly the moment it became interesting.
        val size = (right - left) * ChargeOverlay.TEXT_SIZE
        extras.textSize = size
        val was = extras.textAlign
        extras.textAlign = Paint.Align.CENTER
        extras.color = tint
        // THE FIGURE CLIMBS WITH THE LIQUID.
        //
        // It used to show the final number from the first frame, which makes the reading
        // and the picture disagree for three seconds — the water says a fifth full and
        // the text says sixty-two per cent. Counted from the same fill fraction the
        // surface is drawn at, so the two cannot drift apart: they are the same number
        // twice, and it arrives exactly as the level settles.
        canvas.drawText(
            Math.round(fill * 100f).toString() + "%",
            (left + right) / 2f, (top + bottom) / 2f + size * 0.36f, extras,
        )
        extras.textAlign = was
        extras.textSize = 18f
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
    /**
     * One lens ring.
     *
     * [blend] carries it from what it reports on the readout to what it reports on the
     * media page — 0 is usage, 1 is [mediaFraction] in [mediaColor]. The ring does not
     * fade out and a second one does not fade in: it is the SAME dial, sweeping from one
     * reading to the other as the page turns. Cross-fading was the first attempt and it
     * looked like two rings in the same place, which is exactly what it was.
     *
     * A ring set to NONE still appears on the media page. The user switched off a reading
     * about usage; the volume is not that reading, and a page with one dial on it would
     * look broken rather than configured.
     */
    private fun drawRing(
        canvas: Canvas,
        g: RingGeometry,
        spec: RingSpec,
        account: Account,
        breath: Float = 0f,
        blend: Float = 0f,
        mediaFraction: Float = 0f,
        mediaColor: Int = 0,
        /** Where this ring's media reading starts and how far round it can go. */
        mediaStart: Float = -90f,
        mediaSweep: Float = 360f,
        /** How held this dial is, 0 to 1 — see [engagement]. */
        engaged: Float = 0f,
        /**
         * A dim arc drawn UNDER the reading, showing where it would be otherwise.
         *
         * For the volume while muted: the live arc is at zero, correctly, because that is
         * what is coming out of the speaker — but zero on its own says nothing about
         * whether unmuting returns you to a whisper or to full. The ghost is the same
         * grey the playback dial wears under a finger, and for the same reason: it is a
         * value that is not currently a fact about what you can hear.
         */
        ghostFraction: Float = 0f,
    ) {
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
        val presence = animateTo("ring:" + account.name + ":presence",
            if (spec.metric == RingMetric.NONE) 0f else 1f)
        // The media page has its own reason for this ring to exist, so a ring switched
        // off for usage comes back as the page turns.
        val visible = maxOf(presence, blend)
        if (visible <= 0f) return

        // NO UNFILLED RING, on either page. The grey circle behind the arc was there to
        // say how much of a whole the reading was, and on a panel drawn around two camera
        // lenses it mostly said "there is a grey circle here" — it read as hardware
        // rather than as a scale, and it was the only element on the panel with a
        // permanent visible ground. The arc alone is the reading.
        val r = g.radius + breath
        scratch.set(g.cx - r, g.cy - r, g.cx + r, g.cy + r)

        // No data, or data we can't vouch for: dim ring, no arc. Never render
        // a stale or absent reading as if it were live.
        //
        // Unless the media page is asking for it, which is a different reading entirely —
        // an account that failed to fetch says nothing about the volume.
        val usable = status != null && window != null && status.health != Health.AUTH
        if (!usable && blend <= 0f) return

        // A dial being DRIVEN must not look like a dial being read. The finger doing the
        // driving is on the far side of the phone and cannot be seen, so the ring itself
        // has to say it is held: it thickens, because that is what a gripped thing does,
        // and it greys, because a reading under a finger is a proposal rather than a fact
        // about the music. Both come back when the finger lifts, whether the gesture was
        // committed or abandoned — the colour says "held", not "accepted".
        //
        // Scaled by the page, so a ring caught mid-scroll is not greyed on the readout,
        // where this reading has no meaning at all.
        val grip = (engaged * blend).coerceIn(0f, 1f)
        arc.strokeWidth = g.stroke * (1f + (MediaPage.ENGAGED_STROKE - 1f) * grip)
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
        val target = when {
            !usable || window == null -> 0f
            spec.metric == RingMetric.TIME_TO_RESET -> windowElapsed(window)
            spec.metric == RingMetric.UTILIZATION -> window.utilization
            // Not a reading the ring reports — the value it retreats to.
            else -> 0f
        }
        // Keyed by the ACCOUNT alone, not by what its ring currently shows. A Tween
        // opens on its first target instead of animating up from zero, so folding the
        // window and the metric into the key meant every change to what a ring reports
        // built a fresh tween and snapped — including the change to NONE, where the
        // unwind is the whole point.
        val shown = animateTo("ring:" + account.name, target).coerceIn(0f, 1f)
        val usageColor = when {
            status != null && status.health != Health.OK -> Panel.Palette.DIM
            spec.metric == RingMetric.UTILIZATION -> RampColor.at(shown, config.rampColors)
            else -> accentOf(account)
        }
        // The sweep and the colour travel together, on the page's own easing rather than
        // a tween of their own — so the dial arrives exactly as the page settles.
        val fraction = shown + (mediaFraction.coerceIn(0f, 1f) - shown) * blend
        val settledColor =
            if (blend <= 0f) usageColor else RampColor.lerp(usageColor, mediaColor, blend)
        arc.color =
            if (grip <= 0f) settledColor
            else RampColor.lerp(settledColor, Panel.Palette.DIM, grip)
        arc.alpha = (255 * visible).toInt()
        // Where the arc starts and how far it can reach both travel with the page, so a
        // ring that becomes a segment closes up as it goes rather than jumping to its new
        // shape once the page lands.
        val startAngle = -90f + (mediaStart - -90f) * blend
        val fullSweep = 360f + (mediaSweep - 360f) * blend
        // The ghost first, so the live reading is drawn over it rather than under.
        val ghost = (ghostFraction.coerceIn(0f, 1f) * blend)
        if (ghost > 0.001f) {
            val was = arc.color
            arc.color = Panel.Palette.DIM
            arc.alpha = (255 * visible * blend).toInt().coerceIn(0, 255)
            canvas.drawArc(scratch, startAngle, fullSweep * ghostFraction, false, arc)
            arc.color = was
            arc.alpha = (255 * visible).toInt()
        }
        canvas.drawArc(scratch, startAngle, fullSweep * fraction, false, arc)
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

    /**
     * Inside a target on the X axis alone.
     *
     * For the volume strip, where the Y axis is the reading and cannot also be the test:
     * running off the top is how you ask for maximum, so treating it as leaving the
     * control would cancel the gesture at exactly the moment it succeeded.
     */
    private fun within(r: android.graphics.RectF?, x: Float): Boolean {
        val b = r ?: return false
        return x >= b.left - CAMERA_SLOP && x <= b.right + CAMERA_SLOP
    }

    @Suppress("ClickableViewAccessibility")   // the rear panel has no accessibility path
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (!interactive) return false
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
        val onMute = !ambient && near(muteButton, event.x, event.y)
        // Checked after mute, so the button wins where their slop regions overlap: it
        // sits inside the volume area, and a press meant for mute that instead began a
        // volume drag would look identical and do the wrong thing.
        val onVolume = !ambient && !onMute && near(volumeBar, event.x, event.y)
        // The open area, which is everything above the buttons that is not the strip.
        // A scrub is only offered where there is something to scrub to: a live stream or
        // a player that reports no timeline has no position to drag, and a dial that
        // followed the finger and then sprang back would be a control that lies.
        val onScrub = !ambient && !onMute && !onVolume &&
            (media?.durationMs ?: 0L) > 0L && near(scrubBar, event.x, event.y)
        val hit = onCamera || onMedia != null || onVolume || onScrub || onMute ||
            (config.showClose && onClose)

        return when (event.actionMasked) {
            // Claim the gesture on the way down if it started on a target, so a slight
            // drag still counts — a finger on the back of a phone is not steady, and
            // without claiming it there is no ACTION_UP to act on.
            //
            // And claim it when there is anywhere to page to, whether or not it landed on
            // anything: a drag has to be watched from the start to be recognised at all.
            android.view.MotionEvent.ACTION_DOWN -> {
                drag = Drag.UNDECIDED
                downX = event.x
                downY = event.y
                dragInBounds = true
                volumeDownY = event.y
                volumeDownGhost = mutedFrom
                volumeDownLevel = audio
                    ?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                    ?.toFloat() ?: 0f
                // All three are armed on the way down; which one gets the gesture is
                // decided on the first move that clears the slop.
                pager.onDown(event.y, android.os.SystemClock.uptimeMillis())
                hit || pager.enabled() || onVolume || onScrub
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dxTravel = Math.abs(event.x - downX)
                val dyTravel = Math.abs(event.y - downY)
                if (drag == Drag.UNDECIDED && maxOf(dxTravel, dyTravel) >= DRAG_SLOP) {
                    // Region AND axis, both. Each control owns one direction in one
                    // place, so the other two remain reachable from inside it.
                    val sideways = dxTravel > dyTravel
                    drag = when {
                        !sideways && onVolume -> Drag.VOLUME
                        sideways && onScrub -> Drag.SCRUB
                        else -> Drag.PAGE
                    }
                    // NO auto-unmute here any more. It used to fire on the first move,
                    // on the reasoning that raising the volume on a muted stream should
                    // start from where it was muted rather than fight the mute — but the
                    // effect was that the volume control could do exactly one thing while
                    // muted, which was cancel the mute. A drag now moves the ghost and
                    // leaves the silence alone; see dragVolume.
                }
                when (drag) {
                    Drag.VOLUME -> {
                        // No bounds test and nothing to take back: this applies live, and
                        // a relative drag has no absolute position to be outside of. The
                        // way to undo it is to drag the other way, which is how it has
                        // always worked and is the reason it needs no ceremony.
                        dragVolume(event.y)
                        true
                    }
                    Drag.SCRUB -> {
                        dragInBounds = near(scrubBar, event.x, event.y)
                        val area = scrubBar
                        if (area != null) {
                            scrubTo =
                                MediaPage.scrubFraction(event.x, area.left, area.right)
                        }
                        invalidate()
                        true
                    }
                    Drag.PAGE -> {
                        if (pager.enabled() &&
                            pager.onMove(event.y, android.os.SystemClock.uptimeMillis())
                        ) {
                            invalidate()
                        }
                        true
                    }
                    Drag.UNDECIDED -> hit
                }
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                // The system taking the gesture away is not a decision to commit to it.
                endDrag(commit = false)
                pager.onUp(android.os.SystemClock.uptimeMillis())
                invalidate()
                false
            }
            android.view.MotionEvent.ACTION_UP -> {
                if (drag == Drag.VOLUME || drag == Drag.SCRUB) {
                    if (drag == Drag.VOLUME) dragVolume(event.y)
                    endDrag(commit = dragInBounds)
                    return true
                }
                drag = Drag.UNDECIDED
                // A drag is not a tap, and must not also fire whatever it started on.
                // Without this, letting go of a page swipe that happened to begin over
                // the close button closed the panel.
                if (pager.dragging) {
                    pager.onUp(android.os.SystemClock.uptimeMillis())
                    invalidate()
                    return true
                }
                pager.onUp(android.os.SystemClock.uptimeMillis())
                when {
                    onCamera -> onCameraTap?.invoke()
                    // Before the close branch: a transport press is a press on the
                    // readout, not a request to sweep it away.
                    onMute -> toggleMute()
                    onMedia != null -> {
                        val now = android.os.SystemClock.uptimeMillis()
                        when (onMedia) {
                            // A skip we ASKED FOR, which is the case the guard exists
                            // for and the case it was missing. It used to be inferred
                            // from the title changing, and a skip that drops the session
                            // never produces one to compare: `active` goes false, so
                            // there is no new title, and when the track returns there is
                            // no old one left to have changed from.
                            RearActivity.MEDIA_NEXT,
                            RearActivity.MEDIA_PREVIOUS,
                            -> skipUntil = now + SKIP_GUARD_MS
                            // And a toggle we asked for, which needs the opposite: the
                            // player's answer to our own button press is evidence, and
                            // waiting half a second to believe it is lag on the one
                            // press where the user already knows what they did.
                            RearActivity.MEDIA_PLAY_PAUSE -> {
                                expecting = !playGate.shown
                                expectingUntil = now + EXPECT_MS
                            }
                        }
                        onMediaTap?.invoke(onMedia)
                    }
                    // ONLY the close button, never a general "something was hit".
                    //
                    // This tested `hit`, which was correct while the only two targets
                    // were the camera and the close button. Every target added since
                    // joined `hit`, so by the time the volume area existed a tap anywhere
                    // above the transport row swept the whole panel away — the media page
                    // behaved as one enormous close button.
                    onClose -> {
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

        // A NOTIFICATION THAT ARRIVES WHILE THE PANEL IS DARK IS NOT NEWS, IT IS HISTORY.
        //
        // The rear display sleeps; the broadcasts do not stop. Everything posted through
        // the night was still being queued, and the first thing the panel did on waking
        // was play back ten hours of other people's evenings — which is not a
        // notification strip, it is a log.
        //
        // Dropped rather than shown late, and dropped HERE, before the queue: the queue's
        // job is to pace things worth showing, and it cannot do that job well if it is
        // also holding things that should never have been admitted. Nothing else about it
        // changes — see promoteNotices.
        //
        // The display's own state is the test, not a timestamp. What matters is whether
        // there was a screen to show it on, and the display knows that exactly.
        if (display?.state == android.view.Display.STATE_OFF) return

        val next = Notice(app, pkg, title, text, moreTitle, moreText)

        // QUEUED, not raced. Notifications arrive in bursts — a group syncing, a chat
        // catching up — and replacing on arrival meant each one was on screen for
        // however long the next took to turn up. Three of them inside a second is three
        // wheel turns and nothing anybody could read, which looks like a fault rather
        // than like three messages.
        //
        // So an arrival while one is showing goes to the back of the queue, and the
        // promotion happens on the panel's own clock — see [promoteNotices]. The strip
        // stays open across the whole burst and only the content turns over.
        if (notice == null) {
            // Nothing showing: straight up, and no wheel. The first notice of a run
            // arrives into an empty strip that is itself sliding in, and turning at the
            // same time would have it enter twice over.
            notice = next
            noticeAt = now
        } else if (!sameAs(notice, next)) {
            // BOUNDED. A queue that grows without limit turns a notification storm into
            // minutes of strip long after the phone has gone quiet, and the oldest
            // unshown message is the one least worth waiting for.
            while (pending.size >= MAX_QUEUED) pending.removeFirst()
            pending.addLast(next)
        }
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

    /**
     * The sheet for whatever is playing, and the raw text it was parsed from.
     *
     * The raw copy is kept ONLY so the same broadcast arriving twice does not re-parse a
     * couple of hundred lines. Media broadcasts land on every pause, every position
     * report and every artwork callback, and almost all of them carry the same sheet.
     */
    private var lyrics: dev.kb896.aiusage.data.Lrc = dev.kb896.aiusage.data.Lrc.NONE
    private var lyricsRaw = ""

    /**
     * Which recording the held sheet was resolved for.
     *
     * Not the same question as "which sheet is this", which [lyricsRaw] answers. Two
     * tracks can carry the same words — a live cut and the studio one — and the panel
     * still has to know that the sheet on screen stopped being about what is playing.
     */
    private var lyricsShownFor = ""

    /** The sheet the departing record carries out with it — see drawLeavingLyrics. */
    private var swapLyrics: dev.kb896.aiusage.data.Lrc = dev.kb896.aiusage.data.Lrc.NONE
    private var swapLyricLine = -1

    /** Which line is up, which one it came from, and when it arrived. */
    private var lyricLine = -1
    private var lyricFrom = -1
    private var lyricAt = 0L

    /** Whether words are WANTED. What is drawn lags this by a fade — see [lyricFade]. */
    private var lyricsOn = false

    /**
     * How present the pair of lines is, 0 to 1.
     *
     * The whole block arriving or leaving, which is a different event from a line being
     * promoted and gets a different treatment: promotion travels because a song moving on
     * has a direction, and this fades because being switched on does not.
     *
     * Multiplied by the page, so scrolling between the readout and the record carries the
     * words with it instead of snapping them on at the end of the gesture.
     */
    private fun lyricFade(paging: Float, notice: Float): Float =
        ease(
            "media:lyricfade",
            if (lyricsOn && lyricsFit(notice)) 1f else 0f,
            LyricsRing.FADE_MS,
        ) * paging.coerceIn(0f, 1f)

    /**
     * How much room the lyric arcs are taking, and how much they are on their way to.
     *
     * The target is only ever moved on a RESOLVED answer — a sheet, or a definite "this
     * track has none". It is deliberately NOT moved during the gap while the next track's
     * sheet is being looked up, and that gap is why this is a held field rather than a
     * question asked of [lyrics] each frame.
     *
     * Without the hold the record pumps once per song: the sheet is dropped the moment the
     * title changes, so the disc grows back to full size, and a second later the new sheet
     * lands and it shrinks again. Two animations per track change, neither of which is
     * about anything the listener did. Holding the band through the gap costs a second of
     * empty arc — a space where words are about to be — and the record simply stays put.
     *
     * Zero when the feature is off or a resolved track has no sheet, which is most of
     * them: instrumentals, podcasts, browser tabs, anything nobody has written one for.
     */
    private var lyricsWanted = false

    /**
     * Whether the words fit alongside whatever else is on the page.
     *
     * Derived from the record they would wrap, rather than from a rule about
     * notifications: if giving up the band would leave a record too small to bend a
     * sentence around, there is no room, and the answer corrects itself if any of the
     * sizes ever move. Measured against the FULL band rather than the eased one, so the
     * decision cannot oscillate with its own animation.
     */
    private fun lyricsFit(notice: Float): Boolean =
        MediaPage.disc(notice + LyricsRing.STACK).second >= LyricsRing.MIN_DISC

    private fun lyricBand(notice: Float): Float =
        ease(
            "media:lyricband",
            if (lyricsWanted && lyricsFit(notice)) LyricsRing.STACK else 0f,
            LyricsRing.BAND_MS,
        )

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
        lyricsLrc: String = "",
        lyricsFor: String = "",
    ) {
        val now = SystemClock.uptimeMillis()
        // The sheet has to NAME the recording before it is hung on it.
        //
        // This is the artwork's oldest bug, pre-empted in the one place it would be most
        // obvious. Metadata is staged, so a broadcast during a track change carries the
        // new title beside whatever was resolved for the old one — and a stranger's words
        // over the wrong song is not a subtle mistake the way a stale sleeve is. The id
        // is built the same way on both sides; if they disagree, there are no lyrics yet.
        val wants = title + " " + artist + " " + (durationMs / 1000L)
        val resolved = config.showLyrics && active && lyricsFor.isNotEmpty() &&
            lyricsFor == wants
        if (!resolved) {
            // A SHEET ABOUT ANOTHER RECORDING GOES AT ONCE, with no fade.
            //
            // Fading is right for the apparatus LEAVING — switched off, scrolled away,
            // the music stopping — where the words on screen are still about the song
            // that is playing and simply on their way out. It is wrong for a track
            // change, and this is where that was got wrong: the sheet was left to fade
            // over three hundred milliseconds while the new track's title, artist and
            // record were already up, so the old song's words appeared, briefly, to be
            // the new song's. That is the artwork's oldest bug with a stranger's
            // sentence in it, which is worse than a stale sleeve rather than better.
            //
            // Decidable rather than guessable, by the same identity the broadcast
            // carries: if the held sheet was resolved for a different recording than the
            // one now playing, it is not late, it is wrong.
            if (lyricsShownFor != wants) {
                // Handed to the record that is leaving, exactly as the artwork is — see
                // swapCover. The words belong to that disc, so they go out with it and
                // fade on the way rather than being cut off the instant the title
                // changes. What they must never do is attach to the ARRIVING disc, which
                // is why this is a snapshot travelling with the old one rather than the
                // live sheet being allowed to linger.
                if (!lyrics.isEmpty) {
                    swapLyrics = lyrics
                    swapLyricLine = lyricLine
                }
                lyricsRaw = ""
                lyricsShownFor = ""
                lyrics = dev.kb896.aiusage.data.Lrc.NONE
                lyricLine = -1
                lyricFrom = -1
                snapEase("media:lyricfade", 0f)
            }
            lyricsOn = false
            if (!config.showLyrics || !active) lyricsWanted = false
        } else {
            if (lyricsLrc != lyricsRaw || lyricsShownFor != wants) {
                lyricsRaw = lyricsLrc
                lyricsShownFor = wants
                lyrics = dev.kb896.aiusage.data.Lrc.parse(lyricsLrc)
                // A new sheet starts fresh rather than promoting from the last song's line.
                lyricLine = -1
                lyricFrom = -1
            }
            // A resolved answer, either way: claim the band or give it back.
            lyricsOn = !lyrics.isEmpty
            lyricsWanted = !lyrics.isEmpty
        }
        val next =
            if (active && config.showMedia) {
                Media(title, artist, playing, artPath,
                    durationMs, positionMs, positionAtMs, speed)
            } else {
                null
            }

        // PRESENCE IS DEBOUNCED TOO, on the same rule as the play/pause glyph.
        //
        // Pressing next does not hand over one track for another. The player drops the
        // session for a moment while it loads — active goes false, then true again a few
        // hundred milliseconds later — so a row driven by every report tore itself down
        // and rebuilt on every skip: the record slid off the bottom and climbed back, the
        // artwork flashed in between, and on the media page the whole page went away and
        // returned. The gap is not news, and only holding the play state still was
        // treating half the problem.
        //
        // A gap that does NOT come back inside the window is a real stop, and then the
        // record leaves properly.
        presenceGate.report(next != null, now)
        if (next != null && media == null && !presenceGate.shown) {
            // Arriving from nothing: no reason to wait, there is nothing on screen yet.
            presenceGate.snap(true)
        }
        val wasShown = media != null
        // Only the ARRIVAL is acted on here. A departure waits for settlePresence, or a
        // skip would start the record sliding off before the next track had a chance to
        // arrive — which is the flicker this is all here to prevent.
        if (next != null && !wasShown) {
            mediaFrom = mediaSwap(now)
            mediaTo = 1f
            mediaAt = now
        }

        // The GLYPH's state is only REPORTED here, not acted on — see PlayGate, which
        // holds a verdict back until the player has stopped changing its mind.
        // A track change starts the skip guard. Both titles have to be real for it to
        // count: arriving from nothing is not a skip, and neither is stopping.
        // Falls back to the DEPARTING track, so a skip that drops the session on the way
        // through is still recognised when the next one arrives — by then `media` is
        // already null and there would be nothing to compare against.
        val previous = media?.title ?: departing?.title
        if (next != null && !previous.isNullOrBlank() && !next.title.isNullOrBlank() &&
            next.title != previous
        ) {
            skipUntil = now + SKIP_GUARD_MS
            // And start the record changer. The track that is leaving is held so the
            // disc can carry its own sleeve out — the cover file is a single path that
            // has already been overwritten by now, which is why the outgoing bitmap is
            // kept rather than re-read.
            swapFrom = media
            swapAt = now
            // When the change happened, on the clock positions are stamped against. A
            // report whose stamp PREDATES this is describing the track that just left —
            // see the note in progressRaw.
            trackChangedAtReal = SystemClock.elapsedRealtime()
            // Hold the bitmap that is ON SCREEN right now, not a path to it. There is one
            // cover file and the new track overwrites it, so asking for "the departing
            // track's artwork" by path returns whatever has since been decoded into that
            // file — which is the arriving cover. That is how a record on its way out
            // ended up wearing the sleeve of the one replacing it.
            swapCover = coverBitmap
        }

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

        // Held so the record can leave. Cleared only once the slide has finished, in
        // mediaSwap — a departing track that were dropped here would take the disc with
        // it before it had moved.
        if (next == null) {
            if (media != null) departing = media
        } else {
            departing = null
        }
        media = next
        invalidate()
    }

    /**
     * Commit a presence change once it has held.
     *
     * Called every frame, next to settlePlaying, and does the same job for "is there
     * anything playing" that that does for "is it running". A skip trips this and it
     * never reports it; a real stop trips it and half a second later the row goes.
     */
    private fun settlePresence(nowMs: Long) {
        if (presenceGate.waiting()) animating = true
        if (!presenceGate.commit(nowMs)) return
        if (!presenceGate.shown && media == null) {
            // Confirmed gone. Start the record on its way out; departing already holds
            // the track it needs to draw while it leaves.
            mediaFrom = mediaSwap(nowMs)
            mediaTo = 0f
            mediaAt = nowMs
        }
    }

    private val presenceGate = PlayGate(MEDIA_SETTLE_MS)

    /** While the clock is short of this, a track change is still in flight. */
    private var skipUntil = 0L

    /** When the track last changed, on the clock positions are stamped against. */
    private var trackChangedAtReal = 0L

    /** The track that just stopped, kept until the record has slid off. */
    private var departing: Media? = null

    /** The track being replaced, and when the change started. See [swapPhase]. */
    private var swapFrom: Media? = null
    private var swapAt = 0L

    /**
     * Where the record changer has got to: whether the old disc is still leaving, and
     * how far through that leg it is. Null when nothing is changing.
     *
     * Two legs rather than one crossfade, because they are two different events with a
     * moment between them — the deck is briefly empty, which is the part that makes it
     * read as a record being changed rather than a picture being replaced.
     */
    private fun swapPhase(nowMs: Long): Pair<Boolean, Float>? {
        if (swapAt == 0L) return null
        val t = (nowMs - swapAt).toFloat()
        return when {
            t < MediaPage.SWAP_EXIT_MS -> true to (t / MediaPage.SWAP_EXIT_MS)
            t < MediaPage.SWAP_EXIT_MS + MediaPage.SWAP_ENTER_MS ->
                false to ((t - MediaPage.SWAP_EXIT_MS) / MediaPage.SWAP_ENTER_MS)
            else -> {
                swapAt = 0L
                swapFrom = null
                swapCover = null
                null
            }
        }
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
        // A SKIP GETS LONGER THAN THE ORDINARY SETTLE, and needs it.
        //
        // Half a second covers a player changing its mind; it does not cover a player
        // fetching the next track. Pressing next put the glyph back to play for a moment
        // whenever the load ran past the settle, which is most of the time on anything
        // streaming. So a skip suspends the verdict outright until the player has had a
        // fair chance to start the new track.
        //
        // Detected from the TRACK CHANGING rather than from the button, so a skip from
        // the phone, a headset or the next track simply ending is covered too — the
        // panel has no idea which of those happened and the answer is the same for all
        // of them.
        // An answer to our own press is believed at once.
        expecting?.let { want ->
            if (playGate.latest == want) {
                expecting = null
                if (playGate.accept()) retargetPlayGlyph(nowMs)
                return
            }
            if (nowMs > expectingUntil) expecting = null else animating = true
        }
        if (nowMs < skipUntil) {
            animating = true
            return
        }
        if (!playGate.commit(nowMs)) return
        retargetPlayGlyph(nowMs)
    }

    /**
     * Send the play/pause glyph to whatever the gate has settled on.
     *
     * Retargeted from wherever it currently is, so a change arriving mid-travel reverses
     * instead of snapping — the same rule the ambient ripple follows.
     */
    private fun retargetPlayGlyph(nowMs: Long) {
        playFrom = playSwap(nowMs)
        playTo = if (playGate.shown) 1f else 0f
        playAt = nowMs
    }

    /** A state this panel asked for, and how long it will wait to see it. */
    private var expecting: Boolean? = null
    private var expectingUntil = 0L

    private val playGate = PlayGate(MEDIA_SETTLE_MS)

    /**
     * Vertical paging. One page today plus the media page; built for more.
     *
     * The span is the whole panel, so a page IS a screenful. That is the unit the eye
     * expects from a surface with no scrollbar and no visible edges to judge against.
     */
    private val pager = PanelPager(Panel.HEIGHT.toFloat(), PAGE_SETTLE_MS)

    /**
     * Where the panel keeps the little it must survive its own process.
     *
     * ITS OWN FILE, not the config. The config is the user's settings, written by the
     * front screen and read here; this is the panel's own scratch note about where it
     * was standing, and mixing the two would mean a scroll position rides in the same
     * blob as everything a person deliberately chose.
     *
     * Only this process reads or writes it, which matters: SharedPreferences caches per
     * process, so a file edited under a live reader is ignored. Here the reader is the
     * writer, and a respawn is a fresh process reading fresh.
     */
    private val panelPrefs by lazy {
        context.getSharedPreferences(PANEL_PREFS, android.content.Context.MODE_PRIVATE)
    }

    private var pageRestored = false
    private var pageSaved = 0

    /** The volume area's touch target, or null when the media page is not up. */
    private var volumeBar: android.graphics.RectF? = null

    /** The mute button, beside the speaker rather than on it — see drawMediaPage. */
    private var muteButton: android.graphics.RectF? = null

    /**
     * The music stream's volume, 0..1.
     *
     * Read and written HERE rather than sent to the main process the way transport
     * commands are. Those have to travel because a MediaController belongs to the process
     * that obtained it under notification access; volume is an ordinary system service
     * any app may call, so a round trip would buy nothing and cost the delay between
     * moving a finger and seeing the bar follow it.
     */
    private val audio by lazy {
        context.getSystemService(android.media.AudioManager::class.java)
    }

    private fun volumeLevel(): Float {
        val a = audio ?: return 0f
        val max = a.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        if (max <= 0) return 0f
        return a.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() / max
    }

    /**
     * Move the volume by how far the finger has travelled since it went down.
     *
     * RELATIVE, from wherever the volume already was — the axis changed when the control
     * moved to the right-hand strip, and nothing else about it did. Absolute was tried
     * on the way past and it was worse in use: it makes every touch a commitment to a
     * level rather than a nudge, on a surface nobody is looking at.
     *
     * Rounded to the stream's own steps, because that is all the system can store — a bar
     * drawn from a finer number than the device holds drifts away from what it claims.
     */
    private fun dragVolume(y: Float) {
        val a = audio ?: return
        val max = a.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        val travelledNow = MediaPage.volumeTravel(y, volumeDownY, Panel.HEIGHT.toFloat())
        if (muted()) {
            // MUTED, SO THE DRAG SETS WHERE IT COMES BACK TO.
            //
            // The stream stays silent — that is what mute means, and a drag should not
            // quietly undo it — but the level you are choosing has to be visible or you
            // are setting a number you cannot see. So the ghost is the thing being
            // dragged, and unmuting lands exactly where you left it.
            //
            // This replaces an auto-unmute on the first move, which made the volume
            // control unable to do anything while muted except cancel the mute.
            mutedFrom = (volumeDownGhost + travelledNow).coerceIn(0f, 1f)
            invalidate()
            return
        }
        // A DRAG FORGETS THE GHOST, whatever the system thinks about mute.
        //
        // Dragging to the bottom sets the stream to zero, and Android may set its mute
        // flag along with it — so the panel found itself "muted" holding a remembered
        // level from some earlier press of the mute button. The ghost then promised a
        // volume that unmuting would not restore, which is worse than no ghost: it is a
        // reading that is confidently wrong.
        //
        // The ghost belongs to the mute BUTTON alone. That is the only gesture that
        // means "keep this level, I will want it back"; setting the volume by hand is a
        // statement about where it should be now, and silence reached that way is just
        // silence.
        mutedFrom = 0f
        applyVolume(Math.round(volumeDownLevel + travelledNow * max).coerceIn(0, max))
    }

    /** Where the volume gesture started, and what the volume was then. */
    private var volumeDownY = 0f
    private var volumeDownLevel = 0f

    /** And where the ghost was, for a drag that is moving the ghost rather than the stream. */
    private var volumeDownGhost = 0f

    /**
     * Where the scrub has been dragged to, or null when nothing is being scrubbed.
     *
     * The dial and the elapsed label both read this instead of the player while it is
     * set, which is what makes the whole page follow the finger rather than just an arc.
     */
    private var scrubTo: Float? = null

    /** Whether the finger is still inside the control it started in. */
    private var dragInBounds = true

    /**
     * Which way a drag on the media page is going, decided once and then held.
     *
     * Three controls share this surface and are told apart by REGION AND AXIS together,
     * which is what makes them impossible to confuse:
     *
     *   the right sixth, vertically   → the volume
     *   the open area, horizontally   → the playback position
     *   anywhere, vertically          → the page
     *
     * So a sideways wobble inside the strip cannot start a scrub, and a vertical one in
     * the open area still turns the page. Splitting the panel into regions ALONE was an
     * earlier attempt and it was worse in the way that matters: the way back from this
     * page moved somewhere you had to learn.
     *
     * Decided at the moment the slop is crossed and then not revisited, because a gesture
     * that changes its mind halfway does both things badly.
     */
    private enum class Drag { UNDECIDED, VOLUME, SCRUB, PAGE }

    private var drag = Drag.UNDECIDED
    private var downX = 0f
    private var downY = 0f

    /** The scrub area's touch target, or null when the media page is not up. */
    private var scrubBar: android.graphics.RectF? = null

    /**
     * How engaged each control looks, 0 to 1, eased.
     *
     * A dial being DRIVEN should not look like a dial being read, or there is no way to
     * tell that a finger has taken hold of it — which matters most here, because the
     * finger is on the far side of the phone from the eye and cannot be seen. So the ring
     * thickens and greys while it is held: grey because the reading under a finger is not
     * yet a fact about the music, and thicker because that is what a thing being gripped
     * does.
     */
    private fun engagement(which: Drag): Float =
        ease(
            "ring:engaged:" + which.name,
            if (drag == which) 1f else 0f,
            MediaPage.ENGAGED_MS,
        )

    /**
     * Let go of a drag: commit it, or put it back.
     *
     * Lifting OUTSIDE the control abandons the gesture, which is the standard way to
     * change your mind about a slider and the only one available on a surface where you
     * cannot see what you are about to commit to.
     *
     * The two controls part company on WHEN they commit, and it is not an inconsistency —
     * it is the whole reason only one of them has an abandon at all:
     *
     *   The volume is applied LIVE, because the point of moving it is to hear the result.
     *   There is nothing to commit and so nothing to take back; the way to undo it is to
     *   drag the other way.
     *
     *   The position is only PREVIEWED. Seeking a player live would have it re-buffer on
     *   every frame of the drag, so the dial and the clock move alone and the seek is sent
     *   once, on release. Abandoning sends nothing and the dial eases back on its own —
     *   the follower is already pointed at the true position, so letting go IS the animation.
     */
    private fun endDrag(commit: Boolean) {
        when (drag) {
            Drag.SCRUB -> {
                val to = scrubTo
                val m = media
                if (commit && to != null && m != null && m.durationMs > 0L) {
                    onMediaTap?.invoke(
                        RearActivity.MEDIA_SEEK + ":" + (to * m.durationMs).toLong(),
                    )
                    // Believe our own seek at once rather than holding the preview until
                    // the player answers. The same reasoning as the play/pause gate: a
                    // report that answers a command this panel just sent is evidence, and
                    // waiting for it is a visible hesitation on the one gesture where the
                    // user already knows what they did.
                    seekedTo = to
                    seekedAt = SystemClock.elapsedRealtime()
                }
                scrubTo = null
            }
            else -> Unit
        }
        drag = Drag.UNDECIDED
        dragInBounds = true
        invalidate()
    }

    /** A seek we asked for, believed until the player catches up. */
    private var seekedTo: Float? = null
    private var seekedAt = 0L

    /**
     * Set the stream, and tick once per step actually crossed.
     *
     * The haptic is on the STEP, not on the movement, and that is what makes it useful
     * rather than noise: dragging the column feels like counting notches, so the volume
     * can be set without looking at a screen that is facing away from you. A buzz per
     * frame would be a rattle.
     */
    private fun applyVolume(step: Int) {
        val a = audio ?: return
        val current = a.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        if (step == current) return
        // No system UI flag: this panel is the UI, and the phone's own volume popup
        // appearing on the FRONT screen because someone touched the back is the exact
        // surprise this app exists to avoid.
        runCatching {
            a.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, step, 0)
        }
        // IGNORE_VIEW_SETTING, because this view is on a display the user cannot see and
        // has never had the chance to enable feedback on. The gesture is deliberate and
        // the tick is the only confirmation it has.
        performHapticFeedback(
            android.view.HapticFeedbackConstants.CLOCK_TICK,
            android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
        )
        invalidate()
    }

    /**
     * Mute, using the system's own mute rather than a volume of zero.
     *
     * These are genuinely different states on Android, as they are on a Mac: a muted
     * stream remembers the level it was at and comes back to it, where a stream turned
     * down to zero has simply lost that number. The first version here zeroed the volume
     * and kept the old value in a field of its own — which reimplemented the platform's
     * behaviour, worse, and threw it away whenever the panel's process was recycled.
     *
     * ADJUST_TOGGLE_MUTE also means the phone agrees with the panel: mute set anywhere
     * else shows here, and mute set here shows everywhere else.
     */
    /**
     * The level a muted stream will come back to, 0..1, or 0 when not muted.
     *
     * Remembered here because it cannot be read back: a muted stream reports a volume of
     * zero, so once mute is on there is nothing left to ask about what it was. Android
     * keeps that number internally and restores it on unmute — this is the panel's copy
     * of the same fact, kept so it can be SHOWN.
     */
    private var mutedFrom = 0f

    private fun toggleMute() {
        val a = audio ?: return
        // Read BEFORE the toggle, and only when engaging it: after the call the stream
        // reports zero, and on the way back out the live level is the answer again.
        // ADJUST_MUTE and ADJUST_UNMUTE rather than ADJUST_TOGGLE_MUTE. The toggle asks
        // the system which way to go, and the system's answer comes from the same
        // overloaded flag: at zero volume it already considers the stream muted, so
        // "toggle" on a stream the user had merely turned down would UNMUTE it and put
        // the volume back up. Saying which direction is meant removes the question.
        val wasMuted = muted()
        if (!wasMuted) mutedFrom = volumeLevel()
        runCatching {
            a.adjustStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                if (wasMuted) android.media.AudioManager.ADJUST_UNMUTE
                else android.media.AudioManager.ADJUST_MUTE,
                0,
            )
        }
        mutedHere = !wasMuted
        if (wasMuted) {
            // Land on the level the ghost was showing, which is not necessarily the one
            // Android remembers: the ghost can have been dragged while muted, and the
            // arc has been promising that number the whole time. Honouring the system's
            // own memory instead would make the visible promise the wrong one.
            val max = a.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            if (max > 0) applyVolume(Math.round(mutedFrom * max).coerceIn(0, max))
            mutedFrom = 0f
            // applyVolume can trip the system flag again on the way past zero, so the
            // panel's own state is restated after it rather than before.
            mutedHere = false
        }
        performHapticFeedback(
            android.view.HapticFeedbackConstants.CLOCK_TICK,
            android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
        )
        invalidate()
    }

    /**
     * Muted, as this panel means it: a deliberate silence with a level to come back to.
     *
     * NOT the system's own flag, and that is the whole point. Setting STREAM_MUSIC to
     * zero makes isStreamMute() true, so as far as Android is concerned "muted" and
     * "turned all the way down" are the same bit — and they are not the same thing here.
     * One keeps a level it will return to and shows it as a ghost arc; the other IS the
     * level, with nothing held in reserve and nothing to promise.
     *
     * Reading the system flag conflated them, and the result was worse than cosmetic:
     * dragging to the bottom set the flag, the panel then believed itself muted, and the
     * next drag moved the GHOST instead of the volume — so once you reached silence by
     * hand you could not get back out of it by hand.
     *
     * So the state is owned here and set only by [toggleMute]. The system flag still has
     * one job: if it says the stream is live while this says otherwise, something else
     * unmuted it — the volume keys, another app — and this defers to that.
     */
    private fun muted(): Boolean {
        if (mutedHere && audio?.isStreamMute(android.media.AudioManager.STREAM_MUSIC)
            == false
        ) {
            mutedHere = false
            mutedFrom = 0f
        }
        return mutedHere
    }

    private var mutedHere = false



    /** 0 while the artwork holds the slot, 1 while the controls do. */
    private fun mediaSwap(nowMs: Long): Float {
        val v = slide(mediaFrom, mediaTo, mediaAt, nowMs)
        // Once the slot is fully back with the artwork there is nothing left to animate
        // out, so the departing track can go. Released here rather than on a timer
        // because this is the value that decides when the movement is over.
        if (v <= 0.001f && departing != null) departing = null
        return v
    }

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

    /**
     * Move the queue along, if the one on screen has had its turn.
     *
     * MIN_SHOW_MS is a floor on how long a notice is up, not a target: with nothing
     * waiting, a notice still gets the full NOTICE_MS. It only shortens the wait when
     * something is queued behind it, which is the case where holding the full five
     * seconds each would put a burst of four notifications twenty seconds into the
     * future — long enough that the last of them arrives as news about the past.
     */
    private fun promoteNotices(nowMs: Long) {
        // BEFORE the early return, because that is exactly when this matters: `outgoing`
        // is only ever set once `notice` is null, so a cleanup below the guard would
        // never run and the last notice's words would be held on the row for good.
        if (outgoing != null && nowMs - outgoingAt > SLIDE_MS) outgoing = null
        val showing = notice ?: return
        val age = nowMs - noticeAt
        if (pending.isNotEmpty()) {
            if (age >= MIN_SHOW_MS) {
                leaving = showing
                wheelAt = nowMs
                notice = pending.removeFirst()
                noticeAt = nowMs
            } else {
                // The frame that ends the hold has to be asked for, or the next notice
                // waits for something else to happen to invalidate the view.
                animating = true
            }
        } else if (age > NOTICE_MS) {
            // HANDED TO `outgoing` RATHER THAN DROPPED.
            //
            // The strip slides up when there is no notice, and it did that correctly —
            // but the words vanished on the frame the clock ran out, so what left the
            // screen was an empty card. The card was animating and its contents were not.
            //
            // The text now rides the strip out, exactly as the artwork rides the record
            // out on a track change: the thing leaving keeps what it was showing until it
            // has gone.
            outgoing = showing
            outgoingAt = nowMs
            notice = null
        }
    }

    private fun noticeSlide(nowMs: Long): Float {
        promoteNotices(nowMs)
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
        val miniLeft = miniCardLeft()

        // ---- the mini card, drawn FIRST so it sits UNDER the headline ----------
        //
        // NO OUTLINE on either card. They used to carry a hairline stroke at a tenth of
        // the notification's own colour, and it was doing two jobs badly: separating the
        // card from the panel, which its opaque ground already does, and separating the
        // two cards from each other, which the step between them does. What it added was
        // a third edge on a shape that has two.
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

            // The shadow the headline card casts onto this one.
            //
            // This card comes out from UNDER the one above it, and nothing said so — the
            // two cards are the same colour, so the join between them read as one shape
            // with a step in it rather than as one sheet lying over another. A gradient
            // from the top edge is what tells you which is in front.
            //
            // Spent by half the card's height, because a shadow that reached the bottom
            // would be a dark card rather than a shadow: the eye reads the fade as depth
            // only while it can see where it ends.
            //
            // Drawn over the ground and UNDER the text. A real shadow would fall across
            // the type as well, and on a panel read from across a room the legibility is
            // worth more than the last of the realism.
            val shadowTop = cardTop + r
            noticePaint.shader = android.graphics.LinearGradient(
                0f, shadowTop, 0f, shadowTop + CASCADE_H * MINI_SHADOW_SPAN,
                (MINI_SHADOW_ALPHA shl 24), 0x00000000,
                android.graphics.Shader.TileMode.CLAMP,
            )
            canvas.drawRect(
                miniLeft, shadowTop, Panel.WIDTH.toFloat(),
                shadowTop + CASCADE_H * MINI_SHADOW_SPAN, noticePaint,
            )
            noticePaint.shader = null

            // Padded down by the tuck, which is what keeps the text where it was. Its own
            // wheel turns with the card above it, so a replacement moves both.
            val rowTop = cardTop + r
            leaving?.takeIf { it.hasMore }
                ?.let { drawMiniRow(canvas, it, rowTop + turn * CASCADE_H, CASCADE_H) }
            notice?.takeIf { it.hasMore }
                ?.let { drawMiniRow(canvas, it, rowTop - (1f - turn) * CASCADE_H, CASCADE_H) }
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
        // `outgoing` stands in for a notice that has expired, so the words leave with the
        // card rather than blinking out from under it. It is never both — a notice is
        // either current or on its way out.
        (notice ?: outgoing)?.let { drawNoticeRow(canvas, it, top - (1f - turn) * NOTICE_H) }
        canvas.restore()
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
    /** The mini card's contents: the next notification in the group, no icon. */
    /**
     * Where the cascaded card's left edge is.
     *
     * The step must CLEAR the curve it steps around. MINI_INSET_LEFT is 34 and the radius
     * here is about 32 — correct on this display and one bad panel away from wrong, since
     * the radius is read from the hardware and the inset is a constant. Taking the larger
     * of the two makes the relationship hold wherever it runs rather than by two units of
     * luck.
     *
     * A function because TWO things need it, and only one of them knew: the card was
     * drawn from this and its text was positioned from the bare constant, so the text's
     * padding was silently whatever was left over. That is how the second card ended up
     * with fifteen units of margin against the headline's twenty-six, tight enough to
     * read as clipped.
     */
    private fun miniCardLeft(): Float =
        NOTICE_LEFT + maxOf(MINI_INSET_LEFT, cornerRadius() / CORNER_DIVISOR + MINI_CORNER_CLEARANCE)

    private fun drawMiniRow(canvas: Canvas, item: Notice, y: Float, h: Float) {
        // Padded from the CARD, not from the panel, and by the same amount the headline
        // uses — so the two cards' text lines up against their own edges the same way.
        val left = miniCardLeft() + NOTICE_PAD
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
    private fun drawIcon(
        canvas: Canvas,
        icon: PanelIcons.Icon,
        x: Float,
        y: Float,
        h: Float,
        /**
         * Anti-alias, for a glyph drawn under a rotation.
         *
         * Off everywhere else, and that is the point: these are stacks of one-pixel rows
         * and hard edges are what keeps them crisp. Under a rotation there are no pixel
         * rows to align to — the whole grid is at an angle — so the snapping that keeps
         * an upright glyph clean instead leaves a staircase down every edge.
         */
        smooth: Boolean = false,
    ) {
        val w = h * icon.aspect
        val left = x + (h - w) / 2f          // centred in the cell, not left-aligned in it
        iconPaint.isAntiAlias = smooth
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
     * A record, big, behind the readout.
     *
     * The first version drew the cover art over the whole disc and it looked like a CD,
     * because that is what a CD is. A record is black; its picture is a label the size of
     * a fist in the middle; and the rest of it is grooves. Getting that round meant
     * giving up the idea that the artwork IS the disc.
     *
     * The grooves are concentric and therefore invariant under rotation — they cannot
     * show that it is turning, and are not asked to. The label carries the movement,
     * exactly as it does on a deck.
     *
     * Everything here is drawn against the panel's own foreground at low alpha rather
     * than in absolute greys. The background is the user's to choose, and a record mixed
     * from black would vanish on a dark panel and sit like a hole in a light one.
     */
    /**
     * The cover object: placement, and how many of them there are this frame.
     *
     * Split from the drawing because a track change can put TWO on the panel at once —
     * one leaving and one arriving — and the thing that draws a record has no business
     * knowing about that.
     */
    private fun drawVinyl(
        canvas: Canvas,
        paging: Float,
        notice: Float,
        rings: PanelGeometry,
    ) {
        // The track that is playing, or the one that just stopped. A cover cannot leave
        // if it is deleted the instant the music ends — this used to return here and the
        // disc simply blinked out, which is the one exit a physical object never makes.
        val m = media ?: departing ?: return
        val swap = mediaSwap(frameNowMs)
        if (swap <= 0.001f) return

        val base = discAt(paging, notice)
        // Slides out of the bottom when the music stops. It arrived as an object and it
        // leaves as one, the way a record comes off a deck.
        val resting = MediaLayout.Disc(
            base.cx,
            base.cy + (1f - swap) * (Panel.HEIGHT + base.radius),
            base.radius,
        )

        // ---- changing tracks --------------------------------------------------
        //
        // The two styles change differently, because they are different objects — and
        // the record changes differently again depending on which page it is on, because
        // only one of them has rings to use.
        //
        // A RECORD crosses: the old one slides away and the new one arrives from the
        // opposite side at the same moment, the way a stack feeds through. A SQUARE does
        // not cross — it leaves and the next one follows it back in from the same side,
        // like a card being pulled out and another pushed in behind it. Two pictures
        // passing each other mid-air would read as a shuffle rather than a change.
        //
        // The AXIS follows the page: down and up on the readout, where the panel is tall
        // and the record hangs off the bottom, and sideways on the media page, where it
        // is the only thing in the middle and there is width to move it through. Blended
        // by `paging`, so a change caught mid-scroll travels on the diagonal rather than
        // snapping from one axis to the other.
        val leaving = swapFrom
        val progress = swapProgress(frameNowMs)
        if (progress == null || leaving == null) {
            // The change is over: the departing sheet has nothing left to travel with.
            if (!swapLyrics.isEmpty) {
                swapLyrics = dev.kb896.aiusage.data.Lrc.NONE
                swapLyricLine = -1
            }
            drawCoverAt(canvas, resting, m, paging)
            // Around the settled record only. A track being changed has no sheet yet by
            // construction — the old one was dropped the moment the title moved — so
            // there is nothing to carry through the swap and nothing to decide about how
            // it should travel.
            drawLyrics(canvas, resting, paging, notice, colorFor("media", config.mediaTint))
            return
        }
        animating = true

        val reach = MediaLayout.Disc(0f, 0f, resting.radius)
        val down = Panel.HEIGHT + reach.radius
        val right = Panel.WIDTH - Panel.CUTOUT_RIGHT + reach.radius

        fun shifted(along: Float) = MediaLayout.Disc(
            resting.cx + right * along * paging,
            resting.cy + down * along * (1f - paging),
            resting.radius,
        )

        val exit = MediaPage.SWAP_EXIT_MS /
            (MediaPage.SWAP_EXIT_MS + MediaPage.SWAP_ENTER_MS)

        // ON THE MEDIA PAGE a record still changes through the rings: it shrinks into
        // the top one and the next grows out of the bottom one. That belongs to this page
        // and to this object — there are two lens rings here doing nothing else, and a
        // ring encircles a lens with no display behind it, so a disc shrunk to that size
        // has genuinely gone rather than merely faded.
        //
        // The readout page has no such rings to use, which is why the record slides there
        // instead. Chosen by which page the change lands on rather than blended between
        // them: a flight into a ring and a slide off the bottom are different journeys,
        // and halfway between the two is neither.
        if (config.mediaArt == MediaArt.VINYL && paging > 0.5f) {
            val ring = if (progress < exit) rings.personal else rings.work
            val inRing = MediaLayout.Disc(
                ring.cx, ring.cy, ring.radius - MediaPage.SWAP_RING_INSET)
            if (progress < exit) {
                val e = Tween.easeInOut(progress / exit)
                val going = lerpDisc(resting, inRing, e)
                drawCoverAt(canvas, going, leaving, paging)
                drawLeavingLyrics(canvas, going, 1f - e, paging,
                    colorFor("media", config.mediaTint))
            } else {
                val e = Tween.easeInOut((progress - exit) / (1f - exit))
                drawCoverAt(canvas, lerpDisc(inRing, resting, e), m, paging)
            }
            return
        }

        if (config.mediaArt == MediaArt.VINYL) {
            // Crossing: both at once, one going and one coming from the far side.
            val e = Tween.easeInOut(progress)
            if (e < 0.999f) {
                val going = shifted(e)
                drawCoverAt(canvas, going, leaving, paging)
                drawLeavingLyrics(canvas, going, 1f - e, paging,
                    colorFor("media", config.mediaTint))
            }
            drawCoverAt(canvas, shifted(-(1f - e)), m, paging)
            return
        }

        // Following: out first, then the next one back in behind it.
        if (progress < exit) {
            val e = Tween.easeInOut(progress / exit)
            val going = shifted(e)
            drawCoverAt(canvas, going, leaving, paging)
            drawLeavingLyrics(canvas, going, 1f - e, paging,
                colorFor("media", config.mediaTint))
        } else {
            val e = Tween.easeInOut((progress - exit) / (1f - exit))
            drawCoverAt(canvas, shifted(1f - e), m, paging)
        }
    }

    /**
     * How far through a track change we are, 0 to 1, or null when nothing is changing.
     *
     * One number for both styles even though they spend it differently — the record
     * crosses over the whole of it, the square splits it into a leaving half and an
     * arriving one.
     */
    private fun swapProgress(nowMs: Long): Float? {
        if (swapAt == 0L) return null
        val total = MediaPage.SWAP_EXIT_MS + MediaPage.SWAP_ENTER_MS
        val t = (nowMs - swapAt) / total
        if (t >= 1f) {
            swapAt = 0L
            swapFrom = null
            swapCover = null
            return null
        }
        return t.coerceIn(0f, 1f)
    }

    /** One cover, drawn at [disc]: a record, or the sleeve on its own. */
    private fun drawCoverAt(
        canvas: Canvas,
        disc: MediaLayout.Disc,
        sleeve: Media,
        paging: Float,
    ) {

        val ink = colorFor("vinyl", config.vinylTint)
        // Full strength throughout: the slide is what says it is leaving, and a record
        // that faded as it slid would read as two things happening to one object.
        fun shade(alpha: Int) = (ink and 0x00FFFFFF) or (alpha shl 24)

        // ---- the square cover, which is the same journey without the record ---
        //
        // No body, no grooves, no rim, no spin: it is the sleeve itself, travelling and
        // resizing between the row's corner and the middle of the media page. That IS the
        // difference between the two styles — one is an object pretending to be a record
        // and the other is a picture, and a picture that rotated would be a picture on a
        // turntable rather than a cover.
        if (config.mediaArt != MediaArt.VINYL) {
            val square = sleeveArt(sleeve) ?: return
            val face = android.graphics.RectF(
                disc.cx - disc.radius, disc.cy - disc.radius,
                disc.cx + disc.radius, disc.cy + disc.radius,
            )
            canvas.save()
            canvas.clipRect(face)
            artPaint.alpha = 255
            canvas.drawBitmap(
                square, null,
                ArtPlacement.destination(face, square.width, square.height, 0f, 0f, 1f),
                artPaint,
            )
            canvas.restore()
            // A hairline of the row's colour around it, because a photograph on black
            // with no edge has no size — the corners of a dark cover simply dissolve.
            vinylPaint.style = Paint.Style.STROKE
            vinylPaint.strokeWidth = SQUARE_EDGE
            vinylPaint.color = shade(VINYL_LABEL_ALPHA)
            canvas.drawRect(face, vinylPaint)
            return
        }

        // BLACK, not a tint wash. The first attempt built the disc out of the row's own
        // colour at low alpha and it came out as a pale blue circle with rings on it —
        // a radar sweep. A record is black; what you actually see of one is the light
        // its grooves catch, which is what everything below this line is.
        vinylPaint.style = Paint.Style.FILL
        vinylPaint.color = (0x000000 or (VINYL_BODY_ALPHA shl 24))
        canvas.drawCircle(disc.cx, disc.cy, disc.radius, vinylPaint)

        vinylPaint.style = Paint.Style.STROKE

        // The grooves. Fine, many, and banded: every few rings is brighter, which is
        // where one track ends and the next begins on a real pressing. Evenly spaced
        // identical rings read as a target rather than as a surface.
        val steps = MediaLayout.VINYL_GROOVES
        vinylPaint.strokeWidth = VINYL_GROOVE_W
        for (i in 0 until steps) {
            val f = MediaLayout.VINYL_GROOVE_INNER +
                (MediaLayout.VINYL_GROOVE_OUTER - MediaLayout.VINYL_GROOVE_INNER) *
                (i.toFloat() / (steps - 1))
            vinylPaint.color = shade(
                if (i % VINYL_BAND_EVERY == 0) VINYL_BAND_ALPHA else VINYL_GROOVE_ALPHA,
            )
            canvas.drawCircle(disc.cx, disc.cy, disc.radius * f, vinylPaint)
        }

        // The rim, where the disc ends. A record has a raised lip and it is the brightest
        // line on the whole thing — without it the grooves just fade out and the disc has
        // no edge.
        vinylPaint.strokeWidth = VINYL_RIM_W
        vinylPaint.color = shade(VINYL_RIM_ALPHA)
        canvas.drawCircle(disc.cx, disc.cy, disc.radius * VINYL_RIM_AT, vinylPaint)

        // A sheen: two broad arcs of reflected light across the grooves. STATIC, while
        // everything else about the label turns — a reflection belongs to the room, not
        // to the record, and one that rotated with it would look painted on.
        vinylPaint.strokeWidth = disc.radius * VINYL_SHEEN_W
        vinylPaint.color = shade(VINYL_SHEEN_ALPHA)
        val sheen = android.graphics.RectF(
            disc.cx - disc.radius * VINYL_SHEEN_AT, disc.cy - disc.radius * VINYL_SHEEN_AT,
            disc.cx + disc.radius * VINYL_SHEEN_AT, disc.cy + disc.radius * VINYL_SHEEN_AT,
        )
        canvas.drawArc(sheen, VINYL_SHEEN_FROM, VINYL_SHEEN_SWEEP, false, vinylPaint)
        canvas.drawArc(
            sheen, VINYL_SHEEN_FROM + 180f, VINYL_SHEEN_SWEEP, false, vinylPaint)

        // The label, which is where the cover finally goes and the only part that turns.
        val label = disc.radius * MediaLayout.VINYL_LABEL
        // The departing record shows the departing sleeve. There is one cover file and
        // the new track has already overwritten it, so what leaves is the bitmap the
        // panel had decoded before — see coverArt, which keeps the one it replaces.
        val cover = sleeveArt(sleeve)
        val face = android.graphics.RectF(
            disc.cx - label, disc.cy - label, disc.cx + label, disc.cy + label,
        )
        canvas.save()
        canvas.clipPath(
            android.graphics.Path().apply {
                addCircle(disc.cx, disc.cy, label, android.graphics.Path.Direction.CW)
            },
        )
        // Clip first, rotate second: the circle is inscribed in the square, so a square
        // bitmap turned about their shared centre covers it at every angle.
        canvas.rotate(vinylAngle(sleeve), disc.cx, disc.cy)
        if (cover != null) {
            // Held back from full strength. The transport buttons are drawn over this and
            // a busy cover at full opacity is the one thing on the panel that can make
            // them hard to find.
            artPaint.alpha = VINYL_COVER_ALPHA
            canvas.drawBitmap(
                cover, null,
                ArtPlacement.destination(face, cover.width, cover.height, 0f, 0f, 1f),
                artPaint,
            )
            artPaint.alpha = 255
        } else {
            // No cover is not no label. A record without one has a blank label, not a
            // hole in the middle of the disc.
            vinylPaint.style = Paint.Style.FILL
            vinylPaint.color = shade(VINYL_LABEL_ALPHA)
            canvas.drawCircle(disc.cx, disc.cy, label, vinylPaint)
        }
        canvas.restore()

        // The label's edge, and the spindle punched through the middle of it.
        vinylPaint.style = Paint.Style.STROKE
        vinylPaint.strokeWidth = VINYL_RIM_W
        vinylPaint.color = shade(VINYL_LABEL_ALPHA)
        canvas.drawCircle(disc.cx, disc.cy, label, vinylPaint)

        vinylPaint.style = Paint.Style.FILL
        vinylPaint.color = colorFor("bg", config.background or (0xFF shl 24))
        canvas.drawCircle(
            disc.cx, disc.cy, disc.radius * MediaLayout.VINYL_SPINDLE, vinylPaint)
    }

    /** The record's body, grooves and label. Anti-aliased — it is the one thing on this
     *  panel that is genuinely round rather than pixel art. */
    private val vinylPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * The media page's furniture: type, transport, timeline and volume.
     *
     * Everything here fades in with [paging] rather than sliding. The record is already
     * carrying the movement — it shrinks and travels while the readout leaves over the
     * top of it — and a second set of things flying in from the edge at the same time
     * would be two animations competing to explain one gesture.
     *
     * Positioned absolutely rather than relative to the page's scroll, because this
     * canvas is not translated by the page offset: the page does not slide in, it
     * assembles in place as the readout clears.
     */
    private fun drawMediaPage(
        canvas: Canvas,
        paging: Float,
        dx: Float,
        dy: Float,
        notice: Float,
        rings: PanelGeometry,
        /** The eased volume the arc is actually drawn at, so the glyph rides its head
         *  rather than the raw value and arrives ahead of it. */
        volumeShown: Float,
    ) {
        val m = media ?: return
        val tint = colorFor("media", config.mediaTint)
        val alpha = { a: Int -> (a * paging).toInt().coerceIn(0, 255) }
        fun shade(base: Int, a: Int) = (base and 0x00FFFFFF) or (alpha(a) shl 24)

        val cx = MediaPage.centreX(Panel.WIDTH.toFloat(), Panel.CUTOUT_RIGHT.toFloat())

        // The labels and end marks naming the two rings.
        //
        // The BURN-IN DRIFT IS UNDONE around them, and that is not a nicety. The rings
        // are the one thing on this panel that deliberately does not drift — they are
        // drawn concentric with the camera lenses and must stay there — while everything
        // in this method rides the drift with the rest of the page. So a mark meant to
        // sit two units off a ring was sliding up to four units against it on a
        // ten-minute cycle: sometimes clear, sometimes touching, sometimes overlapping.
        // That is the "some look aligned and some do not" of it, and the answer is that
        // anything attached to a ring has to keep the ring's frame.
        canvas.save()
        canvas.translate(-dx, -dy)
        drawMediaRings(canvas, rings, paging, tint, volumeShown)
        canvas.restore()

        // ---- title and artist, centred under the disc -------------------------
        // Centred while it fits, scrolling when it does not — and left-aligned once it
        // scrolls, because a marquee has to start somewhere fixed. Centring a line that
        // is travelling means centring it on a different position every frame.
        //
        // The raw text goes to `rolling`, not a shortened copy: it compares strings to
        // notice a change, and two different long titles cut to the same width are the
        // same string.
        extras.textSize = MediaPage.TITLE_SIZE
        extras.color = shade(tint, 0xFF)
        var runs = extras.measureText(m.title) > PAGE_TEXT_WIDTH
        extras.textAlign = if (runs) Paint.Align.LEFT else Paint.Align.CENTER
        rolling(
            canvas, "page:title", m.title,
            if (runs) cx - PAGE_TEXT_WIDTH / 2f else cx,
            MediaPage.TITLE_BASELINE,
            lineBand(cx, MediaPage.TITLE_BASELINE, PAGE_TEXT_WIDTH, MediaPage.TITLE_SIZE),
            width = PAGE_TEXT_WIDTH,
        )
        if (m.artist.isNotBlank()) {
            extras.textSize = MediaPage.ARTIST_SIZE
            extras.color = shade(tint, MediaLayout.ARTIST_ALPHA)
            runs = extras.measureText(m.artist) > PAGE_TEXT_WIDTH
            extras.textAlign = if (runs) Paint.Align.LEFT else Paint.Align.CENTER
            rolling(
                canvas, "page:artist", m.artist,
                if (runs) cx - PAGE_TEXT_WIDTH / 2f else cx,
                MediaPage.ARTIST_BASELINE,
                lineBand(cx, MediaPage.ARTIST_BASELINE, PAGE_TEXT_WIDTH,
                    MediaPage.ARTIST_SIZE),
                width = PAGE_TEXT_WIDTH,
            )
        }
        extras.textAlign = Paint.Align.LEFT
        extras.textSize = 18f

        // ---- the transport row, centred ---------------------------------------
        //
        // Its own geometry rather than MediaLayout's, which lays out from the right
        // against a strip. Here the three are a group centred under a record, which is a
        // different arrangement of the same three things.
        val gh = MediaPage.TRANSPORT_H
        val widths = listOf(
            gh * PanelIcons.PREV.aspect,
            gh * PanelIcons.PAUSE.aspect,
            gh * PanelIcons.NEXT.aspect,
        )
        val total = widths.sum() + MediaPage.TRANSPORT_GAP * 2f
        var x = cx - total / 2f
        val top = MediaPage.TRANSPORT_CY - gh / 2f
        val centres = mutableListOf<Float>()

        extras.color = shade(tint, 0xFF)
        drawIcon(canvas, PanelIcons.PREV, x, top, gh)
        centres += x + widths[0] / 2f
        x += widths[0] + MediaPage.TRANSPORT_GAP

        // The play/pause drum again, one size up. Same clip-and-slide as the row's.
        canvas.save()
        canvas.clipRect(x, top - gh, x + gh, top + gh * 2f)
        drawPlayPause(canvas, x, top, gh, shade(tint, 0xFF))
        canvas.restore()
        centres += x + widths[1] / 2f
        x += widths[1] + MediaPage.TRANSPORT_GAP

        drawIcon(canvas, PanelIcons.NEXT, x, top, gh)
        centres += x + widths[2] / 2f

        // No clocks along the bottom. Elapsed and total are on the playback ring now,
        // curved at the two ends of the arc that measures them; a second copy pinned to
        // the panel's bottom corners was the same two numbers again, five hundred units
        // from the dial they belong to and from each other.

        // ---- the speaker, riding the head of the volume arc -------------------
        //
        // It travels with the arc and turns with it, so the glyph is the arc's pointer
        // rather than a legend parked beside it — the reading is one object, and where
        // it is IS the value.
        //
        // Which costs it its old job. It used to be the mute button, and the whole lower
        // ring is drawn over the camera glass where there is no digitizer: anywhere the
        // arc goes is somewhere a finger cannot follow. So mute moved to a TAP anywhere
        // in the volume area — drag to set it, tap to silence it — and this is now purely
        // something to look at.
        val isMuted = muted()
        val level = if (isMuted) 0f else volumeLevel()
        // THE CONE IS A GLYPH; THE WAVES ARE DRAWN.
        //
        // It used to be three whole glyphs — no waves, one wave, and the three-wave one
        // above a single threshold at half volume — so the middle of the range had no
        // picture of its own. There is no fourth glyph to reach for: this font's only
        // clean two-wave speaker is Octicons' f485, whose cone is an OUTLINE where every
        // FontAwesome one here is solid, and putting the two side by side on a dial is a
        // mismatch you notice immediately. Clipping a wave off the three-wave glyph was
        // the other attempt, and its arcs merge into one run at the top and bottom rows,
        // so every clip leaves a stub where it cut through them.
        //
        // So the bare cone is the glyph and the waves are arcs, which the panel draws
        // everywhere already — the rings, the ticks, and this glyph's own mute slash.
        // Nothing has to match a typeface, the count is free, and it scales exactly.
        val speaker = PanelIcons.VOLUME_OFF
        val waves = if (isMuted) 0 else MediaPage.volumeWaves(level)
        val ring = rings.work
        // In the RING's frame, not the page's — see the note by drawMediaRings. This
        // orbits a ring that does not drift, so it must not drift either.
        canvas.save()
        canvas.translate(-dx, -dy)
        // OUTSIDE the arc. Inside is the camera lens — there is no display in the hole a
        // ring encircles, so a glyph put there is drawn onto glass.
        //
        // The glyph is CENTRED on its orbit, so half its height comes off the clearance
        // too — missing that is what made a stated gap of ten read as stuck to the ring.
        val orbit = ring.radius + ring.stroke / 2f + MediaPage.MUTE_GAP + MediaPage.MUTE_H / 2f
        // The arc's head, held back to wherever the glyph still fits on the panel.
        //
        // At this size it cannot reach the end. The lower ring is centred 420 down a 572
        // panel and the glyph orbits 178 units out, so at five o'clock its bottom lands
        // twenty units past the edge — it rides the head to about four fifths of the
        // range and then holds while the arc carries on. See MediaPage.speakerLimit,
        // which works that limit out from the sizes rather than stating an angle.
        val head = (MediaPage.VOLUME_ARC_START + MediaPage.VOLUME_ARC_SWEEP * volumeShown)
            .coerceAtMost(MediaPage.speakerLimit(ring.cy, orbit, Panel.HEIGHT.toFloat()))
        val radians = Math.toRadians(head.toDouble())
        val hx = ring.cx + orbit * Math.cos(radians).toFloat()
        val hy = ring.cy + orbit * Math.sin(radians).toFloat()
        val mh = MediaPage.MUTE_H
        val speakerW = mh * speaker.aspect

        extras.color = shade(tint, if (isMuted) MediaLayout.ARTIST_ALPHA else 0xFF)
        canvas.save()
        // Turned to the arc's own angle, so the speaker points out of the ring wherever
        // it has got to. The glyph's natural direction is +x, which is the angle's own
        // direction, so the rotation IS the angle.
        canvas.rotate(head, hx, hy)
        drawIcon(
            canvas, speaker,
            hx - speakerW / 2f - (mh - speakerW) / 2f, hy - mh / 2f, mh,
            smooth = true,
        )
        // The waves, concentric on the cone's mouth and clipped to a wedge, so they read
        // as sound leaving the speaker rather than as rings around it.
        run {
            closePaint.style = Paint.Style.STROKE
            closePaint.strokeCap = Paint.Cap.ROUND
            closePaint.strokeWidth = mh * WAVE_STROKE
            val originX = hx + speakerW / 2f
            for (i in 1..WAVE_MAX) {
                // A WAVE LEAVES THE CONE AND TRAVELS OUT, rather than appearing where it
                // will end up. That is what sound does, and it is also what makes a
                // change of one step legible: a wave arriving at full radius is a shape
                // switching on somewhere in the corner of your eye, where one moving
                // outward from the speaker's mouth reads as the speaker doing something.
                //
                // Each has its own leg, so going from one wave to three sends two out
                // together from where they belong rather than as one group. Turning down
                // runs the same journey backwards, into the cone.
                val out = ease("vol:wave:" + i, if (i <= waves) 1f else 0f, WAVE_MS)
                if (out <= 0.01f) continue
                // Radius from the cone's mouth outward. Alpha with it, so a wave that has
                // barely left is not a hard dot sitting on the speaker's lip.
                val r = mh * (WAVE_FIRST + WAVE_STEP * (i - 1)) * out
                closePaint.color = (extras.color and 0x00FFFFFF) or
                    ((((extras.color ushr 24) and 0xFF) * out).toInt()
                        .coerceIn(0, 255) shl 24)
                scratch.set(originX - r, hy - r, originX + r, hy + r)
                canvas.drawArc(scratch, -WAVE_SPREAD / 2f, WAVE_SPREAD, false, closePaint)
            }
            closePaint.strokeWidth = 0f
        }
        if (isMuted) {
            // TWO strokes: a wider one in the panel's own background, then the slash
            // itself inside it. The gap is what makes the line read as a cut THROUGH the
            // speaker rather than a wire laid across it — the same trick the close
            // button's X uses to look like missing material.
            closePaint.style = Paint.Style.STROKE
            closePaint.strokeCap = Paint.Cap.ROUND
            val x0 = hx - speakerW / 2f - MUTE_SLASH
            val y0 = hy + mh / 2f + MUTE_SLASH
            val x1 = hx + speakerW / 2f + MUTE_SLASH
            val y1 = hy - mh / 2f - MUTE_SLASH
            closePaint.strokeWidth = MUTE_SLASH * 3f
            closePaint.color = colorFor("bg", config.background or (0xFF shl 24))
            canvas.drawLine(x0, y0, x1, y1, closePaint)
            closePaint.strokeWidth = MUTE_SLASH
            closePaint.color = extras.color
            canvas.drawLine(x0, y0, x1, y1, closePaint)
        }
        canvas.restore()
        canvas.restore()

        // Targets, and only once the page has arrived — the same rule the row follows.
        // A button that can be pressed while it is still fading in is a button in a place
        // it does not yet occupy.
        if (paging >= 0.999f) {
            mediaStrip = android.graphics.RectF(
                centres.first() - MediaPage.TRANSPORT_GAP - dx, top - dy,
                centres.last() + MediaPage.TRANSPORT_GAP - dx, top + gh - dy,
            )
            mediaCentres = centres.map { it - dx }
            // The volume is a full-height strip down the right edge, and the position
            // takes the open area to its left. They swapped places: the volume used to
            // have the whole area and the position had nothing, which put the wide
            // horizontal region on the control with no width to it and left a timeline
            // with nowhere to be dragged.
            //
            // FULL HEIGHT, deliberately, and it is the one target here that ignores the
            // notification push. There is no edge to find on the back of a phone, so the
            // strip runs the whole way and spends its range over the middle 70% — see
            // MediaPage.stripFraction. Starting it below a card would move the volume's
            // ends every time a notification arrived.
            volumeBar = android.graphics.RectF(
                MediaPage.volumeStripLeft(Panel.WIDTH.toFloat()) - dx, -dy,
                Panel.WIDTH - dx, Panel.HEIGHT - dy,
            )
            // The scrub area starts below whatever a notification is occupying, so a drag
            // meant for the position cannot begin on a card.
            scrubBar = android.graphics.RectF(
                Panel.CUTOUT_RIGHT - dx, notice - dy,
                MediaPage.volumeStripLeft(Panel.WIDTH.toFloat()) - dx,
                MediaPage.dragAreaBottom() - dy,
            )
            // Mute is a BUTTON again, not the whole area. Tap-anywhere-to-mute meant
            // every stray touch on two thirds of the page silenced the music, which is
            // the one thing on this panel you would not want to happen by accident.
            //
            // It sits just clear of the cutout at the speaker's own height, because the
            // speaker itself is over camera glass with no digitizer behind it. Tracking
            // the glyph's height is what keeps the two associated when they cannot share
            // a position.
            // NO drift correction on this one. `hy` comes from the ring's frame, which
            // is the view's frame — the rings do not drift — so subtracting the drift
            // again would walk the target away from the glyph it belongs to, in the
            // opposite direction to the bug it was meant to fix.
            muteButton = android.graphics.RectF(
                Panel.CUTOUT_RIGHT.toFloat(),
                hy - MediaPage.MUTE_TARGET_H / 2f,
                Panel.CUTOUT_RIGHT + MediaPage.MUTE_TARGET_W,
                hy + MediaPage.MUTE_TARGET_H / 2f,
            )

        } else {
            volumeBar = null
            scrubBar = null
            muteButton = null
        }
        if (m.playing) animating = true
    }

    /**
     * The two lens rings, reporting on the music instead of on usage.
     *
     * The best thing about this page is that it did not need to invent anything: there
     * are already two rings on this panel, drawn concentric with the phone's actual
     * camera lenses, and on this page they had nothing to say. So the top one becomes the
     * playback position and the bottom one becomes the volume — two dials, on the two
     * pieces of hardware that look like dials.
     *
     * They CROSS-FADE with the usage rings rather than replacing them: same centres, same
     * radius, same stroke, one fading out as the other fades in. The circle never moves,
     * which is the whole reason the usage rings were made to fade in the first place.
     */
    private fun drawMediaRings(
        canvas: Canvas,
        rings: PanelGeometry,
        paging: Float,
        tint: Int,
        /** The eased volume the arc is drawn at, so its label reads the same number. */
        volumeShown: Float,
    ) {
        val m = media ?: departing ?: return
        val alpha = (255 * paging).toInt().coerceIn(0, 255)
        if (alpha <= 0) return

        val elapsed = m.elapsed(SystemClock.elapsedRealtime())

        // Where each arc starts and stops. Drawn under the labels and over nothing, so a
        // volume at zero still shows its two ends and a track at the beginning still
        // shows how far it has to run.
        tick(canvas, rings.personal, MediaPage.PLAYBACK_ARC_START, tint, alpha)
        tick(canvas, rings.personal, MediaPage.PLAYBACK_ARC_END, tint, alpha)
        tick(canvas, rings.work, MediaPage.VOLUME_ARC_START, tint, alpha)
        tick(
            canvas, rings.work,
            MediaPage.VOLUME_ARC_START + MediaPage.VOLUME_ARC_SWEEP, tint, alpha,
        )

        // The rings themselves are drawn with the readout's, sweeping from the usage
        // reading to this one — see drawRing's blend. What is left here is what could not
        // travel: text, which cannot tween from one string to another, so it fades in.
        //
        // The labels, curved inside their rings. Remaining time rather than elapsed:
        // elapsed is already the arc's own length, and a number that repeats the shape
        // beside it is a number saying nothing.
        extras.textSize = MediaPage.LABEL_SIZE
        extras.textAlign = Paint.Align.CENTER
        extras.color = (tint and 0x00FFFFFF) or
            ((MediaLayout.ARTIST_ALPHA * paging).toInt().coerceIn(0, 255) shl 24)
        // How far in where the arc used to start, how long it runs where it used to end.
        // The arc has been turned half an hour out from under them — see
        // MediaPage.PLAYBACK_ROTATE — so each label sits in the gap that opened just
        // before its own mark. Two readings, and
        // no third: a remaining-time counter sat between them for a while and it was the
        // same fact a third way — the arc already shows how much is left, as a shape.
        //
        // They face opposite ways because of where they are. Text stands perpendicular to
        // its path, so tops-outward reads correctly around the upper half and tops-inward
        // around the lower — at five o'clock, outward would have the total reading down
        // and to the left.
        // The clock reads WHERE THE DIAL IS, not where the player is, whenever the two
        // disagree — which is the whole of a scrub and the moment after one. A dial that
        // followed the finger while the number beside it stayed put would be two answers
        // to the same question, and the number is the one you scrub by.
        val held = scrubTo ?: seekedTo
        val shownElapsed =
            if (held != null && m.durationMs > 0L) (held * m.durationMs).toLong()
            else elapsed
        curvedRolling(
            canvas, "dial:elapsed", MediaPage.clock(shownElapsed), rings.personal,
            centreDeg = MediaPage.ELAPSED_AT, outward = true,
            animate = scrubTo == null,
        )
        if (m.durationMs > 0L) {
            curvedRolling(
                canvas, "dial:total", MediaPage.clock(m.durationMs), rings.personal,
                centreDeg = MediaPage.TOTAL_AT, outward = false,
            )
        }
        // Bottom-left of its ring rather than over the top of it. Outside at twelve
        // o'clock lands in the twenty units between the two rings, which is not enough,
        // and any further out is inside the ring above. Down here there is open panel.
        // VOLUME with its reading attached. One label rather than two, so the percentage
        // rolls in the same wheel as everything else and the word acts as its unit.
        curvedRolling(
            canvas, "dial:volume",
            MediaPage.VOLUME_LABEL + " " + Math.round(volumeShown * 100f) + "%",
            rings.work, centreDeg = 135f, outward = false,
        )
        extras.textAlign = Paint.Align.LEFT
        extras.textSize = 18f
    }

    /**
     * Text that turns over when it changes, like the notification strip's drum.
     *
     * ONLY when it actually changes, and only when the change is worth showing. A track
     * ending and the next one starting replaces four things at once — title, artist,
     * elapsed, total — and any of them may be identical across that change: two tracks
     * from the same album share an artist, two tracks of the same length share a total.
     * Rolling a value to itself is a flicker that says something happened when nothing
     * did, so each field decides for itself.
     *
     * [animate] false updates the text with no movement at all. That is what the running
     * clock uses for its ordinary tick: a second passing is not news, and a wheel turning
     * once a second would be the most distracting thing on the panel.
     *
     * Position only, nothing fades — see drawNotice. A drum's rows do not become
     * transparent as they turn, they pass the edge and are gone.
     */
    private fun rolling(
        canvas: Canvas,
        key: String,
        text: String,
        x: Float,
        baseline: Float,
        clip: android.graphics.RectF,
        /** The column a line may scroll inside, or 0 to cut it short instead. */
        width: Float = 0f,
        animate: Boolean = true,
    ) {
        val state = rolls.getOrPut(key) { Roll(text) }
        if (text != state.shown) {
            // A first sighting is not a change. getOrPut has already seeded `shown` with
            // the text, so this only fires for a genuine replacement.
            state.leaving = if (animate) state.shown else null
            state.at = if (animate) frameNowMs else 0L
            state.shown = text
        }
        val turn =
            if (state.at == 0L) 1f
            else ((frameNowMs - state.at) / WHEEL_MS).coerceIn(0f, 1f)
        if (turn >= 1f) {
            state.leaving = null
            state.at = 0L
        } else {
            animating = true
        }

        val height = clip.height()
        canvas.save()
        canvas.clipRect(clip)
        // Each row of the wheel can itself be scrolling. The two clips nest: this one
        // is the line's band, the marquee's is the text column, and a long title being
        // replaced by another long title needs both at once.
        state.leaving?.let { line(canvas, it, x, baseline + turn * height, width, clip) }
        line(canvas, state.shown, x, baseline - (1f - turn) * height, width, clip)
        canvas.restore()
    }

    /** One line: drawn plainly if it fits, scrolled if it does not. */
    private fun line(
        canvas: Canvas,
        text: String,
        x: Float,
        baseline: Float,
        width: Float,
        band: android.graphics.RectF,
    ) {
        if (width <= 0f || extras.measureText(text) <= width) {
            canvas.drawText(text, x, baseline, extras)
        } else {
            marquee(canvas, text, x, baseline, width, band)
        }
    }

    /** One rolling field: what is shown, what is leaving, and when it started. */
    private class Roll(var shown: String, var leaving: String? = null, var at: Long = 0L)

    private val rolls = mutableMapOf<String, Roll>()

    /** The last whole second the elapsed clock showed, so an ordinary tick can be told
     *  from a jump. -1 until the first draw, which is neither. */
    private var lastElapsedSeconds = -1L

    /**
     * The band a line of text occupies, for a wheel to turn inside.
     *
     * Generous on width — a rolling replacement can be wider than the text it replaces,
     * and a clip cut to the outgoing string would shave the incoming one.
     */
    private fun lineBand(
        centreX: Float,
        baseline: Float,
        width: Float,
        size: Float,
    ) = android.graphics.RectF(
        centreX - width / 2f, baseline - size,
        centreX + width / 2f, baseline + size * 0.35f,
    )

    /**
     * The play/pause drum: one glyph leaves as the other arrives.
     *
     * Slides AND fades, which is the one place on this panel where both happen at once
     * and the exception is deliberate. The notification wheel is a window onto a drum and
     * its rows do not become transparent as they turn — they pass the edge and are gone,
     * and fading them would break the illusion that there is a physical thing behind the
     * opening. This is a single glyph on open panel with no window to pass out of, so a
     * slide alone reads as the icon jumping rather than changing.
     *
     * The two are cross-faded rather than fading out and then in: at the halfway point
     * both are half strength, which keeps the total ink roughly constant instead of
     * blinking dark in the middle of every press.
     */
    private fun drawPlayPause(canvas: Canvas, x: Float, top: Float, h: Float, tint: Int) {
        val turn = playSwap(frameNowMs)
        fun at(alpha: Float) =
            (tint and 0x00FFFFFF) or
                ((((tint ushr 24) and 0xFF) * alpha).toInt().coerceIn(0, 255) shl 24)
        if (turn < 0.999f) {
            extras.color = at(1f - turn)
            drawIcon(canvas, PanelIcons.PLAY, x, top + turn * h, h)
        }
        if (turn > 0.001f) {
            extras.color = at(turn)
            drawIcon(canvas, PanelIcons.PAUSE, x, top - (1f - turn) * h, h)
        }
        extras.color = tint
    }

    /**
     * A short stub of ring at [deg], marking one end of an arc's travel.
     *
     * A hairline pointing straight out of the ring, in the arc's own colour.
     *
     * PERPENDICULAR, which is the whole difference. Drawn along the ring — as this was
     * twice — the mark is an arc of the same circle as the reading, in the same colour,
     * and reads as a piece of arc already filled in. Crossing the scale rather than lying
     * on it is what a mark on a dial has always looked like.
     */
    private fun tick(
        canvas: Canvas,
        g: RingGeometry,
        deg: Float,
        tint: Int,
        alpha: Int,
    ) {
        arc.strokeWidth = MediaPage.TICK_STROKE
        arc.color = tint
        arc.alpha = alpha
        // A line straight out of the ring, starting just clear of its edge.
        val inner = g.radius + g.stroke / 2f + MediaPage.TICK_GAP
        val outer = inner + MediaPage.TICK_LEN
        val radians = Math.toRadians(deg.toDouble())
        val cos = Math.cos(radians).toFloat()
        val sin = Math.sin(radians).toFloat()
        canvas.drawLine(
            g.cx + inner * cos, g.cy + inner * sin,
            g.cx + outer * cos, g.cy + outer * sin,
            arc,
        )
        arc.alpha = 255
    }

    /**
     * A curved label whose characters roll individually when they change.
     *
     * One clock ticking from 1:21 to 1:22 should move ONE digit. Rolling the whole string
     * makes a change of one second look like a change of track, which on a panel read out
     * of the corner of an eye is the difference between "time is passing" and "something
     * happened".
     *
     * Each character is drawn with the canvas TURNED so that character stands upright
     * above the ring's centre — after which it is ordinary straight text with a
     * rectangular clip, and the wheel is the same one the flat labels use. Bending each
     * glyph along a path instead would mean clipping to an annular sector per character,
     * which is a lot of geometry to arrive at the same picture.
     *
     * Monospaced, so the angular step is one advance divided by the radius and every
     * character's place is arithmetic. [outward] flips the direction of travel, because
     * left-to-right runs with increasing angle above the ring and against it below.
     */
    private fun curvedRolling(
        canvas: Canvas,
        key: String,
        text: String,
        g: RingGeometry,
        centreDeg: Float,
        outward: Boolean,
        /**
         * False while a value is being DRAGGED. The wheel says "this turned over on its
         * own"; under a finger the digits are being moved by hand, and rolling each one
         * as it passes turns a scrub into a blur of tumbling numerals.
         */
        animate: Boolean = true,
    ) {
        if (text.isEmpty()) return
        val radius = MediaPage.labelRadius(g.radius, g.stroke, outward)
        val advance = extras.measureText("0")
        val step = Math.toDegrees((advance / radius).toDouble()).toFloat()
        val size = extras.textSize
        val previous = extras.textAlign
        extras.textAlign = Paint.Align.CENTER
        extras.style = Paint.Style.FILL_AND_STROKE
        extras.strokeWidth = MediaPage.LABEL_WEIGHT

        for (i in text.indices) {
            val fromMiddle = (i - (text.length - 1) / 2f) * step
            val deg = if (outward) centreDeg + fromMiddle else centreDeg - fromMiddle
            canvas.save()
            // Turn the canvas so this character stands upright directly above (or below)
            // the centre, then everything below is flat text.
            canvas.rotate(if (outward) deg + 90f else deg - 90f, g.cx, g.cy)
            val x = g.cx
            // The baseline sits EXACTLY on the label radius, as it does when the whole
            // string is bent along a path. Half the text size was being added here and
            // nowhere else, which pushed the outward labels down onto the ring and the
            // inward ones out past it — the same label at two different distances
            // depending on which way up it was, which is what read as bad alignment.
            val baseline = if (outward) g.cy - radius else g.cy + radius
            rolling(
                canvas, key + ":" + i, text[i].toString(), x, baseline,
                android.graphics.RectF(
                    x - advance, baseline - size, x + advance, baseline + size * 0.35f,
                ),
                animate = animate,
            )
            canvas.restore()
        }

        extras.style = Paint.Style.FILL
        extras.strokeWidth = 0f
        extras.textAlign = previous
    }

    /**
     * A label bent around a ring, centred on [centreDeg].
     *
     * [outward] chooses which way up the glyphs stand: true points their tops away from
     * the ring's centre, which is what reads correctly anywhere on the upper half; false
     * points them inward, which is what reads correctly underneath. Text stands
     * perpendicular to the way its path travels, so the two cases are the same arc swept
     * in opposite directions — and getting that backwards is exactly what put an earlier
     * version of these labels on the right-hand edge of their rings, reading vertically.
     *
     * The radius is OUTSIDE the ring, always. Inside is the camera lens the ring is
     * drawn around, and there is no display there — labels put inside rendered into the
     * framebuffer and onto a piece of glass.
     *
     * The arc is built to FIT the text rather than the text positioned along a
     * half-circle. Passing a long path and centring with drawTextOnPath's hOffset is the
     * obvious way and the offset is measured along the curve, which is a way to be wrong
     * quietly. An arc whose sweep is the text's own angular width starts and ends exactly
     * where the text does, so the offset is zero and there is nothing left to get wrong.
     */
    private fun curved(
        canvas: Canvas,
        text: String,
        g: RingGeometry,
        centreDeg: Float,
        outward: Boolean,
    ) = curvedOn(
        canvas, text, g.cx, g.cy,
        MediaPage.labelRadius(g.radius, g.stroke, outward), centreDeg, outward,
    )

    /**
     * Text bent along any circle, rather than around a ring's label orbit.
     *
     * The same drawing the ring labels use, with the circle handed in — because the
     * lyrics are concentric with the RECORD, which moves and changes size, and nothing
     * about bending a string along an arc cares which circle it is.
     */
    private fun curvedOn(
        canvas: Canvas,
        text: String,
        cx: Float,
        cy: Float,
        radius: Float,
        centreDeg: Float,
        outward: Boolean,
    ) {
        val width = extras.measureText(text)
        // What the text subtends at this radius. Android's angles are degrees, clockwise
        // from three o'clock.
        val span = Math.toDegrees((width / radius).toDouble()).toFloat()
        scratch.set(cx - radius, cy - radius, cx + radius, cy + radius)
        val path = android.graphics.Path().apply {
            if (outward) {
                addArc(scratch, centreDeg - span / 2f, span)
            } else {
                addArc(scratch, centreDeg + span / 2f, -span)
            }
        }
        // Thickened with a stroke over the fill. Restored immediately: `extras` is shared
        // with the clock, the battery, the media row and the transport glyphs, and a
        // paint left in FILL_AND_STROKE would quietly embolden all of them.
        extras.style = Paint.Style.FILL_AND_STROKE
        extras.strokeWidth = MediaPage.LABEL_WEIGHT
        canvas.drawTextOnPath(text, path, 0f, 0f, extras)
        extras.style = Paint.Style.FILL
        extras.strokeWidth = 0f
    }

    /**
     * The words, curved around the record.
     *
     * The line being sung sits against the disc; the one coming next waits dimmed outside
     * it. When the song moves on the outer line travels INWARD and brightens while the old
     * one sinks toward the record and fades — so the change has a direction, and what
     * arrives is the thing you were already reading ahead of. A cross-fade in place would
     * deliver the same words and none of that.
     *
     * Drawn INSIDE the drift, with the record — which is the opposite of the rule for
     * everything else bent around a circle here, and for the same underlying reason. The
     * ring labels are held still because the rings are held still, being concentric with
     * physical lenses. The record has no hardware to stay aligned with and IS a burn-in
     * target, so it drifts; words wrapped around it have to drift with it or they would
     * swim against the thing they are wrapped around.
     */
    private fun drawLyrics(
        canvas: Canvas,
        disc: MediaLayout.Disc,
        paging: Float,
        notice: Float,
        tint: Int,
    ) {
        if (lyrics.isEmpty) return
        val fade = lyricFade(paging, notice)
        if (fade <= 0.004f) return
        val m = media ?: return
        // The sung position, not the dial's. While a scrub is in flight the dial is a
        // proposal and the music has not moved — showing the words for a place nobody is
        // listening to would be the panel narrating a gesture rather than a song.
        //
        // FROZEN while the block is leaving. On the way out these words belong to a song
        // that has ended, and running the new track's clock against the old sheet would
        // have them promoting through someone else's verses as they faded.
        if (lyricsOn) {
            val at = lyrics.indexAt(m.elapsed(SystemClock.elapsedRealtime()))
            if (at != lyricLine) {
                lyricFrom = lyricLine
                lyricLine = at
                lyricAt = frameNowMs
            }
        }
        val t = LyricsRing.promotion(lyricFrom, lyricLine, (frameNowMs - lyricAt).toFloat())
        if (t < 1f) animating = true

        val eased = Tween.easeInOut(t)
        val previous = extras.textSize
        val was = extras.textAlign
        extras.textAlign = Paint.Align.CENTER

        // A curve is what you do to text that has to follow the edge of a record. A
        // square cover has no edge to follow, so under that style the lines are straight —
        // bending them anyway would be decoration borrowed from a shape that is not there.
        //
        // The two share everything else: the same band, the same pair of lines, the same
        // promotion. Only the path differs, and "further from the cover" means a larger
        // radius in one and a higher baseline in the other.
        val curved = config.mediaArt == MediaArt.VINYL
        val now = if (curved) LyricsRing.currentRadius(disc.radius)
            else LyricsRing.rowBaseline(disc.cy - disc.radius)
        val next = if (curved) LyricsRing.nextRadius(disc.radius)
            else LyricsRing.rowNextBaseline(disc.cy - disc.radius)
        // Leaving means sinking toward the cover, which is inward on a circle and downward
        // on a page — the same gesture, opposite signs.
        val exit = if (curved) -LyricsRing.EXIT_DROP else LyricsRing.EXIT_DROP

        fun place(text: String, at: Float, alpha: Int) {
            if (curved) drawLyricArc(canvas, text, disc, at, tint, alpha)
            else drawLyricRow(canvas, text, disc, at, tint, alpha)
        }

        // The line on its way out: from where it was sung, toward the cover, fading. Only
        // during a promotion — a settled line has no predecessor still on screen.
        if (t < 1f) {
            lyrics.lineAt(lyricFrom)?.let {
                place(it, now + exit * eased, ((1f - eased) * 255f * fade).toInt())
            }
        }
        // The line being sung, arriving from where it waited.
        lyrics.lineAt(lyricLine)?.let {
            place(
                it, next + (now - next) * eased,
                ((LyricsRing.NEXT_ALPHA +
                    (255 - LyricsRing.NEXT_ALPHA) * eased) * fade).toInt(),
            )
        }
        // And the one after it, taking its place as the promotion finishes.
        lyrics.lineAt(lyricLine + 1)?.let {
            place(it, next, (LyricsRing.NEXT_ALPHA * eased * fade).toInt())
        }

        extras.textSize = previous
        extras.textAlign = was
    }

    /**
     * The departing record's words, going out with it.
     *
     * Only ever drawn around the disc that is LEAVING, and only while it is leaving. The
     * lines are the ones that were up when the track changed — frozen, because they are
     * about a song that has ended and running its clock on would have them promoting
     * through verses nobody is listening to.
     */
    private fun drawLeavingLyrics(
        canvas: Canvas,
        disc: MediaLayout.Disc,
        fade: Float,
        paging: Float,
        tint: Int,
    ) {
        if (swapLyrics.isEmpty || fade <= 0.01f || paging <= 0.001f) return
        val a = (fade * paging).coerceIn(0f, 1f)
        val curved = config.mediaArt == MediaArt.VINYL
        val now = if (curved) LyricsRing.currentRadius(disc.radius)
            else LyricsRing.rowBaseline(disc.cy - disc.radius)
        val next = if (curved) LyricsRing.nextRadius(disc.radius)
            else LyricsRing.rowNextBaseline(disc.cy - disc.radius)
        val was = extras.textAlign
        val size = extras.textSize
        extras.textAlign = Paint.Align.CENTER
        fun place(text: String, at: Float, alpha: Int) {
            if (curved) drawLyricArc(canvas, text, disc, at, tint, alpha)
            else drawLyricRow(canvas, text, disc, at, tint, alpha)
        }
        swapLyrics.lineAt(swapLyricLine)?.let { place(it, now, (255 * a).toInt()) }
        swapLyrics.lineAt(swapLyricLine + 1)
            ?.let { place(it, next, (LyricsRing.NEXT_ALPHA * a).toInt()) }
        extras.textSize = size
        extras.textAlign = was
    }

    /** One line, bent along its arc. Blank entries draw nothing — see Lrc. */
    private fun drawLyricArc(
        canvas: Canvas,
        text: String,
        disc: MediaLayout.Disc,
        radius: Float,
        tint: Int,
        alpha: Int,
    ) {
        if (text.isBlank() || alpha <= 2) return
        val fitted = LyricsRing.fit(text, radius) { size ->
            extras.textSize = size
            extras.measureText("0")
        }
        extras.textSize = fitted.size
        extras.color = (tint and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
        // Tops outward, which is the only way round that reads over the top of a circle.
        curvedOn(
            canvas, fitted.text, disc.cx, disc.cy, radius,
            LyricsRing.CENTRE_DEG, outward = true,
        )
    }

    /**
     * One line, straight, centred over a square cover.
     *
     * No stroke thickening, unlike the curved form and the ring labels. That weight exists
     * because bent, anti-aliased type sitting over camera glass at the edge of the display
     * is the least favourable place type can be. A horizontal line in the middle of open
     * panel is the most favourable, and thickening it there would just make it look heavy.
     */
    private fun drawLyricRow(
        canvas: Canvas,
        text: String,
        disc: MediaLayout.Disc,
        baseline: Float,
        tint: Int,
        alpha: Int,
    ) {
        if (text.isBlank() || alpha <= 2) return
        val room = LyricsRing.straightRoom(
            disc.cx, Panel.CUTOUT_RIGHT.toFloat(), Panel.WIDTH.toFloat(),
        )
        val fitted = LyricsRing.fitTo(text, room) { size ->
            extras.textSize = size
            extras.measureText("0")
        }
        extras.textSize = fitted.size
        extras.color = (tint and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
        canvas.drawText(fitted.text, disc.cx, baseline, extras)
    }

    /**
     * The bitmap belonging to a particular track.
     *
     * There is ONE cover file, and by the time a change is being animated the new track
     * has already overwritten it — so a cover that is not the current one comes from the
     * bitmap the panel decoded before and deliberately did not release. See coverArt.
     */
    private fun sleeveArt(sleeve: Media): android.graphics.Bitmap? =
        if (sleeve === swapFrom) swapCover else coverArt(sleeve.artPath)

    /** The artwork the outgoing cover keeps while it leaves. */
    private var swapCover: android.graphics.Bitmap? = null

    /** Two discs, blended. */
    private fun lerpDisc(a: MediaLayout.Disc, b: MediaLayout.Disc, t: Float) =
        MediaLayout.Disc(
            a.cx + (b.cx - a.cx) * t,
            a.cy + (b.cy - a.cy) * t,
            a.radius + (b.radius - a.radius) * t,
        )

    /**
     * Where the record is, between its two pages.
     *
     * One disc, two destinations, interpolated. It is not two records cross-fading, and
     * that is the point of the gesture: the object stays put under your finger while the
     * readout slides off over the top of it, then settles into the middle of a page that
     * is about the music. Cross-fading would make it a slide transition between screens,
     * which is a different and much cheaper effect.
     */
    private fun discAt(paging: Float, notice: Float): MediaLayout.Disc {
        // Where the cover sits on the READOUT page, which is the one thing the two styles
        // genuinely differ about. The record is a panel-height object behind everything;
        // the square is the row's own thumbnail, tucked in the corner beside the track
        // name. Both then travel to the same place on the media page — which is what lets
        // one piece of arithmetic carry either of them.
        val readout =
            if (config.mediaArt == MediaArt.VINYL) {
                MediaLayout.vinyl(
                    Panel.WIDTH.toFloat(), Panel.HEIGHT.toFloat(),
                    Panel.CUTOUT_RIGHT.toFloat(),
                )
            } else {
                val half = MARK_H / 2f
                MediaLayout.Disc(
                    cx = CONTENT_EDGE + half,
                    cy = Panel.HEIGHT - MARK_BOTTOM - half,
                    radius = half,
                )
            }
        if (paging <= 0.001f) return readout
        val cx = MediaPage.centreX(Panel.WIDTH.toFloat(), Panel.CUTOUT_RIGHT.toFloat())
        // The page's disc is sized against whatever the notice has left, so a strip
        // arriving shrinks the record rather than moving anything. Lyrics claim their
        // band the same way and by the same mechanism — the record already knows how to
        // be smaller when something is above it, so two things above it simply shrink it
        // twice and neither needs to know about the other.
        val (pageCy, pageRadius) = MediaPage.disc(notice + lyricBand(notice))
        return MediaLayout.Disc(
            cx = readout.cx + (cx - readout.cx) * paging,
            cy = readout.cy + (pageCy - readout.cy) * paging,
            radius = readout.radius + (pageRadius - readout.radius) * paging,
        )
    }

    /**
     * Whether there is a second page to scroll to.
     *
     * Under EITHER cover style. It was the record's page at first, on the reasoning that
     * a square cover gives nothing to carry across the transition — which turned out to
     * be wrong: the row's thumbnail is an object, it simply travels rather than spins.
     *
     * With nothing playing there is no page, though. There would be nothing for it to be
     * a page about, and a panel that scrolls to a blank record player is a panel that
     * appears to have broken.
     */
    private fun mediaPageAvailable(): Boolean =
        config.showMedia &&
            // The GATE, not `media`, so a skip does not take the page away and bring it
            // back underneath someone who is standing on it.
            (media != null || presenceGate.shown)

    /**
     * Where the record has turned to, in degrees.
     *
     * DERIVED FROM THE POSITION IN THE TRACK, at one revolution per [SPIN_MS] of music,
     * rather than accumulated off the wall clock. The angle is then a picture of where
     * the track is rather than of how long the panel has been awake, and three separate
     * behaviours stop needing code of their own:
     *
     *  - It stops on pause, because the position stops.
     *  - It turns UNDER A SCRUB, forward or backward, because it reads the same eased
     *    follower the dial does, and that follower is pinned to the finger while a drag
     *    is in flight.
     *  - It SPINS BACK when a scrub is abandoned outside the area, because the follower
     *    eases back to the true position and the record simply follows it there.
     *
     * The follower is one frame stale here — the record is drawn before this frame's
     * reading is worked out — which at sixteen milliseconds is a third of a degree.
     *
     * A track with NO TIMELINE has no position to derive from: a live stream, a radio
     * session. Those keep the old accumulating spin, so the record still reads as
     * running rather than as stopped.
     */
    private fun vinylAngle(m: Media): Float {
        if (m.durationMs > 0L) {
            if (m.playing) animating = true
            val elapsed = progressShown.coerceIn(0f, 1f) * m.durationMs
            return (elapsed / SPIN_MS * 360f) % 360f
        }
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

    /** The eased playback reading, shared by the dial and the readout page's hairline. */
    private var progressShown = 0f

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
        // WHETHER to draw is this track's question — no duration, no timeline, no bar.
        // HOW FULL is the follower's, which is why the reading is not recomputed here.
        // This used to take the position directly, and so it was the one part of the
        // panel that was always right and never moved: it snapped to the new track's
        // start instead of running down to it.
        if (m.durationMs <= 0L) return
        val fraction = progressShown
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
        CONTENT_EDGE, mark.top, MEDIA_RIGHT, mark.bottom,
    )

    /**
     * What the media row is clipped to: its content band plus the strip above it.
     *
     * Separate from [mediaRegion] rather than folded into it. The band's HEIGHT is what
     * makes the cover square and centres the two lines of text, so growing it to make
     * room for the progress bar would have quietly stretched both.
     */
    private fun mediaClip(mark: android.graphics.RectF) = android.graphics.RectF(
        CONTENT_EDGE, mark.top, MEDIA_RIGHT, mark.bottom + MediaLayout.PROGRESS_TOTAL,
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
        val m = media ?: departing ?: return
        settlePlaying(frameNowMs)
        val tint = colorFor("media", config.mediaTint)
        val band = mediaRegion(region)

        drawMediaProgress(canvas, m, band, tint)

        // The cover first, because where it ends is where the text begins.
        //
        // A missing cover is ordinary — plenty of tracks have none, and a podcast or a
        // browser tab rarely does — so the row closes up rather than leaving a hole. The
        // text simply starts at the band's left edge instead.
        // The thumbnail belongs to the SQUARE style alone. Under VINYL the record behind
        // the readout IS the artwork, and a second copy of the same cover in the row
        // would be the same picture twice — so the text takes the width instead.
        // The thumbnail is RESERVED here but drawn elsewhere. Under the square style the
        // cover is the object that travels between this row and the media page, so it is
        // painted by the travelling layer — see discAt, which treats this corner as its
        // readout-page position. Drawing it here as well would put two copies on the
        // panel the moment the page began to move.
        val hasCover =
            config.mediaArt == MediaArt.SQUARE && coverArt(m.artPath) != null
        val textLeft =
            if (hasCover) band.left + band.height() + MediaLayout.COVER_GAP else band.left

        val row = MediaLayout.of(textLeft, band.right, region.centerY())
        val gh = MediaLayout.GLYPH_H
        val (prev, play, next) = Triple(row.prev, row.play, row.next)

        extras.color = tint
        drawIcon(canvas, PanelIcons.PREV, prev, row.glyphTop, gh)
        drawIcon(canvas, PanelIcons.NEXT, next, row.glyphTop, gh)

        // The middle glyph is its own little drum, clipped to one cell: pause rises out
        // of the top as play drops off the bottom. It is the same gesture as the row
        // arriving, one size down, so a track being paused and the controls appearing
        // read as the same machine.
        canvas.save()
        canvas.clipRect(play, region.top, play + gh, region.bottom)
        drawPlayPause(canvas, play, row.glyphTop, gh, tint)
        canvas.restore()

        val hasArtist = m.artist.isNotBlank()
        var baseline = MediaLayout.firstBaseline(region.centerY(), hasArtist)

        // The same wheel the media page uses, for the same reason: a track changing is
        // news and should be seen changing. `rolling` hands each line to the marquee when
        // it does not fit, so a long title both turns over and then scrolls.
        extras.textAlign = Paint.Align.LEFT
        extras.textSize = MediaLayout.TEXT_SIZE
        extras.color = tint
        rolling(
            canvas, "row:title", m.title, textLeft, baseline,
            android.graphics.RectF(
                textLeft, baseline - MediaLayout.TEXT_SIZE,
                textLeft + row.textWidth, baseline + MediaLayout.TEXT_SIZE * 0.35f,
            ),
            width = row.textWidth,
        )
        if (hasArtist) {
            baseline += MediaLayout.LINE
            // Dimmed against the title rather than given a colour of its own. Which of
            // the two lines is the track is the only thing this row has to say at a
            // glance, and a second hue would make that a question about the palette.
            extras.color = (tint and 0x00FFFFFF) or (MediaLayout.ARTIST_ALPHA shl 24)
            rolling(
                canvas, "row:artist", m.artist, textLeft, baseline,
                android.graphics.RectF(
                    textLeft, baseline - MediaLayout.TEXT_SIZE,
                    textLeft + row.textWidth, baseline + MediaLayout.TEXT_SIZE * 0.35f,
                ),
                width = row.textWidth,
            )
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
            prev - MediaLayout.GAP / 2f - dx,
            region.top - dy,
            band.right - dx,
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
                // NOT recycled. The bitmap being replaced may be the one a departing
                // cover is still drawing — recycling it would draw from freed memory,
                // and a cover animation is not worth a crash. Letting the collector have
                // it is a 140-pixel PNG's worth of patience.
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
         * The media row's right edge: where the camera button starts, less a gap.
         *
         * NOT the bar column's right edge, which is where it used to stop. The row is
         * three things side by side and the bars are one thing; stopping the row where
         * the bars stop left 80 units of empty panel between it and the camera while the
         * track title was being clipped at thirteen characters.
         *
         * Derived from the camera's own placement rather than measured against it, so
         * the two cannot drift apart. Held whether or not the camera is drawn — a row
         * whose width depended on another switch would resize when that was toggled.
         */
        private val MEDIA_RIGHT: Float = run {
            val bottom = (Panel.HEIGHT - MARK_BOTTOM - MARK_H / 2f) + CAMERA_H / 2f
            val right = Panel.WIDTH - (Panel.HEIGHT - bottom)
            right - CAMERA_H * PanelIcons.CAMERA.aspect - MEDIA_CAMERA_GAP
        }

        /**
         * Between the last transport button and the camera.
         *
         * WIDER than the gap inside the row, deliberately, and this is the one place the
         * even spacing does not apply. The camera is not part of the player — it opens
         * another app — and at the row's own spacing it read as a fourth transport
         * button. The three belong together; this says the next thing does not.
         */
        private const val MEDIA_CAMERA_GAP = 44f

        /**
         * How far outside the drawn glyph a touch still counts.
         *
         * Generous, and it has to be: 30 panel units on a 904-unit panel is a few
         * millimetres of glass on the back of a phone being held rather than looked at.
         * The target is what the finger needs; the glyph is only what says it is there.
         */
        private const val CAMERA_SLOP = 26f

        /** How long a notification holds the strip before it retracts. */
        /** The charging cell's ink. See ChargeOverlay for the shape and the timings. */
        // Near enough to opaque. At 0xE6 the readout underneath still read through the
        // glass — the masthead and the bar figures are bright and thin, which is exactly
        // what survives a wash — and a cell you can read the panel through looks like a
        // tint laid over the panel rather than an object in front of it.
        private const val CHARGE_GROUND_ALPHA = 0xF7
        private const val CHARGE_LIQUID_ALPHA = 0x66
        private const val CHARGE_RIM_ALPHA = 0x99

        /** Drops are drawn this much larger than they are simulated, so they merge. */


        private const val CHARGE_SURFACE = 2.5f
        private const val CHARGE_MENISCUS = 0xDD
        private const val CHARGE_RIM = 2f

        private const val NOTICE_MS = 5_000L

        /**
         * The shortest a notice stays up when another is waiting behind it.
         *
         * Long enough to read a name and a line of text, which is all the strip carries.
         * Below about this the burst becomes a flicker of half-read messages and the
         * queue is worse than no queue at all.
         */
        private const val MIN_SHOW_MS = 3_000L

        /** How many notices may wait. Beyond this the OLDEST unshown one is dropped. */
        private const val MAX_QUEUED = 8

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
         * How long a track change suspends the play/pause verdict for.
         *
         * Generous, because the cost of being wrong in this direction is a pause taking a
         * moment longer to show on a track you just skipped, and the cost in the other is
         * the button flicking under the finger that pressed it.
         */
        private const val SKIP_GUARD_MS = 2_000L

        /**
         * How long a position stamped before a track change is disbelieved.
         *
         * Bounded, because a player that never refreshes its stamp would otherwise leave
         * the dial pinned at the beginning for the whole track. Long enough to cover the
         * gap, short enough that being wrong costs seconds rather than a song.
         */
        private const val POSITION_TRUST_MS = 3_000L

        /** How long the panel waits for the player to answer its own play/pause press
         *  before going back to treating reports with the usual suspicion. */
        private const val EXPECT_MS = 3_000L

        /**
         * How long a full page takes to settle after a release.
         *
         * Longer than any other movement on this panel, and it should be: everything
         * else here is a strip or a glyph, and this is the entire surface travelling its
         * own height. PanelPager shortens it in proportion to the distance actually
         * left, so a release near the edge still lands at once.
         */
        private const val PANEL_PREFS = "rear_panel"
        private const val KEY_PAGE = "page"

        private const val PAGE_SETTLE_MS = 340f

        /** How long the volume ring takes to catch up with a step. Short: it is
         *  following a finger, not settling to a poll. */
        /** How quickly the volume ring catches up when the volume changes from
         *  somewhere else. Short — it is confirming a change, not settling to a poll. */
        private const val VOLUME_EASE_MS = 130f

        /**
         * How long the playback dial takes to wind back when a track ends.
         *
         * Longer than the volume's, and for the opposite reason: the volume is following
         * a finger and must not lag it, while this is a movement nobody asked for and
         * should be seen happening.
         */
        private const val PLAYBACK_EASE_MS = 550f

        /**
         * How far the playback reading must move to count as a jump rather than a tick.
         *
         * One frame of an ordinary track advances the dial by about eight
         * hundred-thousandths; a track change moves it by tenths. Anything between is
         * a seek, and easing one of those is right.
         */
        private const val PROGRESS_JUMP = 0.01f

        /** How far a finger travels before the panel decides what the gesture is. */
        private const val DRAG_SLOP = 16f

        /** The stroke through a muted speaker. */
        /** The waves beside the cone, as fractions of the glyph's height. */
        private const val WAVE_FIRST = 0.34f
        private const val WAVE_STEP = 0.24f
        private const val WAVE_STROKE = 0.09f

        /** How much of each circle is drawn, in degrees, centred on straight out. */
        private const val WAVE_SPREAD = 108f

        /** The most the speaker ever shows — see MediaPage.volumeWaves. */
        private const val WAVE_MAX = 3

        /**
         * How long a wave takes to travel out of the cone, or back into it.
         *
         * Quick. This is feedback on a control being turned, not an event: a drag across
         * the volume strip crosses both thresholds in well under a second, and a wave
         * still ambling outward while the next one leaves would read as lag.
         */
        private const val WAVE_MS = 220f

        private const val MUTE_SLASH = 2.5f

        /** How far outside the volume column a touch still counts. It is 10 units wide
         *  and this is the back of a phone. */
        private const val VOLUME_SLOP = 30f

        /** What a title on the media page may occupy before it is cut. Centred, so the
         *  binding limit is twice the distance to the nearer edge. */
        private val PAGE_TEXT_WIDTH =
            2f * (MediaPage.centreX(Panel.WIDTH.toFloat(), Panel.CUTOUT_RIGHT.toFloat()) -
                Panel.CUTOUT_RIGHT - MediaPage.MARGIN)

        /**
         * The vinyl: how fast it turns, and where its groove, label and spindle sit as
         * fractions of the radius.
         *
         * 100 degrees a second — a little over 16rpm, well under a record's 33. A real
         * speed on a 70-unit disc is a blur rather than a record, and this is meant to be
         * read from across a room as "it is playing" rather than measured.
         */
        private const val VINYL_DEG_PER_MS = 0.1f

        /**
         * How much music one revolution of the record represents.
         *
         * Ten seconds. Nothing like a real record — 33 rpm is one turn per 1.8s — and
         * that is the point: this disc is a readout as much as an object, and at record
         * speed a scrub is a blur and ordinary playback is a flicker on an always-on
         * panel. Slow enough to follow with the eye makes winding legible AS winding.
         */
        private const val SPIN_MS = 10_000f
        /**
         * The record's material.
         *
         * BODY is black over the panel, not a shade of the row's colour — see drawVinyl.
         * Everything else is the row's tint at a low alpha, so the disc takes its
         * highlights from whatever colour the media row was set to.
         */
        private const val VINYL_BODY_ALPHA = 0xC4
        private const val VINYL_GROOVE_W = 0.9f
        private const val VINYL_GROOVE_ALPHA = 0x1E

        /** Every Nth groove is brighter — where one track ends and the next starts. */
        private const val VINYL_BAND_EVERY = 6
        private const val VINYL_BAND_ALPHA = 0x3C

        /** The lip at the edge of the disc, the brightest line on it. */
        private const val VINYL_RIM_W = 1.6f
        private const val VINYL_RIM_AT = 0.985f
        private const val VINYL_RIM_ALPHA = 0x5A

        /** Reflected light across the grooves. Static — a reflection is the room's, not
         *  the record's. */
        private const val VINYL_SHEEN_W = 0.30f
        private const val VINYL_SHEEN_AT = 0.66f
        private const val VINYL_SHEEN_FROM = 205f
        private const val VINYL_SHEEN_SWEEP = 52f
        private const val VINYL_SHEEN_ALPHA = 0x0E

        /** The cover on the label, held back so the buttons over it stay findable. */
        private const val VINYL_COVER_ALPHA = 0xCC

        /** The hairline around a square cover. Without it a dark sleeve on a black panel
         *  has no corners and therefore no size. */
        private const val SQUARE_EDGE = 1.4f
        private const val VINYL_LABEL_ALPHA = 0x55

        /** The unfilled part of the progress bar, against the row's tint. */
        private const val MEDIA_TRACK_ALPHA = 0x33

        private const val MARQUEE_SPEED = 40f
        private const val MARQUEE_GAP = MediaLayout.MARQUEE_GAP
        private const val MARQUEE_DWELL_MS = 1_800f

        /** How far the mini card's step must stay clear of the headline's curve. */
        private const val MINI_CORNER_CLEARANCE = 4f

        /**
         * The shadow the headline card casts on the cascaded one: how dark at the top
         * edge, and how far down it is spent.
         *
         * Half the card. Any further and it stops reading as depth and starts reading as
         * a darker card — the fade is only legible as a shadow while its end is visible.
         */
        private const val MINI_SHADOW_ALPHA = 0x8C
        private const val MINI_SHADOW_SPAN = 0.5f

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
