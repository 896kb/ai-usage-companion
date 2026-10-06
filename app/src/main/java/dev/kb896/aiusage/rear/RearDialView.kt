package dev.kb896.aiusage.rear

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import androidx.core.content.ContextCompat
import dev.kb896.aiusage.BuildConfig
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.R
import dev.kb896.aiusage.data.Account
import dev.kb896.aiusage.data.AccountStatus
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
            val old = field
            field = value
            applyTheme(value.theme, value.font)
            // A new clip, the video just chosen, or Loop switched: it starts over, so the
            // change is seen; otherwise the player only follows the panel's state.
            val changed = value.video.clipPath != old.video.clipPath ||
                value.effectiveBackdrop != old.effectiveBackdrop || value.video.playable != old.video.playable ||
                value.video.easeInWallMs != old.video.easeInWallMs || value.video.easeOutWallMs != old.video.easeOutWallMs
            syncVideo(fresh = changed, changed = changed)
            syncTilt()
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
     * The CRT look is a RenderEffect on a node of its own inside this view, not on the
     * view: the whole picture — scene and overlays — is recorded into [crtNode] and
     * goes through CrtScreen's shader on its way out, and what must not go through the
     * tube is drawn over it afterwards. That is the table lights: they are the
     * display's own margin lit up, and a margin bent by the tube is no longer the
     * margin. The effect object is one per shape, never one per frame.
     */
    private val crtNode = android.graphics.RenderNode("crt")
    private var crtApplied = false
    private var glowOverTube = false

    /** The rings round the lenses, which the tube must not move: ring radius plus half the stroke. */
    private fun crtStills(): List<CrtScreen.Still> {
        val g = PanelGeometry.of(config.effectiveRingLayout)
        return listOf(g.personal, g.work).map {
            CrtScreen.Still(it.cx * fitX, it.cy * fitY, (it.radius + it.stroke / 2f + 4f) * maxOf(fitX, fitY))
        }
    }

    /** A touch on the tube, moved to the flat pixel the tube shows there. No-op without the CRT. */
    fun throughCrt(ev: android.view.MotionEvent) {
        if (!crtApplied) return
        val (sx, sy) = CrtScreen.sourcePoint(ev.x, ev.y, width.toFloat(), height.toFloat())
        ev.offsetLocation(sx - ev.x, sy - ev.y)
    }

    /** The last weather reading, or null. Set with the payload; the line draws it if fresh. */
    var weather: dev.kb896.aiusage.data.WeatherReading? = null
        set(value) { if (field != value) { field = value; invalidate() } }

    /** What the glass draws now: the setting, or for Auto, the sky outside. */
    private fun weatherMode(): String =
        Weather.effective(config.weather, shownWeather(), System.currentTimeMillis())

    /** The reading the page shows: the demo's in demo mode, else the real one if fresh. */
    private fun shownWeather(): dev.kb896.aiusage.data.WeatherReading? =
        demoWeather() ?: weather?.takeIf { it.isFresh(System.currentTimeMillis()) }

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

    /**
     * The record, decoded once.
     *
     * Not scaled on the way in: inScaled would resample it for the device's density, and
     * this is drawn to a radius the panel works out for itself rather than to anything
     * measured in dp. The file is cropped to the disc, so the bitmap's edge IS the
     * record's edge and it can be drawn straight into the disc's own square.
     */
    private val recordArt: android.graphics.Bitmap? by lazy {
        runCatching {
            android.graphics.BitmapFactory.decodeResource(
                resources, R.drawable.vinyl_record,
                android.graphics.BitmapFactory.Options().apply { inScaled = false },
            )
        }.getOrNull()
    }

    private val recordRect = android.graphics.RectF()

    /**
     * The shake of a running deck, in degrees.
     *
     * A record on a turntable is never quite still and never quite regular: the platter
     * has play in it, the stylus drags, and the whole thing trembles a fraction of a degree
     * either way. This panel's disc does not spin — its highlight is fixed, and turning it
     * would turn the lamp with it — so a small shake is the only thing left that can say
     * the deck is running rather than parked.
     *
     * Every step reverses: the disc travels back through where it started and past it, by
     * a fresh amount each time. That is what keeps it from reading as a pendulum. A fixed
     * magnitude, or a magnitude that did not alternate, gives a rocking motion the eye
     * locks onto within a second and then cannot stop watching.
     */
    private fun vinylWobble(m: Media): Float {
        val now = frameNowMs
        if (wobbleAt == 0L) {
            wobbleAt = now
            wobbleTo = nextWobble(-1f)
        }
        var t = (now - wobbleAt).toFloat() / VINYL_WOBBLE_MS
        if (t >= 1f) {
            wobbleFrom = wobbleTo
            // Stopped, it comes to rest square rather than stopping wherever it was.
            wobbleTo = if (m.playing) nextWobble(wobbleFrom) else 0f
            wobbleAt = now
            t = 0f
        }
        val moving = m.playing ||
            Math.abs(wobbleFrom) > 0.01f || Math.abs(wobbleTo) > 0.01f
        if (moving) animating = true
        return wobbleFrom + (wobbleTo - wobbleFrom) * Tween.easeInOut(t.coerceIn(0f, 1f))
    }

    /** The other way from [from], by a fresh amount. See vinylWobble. */
    private fun nextWobble(from: Float): Float {
        val mag = VINYL_WOBBLE_MIN +
            Math.random().toFloat() * (VINYL_WOBBLE_MAX - VINYL_WOBBLE_MIN)
        return if (from > 0f) -mag else mag
    }

    private var wobbleAt = 0L
    private var wobbleFrom = 0f
    private var wobbleTo = 0f

    /**
     * How bright the panel's own backdrop is, in twenty-five cells. See GlassLuminance.
     *
     * Rebuilt only when the backdrop changes, which for a background image and a ground
     * colour means almost never. The reference reads its backdrop back from a layer every
     * frame; this panel is always on and a per-frame pixel readback would stall the render
     * thread on the GPU for the sake of a number that has not moved since yesterday.
     */
    private val lumCells = FloatArray(GlassLuminance.SIDE * GlassLuminance.SIDE)
    private var lumKey: String? = null

    private fun refreshLuminance() {
        when (config.effectiveBackdrop) {
            dev.kb896.aiusage.data.BackdropKind.SHADER -> {
                if (lumKey == "silk") return
                lumKey = "silk"
                dev.kb896.aiusage.ui.glass.Silk.cells(GlassLuminance.SIDE).copyInto(lumCells)
                return
            }
            dev.kb896.aiusage.data.BackdropKind.VIDEO -> {
                val v = config.video
                val key = "video|" + v.clipPath + "|" + v.opacity + "|" + config.background
                if (lumKey == key) return
                lumKey = key
                // The clip at its opacity over the colour behind, as the panes see it.
                if (v.luminance.size == lumCells.size) VideoLuminance.blend(v.luminance, v.opacity, config.background).copyInto(lumCells)
                else lumCells.fill(GlassLuminance.of(config.background or (0xFF shl 24)))
                return
            }
            else -> Unit
        }
        // The picture only while it is the background: switching to a colour keeps it stored.
        val path = config.backdrop.imagePath.takeIf { config.effectiveBackdrop == dev.kb896.aiusage.data.BackdropKind.IMAGE }
        val key = (path ?: "-") + "|" + config.background + "|" +
            config.backdrop.panX + "," + config.backdrop.panY + "," + config.backdrop.zoom +
            "|" + config.backdrop.opacity
        if (key == lumKey) return
        lumKey = key

        // THE PANEL'S BACKDROP, not the file's contents. The image is panned and zoomed
        // into place, so averaging the bitmap would describe a picture nobody is looking
        // at. Drawing it the way the panel draws it, into a five-by-five, gives the same
        // answer the reference gets from reading its own layer back.
        val small = android.graphics.Bitmap.createBitmap(
            GlassLuminance.SIDE, GlassLuminance.SIDE,
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        val into = Canvas(small)
        into.drawColor(config.background or (0xFF shl 24))
        val bmp = if (path != null) backdropCache.of(path) else null
        if (bmp != null) {
            into.scale(
                GlassLuminance.SIDE / Panel.WIDTH.toFloat(),
                GlassLuminance.SIDE / Panel.HEIGHT.toFloat(),
            )
            panelRect.set(0f, 0f, Panel.WIDTH.toFloat(), Panel.HEIGHT.toFloat())
            artPaint.alpha = (255 * config.backdrop.opacity.coerceIn(0f, 1f)).toInt()
            into.drawBitmap(
                bmp, null,
                ArtPlacement.destination(
                    panelRect, bmp.width, bmp.height,
                    config.backdrop.panX, config.backdrop.panY, config.backdrop.zoom,
                ),
                artPaint,
            )
            artPaint.alpha = 255
        }
        for (y in 0 until GlassLuminance.SIDE) {
            for (x in 0 until GlassLuminance.SIDE) {
                lumCells[y * GlassLuminance.SIDE + x] =
                    GlassLuminance.of(small.getPixel(x, y))
            }
        }
        small.recycle()
    }

    /**
     * The tint for a CLEAR pane at [rect] — one with no colour of its own.
     *
     * Distinct from [reactive], which adjusts a tint a pane already has. These have none
     * on purpose, so there is nothing to adjust; what they get instead is a tint only
     * where clear glass stops working, which is at the two ends and not in the middle.
     */
    private fun clearReactive(rect: android.graphics.RectF): Int {
        if (!config.theme.isGlass) return Glass.DISC_TINT
        refreshLuminance()
        return GlassLuminance.clearTint(
            GlassLuminance.average(
                lumCells, rect.left, rect.top, rect.right, rect.bottom,
                Panel.WIDTH.toFloat(), Panel.HEIGHT.toFloat(),
            ),
        )
    }

    /** [base], adjusted for how bright the panel is behind [rect]. */
    private fun reactive(base: Int, rect: android.graphics.RectF): Int {
        if (!config.theme.isGlass) return base
        refreshLuminance()
        return GlassLuminance.reactive(
            base,
            GlassLuminance.average(
                lumCells, rect.left, rect.top, rect.right, rect.bottom,
                Panel.WIDTH.toFloat(), Panel.HEIGHT.toFloat(),
            ),
        )
    }

    /**
     * The charge figure, as a piece of glass.
     *
     * Draws [text] with its right edge at [rightAt] and its middle on [centreY], bending
     * the panel through the letterforms themselves. Returns false when there is nothing to
     * bend — a software canvas, or mid-scene — and the caller should just draw the type.
     *
     * The field is measured on the CPU (see SdfText) and cached against the string, so a
     * figure counting from nothing to sixty-two measures sixty-two fields over three
     * seconds and none at all once it settles. It is the one thing here that costs real
     * work per value rather than per frame, which is why it is worth saying that a
     * percentage only has a hundred values.
     */
    private fun drawSdfText(
        canvas: Canvas,
        text: String,
        paint: Paint,
        rightAt: Float,
        centreY: Float,
    ): Boolean {
        val recorder = canvas as? android.graphics.RecordingCanvas ?: return false
        val source = paneBackdrop
        if (!source.hasDisplayList() || (source === sceneNode && recordingScene)) return false

        val field = sdfFor(text, paint) ?: return false
        // The field was measured with a margin all round for the spread to live in, so the
        // rect it is drawn into is the text's box grown by the same amount.
        val pad = SdfText.SPREAD
        val w = field.width.toFloat()
        val h = field.height.toFloat()
        val left = rightAt - w + pad
        val top = centreY - h / 2f
        sdfRect.set(left, top, left + w, top + h)

        val node = nextNode()
        node.setPosition(
            Math.round(sdfRect.left), Math.round(sdfRect.top),
            Math.round(sdfRect.right), Math.round(sdfRect.bottom),
        )
        val into = node.beginRecording()
        try {
            // IN ROOT SPACE, as drawGlassPane does. The numeral's rect is in the canvas's
            // space, and on the page above the readout that canvas is translated by the
            // slide — so copying the backdrop at the rect's own coordinates took the
            // ground from where the glyph WOULD be at rest. The picture inside the glass
            // then travelled with the numeral instead of staying where the picture is,
            // which is the frozen backdrop the panes were cured of and the clock was not.
            if (rootSpace(source)) {
                canvas.getMatrix().mapRect(sdfAbs, sdfRect)
                into.translate(-sdfAbs.left, -sdfAbs.top)
            } else {
                into.translate(-sdfRect.left, -sdfRect.top)
            }
            into.drawRenderNode(source)
        } finally {
            node.endRecording()
        }

        // THE LIQUID'S COLOUR, because the figure is the liquid's number and the two
        // should read as one instrument rather than as a reading beside a picture.
        //
        // It is the glass that is tinted, not the type. The first attempt mixed the
        // refracted colour toward the hue and it stopped looking like glass — see the
        // shader, which multiplies instead, the way a coloured pane actually treats the
        // light going through it.
        val tint = (paint.color and 0x00FFFFFF) or
            ((Glass.SDF_TINT * 255f).toInt().coerceIn(0, 255) shl 24)

        // KEYED ON THE FIELD AND THE HUE — the number and the colour it is drawn in.
        // Neither moves between frames, and building a RenderEffect per frame is precisely
        // the churn that filled the native heap the last time: a small Java object in
        // front of a large native allocation is the one shape the collector will not
        // hurry over.
        val effectKey = sdfKey + "|" + tint
        var effect = sdfEffects[effectKey]
        if (effect == null) {
            val shader = Glass.sdf
            shader.setInputBuffer(
                "sdfTex",
                android.graphics.BitmapShader(
                    field,
                    android.graphics.Shader.TileMode.CLAMP,
                    android.graphics.Shader.TileMode.CLAMP,
                ),
            )
            shader.setFloatUniform("size", w, h)
            shader.setFloatUniform("sdfTexSize", w, h)
            shader.setFloatUniform("refractionHeight", Glass.SDF_REFRACTION)
            shader.setFloatUniform("lightAngle", Glass.SDF_LIGHT_ANGLE)
            shader.setColorUniform("tint", tint)
            effect =
                android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content")
            // BOUNDED, or this is the native-heap churn again by another route. A clock
            // needs eleven of these and a charge figure a hundred, and both are small,
            // fixed sets — but a cache with no ceiling is one bad key away from being a
            // leak, and the last one cost 158MB a minute before it was found.
            if (sdfEffects.size >= SDF_CACHE_MAX) sdfEffects.clear()
            sdfEffects[effectKey] = effect
        }
        node.setRenderEffect(effect)
        recorder.drawRenderNode(node)
        return true
    }

    /**
     * Measured fields and their effects, by glyph rather than by string.
     *
     * ONE ENTRY WAS ENOUGH FOR A CHARGE FIGURE and is not enough for a clock. A
     * percentage changes about a hundred times in a charge and holds still between; a
     * clock changes every second, forever. Keyed on the whole string, the seconds column
     * would remeasure the entire time every second — which is the field rebuild that made
     * this unusable the first time it was tried.
     *
     * Per GLYPH it is the opposite: there are ten digits and a colon, every one of them
     * is measured once in the panel's life, and the clock then costs nothing per tick but
     * a lookup.
     */
    private val sdfFields = HashMap<String, android.graphics.Bitmap>()
    private val sdfEffects = HashMap<String, android.graphics.RenderEffect>()

    private val sdfRect = android.graphics.RectF()
    /** [sdfRect] mapped to root space, when the backdrop is root-space. */
    private val sdfAbs = android.graphics.RectF()

    /** The measured field for [text] at [paint]'s size and weight, kept until it changes. */
    private fun sdfFor(text: String, paint: Paint): android.graphics.Bitmap? {
        val key = text + "|" + paint.textSize + "|" + paint.fontVariationSettings
        sdfKey = key
        sdfFields[key]?.let { return it }

        val pad = SdfText.SPREAD
        val w = (paint.measureText(text) + pad * 2f).toInt()
        val m = paint.fontMetrics
        val h = (m.descent - m.ascent + pad * 2f).toInt()
        if (w <= 0 || h <= 0) return null

        // The glyphs on their own, in white on nothing: only the coverage matters, and the
        // field is measured from that.
        val ink = android.graphics.Bitmap.createBitmap(
            w, h, android.graphics.Bitmap.Config.ARGB_8888,
        )
        val stamp = Paint(paint)
        stamp.color = android.graphics.Color.WHITE
        stamp.textAlign = Paint.Align.LEFT
        Canvas(ink).drawText(text, pad, pad - m.ascent, stamp)

        val pixels = IntArray(w * h)
        ink.getPixels(pixels, 0, w, 0, 0, w, h)
        ink.recycle()
        val coverage = FloatArray(w * h) { (pixels[it] ushr 24) / 255f }

        val packed = SdfText.pack(coverage, w, h)
        val out = android.graphics.Bitmap.createBitmap(
            packed, w, h, android.graphics.Bitmap.Config.ARGB_8888,
        )
        // Same ceiling as the effects, and cleared together with them so a key can never
        // survive in one and not the other.
        if (sdfFields.size >= SDF_CACHE_MAX) {
            sdfFields.values.forEach { it.recycle() }
            sdfFields.clear()
            sdfEffects.clear()
        }
        sdfFields[key] = out
        return out
    }

    private var sdfKey: String? = null

    /** The battery glyph in panel coordinates, so the charge can be clipped to it. */
    private val batteryMatrix = android.graphics.Matrix()
    private val batteryPath = android.graphics.Path()
    private val batteryBounds = android.graphics.RectF()

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
    /**
     * The last ten notifications, newest first.
     *
     * WRITTEN DOWN, because this process does not live long enough to remember anything.
     *
     * The first version of this kept the ten in memory on the grounds that the panel
     * outlives the notification listener. It does not outlive much: the :rear process is
     * destroyed and recreated on every recovery, every re-placement and every time
     * HyperOS feels like it — the class comment says so — and it holds no other state for
     * exactly that reason. A history that emptied itself several times a day is not a
     * history, and it took an install to notice, because an install is just one more of
     * the many ways this process dies.
     *
     * So it goes to the panel's own preferences, next to the page you were on, which is
     * the one other thing here that has to survive the process that draws it.
     */
    private val history: ArrayDeque<Notice> by lazy {
        val out = ArrayDeque<Notice>()
        panelPrefs.getString(KEY_HISTORY, "").orEmpty()
            .split(RECORD_SEP)
            .filter { it.isNotEmpty() }
            .forEach { row ->
                val f = row.split(FIELD_SEP)
                if (f.size >= 6) out.addLast(Notice(f[0], f[1], f[2], f[3], f[4], f[5]))
            }
        out
    }

    /** The ten, flattened. Two separators no notification text will contain. */
    private fun saveHistory() {
        panelPrefs.edit().putString(
            KEY_HISTORY,
            history.joinToString(RECORD_SEP) {
                listOf(it.app, it.pkg, it.title, it.text, it.moreTitle, it.moreText)
                    .joinToString(FIELD_SEP) { f -> f.replace(FIELD_SEP, " ").replace(RECORD_SEP, " ") }
            },
        ).apply()
    }

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
    private val extrasBody = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 18f; typeface = label.typeface
    }

    /**
     * The same paint at the track title's lighter weight.
     *
     * A SECOND PAINT RATHER THAN A SECOND SETTING, and the difference is the whole point.
     * A weight lives on the PAINT, not on the typeface: Paint.fontVariationSettings builds
     * the varied font and keeps it there, and reading the typeface back afterwards hands
     * you the plain one. Measured, after trying exactly that — the same string came out at
     * 1310 lit pixels through the "prepared" typeface and 3955 through a paint that had
     * been told directly. So a weight cannot be prepared and swapped in; the only thing
     * that carries one is a paint that has been told.
     *
     * The row used to tell `extras` 400 and then tell it 600 again on the way out, every
     * frame, and each of those rebuilt the font out of the variable font's tables — 11ms a
     * frame, with every measureText behind it paying to re-resolve as well. Told once each
     * and swapped by reference, it costs nothing.
     */
    private val extrasMedia = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 18f; typeface = label.typeface
    }

    /**
     * The notification page's date, which is heavier than the panel's body.
     *
     * ITS OWN PAINT, and it has to be. A weight lives on the Paint and setting it builds a
     * Typeface — see the note on fontVariationSettings — so asking for a heavier date at
     * the point of drawing would rebuild one every frame, which is the exact churn that
     * once had this panel rendering at a fifth of its speed. Every weight on this panel is
     * told to a paint once, at theme time, and this is one more of them.
     */
    private val extrasDate = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 18f; typeface = label.typeface
    }

    /** Whichever of the two is drawing. See extrasMedia. */
    private var extras = extrasBody

    /**
     * The charging figure, at the heaviest weight Outfit has.
     *
     * Its own paint for the same reason the media row has one — a weight lives on the
     * paint, and telling a shared paint about 900 and back again every frame is what cost
     * eleven milliseconds the last time. Nine hundred because this is one number the size
     * of a fist, and glass needs something to be made OF: a light weight leaves too little
     * ink for the field to bevel and the letters come out as wire.
     */
    private val chargeFigure = Paint(Paint.ANTI_ALIAS_FLAG)

    private val planTag = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 18f; typeface = label.typeface
        textAlign = Paint.Align.RIGHT
    }

    // -- type ------------------------------------------------------------------------

    /**
     * A size in ORIGINAL's terms, corrected for whichever face is actually set.
     *
     * EVERY SIZE ON THIS PANEL IS STATED ONCE, in the theme the panel was measured in,
     * and the other theme is a correction to it. The alternative — a second set of forty
     * numbers — is two layouts that drift apart the moment either is adjusted, and only
     * one of them is ever in front of you to notice.
     *
     * Not applied to sizes that were MEASURED rather than chosen. The lyric fitter and
     * the charging figure both compute a size from the room available, using the face
     * that is actually set; correcting those again would be correcting twice.
     */
    private fun typed(size: Float): Float = size * typeScale

    private var typeScale = 1f
    private var themeApplied: dev.kb896.aiusage.data.PanelTheme? = null
    private var fontApplied: dev.kb896.aiusage.data.PanelFont? = null

    /**
     * Point every paint at the theme's face.
     *
     * Done on the config setter rather than per frame, because a typeface is a heavy
     * object to hand to eight paints sixty times a second, and it only ever changes when
     * somebody chooses a different theme.
     *
     * The base sizes are re-applied here as well as the face. They were set in the field
     * initialisers, which run before any config exists, so ORIGINAL's numbers are already
     * in place and would otherwise stay there under a face that needs different ones.
     */

    private fun applyTheme(theme: dev.kb896.aiusage.data.PanelTheme, font: dev.kb896.aiusage.data.PanelFont?) {
        if (theme == themeApplied && font == fontApplied) return
        themeApplied = theme
        fontApplied = font
        // The theme's face, or the one chosen in General — see PanelType.face.
        val face = PanelType.face(resources, theme, font)
        typeScale = face.scale
        for (p in listOf(label, heading, value, timeTag, cascadePaint, extrasBody, planTag)) {
            face.set(p, PanelType.BODY)
        }
        // Each weight told once, here, and never again — see extrasMedia.
        face.set(extrasMedia, PanelType.MEDIA)
        face.set(chargeFigure, PanelType.HEAVIEST)
        // The date on the notification page, at the masthead's weight. That page is read
        // from further away than the readout is — it is a clock and a date, the things you
        // glance at across a desk — and the body weight over a record was not enough.
        face.set(extrasDate, PanelType.TITLE)
        // The masthead is heavier than the body: under glass it is a lens shaped like the
        // letters, and a lens needs a stroke wide enough to have an inside.
        face.set(title, PanelType.TITLE)
        tipText.typeface = label.typeface
        tipText.fontVariationSettings = label.fontVariationSettings
        label.textSize = typed(18f)
        heading.textSize = typed(22.5f)
        value.textSize = typed(21f)
        title.textSize = typed(40f)
        timeTag.textSize = typed(18f)
        cascadePaint.textSize = typed(CASCADE_TEXT)
        extras.textSize = typed(18f)
        planTag.textSize = typed(18f)
        invalidate()
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
        /** Overshoot and settle, for things that change SIZE rather than move. */
        back: Boolean = false,
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
        val curve = when {
            linear -> t
            back -> Tween.easeOutBack(t)
            else -> Tween.easeInOut(t)
        }
        val value = leg.from + (leg.to - leg.from) * curve
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

    /**
     * A string held back until the box around it has finished growing.
     *
     * THE BOX MOVES FIRST, THEN THE WORDS. A card and the name on it are one object, and
     * changing both at once makes them two: the sheet resizing while the letters roll over
     * reads as the panel redrawing rather than as a record changing. Worse, a longer title
     * arriving before its card has opened is a title hanging outside its own box for the
     * length of the roll.
     *
     * So the card is given the NEW text's width immediately and springs to it, while the
     * wheel keeps turning the old one until it has. The two become a sequence, which is
     * what it looks like anyway when it is done properly.
     */
    private class HeldText(
        var target: String,
        var shown: String,
        var at: Long,
        var growing: Boolean,
        var holdWidth: Float,
    )

    private val heldTexts = HashMap<String, HeldText>()

    /** The width the card should be at right now, set by [sequenced]. */
    private var sequencedWidth = 0f

    /**
     * The name to draw, and — through [sequencedWidth] — the width to draw its card at.
     *
     * WHICHEVER IS BIGGER GOES FIRST, and that is the whole rule. The box has to be large
     * enough for whatever text is in it at every instant, so the order depends on which
     * way it is going:
     *
     *   growing — the new name is longer, so the box opens first and the wheel turns into
     *             the room it has made.
     *   shrinking — the OLD name is longer, so the wheel turns first and the box closes
     *             behind it, once there is nothing left needing the space.
     *
     * Doing it one way for both looked right in one direction and wrong in the other: a
     * box that shrinks before the words have gone clips the very text it is holding.
     */
    private fun sequenced(key: String, text: String, width: (String) -> Float): String {
        val h = heldTexts.getOrPut(key) { HeldText(text, text, 0L, false, width(text)) }
        if (text != h.target) {
            val old = width(h.shown)
            h.growing = width(text) > old
            h.target = text
            h.at = frameNowMs
            if (!h.growing) {
                // The words leave now; the box keeps their room until they have.
                h.shown = text
                h.holdWidth = old
            }
        }
        if (h.at != 0L) {
            val wait = if (h.growing) CARD_GROW_MS else WHEEL_MS
            if (frameNowMs - h.at >= wait) {
                h.shown = h.target
                h.at = 0L
            } else {
                animating = true
            }
        }
        sequencedWidth = when {
            h.at == 0L -> width(h.shown)
            h.growing -> width(h.target)
            else -> h.holdWidth
        }
        return h.shown
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
     * ALWAYS-ON. The page freezes: after a one-second fade of the backdrop to black no
     * frame is scheduled unless the lock is up (the slider has to slide and be dragged),
     * and the animators that run on a clock — the walk, the ambient walk — read a clock
     * that stopped when the mode began, so they hold their pose even while the slider
     * moves in front of them. Everything else is a tween that has already settled.
     */
    var aod: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            val now = SystemClock.uptimeMillis()
            if (value) {
                aodSince = now; aodWallAt = System.currentTimeMillis(); aodRealAt = SystemClock.elapsedRealtime()
                aodReturnPage = pager.page
                pager.goTo(PAGE_NOTICES, now, AOD_FADE_MS.toFloat()) // the clock page is the always-on page, reached with the fade
            } else {
                aodUntil = now
                aodPeek = false
                removeCallbacks(minuteTick)
                pager.goTo(aodReturnPage, now, AOD_FADE_MS.toFloat()) // and the swipe returns where you were, with the fade
            }
            invalidate()
            syncVideo(fresh = !value)
            syncTilt()
        }
    private var aodReturnPage = PAGE_READOUT
    /** Until when a clock figure is rolling over; the always-on freeze waits for it. */
    private var clockRollUntil = 0L
    private var aodSince = 0L
    private var aodUntil = 0L
    private var aodWallAt = 0L
    private var aodRealAt = 0L

    /**
     * A notification lifting the dim while always-on stays on: the veil, the backdrop,
     * the seconds and the freeze come back for a moment, the slider and the guard do not
     * move. Set and cleared by the activity around a card's time.
     */
    var aodPeek: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            val now = SystemClock.uptimeMillis()
            if (value) aodUntil = now else aodSince = now
            invalidate()
        }

    /** 0 = backdrop fully there, 1 = faded to black; half a second either way. */
    private fun aodFade(now: Long): Float {
        val dimmed = aod && !aodPeek
        val f = if (dimmed) (now - aodSince) / AOD_FADE_MS.toFloat() else 1f - (now - aodUntil) / AOD_FADE_MS.toFloat()
        return f.coerceIn(0f, 1f)
    }

    /** Always-on draws the clock once a minute, on the minute, and otherwise not at all. */
    private val minuteTick = Runnable { if (aod && displayAwake) invalidate() }
    private fun armMinuteTick() {
        removeCallbacks(minuteTick)
        val wall = System.currentTimeMillis()
        postDelayed(minuteTick, 60_000L - wall % 60_000L + 30L)
    }

    /**
     * A page that has settled into always-on wants no more frames than the slider needs —
     * or the charge cell, which plays through when the charger lands and was crawling
     * along on the minute tick before this.
     */
    private fun frozen(now: Long): Boolean =
        aod && !aodPeek && aodFade(now) >= 1f && !lockBusy(now) && chargeAt == 0L && !tableGlowBusy(now) && !pager.animating() && now >= clockRollUntil

    /** The slider is sliding in or out, held by a finger, or springing back. */
    private fun lockBusy(now: Long): Boolean =
        (lockAt != 0L && now - lockAt < MEDIA_MS) || lockGrabbed || lockSpringAt != 0L

    /** Where the slider takes touches, in panel pixels: the band, with its slop. */
    fun lockBand(): android.graphics.RectF {
        val r = markRegion()
        return android.graphics.RectF(0f, r.top - LOCK_BAND_SLOP, Panel.WIDTH.toFloat(), r.bottom + LOCK_BAND_SLOP)
    }

    /**
     * Always-on dims everything under the slider: a veil the view draws, so the slider
     * itself stays bright — and the clock, with the battery line above it, is cut out of
     * the veil and stays as it is. The slider gets its own lighter veil after it is drawn.
     */
    private fun drawAodVeil(canvas: Canvas) {
        if (!aod && aodUntil == 0L) return
        val a = (255 * AOD_VEIL_ALPHA * aodFade(frameNowMs)).toInt()
        if (a <= 0) return
        canvas.save()
        if (aodBandOut()) canvas.clipOutRect(aodClockBand())
        canvas.drawColor(a shl 24)
        canvas.restore()
    }

    /**
     * The clock and battery line's band, left out of the veil so the figures stay
     * bright. Only as wide as the clock, with a little air: the rings round the lenses
     * share these rows, and a band the full width of the panel left their arcs bright.
     */
    /**
     * The battery figure's baseline over the clock: [AOD_BATTERY_GAP] above the clock's
     * FIGURES, measured in the face they are set in, not above the clock's whole em. The
     * figures stop well short of the em's top, so measuring from it left an empty strip
     * between the battery and the time and pushed the battery up into the notifications.
     */
    private fun clockBatteryBaseline(): Float {
        val keep = extras.textSize
        extras.textSize = typed(CLOCK_SIZE)
        extras.getTextBounds("0", 0, 1, digitBounds)
        extras.textSize = keep
        return CLOCK_BASELINE + digitBounds.top - AOD_BATTERY_GAP
    }
    private val digitBounds = android.graphics.Rect()

    private fun aodClockBand(): RectF {
        val half = if (aodClockSpan > 0f) aodClockSpan / 2f + AOD_BAND_AIR else Panel.WIDTH.toFloat()
        return RectF(
            aodClockCx - half, clockBatteryBaseline() - typed(DATE_SIZE) - 8f,
            // Stops just under the baseline: the figures have no descenders, and the old
            // fifth of the clock's height below it reached into the top of the date's
            // glass card, which then sat eight pixels brighter than the rest of it.
            aodClockCx + half, minOf(CLOCK_BASELINE + AOD_BAND_BELOW, DATE_BASELINE - DATE_SIZE - ROW_CARD_PAD_Y - 1f),
        )
    }
    /** Where the clock page last drew its clock: centre and full width, seconds and all. */
    private var aodClockCx = Panel.WIDTH / 2f
    private var aodClockSpan = 0f

    /** Whether the band is left out: always on the way into always-on, at rest on the clock page otherwise. */
    private fun aodBandOut(): Boolean = aod || pager.restingAt(PAGE_NOTICES)

    /**
     * The band's share of the veil, laid on the backdrop itself, under the clock. The veil
     * above clips the band out so the figures stay bright, and without this the backdrop
     * behind them stayed brighter than above and below for as long as the fade ran.
     */
    private fun drawAodBandVeil(canvas: Canvas) {
        if (!aod && aodUntil == 0L) return
        if (!aodBandOut()) return
        val a = (255 * AOD_VEIL_ALPHA * aodFade(frameNowMs)).toInt()
        if (a <= 0) return
        veilPaint.color = a shl 24
        canvas.drawRect(aodClockBand(), veilPaint)
    }

    /** The slider's own, lighter veil in always-on: dimmed a little, never as much as the page. */
    private fun drawAodSliderVeil(canvas: Canvas) {
        if (!aod && aodUntil == 0L) return
        if (!locked) return
        val a = (255 * AOD_SLIDER_VEIL_ALPHA * aodFade(frameNowMs)).toInt()
        if (a <= 0) return
        val b = lockBand()
        veilPaint.color = a shl 24
        canvas.drawRect(b.left, b.top, b.right, b.bottom, veilPaint)
    }
    private val veilPaint = android.graphics.Paint()

    /**
     * Going to sleep — the double tap's dim, or always-on — the rings close to zero over a
     * second, and open again the same way when the panel is back. Its own clock, so it
     * works for the dim too, which the view otherwise knows nothing about.
     */
    var ringsClosed: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            ringsAt = SystemClock.uptimeMillis()
            invalidate()
        }
    private var ringsAt = 0L
    private fun ringsFade(now: Long): Float {
        if (ringsAt == 0L) return if (ringsClosed) 1f else 0f
        val t = ((now - ringsAt) / AOD_FADE_MS.toFloat()).coerceIn(0f, 1f)
        if (t < 1f) animating = true
        return if (ringsClosed) t else 1f - t
    }

    /**
     * Table mode: the phone lying flat on its face. The margins glow (TableGlow), in over
     * half a second and out the same way, and the glow breathes, so it keeps frames going
     * — a page frozen in always-on still lets this through.
     */
    var tableGlow: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            tableGlowAt = SystemClock.uptimeMillis()
            invalidate()
        }
    private var tableGlowAt = 0L
    private fun tableGlowFade(now: Long): Float {
        if (tableGlowAt == 0L) return if (tableGlow) 1f else 0f
        val t = ((now - tableGlowAt) / AOD_FADE_MS.toFloat()).coerceIn(0f, 1f)
        return if (tableGlow) t else 1f - t
    }
    /** The lights' walk as the glow comes on, in quarters of the frame: fast laps first, then the painting quarter, then still. */
    private fun tableGlowWalk(now: Long): Float {
        val whole = (TableGlow.LAPS * 4 + 1).toFloat()
        if (!tableGlow || tableGlowAt == 0L) return whole
        val t = ((now - tableGlowAt) / TABLE_INTRO_MS.toFloat()).coerceIn(0f, 1f)
        // eased out: the laps go by in a rush and the last quarter is slow
        return whole * (1f - (1f - t) * (1f - t) * (1f - t))
    }

    /** Whether the glow is still fading or its lights still walking; at rest it costs no frames. */
    private fun tableGlowBusy(now: Long): Boolean =
        tableGlowAt != 0L && now - tableGlowAt < (if (tableGlow) TABLE_INTRO_MS else AOD_FADE_MS)

    private fun drawTableGlow(canvas: Canvas) {
        val strength = tableGlowFade(frameNowMs)
        if (strength <= 0.001f) return
        if (tableGlowBusy(frameNowMs)) animating = true
        // with the CRT on, the lights are drawn after the tube, by onDraw, not here
        if (crtApplied && !glowOverTube) return
        val paint = TableGlow.frame(
            Panel.WIDTH.toFloat(), Panel.HEIGHT.toFloat(), tableGlowWalk(frameNowMs),
            colorFor("table", config.tableTint), strength, cornerRadius(),
        ) ?: return
        canvas.drawRect(0f, 0f, Panel.WIDTH.toFloat(), Panel.HEIGHT.toFloat(), paint)
    }

    /** Told when the slider is swiped through; not when the lock merely runs out. */
    var onUnlocked: (() -> Unit)? = null

    /**
     * Whether the panel's display is in a state that takes updates at all.
     *
     * MEASURED, and it is the single largest thing this panel spends: with the rear
     * display in DOZE_SUSPEND the process was burning about seventy per cent of a core,
     * drawing a hundred and twenty frames a second into a display that is suspended and
     * does not apply them. The record turned, the mascots walked, fifteen panes of glass
     * resolved their backdrops, and none of it reached a pixel anybody could see.
     *
     * DOZE_SUSPEND and ON_SUSPEND both mean the same thing for our purposes — the last
     * frame stays on the panel and further ones are dropped — which is exactly what an
     * always-on display should do with a readout that has nothing new to say. Stopping
     * is not a frame-rate cap: a capped panel still draws, this one draws nothing at all
     * and resumes the moment the display can show it.
     *
     * The heartbeat deliberately keeps running. It is one frame every fifteen seconds
     * against a hundred and twenty a second, and it is what guarantees that a state
     * change we never hear about cannot leave the panel frozen for good.
     */
    var displayAwake: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            // Straight back to work, without waiting on the heartbeat: whatever moved
            // while the panel was dark has to be caught up in the first frame after it.
            if (value) invalidate()
            // Dark: still at once, so that lighting again picks the silk up from a standstill.
            if (!value) silkSpeed.stopNow()
            syncVideo(fresh = value)
            syncTilt()
        }

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
        // NOT WHILE THE PANEL IS DARK. Everything this heartbeat exists for — the
        // staleness marker appearing, the burn-in drift moving on — is about what is
        // being LOOKED at, and none of it is true of a display that is dropping frames.
        // It used to keep running as a guard against a doze signal we never heard about;
        // that guard is now a touch, which cannot happen on a screen nobody has lit. See
        // onTouchEvent.
        if (displayAwake) invalidate()
        postDelayed(redrawTick, if (displayAwake) REDRAW_INTERVAL_MS else DARK_TICK_MS)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncVideo()
        syncTilt()
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
        video.sleep()
        context.getSystemService(android.hardware.SensorManager::class.java)?.unregisterListener(tiltListener)
        tiltListening = false
        removeCallbacks(redrawTick)
        runCatching { context.unregisterReceiver(powerReceiver) }
        runCatching {
            context.getSystemService(android.hardware.SensorManager::class.java)
                ?.unregisterListener(gravityListener)
        }
        super.onDetachedFromWindow()
    }

    /**
     * A frame, in two passes when the panes need something to refract.
     *
     * GLASS BENDS WHAT IS BEHIND IT, so the panes have to be handed the panel behind them
     * as something they can sample — and a Canvas cannot be read back. So the scene is
     * recorded into a RenderNode first, drawn, and then the overlays are drawn over it
     * with that node as their backdrop. It is not an extra rasterisation: hardware
     * rendering builds a display list for this view either way, and this is one more of
     * the same kind.
     *
     * ORIGINAL takes the single-pass path unchanged. So does anything drawing into a
     * software canvas, where there are no render nodes at all — a preview bitmap on the
     * front screen, for one.
     */
    override fun onDraw(canvas: Canvas) {
        val recorder = canvas as? android.graphics.RecordingCanvas
        val tube = if (config.crt && recorder != null && width > 0 && height > 0) {
            CrtScreen.effect(width.toFloat(), height.toFloat(), crtStills())
        } else null
        crtApplied = tube != null
        if (tube == null) {
            drawPicture(canvas)
            scheduleNext()
            return
        }
        crtNode.setPosition(0, 0, width, height)
        crtNode.setRenderEffect(tube)
        val into = crtNode.beginRecording(width, height)
        try {
            drawPicture(into)
        } finally {
            crtNode.endRecording()
        }
        recorder!!.drawRenderNode(crtNode)
        // over the tube, untouched by it — in panel units like everything else
        glowOverTube = true
        canvas.save()
        canvas.scale(fitX, fitY)
        try { drawTableGlow(canvas) } finally { glowOverTube = false; canvas.restore() }
        scheduleNext()
    }

    /**
     * Whether to ask for the next frame — decided once EVERYTHING has drawn. It used to be
     * decided at the end of the scene, and on GLASS the overlays come after it: the glass
     * buttons and their glyphs, the slider, the table lights. Their animations set the flag
     * too late to count, so they ran only while something in the scene kept frames coming
     * — the spinning record, say — and a pause froze the play glyph's morph, the table
     * lights' walk and everything else mid-way until a touch drew again.
     */
    private fun scheduleNext() {
        publishMirror()
        if (animating && displayAwake && !frozen(frameNowMs)) postInvalidateOnAnimation()
    }

    /** The picture itself: the scene, refracted or flat, and its overlays. */
    /**
     * The panel is laid out in the 17 Pro's 904x572 and scaled to the rear display it is
     * actually on — the 17 Pro Max's is 976x596 — so nothing below needs to know which
     * phone this is. Everything inside draws in panel units; only the tube (CrtScreen)
     * and touches live in real pixels. Per axis, so the whole display is used; the two
     * ratios differ by under four per cent on the Pro Max, which no ring shows.
     */
    val fitX: Float get() = if (width > 0) width / Panel.WIDTH.toFloat() else 1f
    val fitY: Float get() = if (height > 0) height / Panel.HEIGHT.toFloat() else 1f

    /** A touch in real pixels, moved into panel units for everything that reads it. */
    fun toPanel(ev: android.view.MotionEvent) {
        if (fitX == 1f && fitY == 1f) return
        ev.transform(android.graphics.Matrix().apply { setScale(1f / fitX, 1f / fitY) })
    }

    private fun drawPicture(canvas: Canvas) {
        canvas.save()
        canvas.scale(fitX, fitY) // panel units from here on
        try {
            drawPictureInPanelUnits(canvas)
        } finally {
            canvas.restore()
        }
    }

    private fun drawPictureInPanelUnits(canvas: Canvas) {
        // Every pane this frame gets its own node — see nodePool.
        nodesUsed = 0
        pendingButtons.clear()
        pendingRings.clear()
        pendingOver.clear()
        val recorder = canvas as? android.graphics.RecordingCanvas
        if (config.theme.isGlass && recorder != null && width > 0 && height > 0) {
            // POSITION, not just size. beginRecording says how big the display list is;
            // setPosition says where it goes. Without it the node has empty bounds and
            // draws nothing at all — the panel came up as two notification cards floating
            // on black, with the readout recorded perfectly and placed nowhere.
            sceneNode.setPosition(0, 0, Panel.WIDTH, Panel.HEIGHT)
            val scene = sceneNode.beginRecording(Panel.WIDTH, Panel.HEIGHT)
            recordingScene = true
            try {
                drawFrame(scene, overlays = false)
            } finally {
                recordingScene = false
                sceneNode.endRecording()
            }
            recorder.drawRenderNode(sceneNode)
            drawOverlays(canvas)
            return
        }
        drawFrame(canvas, overlays = true)
    }

    /**
     * The condensation, on the same glass as the drops.
     *
     * Drawn by drawWeatheredGround immediately after the water, so the two are one surface
     * and everything on the panel is in front of both. It needs no backdrop of its own:
     * mist is white with holes in it, so the shader paints its own alpha.
     */
    /**
     * Where the cameras are, for the water to go round.
     *
     * The ring layout's centres ARE the lens centres — that is what the calibration
     * measured — and the lens radius is the measured one, not the ring's: the ring is
     * drawn around the lens, and the drops ride it. Not drifted with the readout,
     * because the cameras do not drift; they are the one thing on this panel that is
     * exactly where it is.
     */
    private fun setLenses(shader: android.graphics.RuntimeShader) {
        val lay = config.effectiveRingLayout
        val r = PanelGeometry.LENS_RADIUS
        shader.setFloatUniform("lensA", lay.cx.toFloat(), lay.cyFirst.toFloat(), r)
        shader.setFloatUniform("lensB", lay.cx.toFloat(), lay.cySecond.toFloat(), r)
    }

    private fun drawMist(canvas: Canvas, keep: Float = 1f) {
        if (weatherMode() != Weather.RAIN || width <= 0 || height <= 0 || keep <= 0.001f) return
        val shader = Weather.mist
        shader.setFloatUniform("size", Panel.WIDTH.toFloat(), Panel.HEIGHT.toFloat())
        shader.setFloatUniform("mode", 0f)
        shader.setFloatUniform("amount", Weather.AMOUNT)
        setLenses(shader)
        shader.setFloatUniform(
            "time", (frameNowMs % (Weather.PERIOD_S * 1000L).toLong()) / 1000f,
        )
        mistPaint.shader = shader
        mistPaint.alpha = (255 * keep).toInt()
        panelRect.set(0f, 0f, Panel.WIDTH.toFloat(), Panel.HEIGHT.toFloat())
        canvas.drawRect(panelRect, mistPaint)
        mistPaint.alpha = 255
    }

    private val mistPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** The panel behind the panes, so they have something to bend. */
    private val sceneNode = android.graphics.RenderNode("panel")

    /**
     * Just the ground and the picture on it, for panes that sit UNDER the readout.
     *
     * The overlay panes bend the finished panel, which is what makes them look like glass
     * lying on top of it. A card behind the readout cannot do that — it would be drawn
     * over the very content it is supposed to be under — so it bends the only thing that
     * exists before the readout does.
     */
    private val groundNode = android.graphics.RenderNode("ground")

    /** The ground's colour, held between being worked out and being drawn. */
    private var groundColor = 0

    /**
     * Render nodes for this frame's panes, handed out in order and reused next frame.
     *
     * ONE SHARED NODE IS A BUG, and a quiet one. `drawRenderNode` puts a REFERENCE in the
     * display list rather than a copy, so a node recorded again before the frame is
     * rasterised shows its last contents at its last position — everywhere it was drawn.
     * With one node and six bars, five of the six vanished and the sixth appeared six
     * times in the same place. It was there from the first pane too and simply could not
     * be seen: two notification cards over a black panel refract black either way.
     *
     * Nodes are cheap to keep and expensive to churn, so the pool grows to the busiest
     * frame and then stops.
     */
    private val nodePool = ArrayList<android.graphics.RenderNode>(16)
    private var nodesUsed = 0

    /** Whether the scene is mid-recording, and therefore not something to sample. */
    private var recordingScene = false

    private fun nextNode(): android.graphics.RenderNode {
        if (nodesUsed == nodePool.size) {
            nodePool.add(android.graphics.RenderNode("glass-${nodePool.size}"))
        }
        return nodePool[nodesUsed++]
    }

    /**
     * The three things drawn over the panel rather than in it.
     *
     * Held out of the scene deliberately: they are the glass, and glass cannot be part of
     * what it refracts. Everything else — including the ambient mascot — stays in the
     * scene and is therefore something the panes can bend, which is the point.
     */
    private fun drawOverlays(canvas: Canvas) {
        val recorder = canvas as? android.graphics.RecordingCanvas
        if (recorder == null || !config.theme.isGlass || !sceneNode.hasDisplayList()) {
            swept(canvas) { drawPendingRings(it) }
            swept(canvas) { drawPendingOver(it) }
            swept(canvas) { drawPendingButtons(it) }
            drawCameraTip(canvas)
            drawAodVeil(canvas)
            drawTableGlow(canvas)
            drawLockOverlay(canvas)
            drawAodSliderVeil(canvas)
            // Same rule as the glass path's: the page below already has it. See the
            // GROUP_NOTICE guard.
            if (noticing < 0.5f) {
                drawNotice(canvas, noticeSlideHeld, SystemClock.elapsedRealtime())
            }
            drawCharge(canvas)
            return
        }

        // PANES STACK, RATHER THAN EACH REPLACING WHAT IS UNDER IT.
        //
        // Every pane bends a backdrop, and if that backdrop is always the bare scene then
        // a pane drawn over another shows the scene where the first one is — which erases
        // it exactly where they overlap. Not a limitation of the effect: a limitation of
        // handing every pane the same backdrop.
        //
        // So the backdrop accumulates. Each overlay is recorded into a node holding
        // everything before it, and the next one bends THAT. A pane can never sample the
        // node it is being recorded into — that is the cycle from before — but it can
        // sample the one behind, which is all it needs.
        // ONLY WHAT IS ACTUALLY THERE, and only what actually has something stacked on
        // top of it. The chain used to be built unconditionally, four layers deep, every
        // frame — so a panel showing nothing but its transport buttons still recorded
        // three full-panel nodes for a lock, a notice and a charger that were not on
        // screen. That is not free: a pane's shader resolves its backdrop through every
        // layer above the one it samples, so depth is multiplied by the fifteen panes
        // that sample it. Most frames need no chain at all.
        val active = overlayGroups
        active.clear()
        // Rings first, so a button crossing one is glass over glass in that order rather
        // than the other way round.
        if (pendingRings.isNotEmpty()) active.add(GROUP_RINGS)
        // Straight after the bands, so a ring's own label is legible on top of it.
        if (pendingOver.isNotEmpty()) active.add(GROUP_OVER)
        if (pendingButtons.isNotEmpty()) active.add(GROUP_BUTTONS)
        // The camera tip covers all of the above, and is its own layer so the slider's
        // glass bends the tip rather than the panel hidden under it.
        if (tipAt != 0L || tipLeftAt != 0L) active.add(GROUP_TIP)
        // The lock group also carries the always-on veil and the table lights, which
        // show without any slider: in always-on the slider is always up, which hid
        // that the group never came on for the lights alone on a plain page.
        if (lockSwap(frameNowMs) > 0.001f || tableGlowFade(frameNowMs) > 0.001f ||
            aod || (aodUntil != 0L && frameNowMs - aodUntil < AOD_FADE_MS)
        ) active.add(GROUP_LOCK)
        // NOT WHILE THE PAGE THAT HOLDS THEM IS UP. The strip and the carousel are the
        // same notification drawn twice — the strip slides one in over the top of a page
        // whose whole job is showing it, and the newest card in the carousel IS that
        // notification. Two of it is not emphasis, it is a mistake.
        if (noticeSlideHeld > 0.001f && noticing < 0.5f) active.add(GROUP_NOTICE)
        if (chargeAt != 0L) active.add(GROUP_CHARGE)
        if (active.isEmpty()) return

        // Every group but the last goes into a layer, because something is going to be
        // drawn over it and has to be able to bend it. The last one has nothing above it,
        // so it draws straight onto the canvas over the scene that is already there.
        var backdrop = sceneNode
        for (i in 0 until active.size - 1) {
            val group = active[i]
            backdrop = layered(backdrop) { c -> drawOverlayGroup(group, c) }
        }
        if (backdrop !== sceneNode) recorder.drawRenderNode(backdrop)
        paneBackdrop = backdrop
        try {
            drawOverlayGroup(active[active.size - 1], canvas)
        } finally {
            paneBackdrop = sceneNode
        }
    }

    /** [draw], minus whatever the ripple has already taken. See [sweepFront]. */
    private inline fun swept(canvas: Canvas, draw: (Canvas) -> Unit) {
        val front = sweepFront
        if (front == null) {
            draw(canvas)
            return
        }
        canvas.save()
        canvas.clipOutPath(front)
        try {
            draw(canvas)
        } finally {
            canvas.restore()
        }
    }

    private fun drawOverlayGroup(group: Int, canvas: Canvas) {
        when (group) {
            GROUP_RINGS -> swept(canvas) { drawPendingRings(it) }
            GROUP_OVER -> swept(canvas) { drawPendingOver(it) }
            GROUP_BUTTONS -> swept(canvas) { drawPendingButtons(it) }
            GROUP_TIP -> drawCameraTip(canvas)
            GROUP_LOCK -> { drawAodVeil(canvas); drawTableGlow(canvas); drawLockOverlay(canvas); drawAodSliderVeil(canvas) }
            GROUP_NOTICE -> drawNotice(canvas, noticeSlideHeld, SystemClock.elapsedRealtime())
            else -> drawCharge(canvas)
        }
    }

    /**
     * The ripple's leading edge this frame, or null when it is not sweeping.
     *
     * The overlay pass runs on the outer canvas, after the scene node has been drawn and
     * outside every clip that was set while recording it. That is right for the notice
     * and the charger — they are why the panel is awake and should be readable whatever
     * the readout is doing — and wrong for the glass, which IS readout furniture: the
     * transport, the camera button, the gamepad itself. Left unclipped they hovered over
     * the ambient screen while the sweep passed underneath them.
     */
    private var sweepFront: android.graphics.Path? = null

    /** Which overlays have something to draw this frame, in the order they stack. */
    private val overlayGroups = ArrayList<Int>(4)

    /**
     * Draw one overlay over everything so far, and hand back the result as the next
     * backdrop.
     *
     * The node holds the previous backdrop and then the overlay on top, so what comes
     * next bends both. [paneBackdrop] is what drawGlassPane samples by default, and it
     * points at the PREVIOUS node while this one is being recorded — never at this one.
     */
    private inline fun layered(
        backdrop: android.graphics.RenderNode,
        body: (Canvas) -> Unit,
    ): android.graphics.RenderNode {
        val out = nextNode()
        out.setRenderEffect(null)
        out.setPosition(0, 0, Panel.WIDTH, Panel.HEIGHT)
        val into = out.beginRecording(Panel.WIDTH, Panel.HEIGHT)
        paneBackdrop = backdrop
        try {
            into.drawRenderNode(backdrop)
            body(into)
        } finally {
            paneBackdrop = sceneNode
            out.endRecording()
        }
        return out
    }

    /** What a pane bends when it is not given something of its own. */
    private var paneBackdrop = sceneNode

    /** The strip's slide, taken during the scene pass so the second pass cannot re-advance it. */
    private var noticeSlideHeld = 0f

    /**
     * Draw a rounded rectangle as a piece of glass over the panel.
     *
     * Returns false when it cannot — no scene to sample, or a canvas with no render nodes
     * — so the caller can fall back to drawing the element flat rather than to drawing
     * nothing at all. Every pane on this panel is something the user needs to see.
     *
     * WHAT ACTUALLY HAPPENS, in order: the scene is drawn into a node the size of the
     * pane and shifted so the pane's own top-left is that node's origin; the node is given
     * a blur and then the refraction, so the bending acts on softened content rather than
     * on hard edges; and the result is drawn clipped to the pane's shape. Then a tint for
     * body and a lit rim for an edge, because a lens over a mostly-black panel is
     * otherwise invisible — see Glass.
     */
    private fun drawGlassPane(
        canvas: Canvas,
        rect: android.graphics.RectF,
        radius: Float,
        tint: Int = Glass.TINT,
        /**
         * The shape to fill, when it is not the rounded rectangle itself.
         *
         * For a ring or a partly-filled bar, [rect] is still what the lens is computed
         * against — it is the pane's extent — but only part of it is glass. A ring's
         * annulus cannot be expressed as a rounded rect, and giving one its bounding box
         * would put a pane of glass across both camera lenses.
         */
        shape: android.graphics.Path? = null,
        /**
         * Whether to bend the backdrop as well as blur and tint it.
         *
         * Off for shapes that are not rounded rectangles. The lens is a rounded-rect
         * distance field — that is the whole of how it knows where its edges are — so on
         * an arc it bends toward edges the shape does not have, and the result is a smear
         * rather than a lens. Blur and tint alone still read as glass; they are most of
         * what glass over a dark panel does anyway.
         */
        refract: Boolean = true,
        /** The lit rim. Off where the shape's own outline is not the pane's outline. */
        rim: Boolean = true,
        /**
         * What the pane bends, when it is not the whole panel.
         *
         * THE REFERENCE SLIDER DOES EXACTLY THIS. Its track is published as its own
         * backdrop layer and the thumb's backdrop is that layer combined with the page
         * behind — because a thumb is not lensing the wallpaper, it is lensing the track
         * it is riding. Which also solves the ordering problem here: a bar is drawn inside
         * the scene recording, so it can never sample the scene, but it can perfectly well
         * sample a node holding its own track.
         */
        source: android.graphics.RenderNode = paneBackdrop,
        /**
         * The HELD look, for thumbs.
         *
         * In the reference a thumb at rest is blurred and barely lensed; pressed and held,
         * the blur goes to nothing and the lens comes fully on with chromatic aberration
         * and an ambient rim. That is the state borrowed here, at rest, because these
         * thumbs are never actually held — they are readings. One that permanently looked
         * mid-gesture would be a lie; one that permanently looks like the control's most
         * solid state is simply what the control is.
         */
        held: Boolean = false,
        /** How hard this pane splits light. See Glass.ABERRATION. */
        aberration: Float = Glass.ABERRATION,
    ): Boolean {
        val w = rect.width()
        val h = rect.height()
        if (w < 1f || h < 1f) return false

        // GLASS WITH NOTHING BEHIND IT IS STILL GLASS, and this is the common case rather
        // than a failure. Only the OVERLAYS can bend the panel: the scene is recorded and
        // then drawn, so anything drawn inside that recording — the bars, the rings, the
        // discs behind the transport — is being drawn INTO the very node a pane would have
        // to sample, and it does not exist yet.
        //
        // Rather than dropping those back to flat paint, they get what a pane looks like
        // over nothing: a translucent body and a lit edge. That is not a consolation
        // prize — it is most of what glass over a dark panel does anyway, since bending
        // black gives you black. The refraction is worth having where there is something
        // to bend, and that is exactly where it happens: the notification cards and the
        // unlock slider, both overlays, both lying over the readout and the record.
        // A NODE INSIDE THE SCENE CANNOT SAMPLE THE SCENE. That is a cycle — the node is
        // in the scene's display list and the scene would be in its — and the renderer
        // does not detect it: it walks the tree to prepare it and recurses until the
        // stack goes. Measured as a SIGSEGV on the RenderThread with five hundred frames
        // of prepareTreeImpl, which is not an obvious way for a missing fallback to
        // present itself.
        //
        // The in-scene elements each pass a layer of their own and fall back to the scene
        // when they have none. That fallback is the trap, so it is caught here rather
        // than at four call sites: no layer means no lens, and no lens is a look this
        // already has.
        val recorder = canvas as? android.graphics.RecordingCanvas
        if (recorder == null || !source.hasDisplayList() ||
            (source === sceneNode && recordingScene)
        ) {
            return drawUnbackedGlass(canvas, rect, radius, tint, shape, rim)
        }

        val paneNode = nextNode()
        paneNode.setPosition(
            Math.round(rect.left), Math.round(rect.top),
            Math.round(rect.right), Math.round(rect.bottom),
        )
        val pane = paneNode.beginRecording()
        try {
            // The backdrop, shifted so that the pane's top-left is this node's origin —
            // which is the coordinate space the shader works in.
            //
            // WHOSE top-left, though. A pane's rect is in whatever space the canvas is in
            // when it is drawn, and its source may be in a different one. The overlay
            // panes are drawn at the top level, so the two agree and this never mattered;
            // a pane drawn INSIDE a transform does not, and the difference is not subtle.
            //
            // An account card is drawn inside the page scroll. Shifted by its local rect,
            // it samples the ground at the position it WOULD have without the scroll — so
            // the picture inside the glass travels with the card instead of staying where
            // the picture is, and the card looks like a photograph pasted into it rather
            // than a window onto one. It stops being glass the moment anything moves.
            //
            // Only for the root-space sources. A local backdrop is recorded in the pane's
            // own space by definition, and mapping it would break the thing that works.
            //
            // ASKED OF THE NODE, not listed by name. This named the two root-space nodes
            // it knew about, and missed the ones the overlay pass builds: every layer in
            // that chain covers the whole panel at the origin exactly as the scene does —
            // see layered — so a pane drawn inside a transform and sampling one of THOSE
            // had the same frozen backdrop this branch exists to prevent. A node that
            // spans the panel from its origin is root-space whatever it is called.
            if (rootSpace(source)) {
                canvas.getMatrix().mapRect(paneAbs, rect)
                pane.translate(-paneAbs.left, -paneAbs.top)
            } else {
                pane.translate(-rect.left, -rect.top)
            }
            pane.drawRenderNode(source)
        } finally {
            paneNode.endRecording()
        }

        val short = minOf(w, h)
        // ONE LENS, EVERY PANE — see Glass. Sized as a fraction of the short side and
        // then capped, because a fraction alone is right for a twenty-unit thumb and a
        // fisheye on a card six times that.
        //
        // THE PLAIN LENS WHEN NOTHING IS BEING SPLIT. The dispersion shader takes seven
        // samples of the backdrop per pixel and, with the aberration at zero, they all
        // land on the same coordinate — the same answer for seven times the sampling, on
        // an always-on display that redraws at 120Hz whenever anything moves.

        // Blur FIRST, then bend. createChainEffect applies its second argument first, so
        // this reads backwards and is the right way round: bending a sharp backdrop
        // smears whatever it lands on into a streak, where bending a soft one is a lens.
        // CLEAR GLASS, NEVER FROSTED. There was a blur under the lens at first, which is
        // what the reference does to a thumb at REST — and it is the wrong half of that
        // component to have copied. A blur is what a surface does when you cannot see
        // through it; this panel's whole appeal is that you can, and the readout bending
        // through a notification card is worth more than the card looking soft. The
        // reference itself fades the blur out the moment its thumb is held, which is the
        // state everything here is drawn in.
        //
        // It also costs nothing to remove: the blur was a second full-size pass over
        // every pane, on a display that redraws at 120Hz while anything is moving.
        paneNode.setRenderEffect(if (refract) lensFor(w, h, radius, aberration) else null)

        canvas.save()
        if (shape != null) {
            canvas.clipPath(shape)
        } else {
            paneClip.rewind()
            paneClip.addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW)
            canvas.clipPath(paneClip)
        }
        recorder.drawRenderNode(paneNode)

        // Body. Glass is not clear, and a wholly clear pane on a panel that is largely
        // black is an invisible one — this is what a notification's text is read against.
        // A pane with no body draws none. Thumbs and buttons are held glass — see
        // Glass.THUMB_TINT — and painting a transparent rectangle over the lens is not
        // free on a display that redraws at 120Hz.
        if (tint ushr 24 != 0) {
            panePaint.style = Paint.Style.FILL
            panePaint.shader = null
            panePaint.color = tint
            if (shape != null) canvas.drawPath(shape, panePaint)
            else canvas.drawRoundRect(rect, radius, radius, panePaint)
        }
        canvas.restore()

        if (!rim) return true

        // The rim, over the top and outside the clip so it is not cut in half by it.
        //
        // AMBIENT when held: lit on one side and shaded on the other, with no colour of
        // its own. That is the difference between a sheet of glass, which has an edge,
        // and a bevelled object, which has a lit side.
        val edge = if (held) Glass.ambient else Glass.highlight
        edge.setFloatUniform("size", w, h)
        edge.setFloatUniform("cornerRadii", radius, radius, radius, radius)
        if (!held) edge.setColorUniform("color", Glass.LIGHT_COLOR)
        edge.setFloatUniform("angle", Glass.LIGHT_ANGLE)
        edge.setFloatUniform("falloff", Glass.LIGHT_FALLOFF)
        panePaint.shader = edge
        panePaint.alpha = if (held) (255 * Glass.AMBIENT_ALPHA).toInt() else 255
        panePaint.style = Paint.Style.STROKE
        val rw = if (held) Glass.THUMB_RIM_WIDTH else Glass.RIM_WIDTH
        panePaint.strokeWidth = rw
        if (shape != null) {
            // Stroked round the shape itself, so a pane with a corner of its own — the
            // notification card's, which follows the display's curve — is edged where it
            // actually is rather than round the rounded rectangle it is not.
            //
            // THE SHADER MOVES, NOT THE CANVAS. The rim is computed in the pane's own
            // space (0..w, 0..h) while the path is in the panel's, so translating the
            // canvas would move both and change nothing. A local matrix on the shader
            // offsets one without the other.
            rimShift.setTranslate(rect.left, rect.top)
            edge.setLocalMatrix(rimShift)
            canvas.drawPath(shape, panePaint)
            edge.setLocalMatrix(null)
        } else {
            canvas.save()
            canvas.translate(rect.left, rect.top)
            canvas.drawRoundRect(
                rw / 2f, rw / 2f, w - rw / 2f, h - rw / 2f, radius, radius, panePaint,
            )
            canvas.restore()
        }
        panePaint.shader = null
        panePaint.alpha = 255
        panePaint.style = Paint.Style.FILL
        return true
    }

    /**
     * A pane with no backdrop to sample: body and edge, no lens.
     *
     * Returns false only on a canvas that cannot draw the rim's shader at all, which
     * leaves the caller to paint the element flat — a bar nobody can see is worse than a
     * bar that is not glass.
     */
    private fun drawUnbackedGlass(
        canvas: Canvas,
        rect: android.graphics.RectF,
        radius: Float,
        tint: Int,
        shape: android.graphics.Path?,
        rim: Boolean,
    ): Boolean {
        if (!canvas.isHardwareAccelerated) return false
        if (tint ushr 24 != 0) {
            panePaint.shader = null
            panePaint.style = Paint.Style.FILL
            panePaint.color = tint
            if (shape != null) canvas.drawPath(shape, panePaint)
            else canvas.drawRoundRect(rect, radius, radius, panePaint)
        }
        if (!rim) return true

        val edge = Glass.highlight
        edge.setFloatUniform("size", rect.width(), rect.height())
        edge.setFloatUniform("cornerRadii", radius, radius, radius, radius)
        edge.setColorUniform("color", Glass.LIGHT_COLOR)
        edge.setFloatUniform("angle", Glass.LIGHT_ANGLE)
        edge.setFloatUniform("falloff", Glass.LIGHT_FALLOFF)
        canvas.save()
        canvas.translate(rect.left, rect.top)
        panePaint.shader = edge
        panePaint.style = Paint.Style.STROKE
        panePaint.strokeWidth = Glass.RIM_WIDTH
        canvas.drawRoundRect(
            Glass.RIM_WIDTH / 2f, Glass.RIM_WIDTH / 2f,
            rect.width() - Glass.RIM_WIDTH / 2f, rect.height() - Glass.RIM_WIDTH / 2f,
            radius, radius, panePaint,
        )
        canvas.restore()
        panePaint.shader = null
        panePaint.style = Paint.Style.FILL
        return true
    }

    /**
     * The lens for a pane of this shape, built once and kept.
     *
     * THIS WAS THE LEAK. RenderEffect.createRuntimeShaderEffect was called per pane per
     * frame — fifteen panes at up to 120Hz, so getting on for two thousand a second. Each
     * one is a tiny Java object in front of a large native allocation, which is the worst
     * shape an object can have: the collector sees almost nothing worth collecting and
     * lets them accumulate, so the native heap climbed by roughly a hundred megabytes
     * every fifteen seconds and the phone got hot holding them.
     *
     * It was never necessary. Every uniform the lens takes — size, corner radii,
     * refraction height and amount, aberration — is a function of the pane's SHAPE. None
     * of them is a function of where it is. A bar's thumb is the same lens at every
     * reading; it simply gets drawn somewhere else. So one effect per distinct shape is
     * all this ever needed, and in a steady frame every lookup is a hit.
     *
     * Quantised to whole units because a lens a fraction of a unit wider is the same lens
     * to look at, and because a swelling thumb would otherwise mint a new one per frame
     * of its own animation — which is the leak again, wearing a hat.
     */
    private fun lensFor(
        w: Float,
        h: Float,
        radius: Float,
        aberration: Float,
    ): android.graphics.RenderEffect {
        val key = (Math.round(w).toLong() shl 40) or
            (Math.round(h).toLong() shl 24) or
            (Math.round(radius).toLong() shl 8) or
            Math.round(aberration * 8f).toLong().coerceIn(0L, 255L)
        lensCache[key]?.let { return it }

        val short = minOf(w, h)
        // ONE LENS, EVERY PANE — see Glass. Sized as a fraction of the short side and
        // then capped, because a fraction alone is right for a twenty-unit thumb and a
        // fisheye on a card six times that.
        //
        // THE PLAIN LENS WHEN NOTHING IS BEING SPLIT. The dispersion shader takes seven
        // samples of the backdrop per pixel and, with the aberration at zero, they all
        // land on the same coordinate — the same answer for seven times the sampling, on
        // an always-on display that redraws at 120Hz whenever anything moves.
        val shader = if (aberration > 0f) Glass.dispersion else Glass.refraction
        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("offset", 0f, 0f)
        shader.setFloatUniform("cornerRadii", radius, radius, radius, radius)
        shader.setFloatUniform(
            "refractionHeight", minOf(short * Glass.HEIGHT, Glass.HEIGHT_MAX),
        )
        // Negative: the library negates the amount on the way in, and the sign is which
        // way the backdrop is pushed. Positive here turns a lens into a pinch.
        shader.setFloatUniform(
            "refractionAmount", -minOf(short * Glass.AMOUNT, Glass.AMOUNT_MAX),
        )
        shader.setFloatUniform("depthEffect", Glass.DEPTH)
        if (aberration > 0f) shader.setFloatUniform("chromaticAberration", aberration)

        val lens = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content")
        // A CEILING, and a blunt one. The panel has a few dozen distinct pane shapes in
        // normal use; anything past that is a control mid-animation minting shapes it
        // will not ask for again, and holding those is the same mistake in slow motion.
        if (lensCache.size >= LENS_CACHE_MAX) lensCache.clear()
        lensCache[key] = lens
        return lens
    }

    private val lensCache = HashMap<Long, android.graphics.RenderEffect>()

    /**
     * Magnify the panel through a body of liquid, clipped to the liquid's own shape.
     *
     * The charging cell is a glass with something IN it, and the one thing this panel
     * could never say was which part of the cell was full — the liquid was a tint, and a
     * tint is a colour rather than a substance. A volume of liquid is a lens: what is
     * behind it steps as it crosses the surface. That step is the tell, and it costs one
     * pane to say.
     *
     * [shape] is the liquid, surface and all, so the magnification ends exactly at the
     * meniscus rather than at the cell's edge — which is the difference between a glass
     * with liquid in it and a glass someone put a lens behind.
     */
    private fun drawMagnified(
        canvas: Canvas,
        rect: android.graphics.RectF,
        shape: android.graphics.Path,
        zoom: Float,
    ): Boolean {
        val w = rect.width()
        val h = rect.height()
        if (w < 1f || h < 1f) return false
        val recorder = canvas as? android.graphics.RecordingCanvas ?: return false
        val source = paneBackdrop
        // The same cycle guard the panes have: a node inside the scene recording cannot
        // sample the scene. See drawGlassPane.
        if (!source.hasDisplayList() || (source === sceneNode && recordingScene)) return false

        val node = nextNode()
        node.setPosition(
            Math.round(rect.left), Math.round(rect.top),
            Math.round(rect.right), Math.round(rect.bottom),
        )
        val into = node.beginRecording()
        try {
            into.translate(-rect.left, -rect.top)
            into.drawRenderNode(source)
        } finally {
            node.endRecording()
        }
        node.setRenderEffect(magnifierFor(w, h, zoom))

        canvas.save()
        canvas.clipPath(shape)
        recorder.drawRenderNode(node)
        canvas.restore()
        return true
    }

    /** The magnifier for a vessel this shape, kept for the same reason lenses are. */
    private fun magnifierFor(w: Float, h: Float, zoom: Float): android.graphics.RenderEffect {
        val key = (Math.round(w).toLong() shl 40) or
            (Math.round(h).toLong() shl 24) or
            Math.round(zoom * 64f).toLong().coerceIn(0L, 65535L)
        magnifierCache[key]?.let { return it }
        val shader = Glass.magnifier
        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("zoom", zoom)
        val effect = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content")
        if (magnifierCache.size >= LENS_CACHE_MAX) magnifierCache.clear()
        magnifierCache[key] = effect
        return effect
    }

    private val magnifierCache = HashMap<Long, android.graphics.RenderEffect>()

    /**
     * Draw something into a node of its own, and hand the node back to be lensed.
     *
     * This is the reference component's `layerBackdrop`: the track publishes itself as a
     * layer so the thumb has something local to bend. Everything drawn in [body] appears
     * on the canvas exactly as it would have — the node is drawn straight back — and is
     * additionally available as a backdrop.
     *
     * Returns null on a canvas with no render nodes, where the caller should simply draw
     * the contents itself.
     */
    private inline fun localBackdrop(
        canvas: Canvas,
        bounds: android.graphics.RectF,
        /**
         * Whether the layer is also drawn where it was recorded.
         *
         * Usually yes — a track publishes itself and appears. The exception is the tabs'
         * trick, which the unlock slider borrows: a second copy of the content in the
         * accent colour, kept INVISIBLE and used only as the indicator's backdrop, so
         * that what the indicator passes over is revealed in that colour and nothing
         * else changes.
         */
        visible: Boolean = true,
        body: (Canvas) -> Unit,
    ): android.graphics.RenderNode? {
        val recorder = canvas as? android.graphics.RecordingCanvas ?: return null
        if (bounds.width() < 1f || bounds.height() < 1f) return null
        val backdropNode = nextNode()
        // Cleared, because this node may have been a PANE last frame and a render effect
        // outlives the recording. A track drawn through a leftover refraction is a track
        // that bends for no reason.
        backdropNode.setRenderEffect(null)
        backdropNode.setPosition(
            Math.round(bounds.left), Math.round(bounds.top),
            Math.round(bounds.right), Math.round(bounds.bottom),
        )
        val into = backdropNode.beginRecording()
        try {
            into.translate(-bounds.left, -bounds.top)
            body(into)
        } finally {
            backdropNode.endRecording()
        }
        if (visible) recorder.drawRenderNode(backdropNode)
        return backdropNode
    }

    private val paneClip = android.graphics.Path()
    private val panePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * A round pane behind something that can be pressed.
     *
     * THE PANEL'S TARGETS BECOME OBJECTS UNDER GLASS. A glyph alone is a mark on a
     * surface, and a mark does not look like it can be pressed — which matters more here
     * than on a phone screen, because the finger is on the far side of the device and
     * cannot see what it is approaching. A disc gives the target a body and an edge.
     *
     * A circle is a rounded rectangle whose radius is half its side, so the lens needs no
     * special case: the distance field is exact for it.
     */
    private fun drawGlassDisc(canvas: Canvas, cx: Float, cy: Float, r: Float): Boolean {
        discRect.set(cx - r, cy - r, cx + r, cy + r)
        // HELD, like a thumb. A button and a held thumb are the same object here — a
        // small piece of glass with something on it — so they are made the same way.
        return drawGlassPane(canvas, discRect, r, Glass.DISC_TINT, held = true)
    }

    /**
     * A target as a piece of glass with its glyph inside it.
     *
     * THE GLYPH IS THE BUTTON'S BACKDROP, which is what makes a button and a thumb the
     * same object. A thumb lenses the track it rides; a button has no track, and for a
     * while that meant it had nothing to bend and came out as a bare rim — the one pane
     * on the panel with no lens in it, which is exactly what it looked like.
     *
     * Everything drawn inside the scene has this problem: the scene is being recorded, so
     * it cannot also be sampled. The answer is the same one the sliders use — give the
     * element a layer of its own — and for a button the obvious layer is the mark it
     * holds. The glyph goes into the node, the pane goes over it, and the lens bends the
     * glyph's edges the way it bends a track's. The middle passes straight through, so
     * the mark stays legible; only the outer ring distorts, which is where a real piece
     * of glass distorts too.
     *
     * Outside GLASS the glyph is simply drawn, so call sites read the same in both.
     */
    private inline fun glassButton(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        glyphH: Float,
        /**
         * The disc's own size, when it should not follow the glyph inside it.
         *
         * The camera is a 30-unit mark beside 38-unit transport marks, and sizing every
         * disc from its own glyph made it a visibly smaller button in a row of buttons —
         * which reads as a lesser control rather than as a smaller picture. A row of
         * targets should be a row of the same target.
         */
        discH: Float = glyphH,
        /**
         * How present the button is, for a page that is still arriving.
         *
         * Both the disc and its mark are laid down in the overlay pass, so neither
         * inherits whatever the caller was fading when it asked for the button — this is
         * the only thing that reaches them. It now covers the pair: they are drawn into
         * one layer at this alpha, which is what stops a disc arriving at full strength
         * ahead of the icon that belongs to it.
         */
        alpha: Float = 1f,
        noinline glyph: (Canvas) -> Unit,
    ) {
        if (!config.theme.isGlass) {
            glyph(canvas)
            return
        }
        val r = discH * DISC_RADIUS

        // THE GLYPH GOES OVER THE GLASS, and it used to go under it.
        //
        // What a button bends is what it is sitting ON — the scene, which is being
        // recorded right now — so the disc still cannot be drawn until that recording is
        // finished and can be sampled. That much is unchanged, and it is why a button's
        // glass is deferred at all: a layer holding only the glyph gave the lens nothing
        // to bend, because the lens works a band around the pane's EDGE and around a
        // 27-unit disc that band is the outer 21, which on a glyph-only layer is empty.
        //
        // What changed is which side of that glass the mark ends up on. Drawn into the
        // scene it went under the pane AND under the pane's tint, so the tint recoloured
        // it — and once the luminance was corrected to oppose its backdrop, that tint
        // became a white wash over a dark panel instead of a black one, which is when it
        // stopped being subtle. A mark under glass is a nice idea about buttons and a bad
        // one about legibility. The glyph is carried with the disc now and laid down
        // straight after it, the way the dial's own labels are — see deferOver.
        if (alpha <= 0.004f) return
        discRect.set(cx - r, cy - r, cx + r, cy + r)
        val m = canvas.getMatrix()
        m.mapRect(pendingRect, discRect)
        // THE CLIP TRAVELS WITH IT TOO. The readout's media row turns on a drum and is
        // clipped to the band it has emerged into; drawn later and unclipped, a disc
        // stood clear of a row that was still half under its own edge.
        val bounds = android.graphics.Rect()
        val clip = android.graphics.RectF()
        if (canvas.getClipBounds(bounds)) {
            clip.set(bounds)
            m.mapRect(clip)
        } else {
            clip.set(0f, 0f, Panel.WIDTH.toFloat(), Panel.HEIGHT.toFloat())
        }
        pendingButtons.add(
            PendingButton(
                android.graphics.RectF(pendingRect.left, pendingRect.top,
                    pendingRect.right, pendingRect.bottom),
                clip,
                alpha,
                android.graphics.Matrix(m),
                glyph,
            ),
        )
    }

    /** A button whose glass and mark are both still to be laid down. See glassButton. */
    private class PendingButton(
        val rect: android.graphics.RectF,
        val clip: android.graphics.RectF,
        val alpha: Float,
        /**
         * The transform the glyph was going to be drawn under.
         *
         * Kept for the same reason [rect] is mapped rather than recomputed: the offsets
         * in play are scattered across half a dozen locals — the drift, the page scroll,
         * the notice push, the media row's own drum — and a sum copied out of them is a
         * sum that will drift from them.
         */
        val matrix: android.graphics.Matrix,
        val glyph: (Canvas) -> Unit,
    )

    /**
     * Buttons whose glass is still to be laid over them, in panel coordinates.
     *
     * Noted during the scene and drawn in the overlay pass — see glassButton. Mapped
     * through the canvas's own matrix rather than reconstructed from the offsets in play,
     * because those are scattered across half a dozen locals (the drift, the page scroll,
     * the notice push) and a copy of that sum is a copy that will drift from it.
     */
    private val pendingButtons = ArrayList<PendingButton>(8)
    private val pendingRect = android.graphics.RectF()

    /** Lay the glass over every button noted during the scene. */
    private fun drawPendingButtons(canvas: Canvas) {
        for (b in pendingButtons) {
            canvas.save()
            canvas.clipRect(b.clip)
            val fading = b.alpha < 0.999f
            if (fading) canvas.saveLayerAlpha(b.rect, (255 * b.alpha).toInt())
            // THE TINT COMES FROM THE BACKDROP rather than from the button. A disc with
            // no tint of its own is clear glass, which is what these are meant to be and
            // what they stop being at the ends of the range: over black there is nothing
            // to see through them and the disc disappears, over a bright photograph they
            // are a hole with a rim. See GlassLuminance.clearTint, which gives them
            // nothing at all in the middle, where they already work.
            drawGlassPane(
                canvas, b.rect, b.rect.height() / 2f, clearReactive(b.rect), held = true,
                aberration = Glass.BUTTON_ABERRATION,
            )
            // AND THE MARK ON TOP OF IT, under the transform it was noted with, inside
            // the same fading layer so the two arrive together rather than a disc showing
            // up ahead of its own icon.
            canvas.save()
            canvas.concat(b.matrix)
            b.glyph(canvas)
            canvas.restore()
            if (fading) canvas.restore()
            canvas.restore()
        }
    }

    /** Each wave's journey out of the cone, this frame. See the speaker. */
    private val waveOut = FloatArray(WAVE_MAX)

    /**
     * A ring of glass whose band is still to be laid over the panel.
     *
     * DEFERRED FOR THE SAME REASON THE BUTTONS ARE, and it matters more here. A ring is
     * drawn early, underneath the readout, and anything drawn inside the scene recording
     * can only bend what it publishes for itself — which for a ring is its own track, a
     * hairline. Held back until the scene is finished, the band bends the whole panel:
     * the background picture, the bars it crosses, the record behind it.
     */
    private class PendingRing(
        val cx: Float,
        val cy: Float,
        val radius: Float,
        val thickness: Float,
        val clip: android.graphics.RectF,
    )

    private val pendingRings = ArrayList<PendingRing>(4)

    /** Note a ring's band now, and lay its glass over the finished panel. */
    private fun deferRing(canvas: Canvas, cx: Float, cy: Float, radius: Float, thickness: Float) {
        if (!config.theme.isGlass || !recordingScene) return
        if (radius < 1f || thickness < 0.5f) return
        val m = canvas.getMatrix()
        ringPoint[0] = cx
        ringPoint[1] = cy
        m.mapPoints(ringPoint)
        // The radius travels through the same matrix. Only translation and a uniform
        // scale are ever in play here, so one axis answers for both.
        val scale = m.mapRadius(1f)
        val bounds = android.graphics.Rect()
        val clip = android.graphics.RectF()
        if (canvas.getClipBounds(bounds)) {
            clip.set(bounds)
            m.mapRect(clip)
        } else {
            clip.set(0f, 0f, Panel.WIDTH.toFloat(), Panel.HEIGHT.toFloat())
        }
        pendingRings.add(
            PendingRing(ringPoint[0], ringPoint[1], radius * scale, thickness * scale, clip),
        )
    }

    /**
     * Something that must be drawn OVER the glass rather than under it.
     *
     * The ring bands are held back to the overlay pass so they can bend the finished
     * panel, which puts them on top of everything drawn in the scene — including the
     * labels that belong to those very rings. A time and a volume read through a lens are
     * not a reading, they are a texture.
     *
     * So the label goes with them, one step later. The canvas matrix travels too: the
     * rings are drawn in their own frame and the overlay pass has no idea about it, so the
     * transform in force when the label was deferred is the transform it is replayed
     * under.
     */
    private class PendingOver(
        val matrix: android.graphics.Matrix,
        val draw: (Canvas) -> Unit,
    )

    private val pendingOver = ArrayList<PendingOver>(4)

    /** Returns false when there is no overlay pass, and the caller should just draw. */
    private fun deferOver(canvas: Canvas, draw: (Canvas) -> Unit): Boolean {
        if (!config.theme.isGlass || !recordingScene) return false
        pendingOver.add(PendingOver(android.graphics.Matrix(canvas.getMatrix()), draw))
        return true
    }

    private fun drawPendingOver(canvas: Canvas) {
        for (o in pendingOver) {
            canvas.save()
            // SET, not concat: the matrix was recorded in full at deferral time, page
            // offset included. Concatenating it onto a canvas that already carries the
            // page offset applied that offset twice, and the glass clock and weather line
            // scrolled faster than everything around them.
            canvas.setMatrix(o.matrix)
            o.draw(canvas)
            canvas.restore()
        }
    }

    private val ringPoint = FloatArray(2)
    private val ringRect = android.graphics.RectF()

    /** Lay the glass over every ring noted during the scene. */
    private fun drawPendingRings(canvas: Canvas) {
        val recorder = canvas as? android.graphics.RecordingCanvas ?: return
        val source = paneBackdrop
        if (!source.hasDisplayList() || (source === sceneNode && recordingScene)) return
        // The bands are glass, and always-on keeps no glass on the rings.
        val glass = 1f - aodFade(frameNowMs)
        if (glass <= 0.004f) return
        for (r in pendingRings) {
            val half = r.thickness / 2f
            // Room for the feathered edge on both sides — see the shader.
            val outer = r.radius + half + RING_FEATHER
            ringRect.set(r.cx - outer, r.cy - outer, r.cx + outer, r.cy + outer)
            val node = nextNode()
            node.setPosition(
                Math.round(ringRect.left), Math.round(ringRect.top),
                Math.round(ringRect.right), Math.round(ringRect.bottom),
            )
            val into = node.beginRecording()
            try {
                into.translate(-ringRect.left, -ringRect.top)
                into.drawRenderNode(source)
            } finally {
                node.endRecording()
            }
            node.setRenderEffect(
                ringLensFor(
                    r.cx - ringRect.left, r.cy - ringRect.top, r.radius, half,
                    clearReactive(ringRect),
                ),
            )
            canvas.save()
            canvas.clipRect(r.clip)
            if (glass < 0.996f) canvas.saveLayerAlpha(ringRect, (255 * glass).toInt())
            recorder.drawRenderNode(node)
            // The two edges, lit. A lens with no edge has nowhere to start and reads as a
            // smear in the panel rather than a thing lying on it.
            vinylPaint.style = Paint.Style.STROKE
            vinylPaint.strokeWidth = Glass.RING_RIM_WIDTH
            vinylPaint.color = (Glass.RING_RIM_ALPHA shl 24) or 0x00FFFFFF
            canvas.drawCircle(r.cx, r.cy, r.radius + half, vinylPaint)
            canvas.drawCircle(r.cx, r.cy, r.radius - half, vinylPaint)
            vinylPaint.style = Paint.Style.FILL
            if (glass < 0.996f) canvas.restore()
            canvas.restore()
        }
    }

    /**
     * The lens for a band of this geometry, kept for the same reason every other one is.
     *
     * A ring's geometry does not move between frames — the rings are fixed furniture — so
     * in practice this is built once each and hit forever after. Quantised anyway, because
     * the volume ring's thumb swells and a geometry that crept would mint an effect a
     * frame, which is the native-heap leak in another costume.
     */
    private fun ringLensFor(
        cx: Float,
        cy: Float,
        radius: Float,
        half: Float,
        tint: Int,
    ): android.graphics.RenderEffect {
        val key = (Math.round(cx).toLong() shl 45) or
            (Math.round(cy).toLong() shl 30) or
            (Math.round(radius).toLong() shl 15) or
            Math.round(half * 4f).toLong().coerceIn(0L, 32767L) xor
            (tint.toLong() shl 20)
        ringLensCache[key]?.let { return it }
        val shader = Glass.ringLens
        shader.setFloatUniform("center", cx, cy)
        shader.setFloatUniform("ringRadius", radius)
        shader.setFloatUniform("halfThickness", half)
        val short = half * 2f
        shader.setFloatUniform(
            "refractionHeight", minOf(short * Glass.HEIGHT, Glass.HEIGHT_MAX),
        )
        shader.setFloatUniform(
            "refractionAmount", -minOf(short * Glass.AMOUNT, Glass.AMOUNT_MAX),
        )
        shader.setColorUniform("tint", tint)
        val effect =
            android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content")
        if (ringLensCache.size >= LENS_CACHE_MAX) ringLensCache.clear()
        ringLensCache[key] = effect
        return effect
    }

    private val ringLensCache = HashMap<Long, android.graphics.RenderEffect>()

    private val cardRect = android.graphics.RectF()
    private val extrasRect = android.graphics.RectF()

    /** A pane's rect in root coordinates, for sampling a root-space backdrop. */
    private val paneAbs = android.graphics.RectF()

    /** Whether [node] covers the whole panel from its origin, and so is in root space. */
    private fun rootSpace(node: android.graphics.RenderNode): Boolean =
        node.left == 0 && node.top == 0 &&
            node.width == width && node.height == height

    private val discRect = android.graphics.RectF()
    private val barRect = android.graphics.RectF()
    private val trackRect = android.graphics.RectF()
    private val shapePath = android.graphics.Path()

    private fun drawFrame(canvas: Canvas, overlays: Boolean) {
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
        // Recorded here, before the ground or the scene opens a recording of its own.
        updateParallax()
        tickSilk()
        weatherReady = prepareWeather()

        // NOT GATED ON `hidden`, and that is the point of it. The gamepad takes the
        // readout away — the bars, the rings, the record, the mark — because that screen
        // is going to be something else. It does not take away the colour or the
        // photograph the user chose to look at: those are the panel's ground, not its
        // contents, and a background that disappears along with the thing standing on it
        // was never a background.
        run {
            // Hue and opacity ease SEPARATELY, and they have to: ColorTween runs through
            // RampColor.lerp, which forces alpha to 0xFF so a ramp stop or an accent can
            // never be saved half-invisible. Alpha therefore gets a Tween of its own —
            // the same curve, the same duration, so switching the background off reads
            // as one movement rather than a colour easing while a transparency snaps.
            // FULL, ALWAYS. There used to be a switch for this and it earned its
            // removal twice over: it did nothing a user could see, and the one thing it
            // could have done — take the ground to nothing — is the documented way to
            // lose the display to MIUI's SubScreenLauncher. A setting whose only
            // reachable states are "no change" and "break the panel" is not a setting.
            val alpha = animateTo("bg:alpha", 255f)
            // always-on rests on true black: an OLED spends nothing on it
            val hue = androidx.core.graphics.ColorUtils.blendARGB(
                colorFor("bg", config.background or (0xFF shl 24)), 0xFF000000.toInt(), aodFade(frameNowMs),
            )
            val ground = (hue and 0x00FFFFFF) or (alpha.toInt().coerceIn(0, 255) shl 24)

            // THE GROUND, PUBLISHED AS WELL AS PAINTED.
            //
            // A pane that sits BEHIND the readout cannot be deferred to the overlay pass
            // the way the buttons and the rings are — that is what puts them on top of
            // everything — and a pane drawn inside the scene can only bend what already
            // exists when it is drawn. Which, this early, is exactly the ground and the
            // picture on it, and that is precisely what an account card wants to refract.
            //
            // So the two of them go into a node of their own and are drawn back
            // immediately. It costs one display list holding two operations; everything
            // that wants to be glass over the background can now sample it.
            // HELD, NOT PAINTED, when there is glass that wants to bend it. The record
            // goes into the same node a little further down — see groundNode — and the
            // whole thing is drawn once, there.
            groundColor = ground
        }

        // THE GROUND IS NOT SWEPT AWAY WITH THE READOUT, and this is the whole reason the
        // fill happens up here, before the sweep is clipped out, rather than inside it
        // with everything else.
        //
        // The gamepad's ripple wipes the readout to reveal what is behind it. The ground
        // was being wiped along with it, so the circle opened onto black and the colour
        // or photograph underneath only reappeared once the sweep had finished. It was
        // masked for a while by a see-through wash painted into the swept region, which
        // is why removing that wash is what exposed it.
        //
        // IT IS ALSO WHAT MADE THE GLASS LOOK WRONG MID-SWEEP. A pane takes its tint from
        // a stored luminance grid of the backdrop, not from the pixels beneath it at the
        // moment it draws — that grid is built when the background changes, because
        // reading pixels back every frame is the most expensive thing this panel could
        // do. So over a bright picture the panes lift toward white, and when the ripple
        // took the picture away they were left lifting toward white over black: every
        // pane's tint suddenly legible as a pale patch. The panes were right. The
        // backdrop had gone missing underneath them.
        //
        // Unconditional, and that is deliberate beyond this bug: this fill is the one
        // that occludes, and an occluding fill that can be clipped away is one the
        // SubScreenLauncher can find a gap in.
        // Under glass the picture is already in groundNode, panel-wide, so a second pass
        // buys nothing except while the sweep is carving a hole this one has to fill.
        // ORIGINAL has no groundNode at all, so this IS its ground — and therefore the
        // only place its weather can go.
        if (!config.theme.isGlass || front != null) {
            drawWeatheredGround(canvas)
        } else {
            canvas.drawColor(groundColor)
        }

        // Held for the overlay pass, which draws outside every clip in here. See
        // drawOverlayGroup: the glass belongs to the readout and has to leave with it.
        sweepFront = front
        canvas.save()
        if (front != null) canvas.clipOutPath(front)
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
        dxNow = dx
        dyNow = dy
        // How far a notification strip has pushed the canvas side down. Computed here
        // rather than where it is used, because the record behind the readout rides the
        // same offset and is drawn first.
        noticeSlideHeld = noticeSlide(nowReal)
        val push = noticeHeight(nowReal) * noticeSlideHeld

        // THE LOCK OUTLIVES WHAT RAISED IT BY EXACTLY NOTHING. Checked after the slide
        // has been advanced for this frame, so "the strip has retracted" is this frame's
        // answer rather than the previous one's.
        //
        // The grace period is not politeness, it is a race. The lock is armed by its own
        // broadcast and the notification follows a fifth of a second later — the wake has
        // to land before the notice or the panel drops it — so for that fifth of a second
        // the panel is locked with nothing on it, which is indistinguishable from a lock
        // whose notice has already been and gone. Without the floor the panel armed and
        // expired before the thing it woke for ever arrived.
        //
        // Fired ONCE. This runs every frame, and putting the display out is not something
        // to ask for sixty times a second.
        // Never under the camera tip: its slider waits for the swipe, and gives up on its own.
        if (locked && tipAt == 0L && !lockExpiredSent && frameNowMs - lockArmedAt > LOCK_MIN_MS &&
            lockSpent(nowReal)
        ) {
            lockExpiredSent = true
            onLockExpired?.invoke()
        }

        // Paging. The media page exists only under the vinyl style and only while
        // something is playing — see MediaPage for why a square cover gets no second
        // page. A preview is never paged: it is a picture of the panel on a phone screen,
        // and a scroll position it cannot be given is a scroll position that would just
        // be wrong.
        settlePresence(frameNowMs)
        // The glyph's verdict is taken here, every frame, and not in the readout's media
        // row where it used to be: with no account connected there is no readout, nothing
        // drew that row, and the media page's button kept whatever it showed when the
        // track arrived — pause bars over a paused track, for good.
        if (media != null || departing != null) settlePlaying(frameNowMs)
        val wantsMediaPage = onlyAccount == null && mediaPageAvailable()
        val gone = readoutGone
        val basePages = if (gone) 1 else 2
        if (gone != readoutWasGone) {
            // The readout page came or went — an account connected, or the last one signed
            // out. The pager's pages are renumbered, so it is put on the home page at once.
            readoutWasGone = gone
            pager.pages = basePages + (if (wantsMediaPage) 1 else 0)
            pager.goTo(homePage, frameNowMs)
            pager.advance(frameNowMs + PAGE_SETTLE_MS.toLong())
            if (aod) aodReturnPage = homePage
        }
        val readoutAt = if (onlyAccount == null && !gone) Panel.HEIGHT.toFloat() else 0f
        if (wantsMediaPage) {
            pager.pages = basePages + 1
            // With the readout, music arriving announces itself: the strip slides into the
            // readout and the page below is there to swipe to. Without it there is nothing
            // on the clock page to carry the news, so the media page itself slides up into
            // view when music starts — and walks back home when it stops, as it already
            // did. Not under always-on, which has its own idea of the page, not over a
            // finger, and not on a panel that comes up with music already playing.
            if (gone && mediaWantKnown && !mediaPageWasWanted && !aod && !pager.animating() &&
                pager.restingAt(homePage)
            ) pager.settleTo(mediaPage, frameNowMs)
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
                // Saved indices are the pager's own, and they moved when the page above
                // arrived: what used to be 0 and 1 is 1 and 2. Anything from before is
                // read through PAGE_BASE so an upgrade lands you where you left off
                // rather than one page up from it.
                val saved = if (gone) homePage else panelPrefs.getInt(KEY_PAGE, PAGE_READOUT)
                    .coerceIn(0, pager.pages - 1)
                if (aod) {
                    // Always-on owns the page while it is up — a panel re-placed while the
                    // display was off enters it before its first frame, and this restore
                    // then put the readout under the veil. The saved page is where the
                    // swipe returns to; the clock page is what shows.
                    aodReturnPage = saved
                    pager.goTo(PAGE_NOTICES, frameNowMs)
                } else if (saved != homePage) {
                    pager.goTo(saved, frameNowMs)
                } else {
                    pager.goTo(homePage, frameNowMs)
                }
                pager.advance(frameNowMs + PAGE_SETTLE_MS.toLong())
            }
        } else if (pager.pages > basePages) {
            // The media page can vanish while you are standing on it — the music stops,
            // and the page it was about goes with it. Walk back rather than cutting: the
            // panel drops a page only once it has actually returned to the first one, so
            // what you see is the readout arriving, not the panel blinking.
            // In always-on the clock page stays where it is; only the page the swipe
            // returns to moves.
            if (aod) {
                if (aodReturnPage == mediaPage) aodReturnPage = homePage
                if (pager.restingAt(PAGE_NOTICES)) pager.pages = basePages
            } else {
                pager.settleTo(homePage, frameNowMs)
                if (pager.restingAt(homePage)) pager.pages = basePages
            }
        }
        // Two pages even with nothing playing: the notifications are always there. Only a
        // single-account preview has no paging at all, which is what it has always had.
        if (onlyAccount == null && pager.pages < basePages) pager.pages = basePages
        mediaPageWasWanted = wantsMediaPage
        mediaWantKnown = true


        // The demo turns the page itself, and has to do it after the page COUNT is
        // settled above — a segment asking for the media page while only two exist would
        // be clamped to the readout and the loop would skip a beat.
        driveDemo()
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
        // UNCHANGED IN MEANING: 0 on the readout, 1 on the media page. Measured from the
        // readout's new resting offset rather than from zero.
        val paging = ((paged - readoutAt) / Panel.HEIGHT).coerceIn(0f, 1f)
        // And the same question upwards. 1 is standing on the notification page.
        // with no readout page the clock page is page 0 and the media page is next, so
        // the notices fraction runs the other way from the same offset
        noticing = (if (gone) 1f - paged / Panel.HEIGHT else (readoutAt - paged) / Panel.HEIGHT).coerceIn(0f, 1f)

        pagingNow = paging
        // The transport records where it rests against this, so it has to be the offset
        // the readout is actually displaced by — not the pager's raw position, which now
        // counts from a page above.
        pagedNow = paged - readoutAt
        // Re-earned every frame. The row records it as it draws, and if the row is not
        // there to record it the page falls back to its own arrangement.
        transportHomeKnown = false
        nameHomeKnown = false

        // The notice pushes the READOUT down, and only while the readout is the page you
        // are on. Off-page it must not: the readout sits a full panel height above, so
        // pushing it 143 units down slid its bottom edge back into view underneath the
        // notification — a strip of the first page visible behind a card on the second.
        //
        // On the media page the notice makes room a different way. See drawMediaPage.
        // A NOTICE DOES NOT MOVE THE PANEL UNDER GLASS. Pushing the readout down is what
        // an opaque strip has to do — it is taking the room, so the room has to come from
        // somewhere. A pane takes nothing: you can see through it, so the readout can stay
        // where it is and simply be behind something. Which is also the better answer to
        // the thing the push was working around, because the panel no longer rearranges
        // itself every time somebody sends a message.
        val panelPush = if (config.theme.isGlass) 0f else push
        val readoutPush = panelPush * (1f - paging)
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
        val geometry = PanelGeometry.of(config.effectiveRingLayout).let { g ->
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
        run {
            // EVERYTHING BEHIND THE READOUT, IN ONE NODE.
            //
            // The account cards sit under the readings and bend what is behind them, and
            // "behind them" is not only the ground: the record is back here too. Given
            // just the ground to sample, a card painted a copy of the ground over the
            // record — which looks exactly like the record being clipped to the card's
            // edge, because that is what it was.
            //
            // Nothing draws between the ground and the record, so the two go into one
            // node and it is drawn once. That node is what the cards refract.
            val behind = canvas as? android.graphics.RecordingCanvas
            if (behind != null && config.theme.isGlass && width > 0 && height > 0) {
                groundNode.setPosition(0, 0, Panel.WIDTH, Panel.HEIGHT)
                val into = groundNode.beginRecording(Panel.WIDTH, Panel.HEIGHT)
                try {
                    drawWeatheredGround(into)
                    // The record is contents, so it goes when the panel does. The two
                    // lines above are ground, so they stay. See the run block's note.
                    if (!hidden) {
                        into.save()
                        into.translate(dx, dy + readoutPush)
                        drawVinyl(into, paging, panelPush, geometry)
                        into.restore()
                    }
                } finally {
                    groundNode.endRecording()
                }
                behind.drawRenderNode(groundNode)
            } else if (!hidden) {
            canvas.save()
            // NOT paged. The record is the one thing that does not scroll away — it
            // travels between the two pages instead, shrinking and moving to the middle
            // as the readout leaves over the top of it. That is the whole gesture: the
            // panel turns into a record player rather than showing a second screen.
            // Rides the readout's push while it is behind the readout, and none of it
            // once it has become the media page's record — where the notice shrinks it
            // instead of moving it.
            canvas.translate(dx, dy + readoutPush)
            drawVinyl(canvas, paging, panelPush, geometry)
            canvas.restore()
            }
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
        val demoVol = demoVolume()
        val volumeFraction =
            if (demoVol != null) {
                // Eased like any other arriving value, so the arc is filmed MOVING to the
                // demo's figure rather than already sitting on it.
                animateTo("media:volume:demo", demoVol)
            } else if (drag == Drag.VOLUME) {
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
                // Only while the page is actually the media page: a volume gesture is
                // impossible anywhere else, and a ring that swelled on the readout would
                // be answering a touch that never happened.
                thumbHeld = if (isVolume) engagement(Drag.VOLUME) * paging else 0f,
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
        canvas.translate(dx, dy + readoutPush - (paged - readoutAt))
        if (!hidden && !gone) drawCanvasSide(canvas, dx, dy + readoutPush - (paged - readoutAt))
        if (onlyAccount == null && !hidden && !gone) drawExtras(canvas)

        // Same rule as the extras: it acts on the panel rather than on an account, so a
        // single-account preview leaves it out — and a preview is scaled, which would
        // put the touch target in the wrong place even if it were wanted there.
        // THE PANEL'S OWN BUTTONS GO WHEN THE LOCK ARRIVES. The camera one has to — it
        // sits in the bottom-right corner and the slider spans 80% of the usable panel,
        // so they occupy the same pixels — but both would be wrong to leave anyway: a
        // control that is drawn and does nothing is worse than one that is not there,
        // because it invites the press the lock then swallows.
        //
        // Faded on the lock's own drum rather than cut, so they leave as the slider
        // arrives instead of blinking out in front of it.
        val unlocked = 1f - lockSwap(frameNowMs)
        if (onlyAccount == null && config.showCamera && !hidden && !gone && unlocked > 0.004f) {
            fading(canvas, cameraFadeBounds(), unlocked) {
                drawCamera(canvas, dx, dy + readoutPush - (paged - readoutAt), alpha = unlocked)
            }
        } else {
            cameraButton = null
        }
        // The close button belongs to the READOUT and scrolls away with it. It was
        // briefly pinned across both pages, on the reasoning that it is the panel's one
        // permanent control — but it closes the readout, and on a page that is not the
        // readout it is a button for somewhere else. The way back from the media page is
        // the swipe that got you there.
        if (onlyAccount == null && config.showClose && !hidden && !gone && unlocked > 0.004f) {
            fading(canvas, closeFadeBounds(), unlocked) {
                drawClose(canvas, dx, dy + readoutPush - (paged - readoutAt), alpha = unlocked)
            }
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
        // THE MEDIA PAGE STAYS UP UNDER THE LOCK, and so does the record on the readout.
        //
        // Both were taken away at first — the page faded out on the lock's drum and the
        // disc left through the bottom — and it was more than the job needed. The lock
        // exists to stop a pocket PRESSING something, and the slider already sits over the
        // band that holds every control on either page. What was left was a panel that
        // went blank whenever a notification arrived, which is a lot of ceremony for a
        // record sleeve nobody can tap.
        if (!hidden && onlyAccount == null && paging > 0.001f) {
            canvas.save()
            // NOT pushed by the notice. Everything on this page is anchored to the
            // bottom of the panel and pushing it down puts the transport buttons off the
            // screen — which is what happened, and you cannot press what has left. The
            // record gives up the room instead.
            canvas.translate(dx, dy)
            // With the readout, the media page is a morph: the transport and the title
            // grow out of the readout's media strip as the swipe goes. Without it there
            // is no strip to grow out of, and they merely appeared in place — so there
            // the media page arrives as a page, sliding up from below while the clock
            // page slides away above it. The rings stay put: they are the lenses'.
            if (gone) canvas.translate(0f, (1f - paging) * Panel.HEIGHT)
            drawMediaPage(canvas, paging, dx, dy, panelPush, geometry, volumeFraction)

            canvas.restore()
        } else if (paging <= 0.001f) {
            volumeBar = null
        }

        // The page above the readout. Assembled in place like the media page below it and
        // for the same reason — the readout is what moves, and a second thing sliding in
        // from the other edge would be two animations explaining one gesture.
        if (!hidden && onlyAccount == null && noticing > 0.001f) {
            canvas.save()
            // IT REALLY IS ABOVE, so it arrives by being above and coming down.
            //
            // The media page below assembles in place and fades up, and that is right
            // for it: the record is already travelling and carrying the movement, so a
            // second thing flying in would be two animations explaining one gesture.
            // Nothing carries the movement here. A page that materialises where it
            // stands reads as a screen being switched rather than a surface being
            // scrolled, and the whole point of putting this above the readout is that it
            // IS above the readout.
            //
            // A full panel height up at rest on the readout, level with it at the top of
            // the scroll — so it tracks the finger exactly, the way the thing you are
            // dragging should.
            canvas.translate(dx, dy - (1f - noticing) * Panel.HEIGHT)
            drawNoticePage(canvas)
            if (gone) {
                // With no readout, what lived in its corners lives on the clock page, the
                // one page always there — and out of the notification card's way: the
                // clock, date and battery row hangs in the bottom-right corner (its glass
                // rounded to the screen's corner there, as it is at the top on the
                // readout), the camera sits in the bay the two rings leave between them,
                // and the controller in the bottom-left corner, under the bay. All three
                // ride the page as it slides, their touch targets follow, and all three
                // fade with always-on — not only the buttons with the slider's swap.
                val pageDy = dy - (1f - noticing) * Panel.HEIGHT
                val awake = 1f - aodFade(frameNowMs)
                val present = unlocked * awake
                val gapY = (geometry.personal.cy + geometry.work.cy) / 2f
                val camW = CAMERA_H * Glyphs.aspect(PanelIcons.CAMERA, config.theme)
                val camRight = RING_BAY_X + camW / 2f
                val camBottom = gapY + CAMERA_H / 2f
                val closeCy = Panel.HEIGHT - CORNER_MARGIN - CLOSE_R
                if (config.showCamera && awake > 0.004f) {
                    // The camera keeps the readout's own corner, bottom right — the corner the
                    // unlock slider comes into. It gives way the way the slider arrives: down
                    // and out as the slider comes in, back up as it leaves, by the slider's
                    // own swap and over the slider's own travel. The touch target follows.
                    val swap = lockSwap(frameNowMs)
                    val drop = swap * (markRegion().height() + LOCK_BAND_SLOP)
                    canvas.save()
                    canvas.translate(0f, drop)
                    fading(canvas, cameraFadeBounds(), awake) {
                        drawCamera(canvas, dx, pageDy - drop, alpha = awake)
                    }
                    canvas.restore()
                } else cameraButton = null
                if (config.showClose && present > 0.004f) {
                    fading(canvas, android.graphics.RectF(
                        RING_BAY_X - CLOSE_R - FADE_PAD, closeCy - CLOSE_R - FADE_PAD,
                        RING_BAY_X + CLOSE_R + FADE_PAD, closeCy + CLOSE_R + FADE_PAD,
                    ), present) {
                        drawClose(canvas, dx, pageDy, PointF(RING_BAY_X, closeCy), alpha = present)
                    }
                } else closeButton = null
            }
            canvas.restore()
        }
        canvas.restore()   // the readout clip

        // THE SLIDER IS PINNED, and drawn out here rather than in the slot it replaces.
        //
        // It belongs to the panel, not to a page, and two things it cannot be subject to
        // decided this. The NOTICE PUSH: a notification slides the readout down by up to
        // a quarter of the panel, and the slot sits 26 units off the bottom — so the one
        // control that has to be reachable was pushed clean off a 572-unit display at
        // exactly the moment something had woken the panel to be unlocked for. And
        // PAGING: on the media page the slot is a panel-height away, so the slider was
        // simply not on screen if you happened to be standing there when it locked.
        //
        // Fixed here, it covers the transport row on BOTH pages — the media page puts its
        // buttons at cy 529 and the readout puts its row at 476..546, and the slider's
        // band spans both — so wherever you were, the thing that was under your thumb is
        // now the thing that asks you to swipe.
        //
        // Drawn before the notice and the charging cell, which are allowed over it: they
        // are why the panel is awake, and the knob is at the left where neither reaches.
        if (overlays) { drawCameraTip(canvas); drawAodVeil(canvas); drawTableGlow(canvas); drawLockOverlay(canvas); drawAodSliderVeil(canvas) }

        // Over everything, and outside the readout's ripple clip: a notification arriving
        // while the panel is closing should still be readable.
        if (overlays) drawNotice(canvas, noticeSlideHeld, nowReal)

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
                // NOTHING IS PAINTED HERE ANY MORE, and that is the fix rather than an
                // omission. This used to wash the swept region with a see-through copy of
                // the background colour, because the ground itself was drawn only when
                // the panel was NOT put away — so without it this side came up
                // transparent. The ground is now drawn whatever state the panel is in,
                // which means a wash over the top is no longer covering a gap: it is
                // covering the photograph, at two-thirds opacity, with a flat colour.
                //
                // Which is exactly what "the gamepad trashes the panel but keeps the
                // background" rules out. The readout goes; what it was standing on stays.
                drawAmbientWalk(canvas, if (aod) aodRealAt else nowReal) // frozen in always-on
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
        if (overlays) drawCharge(canvas)

        // Next frame only while something is still moving. When everything has settled
        // this stops entirely and the view falls back to the 15s idle tick — an
        // always-on panel must not hold a 60fps loop for a display that is not changing.
        //
        // TWO SPEEDS, because not everything that moves moves at the same rate. A tween,
        // a spinning record and a dragged dial all want the next vsync. The mascots want
        // twelve frames a second, which is their own frame rate — asking for a vsync on
        // their behalf ran the whole panel at 120Hz to advance a twelve-frame walk cycle,
        // and since there is always a mascot the panel never idled at all. Measured
        // before this: 81% of a core on a static readout.
        //
        // AND ONLY WHILE THE DISPLAY CAN SHOW IT. See displayAwake — a suspended panel
        // drops every frame we hand it, so asking for the next vsync is asking to be
        // woken up to do nothing.
        // fading in or out of always-on is itself a second of frames
        if (aod || aodUntil > 0) { if (aodFade(frameNowMs) in 0.001f..0.999f) animating = true }
        if (ringsAt != 0L && frameNowMs - ringsAt < AOD_FADE_MS) animating = true
        // The next frame is asked for at the end of onDraw, not here: see scheduleNext.

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
    private val surge = ChargeSurge()

    /** A colour pushed toward white, keeping its hue. RGB only; the caller sets alpha. */
    private fun lighten(color: Int, amount: Float): Int {
        val r = ((color shr 16) and 0xFF).let { it + ((255 - it) * amount).toInt() }
        val g = ((color shr 8) and 0xFF).let { it + ((255 - it) * amount).toInt() }
        val b = (color and 0xFF).let { it + ((255 - it) * amount).toInt() }
        return (r shl 16) or (g shl 8) or b
    }

    /**
     * The paint the discharges are drawn with. ADDITIVE, so overlapping flashes build up
     * the way light does rather than the nearest one simply covering the others.
     */
    private val gleam = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        blendMode = android.graphics.BlendMode.PLUS
    }

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
        keepAwakeFor()
        chargePercent = percent
        chargeLevel = percent / 100f
        chargeAt = SystemClock.elapsedRealtime()
        chargeStepAt = 0L
        chargeTenth = -1
        surface.calm()
        surge.clear()
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
            surge.clear()
            return
        }
        animating = true

        val w = ChargeOverlay.width(Panel.WIDTH.toFloat())
        val off = ChargeOverlay.offset(elapsed, w)
        val left = Panel.WIDTH - w + off + ChargeOverlay.MARGIN - ChargeOverlay.RIGHT_INSET
        val right = Panel.WIDTH + off - ChargeOverlay.MARGIN - ChargeOverlay.RIGHT_INSET
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

        // THE CELL IS A GLASS, so under GLASS it is made of glass.
        //
        // This is the one element on the panel where the material was never in question —
        // it has been "a glass with liquid in it" since it was built, and it was only
        // painted flat because there was no lens to hand. It is also an overlay, drawn
        // after the scene, so it gets the real thing: the readout and the record bend
        // through the empty part of the cell above the liquid line.
        //
        // The ground goes with it. That was there so the liquid had something to sit
        // inside rather than floating over whatever the panel was drawing; a pane does
        // that job better, because it is what a vessel actually does to what is behind it.
        val chargeRect = android.graphics.RectF(left, top, right, bottom)
        // NO RIM ON THE CELL. Every other pane wants an edge to say where it ends; this
        // one is a vessel whose contents are the point, and a lit outline around a glass
        // of moving liquid is a second thing competing with the first. The liquid's own
        // surface and the refraction at the walls say where the glass is.
        // NO TINT OF ITS OWN. It wore the notification card's dim for a while, on the
        // reasoning that two glass surfaces at two different weights read as two
        // materials — which is true of two CARDS, and this is not one. A notification is
        // a sheet you read type off, and it needs a colour so the type has something to
        // sit against. A cell is a vessel: the whole point of it is that you see what is
        // inside, and the dim it borrowed came from the notification's ground colour,
        // which is black unless the user has been into the settings to say otherwise. So
        // the cell was a black-tinted glass by default, with coloured liquid behind the
        // tint.
        //
        // Clear, then — and clear here means the same thing it means on the buttons: no
        // colour at all through the middle of the range, and a tint only at the two ends
        // where clear glass genuinely stops reading. The backdrop is the only thing that
        // gives it one. See clearReactive, which exists for exactly this.
        val paned = config.theme.isGlass &&
            drawGlassPane(
                canvas, chargeRect, r, clearReactive(chargeRect), rim = false,
            )

        canvas.save()
        canvas.clipPath(glass)
        if (!paned) {
            // The glass's own ground, so the liquid has something to be inside rather than
            // floating over whatever the panel was drawing there.
            noticePaint.style = Paint.Style.FILL
            noticePaint.shader = null
            noticePaint.color =
                (config.background and 0x00FFFFFF) or (CHARGE_GROUND_ALPHA shl 24)
            canvas.drawRect(left, top, right, bottom, noticePaint)
        }

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

        // WHAT THE LIQUID DOES TO WHAT IS BEHIND IT, before the liquid's own colour goes
        // over the top. The tint is translucent, so the magnified panel reads THROUGH it
        // — which is the right order: the readout is behind the liquid, not floating on
        // it. Only when the cell is really made of glass; without a pane there is no
        // backdrop to magnify and the tint alone is what the cell has always been.
        if (paned) drawMagnified(canvas, chargeRect, body, Glass.LIQUID_ZOOM)

        noticePaint.style = Paint.Style.FILL
        noticePaint.shader = null
        noticePaint.color = (tint and 0x00FFFFFF) or (CHARGE_LIQUID_ALPHA shl 24)
        canvas.drawPath(body, noticePaint)

        // The meniscus. A liquid's surface catches the light, and a brighter line along
        // it is most of what separates "filled shape" from "something with a top".
        //
        // NOT UNDER GLASS, where the liquid magnifies. The lit line was standing in for a
        // surface, and the magnification IS one: what is behind the cell steps as it
        // crosses the level, which is the same statement made by the thing itself rather
        // than by a line drawn where the thing would be. Kept together they read as two
        // tops — a drawn edge sitting a hair off the optical one that the lens produces.
        if (!config.theme.isGlass) {
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
        }

        // ---- the surges ------------------------------------------------------------
        //
        // Charge going in, shown the way a cloud shows it: lit from inside, with the
        // source itself never visible. A drawn bolt would be a bolt on top of a picture of
        // water; a bright patch under the surface whose edges are lost in the body is the
        // water having something happen in it.
        //
        // CLIPPED TO THE LIQUID, which is the whole of why it works. The glow stops
        // exactly where the liquid does, so it lights the stuff rather than being painted
        // over it — and above the surface there is nothing to light, correctly.
        surge.update(elapsed, fill)
        if (surge.size > 0) {
            canvas.save()
            canvas.clipPath(body)
            surge.forEach { flash ->
                val b = surge.brightness(flash, elapsed)
                if (b <= 0.01f) return@forEach
                // Placed in the LIQUID's frame — across the surface and down into the
                // depth — so a flash stays where it is in the water while the water turns.
                val u = (flash.u - 0.5f) * span
                val d = level + flash.v * (depth / 2f - level).coerceAtLeast(1f)
                val px = cx + acrossX * u + downX * d
                val py = cy + acrossY * u + downY * d
                val r = (right - left) * flash.size
                // THE CORE IS NEARLY WHITE, not the liquid's own colour.
                //
                // Drawn in the tint it was invisible, and obviously so in hindsight:
                // adding more of a colour to a body already made of it reads as slightly
                // more liquid, not as light. Measured, the flashes were firing the whole
                // time — one alight, brightness peaking around three quarters — and
                // nothing could be seen. A discharge is white-hot at the middle and takes
                // the colour of what it is shining through further out, so that is what
                // the gradient does.
                gleam.shader = android.graphics.RadialGradient(
                    px, py, r,
                    intArrayOf(
                        lighten(tint, SURGE_WHITEN) or
                            ((SURGE_CORE * b).toInt().coerceIn(0, 255) shl 24),
                        (tint and 0x00FFFFFF) or
                            ((SURGE_HALO * b).toInt().coerceIn(0, 255) shl 24),
                        tint and 0x00FFFFFF,
                    ),
                    floatArrayOf(0f, 0.3f, 1f),
                    android.graphics.Shader.TileMode.CLAMP,
                )
                canvas.drawCircle(px, py, r, gleam)
            }
            gleam.shader = null
            canvas.restore()
        }
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
        //
        // NOT UNDER GLASS. This is the cell's painted outline, in the battery's own
        // colour, and it is what ORIGINAL uses to say where the glass is. A pane says
        // that by refracting, so the outline becomes a second answer to a question
        // already answered — a green line around a piece of glass, which is the one thing
        // a piece of glass does not have.
        if (!paned) {
            vinylPaint.style = Paint.Style.STROKE
            vinylPaint.strokeWidth = CHARGE_RIM
            vinylPaint.color = (tint and 0x00FFFFFF) or (CHARGE_RIM_ALPHA shl 24)
            canvas.drawRoundRect(left, top, right, bottom, r, r, vinylPaint)
            vinylPaint.style = Paint.Style.FILL
        }

        // ---- the figure ------------------------------------------------------------
        //
        // BESIDE THE CELL AND RIDING THE SURFACE, rather than centred in the glass.
        //
        // It sat in the middle of the cell, which put a number over a column of moving
        // liquid — legible, because it was drawn over the top, but placed somewhere that
        // had nothing to do with the reading. Set beside the cell at the height of the
        // water it becomes a label ON the level: the two say the same thing in the same
        // place, and the figure rises as the cell fills.
        //
        // The height comes from the surface's own midpoint, so it follows the liquid
        // exactly — including when the phone is turned and the level tilts.
        val text = Math.round(fill * 100f).toString() + "%"
        val figureRight = left - ChargeOverlay.FIGURE_GAP
        // Clamped to the cell's own span. An empty cell puts the surface at the very top
        // and a full one at the very bottom, and a figure centred on either would hang
        // half off the panel — which is where the reading matters most.
        val figureHalf = (right - left) * ChargeOverlay.TEXT_SIZE * 0.5f
        val figureY = (cy + downY * level)
            .coerceIn(top + figureHalf, bottom - figureHalf)

        val glassFigure = config.theme.isGlass
        val figurePaint = if (glassFigure) chargeFigure else extras
        figurePaint.textSize = (right - left) * ChargeOverlay.TEXT_SIZE
        val was = figurePaint.textAlign
        figurePaint.textAlign = Paint.Align.RIGHT
        figurePaint.color = tint

        // MADE OF GLASS, if there is a panel behind it to bend. Type is nearly all edge,
        // so it takes a distance field of its own rather than the rounded-rect arithmetic
        // every pane here uses — see SdfText.
        // GROWN FROM THE CELL'S EDGE, which is where it is anchored anyway: the figure's
        // right edge is a fixed gap from the cell, and the cell's own slide is already in
        // that number, so scaling about that point makes the two arrive as one object.
        val grow = ChargeOverlay.figureScale(elapsed)
        if (grow > 0.01f) {
            canvas.save()
            canvas.scale(grow, grow, figureRight, figureY)
            if (!glassFigure || !drawSdfText(canvas, text, figurePaint, figureRight, figureY)) {
                val m = figurePaint.fontMetrics
                canvas.drawText(
                    text, figureRight, figureY - (m.ascent + m.descent) / 2f, figurePaint,
                )
            }
            canvas.restore()
        }
        figurePaint.textAlign = was
        extras.textSize = typed(18f)
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
         * How held the ring's THUMB looks, which is not the same as [engaged].
         *
         * The two say different things and the volume dial wants one without the other.
         * `engaged` greys and thickens the arc — "this reading is a proposal" — and the
         * volume must never claim that, because it applies live and you can already hear
         * it. Swelling the thumb says something else entirely: "yes, you have this",
         * which is the one thing a control on the back of a phone cannot say any other
         * way, since the finger driving it is somewhere nobody can see.
         */
        thumbHeld: Float = 0f,
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
            ?.let { demoStatus(it) }
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
        // Not usable — no account, a ghost, nothing to read — is an empty ring, not no
        // ring: the band of glass round the lens stays, with no reading in it. It is the
        // lens's frame, there on every page, whatever the account is doing.


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
        val shown = animateTo("ring:" + account.name, target).coerceIn(0f, 1f) *
            (1f - Tween.easeInOut(ringsFade(frameNowMs)))
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
        if (config.theme.isGlass) {
            // In always-on the glass goes — band, track, thumb — and the reading stays as
            // ORIGINAL draws it, a plain arc, cross-fading so nothing pops.
            val plain = aodFade(frameNowMs)
            if (plain < 0.996f) {
                val pad = arc.strokeWidth * 2f
                fading(canvas, android.graphics.RectF(scratch.left - pad, scratch.top - pad,
                    scratch.right + pad, scratch.bottom + pad), 1f - plain) {
                    drawGlassRing(canvas, scratch, startAngle, fullSweep, fraction, arc, thumbHeld)
                }
            }
            if (plain > 0.004f) {
                val was = arc.alpha
                arc.alpha = (was * plain).toInt()
                canvas.drawArc(scratch, startAngle, fullSweep * fraction, false, arc)
                arc.alpha = was
            }
        } else {
            canvas.drawArc(scratch, startAngle, fullSweep * fraction, false, arc)
        }
    }

    /**
     * The ring, built as the same component the bars are: a track, a reading, and a glass
     * thumb riding the end of it.
     *
     * THE SAME STRATEGY, BENT ROUND A CIRCLE. A ring and a bar are the same instrument on
     * this panel — both plot one fraction — and the reference builds that instrument one
     * way: a muted track for the whole range, the reading drawn over it, and a small glass
     * object at the value. There is no reason for the round one to be made of something
     * else, and every reason for it not to be: two materials for one idea is two things to
     * look at.
     *
     * The track and the arc are published as a layer so the thumb bends THEM rather than
     * the panel — again as the reference does. That is also what makes it work at all
     * here: a ring is drawn inside the scene recording and can never sample the scene, but
     * it can sample a node holding its own track.
     */
    private fun drawGlassRing(
        canvas: Canvas,
        oval: android.graphics.RectF,
        startAngle: Float,
        fullSweep: Float,
        fraction: Float,
        paint: Paint,
        held: Float,
    ) {
        val reading = paint.color
        val alpha = paint.alpha
        val stroke = paint.strokeWidth
        val f = fraction.coerceIn(0f, 1f)
        // The same swell the unlock indicator gets, for the same reason — see thumbHeld.
        val grow = 1f + (LockSlider.PRESSED_SCALE - 1f) * held.coerceIn(0f, 1f)

        // The layer covers the ring plus its stroke and the thumb standing proud of it.
        val pad = stroke / 2f + stroke * RING_THUMB * LockSlider.PRESSED_SCALE
        arcBounds.set(
            oval.left - pad, oval.top - pad, oval.right + pad, oval.bottom + pad,
        )
        // ---- the reading, behind the glass -----------------------------------------
        //
        // Drawn straight onto the panel rather than into a layer of its own. The band is
        // held back to the overlay pass — see deferRing — so what it bends is the finished
        // panel with this colour already on it, which is the whole point: the reading is
        // INSIDE the ring rather than under a separate piece of glass beside it.
        //
        // Slimmer than the band that holds it. A colour the same width as its vessel reads
        // as a coloured ring that happens to be shiny; one the vessel is visibly wider than
        // reads as glass with something in it.
        val fillStroke = stroke * Glass.RING_FILL
        paint.strokeWidth = fillStroke
        paint.color = Panel.Palette.TRACK
        paint.alpha = (255 * RING_TRACK_ALPHA).toInt()
        canvas.drawArc(oval, startAngle, fullSweep, false, paint)
        paint.color = reading
        paint.alpha = alpha
        if (Math.abs(fullSweep * f) > 0.4f) {
            canvas.drawArc(oval, startAngle, fullSweep * f, false, paint)
        }
        paint.strokeWidth = stroke

        // ---- and the band, which is always there ------------------------------------
        //
        // Every page, whatever the reading is doing. The ring is a physical object on this
        // panel and objects do not come and go with what they happen to be reporting: on
        // the readout it holds a usage figure, on the media page the same glass holds the
        // playback and the volume.
        deferRing(
            canvas, oval.centerX(), oval.centerY(), oval.width() / 2f,
            stroke * Glass.RING_BAND,
        )
        val layer: android.graphics.RenderNode? = null

        // The thumb, on the circumference at the reading's end.
        val angle = Math.toRadians((startAngle + fullSweep * f).toDouble())
        val r = (oval.width() / 2f)
        val cx = oval.centerX() + (Math.cos(angle) * r).toFloat()
        val cy = oval.centerY() + (Math.sin(angle) * r).toFloat()
        val tr = stroke * RING_THUMB * grow / 2f
        discRect.set(cx - tr, cy - tr, cx + tr, cy + tr)
        if (!drawGlassPane(
                canvas, discRect, tr, Glass.THUMB_TINT,
                source = layer ?: sceneNode, held = true,
            )
        ) {
            paint.color = reading
            canvas.drawArc(oval, startAngle, fullSweep * f, false, paint)
        }
        paint.color = reading
        paint.alpha = alpha
        paint.strokeWidth = stroke
    }

    private val arcOutline = android.graphics.Path()
    private val arcBounds = android.graphics.RectF()

    /** Drawn after the rings, inside onDraw: bars, the reset footer and the mark. */
    private fun drawCanvasSide(canvas: Canvas, dx: Float, dy: Float) {
        // In single-account mode: the account's own rows and mascot, and nothing else.
        //
        // The masthead, the clock and battery, and the maker's mark all describe the
        // PANEL rather than any account on it. Drawing them into a preview of one
        // account would make them look like part of that account's block, and would ask
        // the eye to ignore two thirds of what it is being shown.
        // The cards go down first — behind everything, including the mascots, who stand on
        // the bars and belong to their block. It also settles where the first one starts,
        // which is what the masthead and the controller are centred against.
        if (config.theme.isGlass) drawBars(canvas, cardsOnly = true)
        if (onlyAccount == null) drawTitle(canvas)
        // MASCOTS FIRST, so the readings are drawn over them. Under GLASS a thumb stands
        // proud of its track and can reach a mascot's feet; with the figures on top, a
        // reading passed BEHIND the thing standing on it, which is the wrong way round —
        // the mascot is standing on the instrument, not holding it.
        //
        // Harmless in ORIGINAL, where a mascot's feet meet the bar's top edge exactly and
        // the two never overlap at all.
        drawMascots(canvas)
        drawBars(canvas)
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
    /**
     * Where the readout's transport rests, in the media page's space.
     *
     * THE TWO PAGES SHOW THE SAME THREE BUTTONS. They were built as two sets — the row
     * lays out from its right edge against a strip, the page centres a group under a
     * record — and on a scroll one set left over the top of the other arriving. Which is
     * an odd thing to say about previous/play/next: they are the same three controls, and
     * a control that exists on both sides of a gesture should travel across it rather
     * than be destroyed and rebuilt one glyph to the left.
     *
     * So the row's arrangement is recorded here as it draws, mapped out of its own
     * transform and with the page slide taken back off, and the page interpolates from it.
     * At either end the geometry is exactly what each page had on its own; in between the
     * three buttons walk from one arrangement to the other, growing as they go.
     */
    private val transportHomeX = FloatArray(3)
    private var transportHomeY = 0f
    private var transportHomeH = 0f
    private var transportHomeKnown = false

    /** Scratch for mapping those three centres out of the readout's matrix. */
    private val transportPoints = FloatArray(6)

    /**
     * And where the name rests, for the same reason and by the same method.
     *
     * The track's title and artist are the other thing that exists on both pages: a line
     * of type in a column beside the artwork, and the same line centred under the record.
     * Recorded here as the row draws so the page can carry it across rather than fade one
     * copy out under another fading in.
     */
    private val nameHomeCard = android.graphics.RectF()
    private var nameHomeLeft = 0f
    private var nameHomeTitle = 0f
    private var nameHomeArtist = 0f
    private var nameHomeWidth = 0f
    private var nameHomeKnown = false
    private val namePoints = FloatArray(4)

    /** This frame's scroll, for the parts drawn on one page that belong to both. */
    private var pagingNow = 0f
    private var pagedNow = 0f

    /** How far onto the notification page the panel is, for the touch handler. */
    private var noticing = 0f

    /**
     * This frame's burn-in drift, RAW.
     *
     * Not the same number the canvas side is handed: that one arrives as
     * `dy + readoutPush - paged`, with the notice and the scroll already folded in. Taking
     * a rest position against it cancelled the wrong term and left the row's recorded home
     * sliding along with the scroll — so the buttons travelled to a destination that was
     * running away from them, which is what turned a transition into a slide.
     */
    private var dxNow = 0f
    private var dyNow = 0f

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
    /**
     * The only gesture the panel answers while locked.
     *
     * The knob must be TAKEN HOLD OF and CARRIED. Landing anywhere else does nothing —
     * not even begin — which is what stops the gesture degrading into "touch the left,
     * then touch the right". A pocket produces touches; it does not produce a touch that
     * starts in one named place and travels the width of the panel without lifting.
     *
     * Every branch returns true. The gesture is consumed whether or not it was the right
     * one, so a stray press is absorbed here rather than falling through to whatever
     * happens to be underneath.
     */
    private fun lockTouch(event: android.view.MotionEvent): Boolean {
        val band = markRegion()
        val h = band.height()
        // Vertically forgiving, and it can afford to be: the slider is the only target
        // on the panel, so there is nothing for a generous band to steal from. The X
        // axis is where the discrimination happens.
        val inBand = event.y >= band.top - LOCK_BAND_SLOP &&
            event.y <= band.bottom + LOCK_BAND_SLOP
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                lockGrabbed = inBand && LockSlider.grabs(event.x, lockProgress, h)
                if (lockGrabbed) {
                    // Where the gesture started, so the indicator moves WITH the finger
                    // rather than jumping to centre itself under it on the first move.
                    lockGrabX = event.x
                    lockGrabFrom = lockProgress
                    lockSpringAt = 0L
                    pressLock(true)
                    invalidate()
                }
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (lockGrabbed) {
                    lockProgress =
                        LockSlider.progressFrom(lockGrabFrom, event.x - lockGrabX, h)
                    invalidate()
                }
            }
            android.view.MotionEvent.ACTION_UP -> {
                if (lockGrabbed) {
                    lockGrabbed = false
                    pressLock(false)
                    if (LockSlider.unlocks(lockProgress)) {
                        unlock()
                    } else {
                        // Let it fall back rather than snapping. See LockSlider.SPRING_MS.
                        lockSpringFrom = lockProgress
                        lockSpringAt = SystemClock.uptimeMillis()
                        invalidate()
                    }
                }
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                // The system taking the gesture away is not a decision to unlock, however
                // far the knob had got. It falls back like any unfinished drag.
                if (lockGrabbed) {
                    lockGrabbed = false
                    pressLock(false)
                    lockSpringFrom = lockProgress
                    lockSpringAt = SystemClock.uptimeMillis()
                    invalidate()
                }
            }
        }
        return true
    }

    /** The artwork slot, which is also the band the slider is drawn in. */
    private fun markRegion() = android.graphics.RectF(
        BAR_LEFT, Panel.HEIGHT - MARK_BOTTOM - MARK_H,
        BAR_RIGHT, (Panel.HEIGHT - MARK_BOTTOM).toFloat(),
    )

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
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            onInteraction?.invoke()
            // A FINGER IS PROOF OF A LIT SCREEN, and it is the backstop for the whole
            // sleeping arrangement. Everything below stops drawing on the strength of a
            // display-state callback; if one is ever missed, the panel would sit frozen
            // with no timer left to rescue it. Nobody taps a display they cannot see, so
            // a touch says the callback was wrong more reliably than any heartbeat.
            displayAwake = true
        }
        if (!interactive) return false
        // LOCKED MEANS LOCKED. Every other target on this panel is skipped entirely —
        // not tested and rejected, never reached — so there is no path by which a
        // pocket can press play, change the volume or turn the page. The slider is the
        // only thing with an answer, and it consumes the gesture either way so that
        // nothing underneath sees it.
        if (locked) return lockTouch(event)
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
                // WHERE THE WHEEL OWNS THE AXIS. A wheel and a pager both want a vertical
                // drag, and on this page they are inches apart — so the card decides. A
                // finger that lands on the notifications is scrolling them; anywhere else
                // on the page is still turning the page, which is how you get back down
                // to the readout. Noted on the way down because that is when the finger
                // chose, not when it has moved far enough to say so.
                // The cards' place: a sideways swipe there puts them away or brings them
                // back; up and down turns them — while they are there to turn.
                onStash = noticing >= 0.999f && shownHistory().isNotEmpty() &&
                    event.y < wheelGrabBottom()
                onWheel = onStash && !noticesStashed
                wheelDownAt = noticeWheelAt
                // The wheel claims the gesture by itself. It used to ride on the pager's
                // claim, and with no readout there is no other page — the clock page is
                // the only one, the pager is off, and a finger on the notifications was
                // let go on the way down: they could not be scrolled at all.
                hit || pager.enabled() || onVolume || onScrub || onWheel || onStash
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
                        sideways && onStash -> Drag.STASH
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
                        if (onWheel) {
                            // A finger beats a settle. Without this the two fought each
                            // other for the rest of the animation.
                            wheelSettling = false
                            // A row per row: dragging up by one card's height moves on by
                            // one notification, so the item follows the finger rather than
                            // the finger asking for an item.
                            noticeWheelAt = (wheelDownAt + (downY - event.y) / NOTICE_H)
                                .coerceIn(0f, (shownHistory().size - 1).toFloat())
                            invalidate()
                        } else if (pager.enabled() &&
                            pager.onMove(event.y, android.os.SystemClock.uptimeMillis())
                        ) {
                            invalidate()
                        }
                        true
                    }
                    Drag.STASH -> {
                        stashDx = event.x - downX
                        invalidate()
                        true
                    }
                    Drag.UNDECIDED -> hit
                }
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                // The system taking the gesture away is not a decision to commit to it.
                if (drag == Drag.STASH) letGoOfStash(commit = false)
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
                if (drag == Drag.STASH) {
                    drag = Drag.UNDECIDED
                    onWheel = false
                    letGoOfStash(commit = true)
                    pager.onUp(android.os.SystemClock.uptimeMillis())
                    return true
                }
                drag = Drag.UNDECIDED
                // A drag is not a tap, and must not also fire whatever it started on.
                // Without this, letting go of a page swipe that happened to begin over
                // the close button closed the panel.
                if (onWheel) {
                    // Settled onto whichever it is nearest, the way a picker does. Nothing
                    // animates it: the fraction IS the position, so rounding it is the
                    // whole of the snap.
                    onWheel = false
                    wheelFrom = noticeWheelAt
                    wheelTo = Math.round(noticeWheelAt).toFloat()
                        .coerceIn(0f, (shownHistory().size - 1).toFloat())
                    wheelSettleAt = frameNowMs
                    wheelSettling = wheelTo != wheelFrom
                    pager.onUp(android.os.SystemClock.uptimeMillis())
                    invalidate()
                    return true
                }
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
    /**
     * Draw something at reduced opacity, through a layer.
     *
     * A layer rather than an alpha on the paint, because both of these are built from
     * more than one colour: the close button is a filled disc with its cross knocked out
     * in the BACKGROUND colour, and making that translucent would show the panel through
     * the hole while the disc around it was still half there — the cross would stop being
     * a hole and start being a grey X. Compositing the finished button and fading that
     * keeps it a button the whole way out.
     *
     * The bounds are generous on purpose. They only decide how much of the canvas the
     * layer covers, and both of these are small corner glyphs that the burn-in drift
     * moves by a few units; a rect measured exactly would clip its own subject.
     */
    private inline fun fading(
        canvas: Canvas,
        bounds: android.graphics.RectF,
        alpha: Float,
        draw: () -> Unit,
    ) {
        if (alpha >= 0.999f) { draw(); return }
        canvas.saveLayerAlpha(bounds, (255 * alpha).toInt().coerceIn(0, 255))
        draw()
        canvas.restore()
    }

    /** Room for the camera glyph, its margin and the drift. See [fading]. */
    // The whole button and its glass disc, with room: it started FADE_PAD from the right
    // edge, which cut the camera through its middle for as long as a fade ran — the
    // deferred glass button records the clip it was given, and this layer's bounds were it.
    private fun cameraFadeBounds() = android.graphics.RectF(
        Panel.WIDTH - CAMERA_H * 3f - FADE_PAD, Panel.HEIGHT - MARK_BOTTOM - MARK_H - FADE_PAD,
        Panel.WIDTH.toFloat() + FADE_PAD, Panel.HEIGHT.toFloat() + FADE_PAD,
    )

    /** The same for the close button, in the opposite corner. */
    /**
     * How far down the extras row moves to hang in the bottom-right corner with the same
     * air under it as it has above it at the top right — the glass card's rect on GLASS,
     * the icons' and baselines' extent on ORIGINAL.
     */
    private fun closeFadeBounds() = android.graphics.RectF(
        CLOSE_CX - CLOSE_R - FADE_PAD, CLOSE_CY - CLOSE_R - FADE_PAD,
        CLOSE_CX + CLOSE_R + FADE_PAD, CLOSE_CY + CLOSE_R + FADE_PAD,
    )

    private fun drawCamera(canvas: Canvas, dx: Float, dy: Float, at: PointF? = null, alpha: Float = 1f) {
        val h = CAMERA_H
        val w = h * Glyphs.aspect(PanelIcons.CAMERA, config.theme)
        val centreY = Panel.HEIGHT - MARK_BOTTOM - MARK_H / 2f
        // Its corner of the readout, or wherever it is asked to be (the media page, when
        // there is no readout): `at` is the glyph's right edge and bottom.
        val margin = Panel.HEIGHT - (centreY + h / 2f)
        val bottom = at?.y ?: (centreY + h / 2f)
        val right = at?.x ?: (Panel.WIDTH - margin)
        // Positioned by its own left edge rather than by a square cell: drawIcon centres
        // within a cell of side h, which is right for the clock and battery sitting in a
        // column and wrong for something pinned to the panel's right margin. Undo the
        // centring so the glyph's own right edge lands on CONTENT_RIGHT, where every
        // other right-aligned thing on the panel ends.
        glassButton(
            canvas, right - w / 2f, bottom - h / 2f, h, discH = MediaLayout.GLYPH_H, alpha = alpha,
        ) { c ->
            extras.color = colorFor("camera", config.cameraTint)
            drawIcon(c, PanelIcons.CAMERA, right - w - (h - w) / 2f, bottom - h, h)
        }

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
     * The way into the game: a controller, on a disc.
     *
     * IT USED TO BE A CROSS, which was an honest description of what the button did — it
     * closed the readout — and a poor one of where it went. What is behind the readout is
     * not nothing; it is the mascot's own screen, and that screen is going to be something
     * to play with. A cross says "dismiss this", a controller says "go there", and only
     * one of those is true.
     *
     * Drawn INSIDE the readout's clip, with the rest of the panel, so the ripple sweeps it
     * away and back exactly as it does everything else. It used to sit outside and
     * cross-fade on its own — the reasoning being that the ripple came out of it and so
     * must not eat it, which sounded right and looked wrong: one element dissolving while
     * the surface around it was wiped reads as two different effects.
     *
     * Under ORIGINAL the glyph is knocked out of a filled disc, so the disc reads as
     * missing material rather than as a second colour laid on top. Under GLASS it gets
     * what every other control on this panel gets — the mark, and glass over it.
     */
    private fun drawClose(canvas: Canvas, dx: Float, dy: Float, at: PointF? = null, alpha: Float = 1f) {
        // The glass reads as a bigger object than the filled disc did, so it IS one — a
        // pane with a lit rim needs room around the mark, or it looks like a lens clamped
        // onto it rather than a button the mark is sitting on. The target grows with it:
        // what looks pressable has to be pressable, and a rim you can see but cannot hit
        // is worse than no rim at all.
        val hit = if (config.theme.isGlass) CLOSE_R * GAMEPAD_DISC else CLOSE_R
        // Down the middle of the same band the masthead is centred in, so the two read as
        // one row of furniture above the accounts rather than two things at two heights.
        // Its own radius keeps it clear of the top edge when there is no card to measure.
        // Its corner of the readout, or wherever it is asked to be: `at` is the centre.
        val closeCx = at?.x ?: CLOSE_CX
        val closeCy = at?.y ?: (if (firstCardTop > 0f) firstCardTop / 2f else CLOSE_CY)
        // Drift subtracted, for the same reason the camera's is: touches arrive in the
        // view's coordinates, which the burn-in translate never moves.
        closeButton = android.graphics.RectF(
            closeCx - hit - dx, closeCy - hit - dy,
            closeCx + hit - dx, closeCy + hit - dy,
        )

        // SIZED BY ITS WIDTH, not its height. The controller is a wide glyph in one theme
        // and a square one in the other — 22 by 13 traced, 1:1 on Material's grid — so
        // asking for a height would give two very different marks on the same disc.
        val aspect = Glyphs.aspect(PanelIcons.GAMEPAD, config.theme)
        val gh = CLOSE_R * GAMEPAD_SPAN / aspect
        val gx = closeCx - gh / 2f
        val gy = closeCy - gh / 2f

        if (config.theme.isGlass) {
            // The disc sized so its glass lands exactly where the filled one used to,
            // which is also where the touch target still is.
            extras.color = colorFor("close", config.closeColor)
            glassButton(canvas, closeCx, closeCy, gh, discH = hit / DISC_RADIUS, alpha = alpha) { c ->
                extras.color = colorFor("close", config.closeColor)
                drawIcon(c, PanelIcons.GAMEPAD, gx, gy, gh)
            }
            return
        }

        closePaint.style = Paint.Style.FILL
        closePaint.color = colorFor("close", config.closeColor)
        canvas.drawCircle(closeCx, closeCy, CLOSE_R, closePaint)

        // The controller in the panel's own background, so the disc reads as missing
        // material rather than as a second colour laid on top.
        extras.color = colorFor("bg", config.background or (0xFF shl 24))
        drawIcon(canvas, PanelIcons.GAMEPAD, gx, gy, gh)
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

        // ---- what this screen is going to be ---------------------------------------
        //
        // CENTRED ON WHAT CAN BE SEEN, not on the panel. The left 296 units are camera
        // glass: the pixels are live and nothing drawn on them is visible, so type centred
        // on the panel's own middle sits a third of the way under a lens. Everything here
        // that has to be read is measured against the usable width instead — see the
        // unlock slider, which learned it the same way.
        //
        // Above the mascot rather than behind it. The animal walks the bottom of this
        // screen and is the way back out of it; type crossing its path would read as
        // something it was carrying.
        val wasAlign = title.textAlign
        val wasSize = title.textSize
        val wasColor = title.color
        title.textAlign = Paint.Align.CENTER
        title.textSize = typed(COMING_SIZE)
        title.color = Panel.Palette.FG
        canvas.drawText(
            COMING_SOON,
            Panel.CUTOUT_RIGHT + (Panel.WIDTH - Panel.CUTOUT_RIGHT) / 2f,
            Panel.HEIGHT * COMING_AT,
            title,
        )
        title.textAlign = wasAlign
        title.textSize = wasSize
        title.color = wasColor

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
        val next = Notice(app, pkg, title, text, moreTitle, moreText)

        // KEPT BEFORE IT IS JUDGED, and the two decisions are genuinely different.
        //
        // What follows drops anything that arrived while the panel was dark, on the
        // grounds that a strip replaying ten hours of somebody's evening is a log rather
        // than news. That reasoning is about the STRIP. The history behind it wants
        // exactly the opposite: the whole point of a page you can scroll back through is
        // that it holds what you were not there to see.
        //
        // Duplicates are the one thing not kept. Apps re-post the same notification for
        // their own reasons — a progress update, a re-rank — and ten copies of one
        // message is not ten notifications.
        if (history.firstOrNull() != next) {
            history.addFirst(next)
            while (history.size > HISTORY_MAX) history.removeLast()
            saveHistory()
            // The Info page's wheel moves with its cards and, on the newest, turns to the
            // new one: the card in view slides down and the new one comes in from above.
            val arrival = NoticeWheel.afterArrival(noticeWheelAt, held = onWheel)
            if (onWheel) wheelDownAt += arrival.at - noticeWheelAt
            noticeWheelAt = arrival.at.coerceAtMost((history.size - 1).toFloat())
            arrival.settleTo?.let { to ->
                wheelFrom = noticeWheelAt
                wheelTo = to
                wheelSettleAt = SystemClock.uptimeMillis()
                wheelSettling = true
            }
            invalidate()
        }

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

        // Admitted, so it is worth keeping the display up for. After the drop check on
        // purpose: something that will never be shown is not a reason to light anything.
        keepAwakeFor()

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
    /**
     * Whether the demo has taken the player over.
     *
     * Set the moment a segment pushes a track, and never cleared while the demo runs: the
     * phone's own music does not stop just because something is being filmed, and a real
     * update arriving mid-take would put somebody's actual listening on the panel halfway
     * through a segment about Aphex Twin.
     */
    private var demoOwnsMedia = false

    /** True only while the demo itself is calling showMedia. See demoOwnsMedia. */
    private var demoPushing = false

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
        // THE PHONE'S OWN MUSIC DOES NOT STOP FOR A FILM. Once a segment has pushed a
        // track the demo keeps the player, or a real update arriving mid-take puts
        // somebody's actual listening on the panel halfway through a segment about
        // something else. The demo's own pushes come through demoPushing.
        if (demoOwnsMedia && !demoPushing) return
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
    private enum class Drag { UNDECIDED, VOLUME, SCRUB, PAGE, STASH }

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



    // -- the lock -------------------------------------------------------------------
    //
    // THE PANEL LOCKS WHEN IT LIT ITSELF. A notification or a charger now wakes the rear
    // display, and the phone that happens to is very often in a pocket. Every control
    // here is then under a thigh: the volume strip, the transport buttons, the page
    // swipe. So a screen that turned itself on refuses to be used until somebody proves
    // they meant it — see LockSlider for why the proof is a long drag and not a tap.
    //
    // Only when it lit ITSELF. If the display was already on, the panel was already
    // there to be touched and nothing has changed; locking then would be the panel
    // seizing up in front of somebody who was looking at it. ShizukuBridge.wake decides
    // this, because it is the only place that sees the display state before the keyevent
    // lands — by the time this process hears about the notification the screen is on
    // either way, and the difference is gone.

    /** Whether the panel is refusing input, and when the slider began arriving. */
    private var locked = false
    private var lockAt = 0L

    /**
     * When the lock was last given a reason, which is NOT when the slider arrived.
     *
     * Two clocks because two different things are being timed. [lockAt] drives the drum
     * and must not be disturbed by a second notification, or the slider drops out and
     * slides back every time somebody messages you. This one arms the expiry, and it
     * must be reset by every fresh reason — otherwise the second notification of a burst
     * inherits the first one's countdown and the panel puts the display out underneath a
     * notice that has only just arrived.
     */
    private var lockArmedAt = 0L

    /** How far the knob has been carried, 0..1, and where it is falling back from. */
    private var lockProgress = 0f
    private var lockSpringFrom = 0f
    private var lockSpringAt = 0L

    /** Whether the gesture in flight took hold of the indicator. See LockSlider.grabs. */
    private var lockGrabbed = false

    /** Where that gesture started, and from what progress. See progressFrom. */
    private var lockGrabX = 0f
    private var lockGrabFrom = 0f

    /** Whether the display has already been asked to go out for this lock. */
    private var lockExpiredSent = false

    /** Told when the lock has nothing left to keep it up. Set by RearActivity. */
    var onLockExpired: (() -> Unit)? = null

    /**
     * Told when the panel has something to show and needs the display kept up for it.
     *
     * Separate from the lock, and it has to be. The lock's expiry RELEASES the screen so
     * MIUI can doze it, and for the few seconds between that release and the display
     * actually going out the panel is still live — a notification arriving in that window
     * was being shown onto a screen already on its way down, and then had the queued
     * hand-back unlock the panel underneath it.
     *
     * So anything worth waking for says so directly, rather than the hold being inferred
     * from the lock. A panel with a notice on it should be lit whether or not it happens
     * to be locked.
     */
    var onNeedsScreen: (() -> Unit)? = null

    /**
     * A finger landed on the panel.
     *
     * Distinct from [onNeedsScreen], which says something ARRIVED worth lighting the
     * display for. This says somebody is looking at it right now, which is the other
     * reason to put off letting it sleep. See RearActivity's sleep timer.
     */
    var onInteraction: (() -> Unit)? = null

    /**
     * Something arrived that is worth being seen: keep the display, and renew the lock.
     *
     * The renewal is only a renewal — it never LOCKS a panel that was not already locked.
     * A notification arriving at a panel somebody is watching must not lock it, and that
     * decision belongs to the wake, which is the only thing that can tell. See
     * ShizukuBridge.wake.
     */
    private fun keepAwakeFor() {
        onNeedsScreen?.invoke()
        if (!locked) return
        // Same clock, same reason as lock().
        lockArmedAt = SystemClock.uptimeMillis()
        lockExpiredSent = false
    }

    /**
     * Put the panel into lock mode.
     *
     * Idempotent while already locked, and deliberately so: a second notification
     * arriving during the first one must not restart the drum, or the slider drops out
     * and comes back every time somebody messages you.
     */
    fun lock() {
        // THE REAL CLOCK, NOT THE FRAME CLOCK. frameNowMs is the last frame the panel
        // DREW, and a panel that has been dozing has not drawn for as long as it slept —
        // so stamping the lock with it dated the lock minutes into the past, the grace
        // period below read as long expired, and the lock let go of the display on its
        // first frame. Which then cascaded: the release armed the wait for a doze, the
        // display was still settling out of one, and the hand-back fired at once. The
        // panel woke for a notification and was unlocked again before it arrived.
        val now = SystemClock.uptimeMillis()
        // A fresh reason re-arms the expiry WHETHER OR NOT the panel was already locked,
        // and that half is not idempotent on purpose. Everything else here is: a second
        // notification must not restart the slider's arrival.
        lockArmedAt = now
        lockExpiredSent = false
        if (locked) { invalidate(); return }
        locked = true
        syncVideo()
        lockAt = now
        lockProgress = 0f
        lockSpringAt = 0L
        lockGrabbed = false
        invalidate()
    }

    /**
     * Let the panel be used again.
     *
     * The record and the media page come back on their own once this clears — they were
     * never removed, only told there was nothing to show — so this hands back to the same
     * animations that took them away. That is the whole reason lock mode is expressed as
     * "there is no media" rather than as a separate screen: the way back is already
     * written, and it is the way the panel already moves.
     */
    fun unlock() {
        if (!locked) return
        locked = false
        syncVideo(fresh = true)
        // The tip's slider, swiped: "I understand" — the camera opens, after the panel has
        // had its usual unlock. A tip that gave up has already ended itself, so nothing.
        val tipConfirmed = endCameraTip()
        onUnlocked?.invoke()
        tipConfirmed?.invoke()
        lockAt = SystemClock.uptimeMillis()
        // the thumb stays where the swipe left it while the band slides out; lockSwap
        // resets it once the band has gone, so the next lock starts clean
        lockSpringAt = 0L
        lockGrabbed = false
        lockExpiredSent = false
        invalidate()
    }

    val isLocked: Boolean get() = locked

    // ---- the camera tip ----

    /**
     * The first camera of all: before it opens, a page over the whole panel says how to
     * close it from the back — a tap in its empty grey band, the only way there is (see
     * RearUserService.watchBandTaps). Unskippable for [CAMERA_TIP_MS]; then the slider
     * comes, saying "I UNDERSTAND", and its swipe opens the camera.
     *
     * The slider is the lock's own, so it moves and answers the way it always does; only
     * its word is different, and it does not run out while the tip is up. Who decides the
     * tip is due, and what "seen" means afterwards, is the activity's business.
     */
    private var tipAt = 0L
    private var tipLeftAt = 0L
    private var tipDone: (() -> Unit)? = null
    val cameraTipUp: Boolean get() = tipAt != 0L

    /** Puts the tip up; [done] runs once its slider is swiped. */
    fun showCameraTip(done: () -> Unit) {
        if (tipAt != 0L) return
        tipAt = SystemClock.uptimeMillis()
        tipLeftAt = 0L
        tipDone = done
        removeCallbacks(tipArrive)
        removeCallbacks(tipGiveUp)
        postDelayed(tipArrive, CAMERA_TIP_MS)
        postDelayed(tipGiveUp, CAMERA_TIP_GIVE_UP_MS)
        onInteraction?.invoke()
        invalidate()
    }

    private val tipArrive = Runnable { if (tipAt != 0L) lock() }

    /** Walked away from: the tip goes, the panel comes back, the camera stays shut — and it is shown again next time. */
    private val tipGiveUp = Runnable {
        if (tipAt == 0L) return@Runnable
        endCameraTip()
        unlock()
    }

    /** Ends the tip, fading it out; what it was going to do, for the caller to run or drop. */
    private fun endCameraTip(): (() -> Unit)? {
        if (tipAt == 0L) return null
        val done = tipDone
        tipAt = 0L
        tipDone = null
        tipLeftAt = SystemClock.uptimeMillis()
        removeCallbacks(tipArrive)
        removeCallbacks(tipGiveUp)
        invalidate()
        return done
    }

    private fun cameraTipFade(now: Long): Float {
        val rising = tipAt != 0L
        val from = if (rising) tipAt else tipLeftAt
        if (from == 0L) return 0f
        val t = ((now - from).toFloat() / CAMERA_TIP_FADE_MS).coerceIn(0f, 1f)
        if (!rising && t >= 1f) { tipLeftAt = 0L; return 0f }
        if (t < 1f) animating = true
        val e = Tween.easeInOut(t)
        return if (rising) e else 1f - e
    }

    private val tipFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tipStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tipText by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = label.typeface; textAlign = Paint.Align.CENTER }
    }
    private val tipRect = android.graphics.RectF()
    /** The tap's glow: one unit-radius gradient, placed and sized by its matrix each frame. */
    private val tipGlow = android.graphics.RadialGradient(
        0f, 0f, 1f,
        intArrayOf(0xCC0091FF.toInt(), 0x550091FF, 0x000091FF),
        floatArrayOf(0f, 0.45f, 1f),
        android.graphics.Shader.TileMode.CLAMP,
    )
    private val tipGlowMatrix = android.graphics.Matrix()

    /** The tip's ground: the front screen's silk, at its settings, over the panel's units. */
    private val tipSilk by lazy {
        dev.kb896.aiusage.ui.glass.Silk.create().apply {
            setFloatUniform("size", Panel.WIDTH.toFloat(), Panel.HEIGHT.toFloat())
        }
    }
    private var tipSilkTime = 0f
    private var tipSilkLastMs = 0L

    /**
     * The left edge of the usable area, in panel units: the left inset of the cutout this
     * view's display reports (296 of 904 on the 17 Pro's rear display), scaled. The 17
     * Pro's when the display says nothing — the same fallback the tap watch uses.
     */
    private fun cutoutLeft(): Float {
        val inset = display?.cutout?.safeInsetLeft ?: 0
        // The view fills the display, so its width is the display's in the cutout's own frame.
        val w = if (width > 0) width else display?.mode?.physicalWidth ?: 0
        return if (inset > 0 && w > 0) Panel.WIDTH * inset / w.toFloat() else Panel.CUTOUT_RIGHT.toFloat()
    }

    /**
     * The page: the front screen's silk over everything, and the real place to tap lit up where it is — the
     * strip at the left of this very screen that the camera leaves grey and the tap watch
     * listens to ([BandTap.BAND] of the width), flashing, with a tap pulsing between the
     * lenses. Beside it, what to do, centred in the room above the slider; and until the
     * slider takes its band, the countdown where it will be.
     */
    private fun drawCameraTip(canvas: Canvas) {
        val now = frameNowMs
        val fade = cameraTipFade(now)
        if (fade <= 0.001f) return
        // The countdown, the flash and the tap's pulse move for as long as the tip is up.
        if (tipAt != 0L) animating = true
        fun alpha(of: Float) = (255 * of * fade).toInt().coerceIn(0, 255)

        // The silk moves as the front's does: a tenth of a second of shader time per second.
        if (tipSilkLastMs != 0L) tipSilkTime += 0.1f * (now - tipSilkLastMs) / 1000f
        tipSilkLastMs = now
        tipSilk.setFloatUniform("time", tipSilkTime)
        tipFill.shader = tipSilk
        tipFill.color = android.graphics.Color.BLACK
        tipFill.alpha = alpha(1f)
        canvas.drawRect(0f, 0f, Panel.WIDTH.toFloat(), Panel.HEIGHT.toFloat(), tipFill)
        tipFill.shader = null

        // ONE POINT, NOT THE ZONE. The whole lens column closes the camera, but the lenses
        // fill most of it, so a finger goes where it is shown: right on the usable area's
        // left edge as this display reports it, halfway down, clear of the lenses. It
        // breathes — a soft glow swelling and fading, slowly — with a ring drifting out of
        // it now and then, rather than blinking.
        val edge = cutoutLeft()
        val stripRight = edge + BandTap.EDGE_ALLOW
        val tx = edge // on the edge itself: the lenses end 38 units short of it on the 17 Pro
        val ty = Panel.HEIGHT / 2f
        val breath = 0.5f - 0.5f * kotlin.math.cos(2.0 * Math.PI * (now % CAMERA_TIP_BREATH_MS) / CAMERA_TIP_BREATH_MS).toFloat()
        tipGlowMatrix.setScale(CAMERA_TIP_GLOW * (0.8f + 0.25f * breath), CAMERA_TIP_GLOW * (0.8f + 0.25f * breath))
        tipGlowMatrix.postTranslate(tx, ty)
        tipGlow.setLocalMatrix(tipGlowMatrix)
        tipFill.shader = tipGlow
        tipFill.color = android.graphics.Color.WHITE
        tipFill.alpha = alpha(0.45f + 0.55f * breath)
        canvas.drawCircle(tx, ty, CAMERA_TIP_GLOW * 1.05f, tipFill)
        tipFill.shader = null

        val drift = (now % CAMERA_TIP_BREATH_MS).toFloat() / CAMERA_TIP_BREATH_MS
        tipStroke.color = CAMERA_TIP_TAP
        tipStroke.strokeWidth = 2f
        tipStroke.alpha = alpha(0.6f * (1f - drift))
        canvas.drawCircle(tx, ty, 12f + 40f * Tween.easeInOut(drift), tipStroke)

        tipFill.color = android.graphics.Color.WHITE
        tipFill.alpha = alpha(0.85f + 0.15f * breath)
        canvas.drawCircle(tx, ty, 9f, tipFill)

        // What to do, beside it: centred in the column right of the strip and in the room
        // above the slider, and shrunk only if a line would not fit across.
        val band = markRegion()
        val colLeft = stripRight + CAMERA_TIP_GAP
        val colRight = Panel.WIDTH - CAMERA_TIP_GAP
        val cx = (colLeft + colRight) / 2f
        tipText.textSize = typed(CAMERA_TIP_BODY)
        val widest = CAMERA_TIP_LINES.maxOf { tipText.measureText(it) }
        tipText.textSize = typed(CAMERA_TIP_TITLE)
        val title = tipText.measureText(CAMERA_TIP_HEADING)
        val fit = minOf(1f, (colRight - colLeft) / maxOf(title, widest))
        val titleSize = typed(CAMERA_TIP_TITLE) * fit
        val bodySize = typed(CAMERA_TIP_BODY) * fit
        val step = bodySize * 1.6f
        val block = titleSize + CAMERA_TIP_TITLE_GAP + step * (CAMERA_TIP_LINES.size - 1) + bodySize
        var y = (band.top - CAMERA_TIP_GAP - block) / 2f + titleSize
        tipText.color = android.graphics.Color.WHITE
        tipText.alpha = alpha(1f)
        tipText.textSize = titleSize
        canvas.drawText(CAMERA_TIP_HEADING, cx, y, tipText)
        y += CAMERA_TIP_TITLE_GAP + bodySize
        tipText.textSize = bodySize
        tipText.color = Panel.Palette.FG
        tipText.alpha = alpha(1f)
        for (line in CAMERA_TIP_LINES) {
            canvas.drawText(line, cx, y, tipText)
            y += step
        }

        // The countdown, in the band the slider is about to take.
        if (tipAt != 0L) {
            val left = CAMERA_TIP_MS - (now - tipAt)
            val secs = ((left + 999L) / 1000L).coerceAtLeast(0L)
            val keep = 1f - lockSwap(now)
            if (secs > 0 && keep > 0.001f) {
                tipText.textSize = typed(LOCK_TEXT)
                tipText.color = Panel.Palette.FG
                tipText.alpha = alpha(keep * LOCK_TEXT_ALPHA / 255f)
                canvas.drawText(
                    "PLEASE READ  ·  $secs", (LockSlider.left() + LockSlider.right()) / 2f,
                    band.centerY() + LOCK_TEXT * 0.36f, tipText,
                )
            }
        }
    }

    /**
     * 0 while the slot holds what it normally would, 1 while the slider does.
     *
     * The SAME drum as the artwork and the controls swap on, at the same duration, by
     * asking for it in the same terms. The instruction was that changing into lock mode
     * should move the way changing between the artwork and the player moves, and the
     * cheapest way to honour that exactly is to use the movement rather than to imitate
     * its numbers.
     */
    private fun lockSwap(nowMs: Long): Float {
        if (lockAt == 0L) return if (locked) 1f else 0f
        val t = (nowMs - lockAt).toFloat() / MEDIA_MS
        val to = if (locked) 1f else 0f
        if (t >= 1f) {
            if (!locked && lockProgress != 0f) { lockProgress = 0f; lockSpringAt = 0L } // gone: the thumb rests
            return to
        }
        animating = true
        val from = 1f - to
        return from + (to - from) * Tween.easeInOut(t.coerceIn(0f, 1f))
    }

    /**
     * Where the knob is now, easing back to rest if the last drag did not finish.
     *
     * A drag in flight is read straight from the finger; only the fall back is animated.
     */
    private fun lockKnob(nowMs: Long): Float {
        if (lockGrabbed) return lockProgress
        if (lockSpringAt == 0L) return lockProgress
        val t = ((nowMs - lockSpringAt).toFloat() / LockSlider.SPRING_MS).coerceIn(0f, 1f)
        if (t >= 1f) {
            lockSpringAt = 0L
            lockProgress = 0f
            return 0f
        }
        animating = true
        return lockSpringFrom * (1f - Tween.easeInOut(t))
    }

    /**
     * Whether the lock still has a reason to be up.
     *
     * It goes when the things that woke the panel have gone — the notice strip fully
     * retracted and the charging cell finished — because at that point there is nothing
     * on screen worth keeping a pocket lit for. The strip has to be RETRACTED rather than
     * merely empty: the queue drains a moment before the strip finishes sliding away, and
     * putting the display out mid-movement would cut the last notification in half.
     */
    private fun lockSpent(nowReal: Long): Boolean =
        notice == null && pending.isEmpty() && leaving == null &&
            noticeSlideNow(nowReal) <= 0.001f && chargeAt == 0L

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
        if (config.theme.isGlass) {
            drawGlassNotice(canvas, slide, nowMs)
            return
        }
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
     * The same notification, as glass: two cards that float rather than one shape with a
     * shoulder.
     *
     * ORIGINAL's strip runs off the top and right of the panel and its second card is
     * TUCKED behind the first, sharing an edge — one assembly, stepped, anchored to the
     * screen. That is right for a readout drawn to the display's own corners, and wrong
     * for glass: a pane is an object with all four edges, and an object cannot be half
     * under another object of the same material and still read as either.
     *
     * So both cards are inset from every edge and fully rounded, and the group card sits
     * BELOW the headline with air between them rather than behind it. It is narrower as
     * well as shorter — inset further on both sides — because that is what says it is
     * subordinate to the card above rather than a second one of equal standing.
     */
    private fun drawGlassNotice(canvas: Canvas, slide: Float, nowMs: Long) {
        val total = noticeHeight(nowMs)
        val top = -total + total * slide
        val turn = wheelTurn(nowMs)
        val m = miniSlide(nowMs)
        val r = GLASS_NOTICE_RADIUS

        // The design's own margin, not one inflated to clear the display's cut. The card's
        // top-right corner follows that curve instead, which is what the inset buys.
        val margin = GlassCard.MIN
        val cardTop = top + margin
        val cardBottom = top + NOTICE_H
        val right = Panel.WIDTH - margin

        // ---- the group card, below and narrower ------------------------------------
        //
        // Drawn FIRST and slid out from under the headline, which keeps ORIGINAL's one
        // good idea: a card that grows in place reads as something inflating, where one
        // that emerges reads as something arriving. It simply emerges into open panel
        // here instead of staying tucked.
        if (m > 0.001f) {
            val miniH = CASCADE_H - GLASS_MINI_SHRINK
            val miniTop = cardBottom + GLASS_NOTICE_GAP - (1f - m) * (miniH + GLASS_NOTICE_GAP)
            val mini = android.graphics.RectF(
                NOTICE_LEFT + GLASS_MINI_INSET, miniTop,
                right - GLASS_MINI_INSET, miniTop + miniH,
            )
            canvas.save()
            // Clipped to the gap below the headline, so it is not seen crossing the card
            // above on its way out.
            canvas.clipRect(
                0f, cardBottom + GLASS_NOTICE_GAP, Panel.WIDTH.toFloat(), Panel.HEIGHT.toFloat(),
            )
            if (!drawGlassPane(canvas, mini, r, reactive(glassNoticeTint(), mini), held = true)) {
                noticePaint.style = Paint.Style.FILL
                noticePaint.color = glassNoticeTint()
                canvas.drawRoundRect(mini, r, r, noticePaint)
            }
            canvas.save()
            canvas.clipRect(mini)
            val rowTop = mini.top + (miniH - CASCADE_H) / 2f
            leaving?.takeIf { it.hasMore }
                ?.let { drawMiniRow(canvas, it, rowTop + turn * CASCADE_H, CASCADE_H) }
            notice?.takeIf { it.hasMore }
                ?.let { drawMiniRow(canvas, it, rowTop - (1f - turn) * CASCADE_H, CASCADE_H) }
            canvas.restore()
            canvas.restore()
        }

        // ---- the headline, over it -------------------------------------------------
        //
        // ITS TOP-RIGHT CORNER FOLLOWS THE DISPLAY'S. That corner is the only one of the
        // four sitting next to the screen's own curve, and a small radius beside a large
        // one reads as a card that has been dropped there. Given screen-minus-inset the
        // two arcs share a centre exactly, so the card's edge runs parallel to the
        // display's the whole way round at a constant margin — see GlassCard.
        val card = android.graphics.RectF(NOTICE_LEFT, cardTop, right, cardBottom)
        val tr = GlassCard.innerRadius(cornerRadius(), margin, r)
        noticeShape.rewind()
        noticeShape.addRoundRect(
            card,
            floatArrayOf(r, r, tr, tr, r, r, r, r),
            android.graphics.Path.Direction.CW,
        )
        // REACTIVE, like the mini one directly above it. This was the bare tint, which
        // made the notification the one pane on the panel that ignored what was behind
        // it — the cards, the buttons, the rings, the charging cell and the notice's own
        // collapsed form all ask, and the full card did not. Over a photograph it sat at
        // whatever weight suited a black panel, which is exactly the failure the
        // luminance grid was built for.
        if (!drawGlassPane(
                canvas, card, r, reactive(glassNoticeTint(), card),
                shape = noticeShape, held = true,
            )
        ) {
            noticePaint.style = Paint.Style.FILL
            noticePaint.color = glassNoticeTint()
            canvas.drawPath(noticeShape, noticePaint)
        }
        canvas.save()
        canvas.clipRect(card)
        // The drum still turns inside the card. Position only, nothing fades — see the
        // note in drawNotice.
        val rowShift = (card.height() - NOTICE_H) / 2f
        leaving?.let { drawNoticeRow(canvas, it, cardTop + rowShift + turn * NOTICE_H) }
        (notice ?: outgoing)?.let {
            drawNoticeRow(canvas, it, cardTop + rowShift - (1f - turn) * NOTICE_H)
        }
        canvas.restore()
    }

    /**
     * The glass card's own tint.
     *
     * The user's notification colour at a fraction of its opacity, rather than Glass.TINT:
     * the ground colour is a setting somebody chose, and a theme that ignored it would be
     * a theme that took a preference away.
     */
    private fun glassNoticeTint(): Int =
        (colorFor("notice:bg", config.notificationGround or (0xFF shl 24)) and 0x00FFFFFF) or
            (GLASS_NOTICE_ALPHA shl 24)

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
        // real pixels from the window, brought into panel units
        return ((fromWindow?.toFloat() ?: FALLBACK_CORNER) / fitX).also { cornerRadius = it }
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

    /**
     * A sheet of glass behind something, bending what is behind the readout.
     *
     * The account cards' own material and the buttons' lens. [screenCorner] gives the
     * top-right corner the display's own curve less the margin, so the two arcs share a
     * centre and the card's edge runs parallel to the screen's — the notification cards
     * learned this first, and any card that reaches the corner needs it or it reads as
     * something dropped there rather than something fitted.
     */
    private fun glassCard(
        canvas: Canvas,
        rect: android.graphics.RectF,
        radius: Float = CARD_RADIUS,
        screenCorner: Boolean = false,
        /** How present it is, for a page still arriving. See the transport buttons. */
        alpha: Float = 1f,
        /** With [screenCorner]: the card sits in the screen's bottom-right corner, not its top-right. */
        bottom: Boolean = false,
    ) {
        if (!config.theme.isGlass || alpha <= 0.004f) return
        if (alpha < 0.999f) {
            fading(canvas, rect, alpha) { glassCard(canvas, rect, radius, screenCorner, bottom = bottom) }
            return
        }
        var shape: android.graphics.Path? = null
        if (screenCorner) {
            val tr = GlassCard.innerRadius(cornerRadius(), Panel.WIDTH - rect.right, radius)
            cardShape.rewind()
            cardShape.addRoundRect(
                rect,
                if (bottom) floatArrayOf(radius, radius, radius, radius, tr, tr, radius, radius)
                else floatArrayOf(radius, radius, tr, tr, radius, radius, radius, radius),
                android.graphics.Path.Direction.CW,
            )
            shape = cardShape
        }
        drawGlassPane(
            canvas, rect, radius, clearReactive(rect), shape = shape,
            source = groundNode, held = true, aberration = Glass.BUTTON_ABERRATION,
        )
    }

    private val cardShape = android.graphics.Path()

    /** Whether the corner block has anything to say at all. */
    private fun anyExtra(): Boolean =
        config.showClock || config.showDate || config.showBattery

    /**
     * The corner block's box, or false when nothing is in it.
     *
     * Worked out in one place because two things need it: the block draws it, and the
     * masthead has to stop short of it. A card cut for the largest possible block would
     * leave a pane of empty glass under a single clock, so the count decides the height
     * and the widest line the width.
     */
    private fun extrasCard(out: android.graphics.RectF): Boolean {
        if (!config.theme.isGlass || !anyExtra()) return false
        // ONE SIZE, WHATEVER IS IN IT — the full three rows if any of the three is on, and
        // nothing at all if none are.
        //
        // Sizing it by the count was the obvious thing and the wrong one. Everything below
        // is laid out against the bottom of this block, so turning the clock off did not
        // just remove a line: it moved every account on the panel. A control that quietly
        // relays the whole readout is worse than a little empty glass under a single
        // reading, and the empty glass is only ever visible to somebody who has turned two
        // of the three off and is looking for it.
        val was = extras.textSize
        extras.textSize = typed(18f)
        val widest = maxOf(
            extras.measureText("00:00"),
            maxOf(extras.measureText("WED 00"), extras.measureText("100%")),
        )
        extras.textSize = was
        val h = EXTRA_ICON_H + (EXTRA_ROWS - 1) * EXTRA_LINE_H + EXTRA_PAD_Y * 2f
        val top = topBandCentre() - h / 2f
        out.set(
            CONTENT_RIGHT + EXTRA_PAD_X -
                (EXTRA_PAD_X * 2f + EXTRA_ICON_H + EXTRA_ICON_GAP + widest),
            top,
            CONTENT_RIGHT + EXTRA_PAD_X,
            top + h,
        )
        return true
    }

    private fun drawExtras(canvas: Canvas, bottomRight: Boolean = false) {

        // ---- the card, sized to whichever of the three are on ------------------------
        //
        // A block of one line and a block of three are different objects, and a card cut
        // for the largest of them would leave a pane of empty glass under a single clock.
        // The count decides the height; the widest line decides the width.
        // LEFT-ALIGNED INSIDE THEIR OWN BOX, not right-aligned against the panel.
        //
        // Right-aligned, three lines of different lengths leave three different left
        // edges — which nothing notices while the block floats on the panel, and which is
        // the first thing you see once it has a card around it: the type drifts away from
        // the edge it is supposed to be sitting against. The icons keep their own column
        // on the right, so both edges of the block are straight.
        val boxed = extrasCard(cardRect)
        if (boxed) glassCard(canvas, cardRect, screenCorner = true, bottom = bottomRight)

        // THE ICON LEADS. Left-aligned rows read left to right, so the mark that says what
        // the number IS should arrive before the number does — trailing it, the eye has to
        // read the value, then check what it was, then go back.
        val iconLeft = if (boxed) cardRect.left + EXTRA_PAD_X else CONTENT_RIGHT - EXTRA_ICON_H
        val textLeft = iconLeft + EXTRA_ICON_H + EXTRA_ICON_GAP
        val align = if (boxed) Paint.Align.LEFT else Paint.Align.RIGHT
        // Its own paint, size and alignment, every frame. `extras` is shared with the
        // clock page (92px figures), the media title, the date and the weather, and this
        // row never set its size — so whatever had drawn last decided how big the date
        // and the battery came out, and after the right sequence they stayed huge,
        // restart or not, since the sequence recurred.
        extras = extrasBody
        extras.textSize = typed(EXTRA_TEXT_SIZE)
        extras.textAlign = align

        // From the card's own top, so the two cannot drift apart when either changes.
        var baseline =
            if (boxed) cardRect.top + EXTRA_PAD_Y + EXTRA_ICON_H - 2f
            else TITLE_BASELINE - EXTRA_LIFT
        // BATTERY FIRST, then the time, then the day. The order is the user's: the
        // reading that changes fastest and matters soonest is the one at the top.
        // Battery, clock, date from the top at the top right; the same three from the
        // bottom when the row hangs in the bottom-right corner, so the battery sits
        // nearest the corner either way.
        val rows = mutableListOf<() -> Unit>()
        if (config.showBattery) rows += {
                extras.color = colorFor("battery", config.batteryTint)
                // Read from the sticky ACTION_BATTERY_CHANGED broadcast rather than
                // registering a receiver: this view is destroyed and recreated constantly,
                // and a receiver would have to be unregistered on a lifecycle this view does
                // not own. A sticky read is a snapshot with nothing to leak.
                val level = batteryPercent()
                if (level >= 0) {
                    val iconX = iconLeft
                    val iconY = baseline - EXTRA_ICON_H + 2f
                    extras.textAlign = align
                    canvas.drawText("$level%", if (boxed) textLeft else iconX - 8f, baseline, extras)
                    // The charging glyph replaces the outline entirely rather than being
                    // overlaid: it already contains the bolt, and two glyphs stacked would
                    // read as one badly drawn one.
                    drawBatteryGlyph(canvas, iconX, iconY, EXTRA_ICON_H, level)
                }
                // DOWN A LINE, like the two below it. The battery had no advance for as long
                // as it was drawn last, where there was nothing after it to push out of the
                // way. Reading order put it first — battery, hour, date — and a row that does
                // not move the baseline is a row the next one is drawn on top of.
        }
        if (config.showClock) rows += {
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
                drawIcon(canvas, PanelIcons.CLOCK, iconLeft,
                    baseline - EXTRA_ICON_H + 2f, EXTRA_ICON_H)
                extras.textAlign = align
                canvas.drawText(text, if (boxed) textLeft else CONTENT_RIGHT - EXTRA_ICON_H - 8f, baseline, extras)
        }
        if (config.showDate) rows += {
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
                drawIcon(canvas, PanelIcons.CALENDAR, iconLeft,
                    baseline - EXTRA_ICON_H + 2f, EXTRA_ICON_H)
                extras.textAlign = align
                canvas.drawText(text, if (boxed) textLeft else CONTENT_RIGHT - EXTRA_ICON_H - 8f, baseline, extras)
        }
        for (row in if (bottomRight) rows.asReversed() else rows) {
            row()
            baseline += EXTRA_LINE_H
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
        val w = h * Glyphs.aspect(icon, config.theme)
        val left = x + (h - w) / 2f          // centred in the cell, not left-aligned in it

        // THE GLASS SET, if this theme has one for this glyph.
        val outline = if (config.theme.isGlass) GlassIcons.of(icon) else null
        if (outline != null) {
            // ALWAYS anti-aliased, which is the opposite of the rule below and right for
            // the same reason. The traced glyphs are pixel rows and want hard edges; these
            // are curves, and a curve without smoothing is a staircase at any angle.
            iconPaint.isAntiAlias = true
            iconPaint.color = extras.color
            iconPaint.style = Paint.Style.FILL
            // Material draws on a 960-unit box whose Y axis runs from -960 to 0, so the
            // glyph has to be brought down by a full box before it is anywhere near the
            // cell it was asked for. Done here rather than in the generated data, which
            // stays exactly as the source has it so regenerating stays a copy.
            val k = h / GlassIcons.VIEWPORT
            canvas.save()
            canvas.translate(left, y)
            canvas.scale(k, k)
            canvas.translate(0f, -GlassIcons.ORIGIN_Y)
            canvas.drawPath(outline.path, iconPaint)
            canvas.restore()
            return
        }

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

    /**
     * The battery as the extras row draws it: the charging glyph while a charger is in,
     * otherwise the shell with its level filled — under GLASS the traced shell, under
     * ORIGINAL the glyph. Shared with the battery line always-on puts above the clock.
     */
    private fun drawBatteryGlyph(canvas: Canvas, iconX: Float, iconY: Float, h: Float, level: Int, charging: Boolean = charging()) {
        if (charging) {
            drawIcon(canvas, PanelIcons.CHARGING, iconX, iconY, h)
        } else {
            // Outline, then filled from the bottom to the actual charge — a full
            // battery drawn at 12% is only ever right once.
            val shell =
                if (config.theme.isGlass) GlassIcons.of(PanelIcons.BATTERY) else null
            if (shell != null) {
                // ---- the glass set's battery -------------------------------
                //
                // TWO THINGS WERE WRONG HERE and they compounded into one fat
                // smear. Material's battery_full is a SOLID silhouette, so
                // drawing it and then painting the charge over it in the same
                // colour said nothing — the glyph was already full. And the
                // charge was measured against Glyphs.aspect, which under GLASS is
                // the CELL's aspect of 1 rather than the glyph's ink width of
                // about 0.42, so the bar came out more than twice the width of
                // the battery it was supposed to be inside.
                //
                // Clipping to the glyph settles both. The charge cannot leave the
                // battery whatever the aspect says, and it takes the battery's own
                // shape on the way — rounded corners, terminal and all.
                val k = h / GlassIcons.VIEWPORT
                batteryMatrix.setTranslate(iconX, iconY)
                batteryMatrix.preScale(k, k)
                batteryMatrix.preTranslate(0f, -GlassIcons.ORIGIN_Y)
                shell.path.transform(batteryMatrix, batteryPath)
                // The glyph's OWN extent, so empty is empty and full is full. A
                // fixed inset off the cell would leave a lit sliver at nothing and
                // stop short at everything.
                batteryPath.computeBounds(batteryBounds, true)

                // The empty shell, held back so the charge reads against it. The
                // set is a filled one and a hairline outline among filled glyphs
                // looks like a different icon set; a dimmer solid does not.
                val was = extras.alpha
                extras.alpha = was * BATTERY_SHELL_ALPHA / 255
                drawIcon(canvas, PanelIcons.BATTERY, iconX, iconY, h)
                extras.alpha = was

                val fillTop = batteryBounds.bottom -
                    batteryBounds.height() * (level / 100f)
                canvas.save()
                canvas.clipPath(batteryPath)
                canvas.drawRect(
                    batteryBounds.left, fillTop,
                    batteryBounds.right, batteryBounds.bottom, extras,
                )
                canvas.restore()
            } else {
                drawIcon(canvas, PanelIcons.BATTERY, iconX, iconY, h)
                // The fill follows the GLYPH inside the cell, not the cell itself,
                // or it would spill into the padding the square adds either side.
                val glyphW =
                    h * Glyphs.aspect(PanelIcons.BATTERY, config.theme)
                val glyphX = iconX + (h - glyphW) / 2f
                val inset = h * 0.16f
                val top = iconY + inset
                val bottom = iconY + h - inset
                val fillTop = bottom - (bottom - top) * (level / 100f)
                canvas.drawRect(glyphX + inset * 0.6f, fillTop,
                    glyphX + glyphW - inset * 0.6f, bottom, extras)
            }
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

    /**
     * The masthead's baseline: the middle of the band above the first card.
     *
     * Optical rather than arithmetic — text centres on its own ink, not on its line box,
     * so the half-height used here is half the CAP height and the descenders are ignored.
     * A title centred on its line box sits visibly low, every time.
     */
    /**
     * Where the masthead's centre is.
     *
     * The bar column's middle until the corner block is there, and then the middle of
     * what is left of the row. Centred on the column it reached under the clock — close
     * enough to look accidental before either had a card, and plainly wrong once both
     * did. Centring it on the space it actually has is the same idea the block above the
     * first card uses: put a thing in the middle of its own region, not at an offset from
     * somewhere else.
     */
    private fun titleMid(w: Float): Float {
        val mid = (BAR_LEFT + BAR_RIGHT) / 2f
        if (!extrasCard(extrasRect)) return mid
        // PUSHED LEFT ONLY AS FAR AS IT HAS TO BE.
        //
        // The first attempt centred the masthead in the space beside the corner block,
        // which moved it the WRONG WAY: that space is wider than the bar column, so
        // centring in it shifted the title right, into the very thing it was supposed to
        // clear. What it actually needs is its own column's centre, and to give ground
        // only when the block would otherwise be underneath it.
        val limit = extrasRect.left - CARD_GAP - w / 2f - TITLE_PAD_X
        return minOf(mid, limit)
    }

    private fun titleBaseline(): Float {
        if (firstCardTop <= 0f) return TITLE_BASELINE
        return topBandCentre() + title.textSize * TITLE_OPTICAL
    }

    /**
     * The middle of the band above the first account card.
     *
     * ONE LINE FOR EVERYTHING ON THAT ROW. The masthead and the corner block are the two
     * things up there, and each was placed against something different — one centred in
     * the band, the other at a fixed offset from the top. Matching their heights made them
     * the same size and no more aligned than before, which reads worse than two obviously
     * different objects: near-alignment looks like a miss.
     */
    private fun topBandCentre(): Float =
        if (firstCardTop > 0f) firstCardTop / 2f
        else TITLE_BASELINE - EXTRA_ICON_H / 2f

    /** How far below a centre a cap-height baseline sits, as a fraction of the size. */
    private var firstCardTop = 0f

    private fun drawTitle(canvas: Canvas) {
        // Centred over the BAR COLUMN, not the whole canvas side — the same rule the
        // maker's mark follows, so the masthead and the artwork share one axis instead
        // of each being centred on something different.
        //
        // PAINTED IN BOTH THEMES. It was briefly a lens shaped like its own letterforms
        // under GLASS — the panel showing through the type, bevelled at the strokes —
        // which worked and was not wanted: a masthead is the one thing on this panel that
        // has to be read at a glance from across a room, and a lens over a dark panel is
        // dark. Glass is for the things you look past; this is a thing you look at.
        // ---- its own card -----------------------------------------------------------
        //
        // Sized to the title rather than to the column: the masthead is free text and a
        // card cut to the bar column would be a wide empty pane behind a short word. The
        // corner is not the screen's — this one sits in the middle of the panel and has
        // the display's curve nowhere near it.
        val baseline = titleBaseline()
        if (config.theme.isGlass) {
            val w = title.measureText(config.title)
            val mid = titleMid(w)
            // STOPPING SHORT OF THE CORNER BLOCK. The masthead is centred on the bar
            // column and the clock is in the corner, and until both had cards the two
            // merely came close; two overlapping sheets of glass make it obvious, and
            // they double their own tint where they cross.
            var rightEdge = mid + w / 2f + TITLE_PAD_X
            if (extrasCard(extrasRect)) {
                rightEdge = minOf(rightEdge, extrasRect.left - CARD_GAP)
            }
            // THE SAME HEIGHT AS THE BLOCK BESIDE IT. Two cards on one row at two
            // heights read as two unrelated things that happen to be level; matched, they
            // read as one band of furniture. The corner block is the taller of the two and
            // is sized by its contents, so it sets the height and the masthead takes it —
            // centred on the title's own middle, so the type stays where it was.
            val mine = title.textSize * TITLE_OPTICAL * 2f + TITLE_PAD_Y * 2f
            val h = if (extrasCard(extrasRect)) maxOf(mine, extrasRect.height()) else mine
            val centreY = baseline - title.textSize * TITLE_OPTICAL
            cardRect.set(
                mid - w / 2f - TITLE_PAD_X,
                centreY - h / 2f,
                rightEdge,
                centreY + h / 2f,
            )
            if (cardRect.width() > CARD_RADIUS * 2f) glassCard(canvas, cardRect)
        }

        title.color = colorFor("title", config.titleColor)
        // CENTRED IN THE BAND IT HAS, rather than pinned to a baseline measured from the
        // top of the panel. Once the accounts are on cards, the space above the first one
        // is a real region with two edges, and type that sits in the middle of it looks
        // placed where type at a fixed offset looks like it happens to be there. Falls
        // back to the fixed baseline when there are no cards to measure against.
        canvas.drawText(config.title, titleMid(title.measureText(config.title)), baseline, title)
    }


    /**
     * 7-day window bars, one block per account. A window row is only drawn when the
     * account actually reported it — an account with no snapshot yet gets no rows at
     * all (not zeroed bars), and an account with no WEEKLY_SCOPED window (e.g. it has
     * never used a scoped model) simply has no third row. `w.label` is rendered
     * verbatim: the third window's name is whatever the live response resolved
     * (`scope.model.display_name`), never a hardcoded model name.
     */
    /**
     * A label in the theme's own case.
     *
     * ORIGINAL SHOUTS ON PURPOSE AND GLASS DOES NOT. Small caps in a pixel face are how a
     * terminal readout labels a column: the letters are the same height as the digits
     * beside them and the row scans as one line. Outfit has real lowercase with ascenders
     * and descenders, and setting it in caps throws that away — the words come out as
     * even grey blocks with nothing to tell them apart at a glance, which is exactly what
     * capitals are for and exactly wrong for a proportional face.
     *
     * Only the first letter, so "ALL 5H" reads "All 5h". The units go down with the rest
     * of it: a lone capital H in a lowercase word is a different word.
     */
    private fun cased(text: String): String {
        if (!config.theme.isGlass || text.isEmpty()) return text
        return text[0].uppercaseChar() + text.substring(1).lowercase()
    }

    /** Account indices in the configured display order. */
    private fun orderedAccounts(): List<Int> = config.orderedAccounts(Account.entries.size)

    /**
     * Whether the account has a credential at all. Never connected, or signed out, is
     * false; expired is true — that account stays, as a ghost with SIGN IN, because it was
     * yours and wants you back. Unknown, before the first state arrives, counts as yes.
     */
    private fun connected(account: Account): Boolean =
        state.statuses.firstOrNull { it.account == account }?.connected ?: true

    /** The accounts the readout shows, in the user's order: those with a credential. */
    private fun shownAccounts(): List<Int> =
        orderedAccounts().filter { i -> Account.entries.getOrNull(i)?.let { connected(it) } ?: false }

    /**
     * No account has a credential: the readout page is not shown at all. The clock page
     * is home and the media page, when there is one, sits below it; the rings stay as
     * the media dials. Never in the single-account preview, which shows what it is told.
     */
    private val readoutGone: Boolean get() = onlyAccount == null && shownAccounts().isEmpty()
    private val homePage: Int get() = if (readoutGone) PAGE_NOTICES else PAGE_READOUT
    private val mediaPage: Int get() = if (readoutGone) 1 else PAGE_MEDIA
    private var readoutWasGone = false

    /**
     * What the preview on the front screen mirrors so that it shows what the back
     * shows: the page, always-on, the slider, the closed rings, the table lights, and
     * the display's own corner radius. The panel publishes one whenever it changes;
     * the preview applies it. Everything else the two already share — the state, the
     * config, the notifications and the media arrive at both by the same broadcasts.
     */
    data class Mirror(
        val page: Int, val aod: Boolean, val locked: Boolean, val ringsClosed: Boolean,
        val tableGlow: Boolean, val corner: Float,
    )
    var onMirror: ((Mirror) -> Unit)? = null
    private var lastMirror: Mirror? = null
    private fun publishMirror() {
        val cb = onMirror ?: return
        val m = Mirror(pager.page, aod, locked, ringsClosed, tableGlow, cornerRadius())
        if (m != lastMirror) { lastMirror = m; cb(m) }
    }
    /** Have the mirror published again on the next frame, whether or not it changed. */
    fun republishMirror() { lastMirror = null; invalidate() }

    /** The preview takes the back's state. The corner is the back's measured one, not this window's. */
    fun mirror(m: Mirror) {
        cornerRadius = m.corner
        if (ringsClosed != m.ringsClosed) ringsClosed = m.ringsClosed
        if (tableGlow != m.tableGlow) tableGlow = m.tableGlow
        if (aod != m.aod) aod = m.aod
        if (m.locked && !locked) lock() else if (!m.locked && locked) unlock()
        if (!aod && pager.page != m.page) pager.goTo(m.page, SystemClock.uptimeMillis())
        invalidate()
    }

    /** Whether the media page was wanted last frame, and whether that has been seen at all yet. */
    private var mediaPageWasWanted = false
    private var mediaWantKnown = false



    /**
     * The account blocks, in two passes over one layout.
     *
     * [cardsOnly] draws the glass and stops. It exists because the cards have to go down
     * before the MASCOTS — the animals stand on the bars and are drawn ahead of them, so a
     * card laid at the start of the readings pass paints over the very figures it is
     * supposed to be behind. Running the same layout twice is not free, but it is a dozen
     * lines of arithmetic against duplicating that arithmetic in a second place, which is
     * how two things that must agree stop agreeing.
     */
    private fun drawBars(canvas: Canvas, cardsOnly: Boolean = false) {
        if (!cardsOnly) sessionBars.clear()
        val left = CONTENT_LEFT
        val right = CONTENT_RIGHT

        // Layout metrics scale with the type. These were originally tuned for 12/15/14pt
        // text; when the sizes went to 18/22.5/21 the rows kept their old pitch and
        // crowded. Keep them proportional to the fonts rather than as loose literals.
        val barH = BAR_ROW_H
        val rowPitch = ROW_PITCH
        val headingAdvance = 30f

        // Measure both account blocks before drawing anything, so the leftover space
        // can be shared out evenly instead of accumulating at the bottom.
        val blocks = orderedAccounts()
            .mapNotNull { Account.entries.getOrNull(it) }
            .filter { onlyAccount == null || it == onlyAccount }
            .filter { onlyAccount != null || connected(it) }

            .map { account ->
            val status = state.statuses.firstOrNull { it.account == account }
            ?.let { demoStatus(it) }
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
        // The corner block is one size or absent — see extrasCard — so this is a yes or a
        // no rather than a count. It used to be a count, which is what made turning the
        // clock off shuffle every account down the panel.
        val extraLines = if (anyExtra()) EXTRA_ROWS else 0
        // A LITTLE FURTHER DOWN, for air under the masthead now that it has a card.
        //
        // Safe to move by construction rather than by measurement: the gap below is not a
        // constant, it is whatever is left of the region divided between the blocks, so
        // pushing the top down takes the space out of the gaps and the last block still
        // ends exactly where it did — clear of the artwork slot and the unlock slider,
        // which is what regionBottom is measured from.
        // WHAT IS ACTUALLY UP THERE, rather than a line count standing in for it.
        //
        // The corner block does not sit at a fixed height: it is CENTRED in the band
        // above the first card — see topBandCentre. A block of height h centred that way
        // has its bottom at firstCardTop/2 + h/2, which clears whatever is below it
        // exactly when firstCardTop is at least h. So the only thing the accounts owe the
        // corner block is enough band for it to be centred in, which is its own height
        // and nothing more.
        //
        // The count this replaces reserved a line of the block's height for every row it
        // had, on top of the masthead's own allowance — about fifty units more than the
        // block occupies. That was the space the two accounts needed, and it is why they
        // came out touching however the gaps were divided: the arithmetic below was
        // sharing out a region that had already given away the room.
        val topBandNeed =
            if (extraLines <= 0) TITLE_BASELINE + 26f
            else EXTRA_ICON_H + (EXTRA_ROWS - 1) * EXTRA_LINE_H + EXTRA_PAD_Y * 2f
        val regionTop = if (onlyAccount != null) SINGLE_ACCOUNT_TOP
            else maxOf(TITLE_BASELINE + 26f, topBandNeed) + BLOCKS_DROP
        // Without a masthead above or a mark below, one account has the whole height.
        val regionBottom = if (onlyAccount != null) Panel.HEIGHT - SINGLE_ACCOUNT_TOP
            else Panel.HEIGHT - MARK_BOTTOM - MARK_H
        // One gap above the first block, one between, one below the last — so the
        // blocks sit evenly rather than hugging the top.
        // One account centres in the space it has; several share it evenly. Centring is
        // what puts the block level with the ring beside it — the two are read as one
        // row, and a block starting at the top with a ring in the middle is not that.
        // WHAT THE CARD NEEDS THAT THE CONTENT DOES NOT.
        //
        // A block's height is its heading and its bars. Its CARD is taller than that at
        // both ends — up over the heading's own cap height and down past the last bar —
        // and none of that was ever in the budget, because the budget was written before
        // there were cards. So the gap between two blocks was measured as if they were
        // two columns of text, and once they became two sheets of glass the personal
        // account's card ran into the work account's heading. At three bars each it
        // overlapped by fifteen units; at four the blocks themselves collided.
        val cardExtra =
            if (!config.theme.isGlass) 0f
            else (CARD_PAD_TOP + CARD_PAD_BOTTOM + heading.textSize + BAR_ROW_H - rowPitch)
                .coerceAtLeast(0f)
        // EQUAL SPACE, MEASURED BETWEEN CARD EDGES.
        //
        // A block's y is its heading's baseline, and its card reaches above that by the
        // heading's own height and its padding. So laying blocks out at an equal PITCH
        // does not put an equal amount of glass-free panel between them: the space above
        // the first card is short by exactly that overhang, which is why raising the
        // stack ran the personal card up into the masthead's band.
        //
        // The gap that matters is the one you can see — card bottom to card top — so that
        // is the one held equal. Above the first card it is measured from the top of the
        // region; between two cards, from one edge to the other. Both come out as v.
        val cardTopOffset =
            if (config.theme.isGlass) heading.textSize + CARD_PAD_TOP else 0f
        val gap: Float
        val lead: Float
        if (onlyAccount != null) {
            gap = ((regionBottom - regionTop - contentH) / 2f).coerceAtLeast(0f)
            lead = gap
        } else if (blocks.size == 1) {
            // One account connected: centred, not left at the bottom of a region laid
            // out for two with a grey SIGN IN taking the rest.
            val slack = (regionBottom - regionTop - contentH - cardExtra).coerceAtLeast(0f)
            gap = slack / 2f
            lead = cardTopOffset + slack / 2f
        } else if (config.theme.isGlass) {
            // n cards need n gaps: one above the first, one between each pair after it.
            // Nothing is reserved below the last, because the region's own bottom is
            // already measured clear of the artwork and the slider.
            val v = ((regionBottom - regionTop - contentH - blocks.size * cardExtra) /
                blocks.size).coerceAtLeast(0f)
            gap = cardExtra + v
            lead = cardTopOffset + v
        } else {
            // No cards, so a block IS its content and the old even share is right.
            gap = ((regionBottom - regionTop - contentH) / (blocks.size + 1))
                .coerceAtLeast(0f)
            lead = gap
        }

        var y = regionTop + lead

        // ---- a card per account ------------------------------------------------------
        //
        // ONE ACCOUNT IS ONE THING, and until now the only thing saying so was the gap
        // between blocks. On a black panel that reads well enough; over a photograph it
        // stops reading at all, because the background has more contrast in it than the
        // spacing does. A card says where an account begins and ends whatever is behind
        // it, which is the job a card has always had.
        //
        // Laid down in one pass BEFORE any of the content, so a card cannot land on the
        // block above it — and bending the ground rather than the panel, because at this
        // point in the frame the ground is all there is. See groundNode.
        if (config.theme.isGlass && cardsOnly) {
            var cardY = y
            firstCardTop = 0f
            for ((_, _, rows) in blocks) {
                val blockH = headingAdvance + rows.size * rowPitch
                cardRect.set(
                    left - CARD_PAD_X,
                    cardY - heading.textSize - CARD_PAD_TOP,
                    right + CARD_PAD_X,
                    cardY + blockH - rowPitch + BAR_ROW_H + CARD_PAD_BOTTOM,
                )
                // THE BUTTONS' OWN GLASS, which means no tint of its own: clear, with
                // whatever the backdrop asks for at the ends of the range. A card with a
                // body is a surface to read type against, and these are not that — they
                // are windows with readings lying on them.
                if (firstCardTop == 0f) firstCardTop = cardRect.top
                drawGlassPane(
                    canvas, cardRect, CARD_RADIUS, clearReactive(cardRect),
                    source = groundNode, held = true,
                    aberration = Glass.BUTTON_ABERRATION,
                )
                cardY += blockH + gap
            }
        }
        if (cardsOnly) return

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
                canvas.drawText(cased(it), right, y, planTag)
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
                canvas.drawText(cased(w.label), left, y + barH - 4f, label)
                val barLeft = BAR_LEFT
                val barRight = BAR_RIGHT
                // UNDER GLASS A BAR IS A SLIDER, and the glass is the THUMB.
                //
                // That is how the reference component is built and it is the right way
                // round: a track made of glass is a long thin pane whose lens has nowhere
                // to work — the bending lives in a band around the edge and the whole
                // thing is edge — where a thumb is a small object with a body, which is
                // exactly what a lens has something to do with. It also costs one pane a
                // row instead of two.
                //
                // So the track goes THIN, the way the reference's does, and the row's
                // height is given over to the thumb standing proud of it.
                val glassBar = config.theme.isGlass
                val trackH = if (glassBar) barH * SLIDER_TRACK else barH
                val trackTop = y + (barH - trackH) / 2f
                val barR = trackH / 2f
                if (!glassBar) {
                    canvas.drawRect(barLeft, y, barRight, y + barH, barTrack)
                }
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
                val fraction = animateTo(
                    "bar:${account.name}:${w.kind.name}",
                    demoBar(account, rows.indexOf(w)) ?: w.utilization,
                )
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
                if (glassBar) {
                    val span = barRight - barLeft
                    val f = fraction.coerceIn(0f, 1f)
                    val end = barLeft + span * f
                    val reading = barFill.color

                    // The track and the reading, published as a layer of their own so the
                    // thumb has something local to bend — see localBackdrop, and the
                    // reference component, which does exactly this.
                    trackRect.set(barLeft, trackTop, barRight, trackTop + trackH)
                    val layer = localBackdrop(canvas, trackRect) { into ->
                        barTrack.color = Panel.Palette.TRACK
                        into.drawRoundRect(trackRect, barR, barR, barTrack)
                        if (end > barLeft + 0.5f) {
                            barRect.set(barLeft, trackTop, end, trackTop + trackH)
                            barFill.color = reading
                            into.drawRoundRect(barRect, barR, barR, barFill)
                        }
                    }
                    if (layer == null) {
                        barTrack.color = Panel.Palette.TRACK
                        canvas.drawRoundRect(trackRect, barR, barR, barTrack)
                        if (end > barLeft + 0.5f) {
                            barRect.set(barLeft, trackTop, end, trackTop + trackH)
                            barFill.color = reading
                            canvas.drawRoundRect(barRect, barR, barR, barFill)
                        }
                    }

                    // The thumb, CENTRED ON THE READING — see SliderThumb. It used to
                    // be packed inside the track, which quietly compresses the whole
                    // travel: the centre then only went from half a thumb in to half a
                    // thumb short, so a bar at nothing showed its thumb already advanced
                    // and one at everything showed it stopping short.
                    val tw = barH * SLIDER_THUMB_W
                    val th = barH * SLIDER_THUMB_H
                    val cx = barLeft + SliderThumb.centre(span, tw, f)
                    val cy = y + barH / 2f
                    barRect.set(cx - tw / 2f, cy - th / 2f, cx + tw / 2f, cy + th / 2f)
                    if (!drawGlassPane(
                            canvas, barRect, th / 2f, Glass.THUMB_TINT,
                            source = layer ?: sceneNode, held = true,
                        )
                    ) {
                        barFill.color = reading
                        canvas.drawRoundRect(barRect, th / 2f, th / 2f, barFill)
                    }
                } else canvas.drawRect(
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
                canvas.drawText(
                    demoTime(account, rows.indexOf(w)) ?: remaining(w.resetsAt),
                    TIME_RIGHT, y + barH - 4f, timeTag,
                )
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

    /** The surface a mascot stands on: the top of whatever the reading is drawn in. */
    private fun mascotFloor(top: Float): Float =
        if (config.theme.isGlass) {
            top + BAR_ROW_H * (1f - SLIDER_TRACK) / 2f
        } else {
            top
        }

    private fun drawMascots(canvas: Canvas) {
        val now = if (aod) aodWallAt else System.currentTimeMillis()
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
                    // On the track, not on the row — the same correction the standing
                    // figures needed. This branch has its own placement, so fixing one
                    // left the walker hovering.
                    top = mascotFloor(bar.top) - h,
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
            // ON THE TRACK, which under GLASS is not the row. The bar fills its whole row
            // in ORIGINAL, so standing on the row's top edge stands on the bar; the glass
            // slider's track is a third of that height and centred, so the same line left
            // the figures hovering a few units above the thing they are standing on.
            val top = mascotFloor(bar.top) - h
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
        val region = markRegion()

        // THE LOCK TAKES THE SLOT, on the same drum and over the same 200ms as the two
        // things that normally share it. Whatever is in there leaves through the bottom
        // and the slider arrives from the top — one movement, indistinguishable from the
        // artwork handing over to the player, which is what was asked for.
        val lock = lockSwap(frameNowMs)
        if (lock >= 0.999f) {
            mediaStrip = null
            return
        }
        if (lock > 0.001f) {
            // The occupant leaves through the bottom on the lock's drum. What replaces it
            // is NOT drawn here — see drawLockOverlay for why the slider cannot live in
            // this slot even though it takes its place.
            canvas.save()
            canvas.clipRect(mediaClip(region))
            canvas.translate(0f, lock * mediaClip(region).height())
            drawSlot(canvas, region, dx, dy)
            canvas.restore()
            // No strip while the slider is anywhere near the slot. The transport buttons
            // register their targets as they draw, and a half-departed row that still
            // answered to a press is exactly the kind of thing the lock exists to stop.
            mediaStrip = null
            return
        }
        drawSlot(canvas, region, dx, dy)
    }

    /** What the slot holds when the panel is not locked: the mark, or the player. */
    private fun drawSlot(
        canvas: Canvas,
        region: android.graphics.RectF,
        dx: Float,
        dy: Float,
    ) {
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
     * The slider, at its fixed place on the panel, arriving on the lock's drum.
     *
     * Same movement as the slot's occupant leaving — in from the top over the same 200ms
     * — so the two read as one drum turning even though only one of them is in the slot.
     * Clipped to its own band so it is not visible above the panel on the way in.
     *
     * No burn-in drift. The drift shifts what is painted and never the window, so a
     * drifted slider would walk a few units away from the touch target that answers for
     * it — and this one is only up for seconds at a time, which is no burn-in risk at all.
     */
    private fun drawLockOverlay(canvas: Canvas) {
        val lock = lockSwap(frameNowMs)
        if (lock <= 0.001f) return
        val region = markRegion()
        canvas.save()
        // THE BAND IS FOR THE ARRIVAL, AND NOTHING ELSE. It clips the slider to the slot
        // while it slides down into it, which is the whole reason it exists — but it was
        // left in place afterwards, and a held knob swells to one and a half times the
        // slot's height. Half of that swell is above the band, so the thing that grows to
        // say "yes, you have this" had its top sheared off at the moment it grew.
        //
        // Once it has arrived there is nothing to slide and the translate is zero, so the
        // band has no work left to do.
        if (lock < 0.999f) {
            canvas.clipRect(
                0f, region.top, Panel.WIDTH.toFloat(), region.bottom + LOCK_BAND_SLOP,
            )
            canvas.translate(0f, -(1f - lock) * (region.height() + LOCK_BAND_SLOP))
        }
        drawLock(canvas, region, lockKnob(frameNowMs))
        canvas.restore()
    }

    /**
     * The unlock slider.
     *
     * Drawn in the panel's own vocabulary rather than in a system lockscreen's: a groove,
     * a knob and a word, in the same greys and the same mono face as everything else. It
     * is not pretending to be Android's lockscreen, because it is not one — the phone is
     * not locked, this one surface is.
     *
     * The track is the FULL slot band and the knob is a disc inside it. That is what
     * makes the target findable by thumb on the back of a phone: the groove is a physical
     * hint about where to put a finger and which way to push, and it is the only thing on
     * the panel at that moment.
     */
    private fun drawLock(
        canvas: Canvas,
        region: android.graphics.RectF,
        progress: Float,
    ) {
        val h = region.height()
        val r = h / 2f
        scratch.set(LockSlider.left(), region.top, LockSlider.right(), region.bottom)
        val glass = config.theme.isGlass

        // The container.
        if (glass) {
            if (!drawGlassPane(canvas, scratch, r, Glass.TINT)) {
                barTrack.color = Panel.Palette.TRACK
                canvas.drawRoundRect(scratch, r, r, barTrack)
            }
        } else {
            // Filled at the palette's track grey and edged a shade brighter, the same two
            // tones the usage bars use — a channel cut into the panel rather than a
            // control laid on top of it.
            barTrack.color = Panel.Palette.TRACK
            canvas.drawRoundRect(scratch, r, r, barTrack)
            track.color = Panel.Palette.RULE
            track.strokeWidth = 1.5f
            canvas.drawRoundRect(scratch, r, r, track)
        }

        // The instruction, in the container's own quiet grey.
        drawLockLabel(canvas, region, Panel.Palette.FG, LOCK_TEXT_ALPHA)

        // AND A SECOND COPY OF IT, IN THE ACCENT, INVISIBLE — with the panel behind it.
        //
        // This is the tab bar's own trick. Its tabs are drawn twice: once normally, and
        // once tinted and at zero alpha, and the indicator's backdrop is the tinted copy.
        // So a tab the indicator is over shows through it in the accent while every other
        // tab stays plain — the indicator does not paint anything, it REVEALS.
        //
        // The panel goes in underneath because this is an overlay: the scene is finished
        // and can be sampled, so the indicator bends the readout as well as the words.
        val layer = if (glass) {
            localBackdrop(canvas, scratch, visible = false) { into ->
                (into as? android.graphics.RecordingCanvas)?.drawRenderNode(paneBackdrop)
                // The container's own body, so the indicator bends the track it is
                // riding rather than showing the panel straight through it.
                panePaint.shader = null
                panePaint.style = Paint.Style.FILL
                panePaint.color = Glass.TINT
                into.drawRoundRect(scratch, r, r, panePaint)
                drawLockLabel(into, region, LOCK_REVEAL, 255f)
            }
        } else {
            null
        }

        // The indicator, swelling while it is held — the reference's pressedScale. The
        // finger is on the BACK of the phone and covers nothing, so there is no contact
        // patch to see; the control growing is the only acknowledgement available.
        val press = lockPress(frameNowMs)
        val grow = 1f + (LockSlider.PRESSED_SCALE - 1f) * press
        val tw = LockSlider.thumbWidth(h) * grow
        val th = LockSlider.thumbHeight(h) * grow
        val cx = LockSlider.thumbLeft(progress, h) + LockSlider.thumbWidth(h) / 2f
        val cy = region.centerY()
        lockThumb.set(cx - tw / 2f, cy - th / 2f, cx + tw / 2f, cy + th / 2f)
        val tr = th / 2f

        val paned = glass && drawGlassPane(
            canvas, lockThumb, tr, Glass.THUMB_TINT,
            source = layer ?: sceneNode, held = true,
        )
        if (!paned) {
            // The indicator, in the panel's foreground so it is the brightest thing on
            // the display — it is the only thing that will answer.
            barFill.color = Panel.Palette.FG
            canvas.drawRoundRect(lockThumb, tr, tr, barFill)
        }

        // A chevron pointing the way. Two strokes rather than a traced glyph: the icon
        // set has no arrow, and an arrow is two lines.
        val a = LOCK_CHEV * 0.5f * grow
        arc.color = if (paned) Panel.Palette.FG else Panel.Palette.BG
        arc.strokeWidth = LOCK_CHEV_W
        arc.strokeCap = Paint.Cap.ROUND
        canvas.save()
        canvas.translate(cx, cy)
        canvas.drawLine(-a * 0.45f, -a, a * 0.5f, 0f, arc)
        canvas.drawLine(a * 0.5f, 0f, -a * 0.45f, a, arc)
        canvas.restore()
        arc.strokeCap = Paint.Cap.BUTT
    }

    /**
     * The instruction, centred in the track.
     *
     * Drawn twice per frame at two colours — see drawLock — so it is one function rather
     * than one function and a copy of it that will drift.
     */
    private fun drawLockLabel(
        canvas: Canvas,
        region: android.graphics.RectF,
        colour: Int,
        alpha: Float,
    ) {
        val was = extras.textAlign
        extras.textAlign = Paint.Align.CENTER
        extras.textSize = typed(LOCK_TEXT)
        extras.color =
            (colour and 0x00FFFFFF) or (alpha.toInt().coerceIn(0, 255) shl 24)
        canvas.drawText(
            if (tipAt != 0L) CAMERA_TIP_LABEL else LOCK_LABEL,
            (LockSlider.left() + LockSlider.right()) / 2f,
            region.centerY() + LOCK_TEXT * 0.36f,
            extras,
        )
        extras.textAlign = was
    }

    /**
     * How far the media page's title block moves up to clear the unlock slider.
     *
     * MEASURED, not a constant, because both ends of it move: the slider sits in the mark
     * band, whose height is derived from the bar span, and the artist's descent depends on
     * the face and the theme's scale. A number typed in here would be right for one of
     * those and quietly wrong for the next.
     *
     * Scaled by the lock's own swap so the block rises as the slider arrives and settles
     * back as it leaves — the same movement, not a jump on either side of it.
     */
    private fun lockLift(): Float {
        val swap = lockSwap(frameNowMs)
        if (swap <= 0.001f) return 0f
        val was = extras.textSize
        extras.textSize = typed(MediaPage.ARTIST_SIZE)
        val below = MediaPage.ARTIST_BASELINE + extras.fontMetrics.descent
        extras.textSize = was
        val room = markRegion().top - LOCK_TEXT_CLEARANCE
        return swap * kotlin.math.max(0f, below - room)
    }

    /** How much air the title block keeps above the slider once it has moved. */
    private val LOCK_TEXT_CLEARANCE = 4f

    /** How far the indicator has swollen, 0 to 1, easing both ways. */
    private fun lockPress(nowMs: Long): Float {
        if (lockPressAt == 0L) return lockPressTo
        val t = (nowMs - lockPressAt).toFloat() / LockSlider.PRESS_MS
        if (t >= 1f) return lockPressTo
        animating = true
        return lockPressFrom + (lockPressTo - lockPressFrom) * Tween.easeInOut(t.coerceIn(0f, 1f))
    }

    private fun pressLock(down: Boolean) {
        val to = if (down) 1f else 0f
        if (to == lockPressTo) return
        lockPressFrom = lockPress(SystemClock.uptimeMillis())
        lockPressTo = to
        lockPressAt = SystemClock.uptimeMillis()
    }

    private var lockPressFrom = 0f
    private var lockPressTo = 0f
    private var lockPressAt = 0L

    private val lockThumb = android.graphics.RectF()
    private val noticeShape = android.graphics.Path()
    private val rimShift = android.graphics.Matrix()

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

        // THE LOCK LEAVES THE RECORD ALONE, at the user's decision after seeing it both
        // ways. It was sent out and brought back at first, on the same exit the music
        // stopping gives it — which animated well, and was the wrong call: the disc is
        // scenery rather than a control, so hiding it bought no safety and cost the panel
        // most of what it looks like. What has to go is anything that can be PRESSED, and
        // the slider covers that band on both pages by itself.
        val base = discAt(paging, notice)
        // Slides out of the bottom when the music stops. It arrived as an object and it
        // leaves as one, the way a record comes off a deck.
        // AND IT GETS OUT OF THE WAY OF THE PAGE ABOVE. The record is drawn behind every
        // page, which is right for the two that were built around it and wrong for the
        // notification page: a clock, a date and a card of somebody's messages, all laid
        // over a black disc with grooves in it. The date in particular was unreadable
        // wherever it crossed the label.
        //
        // It goes DOWN rather than fading, and it goes down with the scroll, because that
        // is what the gesture is already saying: the readout is sliding off the bottom and
        // the record is part of the readout. Sunk until only a slice of its top edge shows
        // along the bottom of the panel — enough to say what is still down there, not
        // enough to read anything through.
        val sunk =
            if (config.mediaArt == MediaArt.VINYL) {
                noticing * (Panel.HEIGHT * (1f - VINYL_PEEK) - (base.cy - base.radius))
            } else {
                // THE SQUARE IS NOT SCENERY. Everything above about the lock and the peek
                // is true of the record and false of the row's thumbnail, which is a
                // control-sized picture sitting in the very band the slider covers and in
                // the corner the page above the readout is supposed to clear.
                //
                // So it leaves through the bottom on the lock's own drum, the way the
                // rest of the row does — on the readout page only, weighted by how far it
                // has travelled toward the media page, where it is the picture in the
                // middle and the slider covers its band by itself. And on the page above
                // it goes ALL the way down rather than to a peek: a peek is a slice of a
                // record's top edge saying what is down there, and a slice of a thumbnail
                // is a stripe of nothing in particular.
                val exit = Panel.HEIGHT - (base.cy - base.radius)
                maxOf(noticing, lockSwap(frameNowMs) * (1f - paging)) * exit
            }
        val resting = MediaLayout.Disc(
            base.cx,
            base.cy + (1f - swap) * (Panel.HEIGHT + base.radius) + maxOf(0f, sunk),
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

        // The record takes the media colour like everything else on the row. It used to
        // have a colour of its own, which is a setting that existed because the record was
        // DRAWN — and a drawn record is a shape that has to be told what colour to be.
        val ink = colorFor("media", config.mediaTint)
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

        // ---- the record itself, which is a photograph of one -----------------------
        //
        // It used to be constructed: a black circle, thirty-four concentric strokes for
        // the grooves, a rim and two sheen arcs. That drew a diagram of a record, and it
        // is the honest limit of the approach — the things that actually say "vinyl" are
        // the way light runs in bands across the grooves, the sheen falling off toward
        // the rim, the faint unevenness of a pressing. A stack of circles at graded
        // alphas can gesture at those; it cannot claim them.
        //
        // THE DISC IS NOT TURNED, which is the decision the sheen used to make on its own
        // and for the same reason. This image has a fixed highlight lying across it the
        // way a record under a lamp does, and spinning the disc would spin the lamp with
        // it. What turns is the label, which is where a record's rotation is legible
        // anyway — the grooves are a featureless black ring whichever way round they are.
        // THE SPINDLE IS A HOLE, and it has to be cut rather than painted.
        //
        // It used to be a dot filled with the panel's background colour, which is the same
        // thing as a hole for exactly as long as the panel's background IS that colour.
        // Put a photograph behind the readout and the illusion goes: the record gains a
        // small flat disc of the wrong colour in its middle, sitting on top of a picture
        // it is supposed to be showing through.
        //
        // So the disc is composed in a layer and the spindle is taken OUT of it, which
        // reveals whatever the panel had already drawn underneath — the ground, the
        // photograph, the readout, whatever it happens to be. A clip would have been
        // cheaper than a layer, but clip paths are not anti-aliased on a hardware canvas
        // and this hole is six pixels across; a jagged six-pixel circle is worse than the
        // wrong colour.
        spindleBounds.set(
            disc.cx - disc.radius, disc.cy - disc.radius,
            disc.cx + disc.radius, disc.cy + disc.radius,
        )
        val discLayer = canvas.saveLayer(spindleBounds, null)

        val record = recordArt
        if (record != null) {
            recordRect.set(
                disc.cx - disc.radius, disc.cy - disc.radius,
                disc.cx + disc.radius, disc.cy + disc.radius,
            )
            // SOLID ON THE MEDIA PAGE, SHEER ON THE READOUT, and travelling between the
            // two with the scroll.
            //
            // The two pages want opposite things from it. On the readout the record is
            // behind six bars and two rings, and a solid disc there is a hole punched in
            // the panel — the background has to carry through it or the readout looks
            // like it is sitting on a coaster. On the media page the record IS the page,
            // and a sheer one looks like a picture of a record rather than the thing.
            //
            // `paging` is already the scroll between them, 0 at the readout and 1 at the
            // media page, so the disc thickens as it arrives. Riding the existing value
            // rather than tweening a second one means it cannot disagree with the scroll.
            artPaint.alpha = (
                VINYL_BODY_ALPHA + (255 - VINYL_BODY_ALPHA) * paging.coerceIn(0f, 1f)
            ).toInt()
            // THE DISC ONLY, never the label. A record's grooves are featureless and its
            // artwork is not: a few degrees of shake reads as a running deck on the black,
            // and as a picture being jogged on the label. The two are drawn separately
            // here anyway, so the wobble simply stops at the edge of the disc.
            canvas.save()
            canvas.rotate(vinylWobble(sleeve), disc.cx, disc.cy)
            canvas.drawBitmap(record, null, recordRect, artPaint)
            canvas.restore()
            artPaint.alpha = 255
        } else {
            // The image ships with the build, so this is not a state that should arise —
            // but a media page with no record on it would look like the panel broke,
            // where a plain black disc just looks like a plain black disc.
            vinylPaint.style = Paint.Style.FILL
            vinylPaint.color = (0x000000 or (VINYL_BODY_ALPHA shl 24))
            canvas.drawCircle(disc.cx, disc.cy, disc.radius, vinylPaint)
        }

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
            artPaint.alpha = 255
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

        // Cut, not painted. Anti-aliased, which is the whole reason this is a layer.
        vinylPaint.style = Paint.Style.FILL
        vinylPaint.xfermode = clearMode
        canvas.drawCircle(
            disc.cx, disc.cy, disc.radius * MediaLayout.VINYL_SPINDLE, vinylPaint)
        vinylPaint.xfermode = null
        canvas.restoreToCount(discLayer)
    }

    /** The record's body, grooves and label. Anti-aliased — it is the one thing on this
     *  panel that is genuinely round rather than pixel art. */
    private val vinylPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** The record's extent, for the layer the spindle is cut out of. */
    private val spindleBounds = android.graphics.RectF()

    /**
     * Kept rather than made, like everything else that wraps a native object here.
     *
     * A PorterDuffXfermode is a small Java object in front of a native one, allocated on
     * the draw path — the exact shape that filled the native heap when the pane lenses
     * were built per frame.
     */
    private val clearMode =
        android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)

    /**
     * The page above the readout: what has come in, and what time it is.
     *
     * Takes no progress value, and that absence is the design: nothing here responds to
     * HOW FAR the scroll has come, because nothing here fades or grows. The page is drawn
     * as it will look and the scroll simply moves it — see the translate at the call site.
     */
    private fun drawNoticePage(canvas: Canvas) {
        // A CLOCK WITH SECONDS ON IT HAS TO KEEP ASKING. Every other moving thing here
        // sets this when it has somewhere to move to; a clock's next frame is a fact
        // about the time rather than about an animation, so it asks unconditionally
        // while the page is on screen. It costs nothing off it — see displayAwake, which
        // stops the request reaching a display that would drop it anyway.
        animating = true
        val ink = colorFor("clock", config.clockTint)
        val cx = MediaPage.centreX(Panel.WIDTH.toFloat(), Panel.CUTOUT_RIGHT.toFloat())
        // NO FADE ON ANYTHING HERE. The page arrives by sliding down from above it — see
        // the translate at the call site — and something that is both moving into place
        // AND becoming visible reads as two effects where the gesture only asked for one.
        // A sheet of paper slid over another does not also become transparent.

        // ---- what has come in ---------------------------------------------------------
        //
        // DEFERRED, for the reason the clock is. A pane can only bend a panel that has
        // finished being drawn, and this page is drawn while the scene is still being
        // recorded — so a card built here got a pane with nothing behind it and came out
        // looking nothing like the notification strip, which is drawn in the overlay pass
        // and gets the real thing. Same pass now, same glass.
        // always-on: the notifications fade away with the backdrop; the clock stays
        val wheelAlpha = 1f - aodFade(frameNowMs)
        if (wheelAlpha > 0.001f) {
            val wheel: (Canvas) -> Unit = { c ->
                if (wheelAlpha < 0.999f) {
                    val layer = c.saveLayerAlpha(0f, 0f, Panel.WIDTH.toFloat(), Panel.HEIGHT.toFloat(), (255 * wheelAlpha).toInt())
                    drawNoticeWheel(c)
                    c.restoreToCount(layer) // whatever the wheel left open, the fade stays the wheel's
                } else drawNoticeWheel(c)
            }
            if (!deferOver(canvas, wheel)) wheel(canvas)
        }

        // ---- the clock, one wheel per digit -----------------------------------------
        //
        // EACH FIGURE ON ITS OWN WHEEL, which is the whole look of it: the seconds roll
        // every second, the tens of seconds every ten, and the hour sits still all hour.
        // A single wheel carrying the whole string would turn all six figures over
        // whenever any one of them changed, which reads as the time being replaced rather
        // than running.
        //
        // Monospaced by construction rather than by font: every slot is measured at the
        // width of a zero and the digit is centred in it, so a 1 does not pull the rest
        // of the clock left as it rolls past.
        val now = java.util.Calendar.getInstance()
        val figures = "%02d:%02d:%02d".format(
            now.get(java.util.Calendar.HOUR_OF_DAY),
            now.get(java.util.Calendar.MINUTE),
            now.get(java.util.Calendar.SECOND),
        )
        extras = extrasBody
        extras.textAlign = Paint.Align.LEFT
        extras.textSize = typed(CLOCK_SIZE)
        val slot = extras.measureText("0")
        val colon = extras.measureText(":")
        // always-on: the seconds fade out over the mode's first second and the rest of
        // the time slides to the centre; on the way back out they return the same way
        val secondsAlpha = 1f - aodFade(frameNowMs)
        // The seconds leave the way the charge cell does: a small grow first, then the
        // shrink to nothing about their own centre — easeOutBack read backwards — and they
        // come back with the same overshoot, the same curve read forwards.
        val secondsScale = Tween.easeOutBack(secondsAlpha)
        var span = 0f
        for (c in figures) span += if (c == ':') colon else slot
        aodClockCx = cx
        aodClockSpan = span // the full width, before the seconds shrink — the band must not breathe
        span -= (colon + 2f * slot) * (1f - secondsScale)
        val clockBase = CLOCK_BASELINE
        var x = cx - span / 2f
        if (aod) armMinuteTick()

        // always-on, if asked: the battery level above the clock, arriving with the mode,
        // and the charging glyph beside it while a charger is in
        // In always-on on request; and always when there is no readout, whose corner row
        // carried the battery — the clock page already says the date and the time.
        val noReadout = onlyAccount == null && readoutGone
        val batteryAlpha = maxOf(
            if (config.aodBattery) aodFade(frameNowMs) else 0f,
            if (noReadout && config.showBattery) 1f else 0f,
        )
        if (batteryAlpha > 0.001f) {
            val pct = batteryPercent()
            if (pct >= 0) {
                val top = clockBatteryBaseline()
                val figure = "$pct%"
                extras.textSize = typed(DATE_SIZE)
                extras.textAlign = Paint.Align.CENTER
                // The battery's own colour, not the clock's it sits over — the corner row's
                // battery has always used it, and the switch for it says "Battery".
                val battery = colorFor("battery", config.batteryTint)
                extras.color = (battery and 0x00FFFFFF) or ((0xFF * batteryAlpha).toInt() shl 24)
                canvas.drawText(figure, cx, top, extras)
                val ih = typed(DATE_SIZE)
                drawBatteryGlyph(canvas, cx + extras.measureText(figure) / 2f + AOD_BATTERY_GAP / 2f, top - ih * 0.85f, ih, pct)
                extras.textAlign = Paint.Align.LEFT
                extras.textSize = typed(CLOCK_SIZE)
            }
        }

        extras.color = (ink and 0x00FFFFFF) or (0xFF shl 24)
        // MADE OF GLASS, the way the charge figure is: the panel is bent through the
        // shapes of the numerals themselves rather than the type being painted on top of
        // a pane. Deferred to the overlay pass because that is the only place it can
        // work — a letterform can only refract a panel that has finished being drawn, and
        // this page is drawn while the scene is still being recorded. See drawSdfText,
        // which says so by returning false, and glassFigure, which is the same test.
        //
        // The wheel survives it. Each slot is clipped to its own cell and the outgoing
        // and incoming numerals are drawn one cell apart, so a figure rolls out of the
        // top as the next rolls in underneath — the same movement as the painted version,
        // made of the same glass as everything else on the panel.
        val glassClock = config.theme.isGlass &&
            deferOver(canvas) { c -> drawGlassClock(c, figures, cx, span, slot, colon, ink, secondsScale) }
        if (!glassClock) {
            for ((i, ch) in figures.withIndex()) {
                val full = if (ch == ':') colon else slot
                val w = if (i >= 5) full * secondsScale else full
                if (i >= 5) {
                    if (secondsScale <= 0.01f) break
                    // the glyph draws at its full size, scaled about the centre of its shrunken slot
                    canvas.save()
                    canvas.scale(secondsScale, secondsScale, x + w / 2f, clockBase - CLOCK_SIZE * 0.35f)
                    canvas.translate(-(full - w) / 2f, 0f)
                }
                if (ch == ':') {
                    canvas.drawText(":", x, clockBase, extras)
                } else {
                    // Keyed by POSITION, not by value: the wheel at index 4 is the tens
                    // of seconds for as long as the panel is up, and a key that moved
                    // with the digit would roll the wrong column on a carry.
                    rolling(
                        canvas, "clock:$i", ch.toString(),
                        x + (w - extras.measureText(ch.toString())) / 2f, clockBase,
                        android.graphics.RectF(
                            x, clockBase - CLOCK_SIZE, x + w, clockBase + CLOCK_SIZE * 0.35f,
                        ),
                    )
                }
                if (i >= 5) canvas.restore()
                x += w
            }
        }

        // ---- the date, spelled out ---------------------------------------------------
        //
        // Longhand, unlike the corner block's WED 00. That one is a glance at a panel
        // already full of readings and has to be small; this page has room and nothing
        // competing, so it can say the whole thing.
        // The date line follows the Date switch, and in always-on its own switch; the
        // weather line its own switch, and in always-on another — both cross-fading with
        // always-on, so neither pops. The weather on the glass is not this line's.
        val aodNow = aodFade(frameNowMs)
        val dateKeep = (if (config.showDate) 1f else 0f) +
            ((if (config.aodDate) 1f else 0f) - (if (config.showDate) 1f else 0f)) * aodNow
        val weatherOn = if (config.showWeatherLine) 1f else 0f
        val weatherKeep = weatherOn + ((if (config.aodWeather) 1f else 0f) - weatherOn) * aodNow
        if (dateKeep > 0.004f) {
            val date = java.text.SimpleDateFormat("EEEE, MMMM d", java.util.Locale.getDefault())
                .format(now.time) + ordinal(now.get(java.util.Calendar.DAY_OF_MONTH)) +
                ", " + now.get(java.util.Calendar.YEAR)
            // ITS OWN COLOUR. The date and the clock are two readings, and the settings give
            // them a colour each — this took the clock's for both, which is the sort of thing
            // that looks deliberate until somebody changes one of them and only half the page
            // moves.
            // Heavier, and at full strength. It was the body weight at 60% opacity over a
            // record, which is two different ways of being quiet at once.
            extras = extrasDate
            extras.textAlign = Paint.Align.LEFT
            extras.textSize = typed(DATE_SIZE)
            val dateInk = colorFor("date", config.dateTint)
            val dateW = extras.measureText(date)
            val dateLeft = cx - dateW / 2f
            if (config.theme.isGlass) {
                clockRect.set(
                    dateLeft - CARD_PAD_X, DATE_BASELINE - DATE_SIZE - ROW_CARD_PAD_Y,
                    dateLeft + dateW + CARD_PAD_X, DATE_BASELINE + ROW_CARD_PAD_Y,
                )
                glassCard(canvas, clockRect, ROW_CARD_RADIUS, alpha = dateKeep * (1f - aodNow))
            }
            extras.color = (dateInk and 0x00FFFFFF) or ((0xFF * dateKeep).toInt() shl 24)
            canvas.drawText(date, dateLeft, DATE_BASELINE, extras)
        }
        if (weatherKeep > 0.004f) {
            shownWeather()?.let { drawWeatherLine(canvas, cx, it, colorFor("weather", config.weatherTint), weatherKeep) }
        }
        extras = extrasBody
        extras.textSize = typed(18f)
    }

    /** The line's two strings, held against the reading that spelled them. See below. */
    private var lineFor: dev.kb896.aiusage.data.WeatherReading? = null
    private var lineTemp = ""
    private var lineWords = ""

    /**
     * `[icon] 14° · Rain` over `Bucharest`, both centred under the date, in its own ink —
     * the date's until told otherwise.
     *
     * Same paint, same size, same glass shell as the date, so the two read as one block.
     * The temperature is glass numerals under GLASS, drawn the way the clock's figures
     * are; the words are plain text in both themes. The place is a caption on its OWN
     * line at 60%, not a suffix beside the reading: at 11px on the end of the line it was
     * legible on a laptop and unreadable on the panel it is actually printed on.
     */
    private fun drawWeatherLine(
        canvas: Canvas,
        cx: Float,
        reading: dev.kb896.aiusage.data.WeatherReading,
        ink: Int,
        keep: Float = 1f,
    ) {
        // The card reaches into the unlock slider's band, so the block goes when the
        // slider comes and returns when it goes — the same bargain the square artwork
        // makes with the lock. A fade, not a slide: nothing on this page moves.
        // The line sits above the slider's band now, so it no longer has to leave when
        // the slider comes; keep stays at full and the fade machinery below is idle.
        // HELD, NOT SPELLED AGAIN. Both strings come from a fact that moves every five
        // minutes, on a page redrawn at the display's full rate — built per frame they are
        // garbage by the thousand for two words. Identity is the right key: a reading is
        // replaced wholesale when it changes, and the same object always reads the same.
        if (reading !== lineFor) {
            lineFor = reading
            lineTemp = WeatherLine.temperature(reading)
            lineWords = WeatherLine.words(reading)
        }
        val temp = lineTemp
        val words = lineWords
        extras = extrasDate
        extras.textAlign = Paint.Align.LEFT
        extras.textSize = typed(DATE_SIZE)
        val tempW = extras.measureText(temp)
        val wordsW = extras.measureText(words)
        val place = reading.place
        extras.textSize = typed(WeatherLine.PLACE_SIZE)
        val placeW = if (place.isEmpty()) 0f else extras.measureText(place)
        // THE TYPE'S OWN SIZE, not the constant behind it. A theme with a type scale sets
        // the words at typed(DATE_SIZE) and left the glyph beside them a tenth short.
        val iconH = typed(DATE_SIZE)
        // EACH LINE CENTRED ON ITS OWN, which is the whole point of the second line: a
        // reading and a caption stacked on one axis, not one run of type that happens to
        // wrap. So the place's width is no term in the reading's width.
        val total = iconH + WeatherLine.ICON_GAP + tempW + wordsW
        val left = cx - total / 2f
        val base = WeatherLine.BASELINE

        if (config.theme.isGlass) {
            // ONE CARD ROUND BOTH, cut to whichever line is wider — two cards, or one cut
            // to the reading with the place hanging out of it, would say these are two
            // things. They are one reading with its source named underneath.
            val wide = maxOf(total, placeW)
            clockRect.set(
                cx - wide / 2f - CARD_PAD_X, base - DATE_SIZE - ROW_CARD_PAD_Y,
                cx + wide / 2f + CARD_PAD_X,
                (if (place.isEmpty()) base else WeatherLine.PLACE_BASELINE) + ROW_CARD_PAD_Y,
            )
            glassCard(canvas, clockRect, ROW_CARD_RADIUS, alpha = keep * (1f - aodFade(frameNowMs)))
        }

        extras.color = (ink and 0x00FFFFFF) or ((0xFF * keep).toInt() shl 24)
        extras.textSize = typed(DATE_SIZE)
        drawIcon(
            canvas, WeatherLine.icon(reading.condition.iconName),
            left, base - iconH, iconH,
        )

        // FIXED BEFORE IT IS DEFERRED. A Kotlin lambda closes over a var by reference, so
        // a running x handed to the overlay pass is read AFTER the rest of the line has
        // advanced it, and the figure lands off the end of it. This is a val for that
        // reason and not because it reads more neatly.
        val tempX = left + iconH + WeatherLine.ICON_GAP
        // PLAIN TYPE WHILE IT IS LEAVING. The glass numeral is a refraction, not an ink:
        // drawSdfText throws the paint's alpha away and tints the field at Glass.SDF_TINT,
        // so a figure asked to fade simply would not — the temperature would sit there at
        // full strength over an empty card while everything around it went. Below full
        // strength the figure is drawn as text instead, which fades with the rest. That
        // makes this a deliberate, instantaneous restyle rather than a fade of its own:
        // keep crosses 0.999 once per swap, close to full opacity on both ends — moments
        // after a lock starts pulling it out, moments before an unlock finishes putting it
        // back — never while the card is actually faint. With the baseline fixed above,
        // the figure no longer moves when the restyle happens.
        // the temperature reads like the rest of the line — plain ink, not a glass run:
        // a figure made of glass beside words made of ink looked like two different things
        canvas.drawText(temp, tempX, base, extras)
        canvas.drawText(words, tempX + tempW, base, extras)

        if (place.isNotEmpty()) {
            extras.textSize = typed(WeatherLine.PLACE_SIZE)
            extras.color = (ink and 0x00FFFFFF) or ((0x99 * keep).toInt() shl 24)
            canvas.drawText(place, cx - placeW / 2f, WeatherLine.PLACE_BASELINE, extras)
        }
        extras.textSize = typed(DATE_SIZE)
    }

    /**
     * The last ten notifications, on a wheel.
     *
     * THE SAME CARD THE STRIP USES, deliberately to the unit: same left edge, same height,
     * same corner following the display's own arc, same row renderer. A notification
     * arriving on the readout and the same notification found again up here should be the
     * one object seen twice, not two designs for one thing — so this asks drawNoticeRow to
     * lay it out exactly as the strip would.
     *
     * Only the newest is at rest. The ones either side are drawn where they will be if you
     * keep scrolling, which is what makes it read as a wheel rather than a slideshow: the
     * next item is already visible at the edge, half out of the card, waiting.
     */
    private fun drawNoticeWheel(canvas: Canvas) {
        val shown = shownHistory()
        if (shown.isEmpty()) return
        canvas.save()
        // THE DRIFT COMES BACK OFF. Everything on this page rides the burn-in offset with
        // the rest of the canvas side, which is right for type floating in the middle of a
        // panel and wrong for a card whose whole shape is an argument about the edge it is
        // attached to: three units of drift left three units of background showing above
        // it and beside it, and a card held off the corner it is supposed to be growing
        // out of is just a card in the wrong place.
        //
        // The strip does not drift either — it is drawn in the overlay pass, outside every
        // translate — so this is the same decision, not a new one.
        canvas.translate(-dxNow, -dyNow)
        // Put away to the right, or on its way there or back. See NoticeStash.
        val stashed = stashOffset()
        if (stashed >= Panel.WIDTH - 0.5f) { canvas.restore(); return }
        canvas.translate(stashed, 0f)
        // THE STRIP'S OWN RECT, not a second one that looks similar. A notification seen
        // arriving on the readout and the same one found again up here is one object seen
        // twice, so it sits exactly where it sat: same left edge, same margin, same
        // height, same corner following the display's arc.
        // EACH THEME'S OWN NOTIFICATION, not one shape wearing two colours. Under glass
        // that is a pane held clear of the edges with its top-right corner following the
        // display's arc; under ORIGINAL it is a full-width opaque band with a single
        // rounded corner at the bottom left, because that one hangs off the top edge of
        // the screen rather than floating on it. Falling back to a translucent version of
        // the glass card gave the original theme something it has never had anywhere else
        // on the panel.
        val glass = config.theme.isGlass
        val margin = if (glass) GlassCard.MIN else 0f
        val r = if (glass) GLASS_NOTICE_RADIUS else cornerRadius() / CORNER_DIVISOR
        noticeWheelRect.set(NOTICE_LEFT, margin, Panel.WIDTH - margin, NOTICE_H)
        val cx = noticeWheelRect.centerX()
        val cy = noticeWheelRect.centerY()
        // TURNED BY THE DEMO, when there is one. A page of notifications that never moves
        // says the panel holds ten and shows one; the whole point of the wheel is that it
        // goes back through them, and a film that does not do it leaves the feature
        // invisible. Paced so the neighbours are legible on the way past rather than
        // flicked through.
        val step = demoNow()
        if (step != null && step.page == PAGE_NOTICES) {
            val into = ((frameNowMs - demoStartedAt) / 1000f) % DEMO_WHEEL_PERIOD
            noticeWheelAt = (into / DEMO_WHEEL_PERIOD * (shown.size - 1))
                .coerceIn(0f, (shown.size - 1).toFloat())
            wheelSettling = false
        }

        // SETTLES, it does not snap. Letting go a little past a card used to round the
        // position and be done with it, which lands the card in place with no travel at
        // all — the one movement on this panel that simply cut. The pager has settled its
        // pages since it was written; this borrows the same duration so a wheel and a page
        // let go of a finger the same way.
        if (wheelSettling) {
            val t = ((frameNowMs - wheelSettleAt) / PAGE_SETTLE_MS).coerceIn(0f, 1f)
            noticeWheelAt = wheelFrom + (wheelTo - wheelFrom) * Tween.easeInOut(t)
            if (t >= 1f) {
                wheelSettling = false
                noticeWheelAt = wheelTo
            } else {
                animating = true
            }
        }
        val at = noticeWheelAt

        // A CAROUSEL, so each notification is its own card rather than rows sliding inside
        // one. Furthest first, so the nearest is laid over the others: the one you are on
        // is whole and in front, and its neighbours sit half a card behind it, a little
        // smaller and a little faded, which is what says there is more in both directions
        // without having to draw a scrollbar to say it.
        for (i in shown.indices.sortedByDescending { Math.abs(it - at) }) {
            val d = i - at
            if (Math.abs(d) > CAROUSEL_REACH) continue
            // TWO STRETCHES, because a neighbour and a departure want different things.
            //
            // Out to one card away it fades only a little: that one is the next
            // notification, it is meant to be read at a glance, and its job is to say
            // there is more. PAST one it keeps going, all the way to nothing by the time
            // it reaches the edge of what is drawn. It used to stop at the neighbour's
            // value and then be culled, so a card left at nearly half strength and then
            // simply ceased to exist — which is the disappearing you can see.
            val away = Math.abs(d)
            val near = away.coerceAtMost(1f)
            // ORIGINAL DOES NOT SHRINK. Its card is attached to the edge of the screen and
            // its neighbours arrive across that edge; a card that is both sliding in from
            // the side AND getting smaller is two ideas about the same movement. Under
            // glass, where the card floats clear of every edge, shrinking is the only
            // thing that can say "behind".
            val scale = if (glass) 1f - CAROUSEL_SHRINK * near else 1f
            val fade =
                if (away <= 1f) 1f - CAROUSEL_FADE * away
                else (1f - CAROUSEL_FADE) *
                    (1f - (away - 1f) / (CAROUSEL_REACH - 1f)).coerceIn(0f, 1f)
            // THE CARD'S RECT IS COMPUTED, NOT TRANSFORMED. Scaling the canvas would
            // scale what the pane SAMPLES along with the pane: the backdrop would come out
            // magnified inside the smaller card, which is a photograph of the panel rather
            // than a window onto it. A pane has to sample and draw at one to one, so the
            // shrinking is done by giving it a smaller rect in the same space.
            val dy = d * NOTICE_H * CAROUSEL_STEP
            val hh = noticeWheelRect.height() / 2f * scale
            if (glass) {
                // Held clear of every edge, so it shrinks about its own middle.
                val hw = noticeWheelRect.width() / 2f * scale
                cardRect.set(cx - hw, cy + dy - hh, cx + hw, cy + dy + hh)
            } else {
                // KEPT ON THE RIGHT EDGE, and arriving across it. This card is attached to
                // the side of the screen rather than floating in the middle of it, so a
                // neighbour that shrank about its centre would pull away from the one edge
                // it is meant to be part of. Its right edge stays put and its LEFT edge
                // travels, which makes the one behind read as still coming in from the
                // side rather than as a smaller copy parked in the middle.
                cardRect.set(
                    noticeWheelRect.left + away.coerceAtMost(1f) * ORIGINAL_SLIDE,
                    cy + dy - hh, noticeWheelRect.right, cy + dy + hh,
                )
            }
            val cr = r * scale
            val layer = fade < 0.999f
            // Restored to this count below: the alpha layer is a save of its own, and
            // popping a fixed two left it open for every fading card — on ORIGINAL,
            // where the wheel is drawn inline, the clock, battery, date and weather
            // then inherited the wheel's always-on fade and popped back when it ended.
            val cardSave = canvas.save()
            if (layer) {
                canvas.saveLayerAlpha(cardRect, (255 * fade).toInt().coerceIn(0, 255))
            }
            val tr = GlassCard.innerRadius(cornerRadius(), margin, cr)
            noticeWheelShape.rewind()
            noticeWheelShape.addRoundRect(
                cardRect,
                if (glass) floatArrayOf(cr, cr, tr, tr, cr, cr, cr, cr)
                // The strip's own corners: bottom-left only. See drawNotice.
                else floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, cr, cr),
                android.graphics.Path.Direction.CW,
            )
            if (!glass ||
                !drawGlassPane(
                    canvas, cardRect, cr, reactive(glassNoticeTint(), cardRect),
                    shape = noticeWheelShape, held = true,
                )
            ) {
                noticePaint.style = Paint.Style.FILL
                // OPAQUE, and the strip's own colour. glassNoticeTint is the ground at a
                // fraction of its opacity, which is what a PANE wants behind type it is
                // refracting; an original-theme card is not refracting anything and a
                // see-through one is just a card you cannot read.
                noticePaint.color =
                    if (glass) glassNoticeTint()
                    else colorFor("notice:bg", config.notificationGround or (0xFF shl 24))
                noticePaint.alpha = if (glass) noticePaint.alpha else 255
                canvas.drawPath(noticeWheelShape, noticePaint)
            }
            // The CONTENTS may scale freely — type and an icon are painted, not sampled,
            // so nothing about them depends on where the panel is underneath.
            // The CONTENTS follow the card: down by the same offset, right by however far
            // its left edge went, and scaled about the middle when the card is. Type and
            // an icon are painted rather than sampled, so unlike the pane they can be
            // transformed freely.
            canvas.save()
            canvas.clipRect(cardRect)
            canvas.translate(cardRect.left - noticeWheelRect.left, dy)
            if (scale < 0.999f) canvas.scale(scale, scale, cx, cy)
            drawNoticeRow(canvas, shown[i], noticeWheelRect.top)
            canvas.restore()
            canvas.restoreToCount(cardSave)
        }
        canvas.restore()
    }

    /**
     * How far down the page a finger still belongs to the wheel.
     *
     * EVERYTHING ABOVE THE CLOCK, not just the card. The card is 143 units of a 572-unit
     * panel and it is where the cards happen to be RESTING — but a carousel is a thing you
     * reach into, and asking someone to land inside the one card that is currently in
     * front is asking them to aim at a moving target on a screen facing away from them.
     *
     * The clock is the boundary because it is the next thing down that means something
     * else: above it is the wheel and its neighbours coming and going, below it is the
     * time and the date, which nothing scrolls. Measured from the clock's cell rather than
     * its baseline so the grab stops where the numerals start, not where they sit.
     */
    private fun wheelGrabBottom(): Float =
        CLOCK_BASELINE - CLOCK_SIZE / 2f - CLOCK_SIZE * CLOCK_ROLL_CELL / 2f

    /** Which of the ten the wheel is on. Whole numbers rest; the fraction is a drag. */
    private var noticeWheelAt = 0f

    /** Whether this gesture belongs to the wheel rather than to the pager. */
    private var onWheel = false

    /** The settle after a release: where it let go, where it is going, and when. */
    private var wheelSettling = false
    private var wheelFrom = 0f
    private var wheelTo = 0f
    private var wheelSettleAt = 0L

    /** Where the wheel stood when the finger landed, so the drag is relative to it. */
    private var wheelDownAt = 0f

    // ---- the cards put away ----

    /** The finger landed on the cards' place: a sideways swipe there puts them away or brings them back. */
    private var onStash = false
    private var stashDx = 0f
    private var stashFrom = 0f
    private var stashTo = 0f
    private var stashAt = 0L

    /** Put away by a swipe to the right, and kept so across restarts. */
    private var noticesStashed: Boolean = false
        get() { if (!stashLoaded) { field = panelPrefs.getBoolean(KEY_NOTICES_STASHED, false); stashLoaded = true; stashFrom = if (field) Panel.WIDTH.toFloat() else 0f; stashTo = stashFrom }; return field }
        set(value) { field = value; stashLoaded = true; panelPrefs.edit().putBoolean(KEY_NOTICES_STASHED, value).apply() }
    private var stashLoaded = false

    /** How far right the cards are now: under a finger, settling, or at rest. */
    private fun stashOffset(): Float {
        val w = Panel.WIDTH.toFloat()
        val stashed = noticesStashed
        if (drag == Drag.STASH) return NoticeStash.offset(stashed, stashDx, w)
        val t = ((frameNowMs - stashAt) / PAGE_SETTLE_MS).coerceIn(0f, 1f)
        if (t < 1f) animating = true
        return stashFrom + (stashTo - stashFrom) * Tween.easeInOut(t)
    }

    /** The finger let go: away, back, or springing to where it was, from wherever it is. */
    private fun letGoOfStash(commit: Boolean) {
        val w = Panel.WIDTH.toFloat()
        val was = noticesStashed
        val now = NoticeStash.offset(was, stashDx, w)
        val stash = if (commit) NoticeStash.release(was, stashDx, w) else was
        if (stash != was) noticesStashed = stash
        stashFrom = now
        stashTo = if (stash) w else 0f
        stashAt = SystemClock.uptimeMillis()
        stashDx = 0f
        invalidate()
    }

    private val noticeWheelRect = android.graphics.RectF()
    private val noticeWheelShape = android.graphics.Path()

    /**
     * A run of glass glyphs, left to right: the clock's numerals with nothing rolling.
     *
     * IT SETS ITS OWN PAINT, exactly as drawGlassClock does, and for the same reason:
     * this only ever runs deferred, and by the time the overlay pass replays it the
     * view's extras has been pointed at the body paint and resized. A run measured with
     * that paint comes out the wrong width, in the wrong ink, under the wrong baseline.
     *
     * Each glyph advances by its own measure rather than a slot. A clock is a column of
     * digits that must not shuffle when a 1 follows a 0; this is a temperature read as a
     * word, and letter-spacing it to the widest figure would look like a ransom note.
     */
    private fun drawGlassRun(c: Canvas, text: String, x: Float, baseline: Float, ink: Int) {
        extras = extrasDate
        extras.textAlign = Paint.Align.LEFT
        extras.textSize = typed(DATE_SIZE)
        extras.color = (ink and 0x00FFFFFF) or (0xFF shl 24)
        var pen = x
        for (glyph in text) {
            val s = glyph.toString()
            val w = extras.measureText(s)
            // drawSdfText's centreY is the field's own midpoint, not a baseline — so this
            // is the centre whose midpoint IS `baseline`, the same conversion the charge
            // figure's fallback makes near line 3212, and why plain text below still lines
            // up with it.
            if (!drawSdfText(c, s, extras, pen + w, baseline + (extras.ascent() + extras.descent()) / 2f)) {
                c.drawText(s, pen, baseline, extras)
            }
            pen += w
        }
        // HANDED BACK, because this is the last thing the overlay pass replays and the
        // unlock label draws through whatever extras points at: left on the date's paint,
        // its title axes put LOCK on the panel a weight heavier than every other label.
        extras = extrasBody
    }

    /**
     * The clock, one glass numeral at a time.
     *
     * Runs in the overlay pass, where there is a finished panel behind it to bend. Each
     * slot keeps its own roll — see clockRolls — so only the columns that actually
     * changed move, and a numeral leaving is drawn one cell above the one arriving.
     */
    private fun drawGlassClock(
        canvas: Canvas,
        figures: String,
        cx: Float,
        span: Float,
        slot: Float,
        colon: Float,
        ink: Int,
        /** always-on: the seconds' scale, 1 at rest, 0 gone — see drawNoticePage */
        secondsScale: Float = 1f,
    ) {
        extras = extrasBody
        extras.textAlign = Paint.Align.LEFT
        extras.textSize = typed(CLOCK_SIZE)
        extras.color = (ink and 0x00FFFFFF) or (0xFF shl 24)
        val cell = CLOCK_SIZE * CLOCK_ROLL_CELL
        var x = cx - span / 2f
        for ((i, ch) in figures.withIndex()) {
            val full = if (ch == ':') colon else slot
            val seconds = i >= 5
            if (seconds && secondsScale <= 0.01f) break
            val w = if (seconds) full * secondsScale else full
            if (seconds) {
                // the glyph at its full size, scaled about the centre of its shrunken slot
                canvas.save()
                canvas.scale(secondsScale, secondsScale, x + w / 2f, CLOCK_BASELINE - CLOCK_SIZE * 0.35f)
                canvas.translate(-(full - w) / 2f, 0f)
            }
            if (ch == ':') {
                // The colon never rolls. It is punctuation, not a reading.
                if (!drawSdfText(canvas, ":", extras, slotRight(x, full, ":"), CLOCK_BASELINE - CLOCK_SIZE / 2f)) {
                    canvas.drawText(":", x, CLOCK_BASELINE, extras)
                }
                if (seconds) canvas.restore()
                x += w
                continue
            }
            val key = "clock:$i"
            val was = clockRolls[key]
            if (was == null || was.first != ch) {
                clockRolls[key] = ch to (if (was == null) 0L else frameNowMs)
                if (was != null) clockRollUntil = frameNowMs + CLOCK_ROLL_MS.toLong() // a frozen page thaws for the roll
            }
            val state = clockRolls[key]!!
            val since = frameNowMs - state.second
            val t =
                if (state.second == 0L) 1f
                else (since / CLOCK_ROLL_MS).coerceIn(0f, 1f)
            if (t < 1f) animating = true
            // Out-cubic: fast away from the old figure, easing onto the new one. Written
            // here rather than added to Tween because it is the only thing that wants it.
            val eased = 1f - (1f - t) * (1f - t) * (1f - t)
            val mid = CLOCK_BASELINE - CLOCK_SIZE / 2f
            canvas.save()
            // WIDE ENOUGH FOR THE FIELD, not just the figure. A signed distance field is
            // the glyph PLUS a margin for its spread to live in, and that margin is where
            // the bevel is — the part that makes it read as glass rather than as a hole.
            // Clipped to the slot, every numeral lost its right-hand edge.
            //
            // Horizontally there is nothing to clip anyway: the slots do not overlap and
            // only one figure is ever drawn in each. The clip exists for the ROLL, which
            // is vertical, so it runs the width of the panel and only bounds the cell.
            canvas.clipRect(0f, mid - cell / 2f, Panel.WIDTH.toFloat(), mid + cell / 2f)
            // Arriving from below, and whatever it replaced leaving through the top.
            val rise = (1f - eased) * cell
            if (!drawSdfText(canvas, ch.toString(), extras, slotRight(x, full, ch.toString()), mid + rise)) {
                canvas.drawText(ch.toString(), x, CLOCK_BASELINE + rise, extras)
            }
            val leaving = clockLeaving[key]
            if (t < 1f && leaving != null && leaving != ch) {
                if (!drawSdfText(
                        canvas, leaving.toString(), extras,
                        slotRight(x, full, leaving.toString()), mid + rise - cell,
                    )
                ) {
                    canvas.drawText(leaving.toString(), x, CLOCK_BASELINE + rise - cell, extras)
                }
            }
            canvas.restore()
            if (seconds) canvas.restore()
            if (t >= 1f) clockLeaving[key] = ch
            x += w
        }
    }

    /**
     * Where to ask [drawSdfText] to put a glyph's right edge so it lands CENTRED in its
     * slot rather than right-aligned in it.
     *
     * drawSdfText was written for a charge figure, which is one string set against a
     * fixed right margin, so it takes the right edge of the FIELD — and a field is the
     * glyph plus a margin for the spread to live in. Handed a slot's right edge, a narrow
     * numeral therefore sits further right than a wide one: a 1 stood twenty-four units
     * clear of the 2 beside it, which is the gap in a clock reading 15:2 1:42.
     *
     * A clock wants the opposite of a right margin. Every slot is the width of a zero and
     * the figure belongs in the middle of it, so that a 1 rolling past does not shunt the
     * rest of the time sideways.
     */
    private fun slotRight(x: Float, w: Float, glyph: String): Float =
        x + (w + extras.measureText(glyph)) / 2f + SdfText.SPREAD

    /** Per slot: the numeral shown, and when it arrived. See drawGlassClock. */
    private val clockRolls = HashMap<String, Pair<Char, Long>>()

    /** Per slot: the numeral it replaced, still rolling out. */
    private val clockLeaving = HashMap<String, Char>()

    private val clockRect = android.graphics.RectF()

    /** 1st, 2nd, 3rd, 4th — and the four teens that break the rule. */
    private fun ordinal(day: Int): String = when {
        day in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }

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

        // THE SAME, WITHOUT THE SCROLL IN IT. Everything on this page fades in with the
        // paging, which is right for page furniture — the timeline, the arcs, the speaker
        // — because it genuinely arrives when the page does. It is wrong for the two
        // things that DO NOT arrive: the transport and the name exist on the readout too
        // and cross between the pages, and multiplying their alpha by the scroll dissolves
        // something that is only moving. That is what made the row's copies vanish at the
        // first flicker of a drag and the page's copies wash in behind them.
        fun solid(base: Int, a: Int) =
            (base and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

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
        // OUT OF THE SLIDER'S WAY, if it is here. The unlock slider is pinned to the
        // mark band so a notification cannot push it off the panel, and on this page the
        // artist line ends three units inside that band — enough for the glass to sit on
        // its descenders and for the whole line to read as slightly sunk.
        //
        // The block lifts rather than the slider moving, because the slider's position is
        // the one thing on the panel that must not depend on which page is showing: a
        // thumb reaching for it in a pocket has to find it in the same place every time.
        val lift = lockLift()
        val titleBase = MediaPage.TITLE_BASELINE - lift
        val artistBase = MediaPage.ARTIST_BASELINE - lift

        // A card behind the name, on both pages, for the same reason the accounts have
        // one: over a photograph the words have nothing to sit on. Sized to the wider of
        // the two lines rather than to the column — a fixed-width pane behind a short
        // title is a pane with a title in the corner of it.
        // HOW FAR ALONG THE TRAVEL THE NAME IS. The row's two lines and these two are the
        // same two lines; see nameHomeCard. With no home recorded — no row drawn this
        // frame — every interpolation below collapses to this page's own geometry.
        val nt = if (nameHomeKnown) paging else 1f
        // The row sets the name lighter than the panel, on the grounds that it is somebody
        // else's writing rather than a label this app chose, and this page has always set
        // it at the panel's weight. A weight cannot be interpolated — it is a different
        // typeface, not a number — so the swap happens at the midpoint of the travel,
        // where the type is moving and resizing fastest and is least likely to be read.
        val wasPen = extras
        extras = if (nt < 0.5f) extrasMedia else extrasBody

        extras.textSize = typed(MediaPage.TITLE_SIZE)
        val pageTitle = sequenced("page:title", m.title) { extras.measureText(it) }
        var pageWant = sequencedWidth
        var pageArtist = ""
        if (m.artist.isNotBlank()) {
            extras.textSize = typed(MediaPage.ARTIST_SIZE)
            pageArtist = sequenced("page:artist", m.artist) { extras.measureText(it) }
            pageWant = maxOf(pageWant, sequencedWidth)
        }
        if (config.theme.isGlass) {
            var w = pageWant
            // EASED TO THE NEW WIDTH, not cut to it. The name changes while you are looking
            // at it, and a card that jumps to the next title's width reads as a second
            // thing happening — the card flinching at the same moment the words turn over.
            // Grown, the two are one event: the sheet opens out and the new name arrives
            // on it. The same wheel the text itself rolls on, near enough.
            w = ease("page:card:w", minOf(w, PAGE_TEXT_WIDTH), CARD_GROW_MS, back = true)
            // Where it is GOING, and then where it is: the row's card travels to this
            // one, corner by corner, and its radius with it. No fade — the card the row
            // drew and the card under the record are one sheet.
            val toL = cx - w / 2f - TRACK_PAD_X
            val toT = titleBase - MediaPage.TITLE_SIZE - TRACK_PAD_Y
            val toR = cx + w / 2f + TRACK_PAD_X
            val toB = (if (m.artist.isNotBlank()) artistBase else titleBase) + TRACK_PAD_Y
            if (nameHomeKnown) {
                cardRect.set(
                    nameHomeCard.left + (toL - nameHomeCard.left) * nt,
                    nameHomeCard.top + (toT - nameHomeCard.top) * nt,
                    nameHomeCard.right + (toR - nameHomeCard.right) * nt,
                    nameHomeCard.bottom + (toB - nameHomeCard.bottom) * nt,
                )
            } else {
                cardRect.set(toL, toT, toR, toB)
            }
            glassCard(
                canvas, cardRect,
                ROW_CARD_RADIUS + (CARD_RADIUS - ROW_CARD_RADIUS) * nt,
            )
        }

        // ---- the name, in flight ----------------------------------------------
        //
        // Drawn LEFT-aligned at an interpolated left edge rather than centred, which is
        // not a change of appearance: a centred line IS a left-aligned line placed at
        // half its own width back from the centre, and saying it that way gives one
        // number to interpolate instead of two incompatible alignments.
        val titleSize =
            MediaLayout.TEXT_SIZE + (MediaPage.TITLE_SIZE - MediaLayout.TEXT_SIZE) * nt
        val artistSize =
            MediaLayout.TEXT_SIZE + (MediaPage.ARTIST_SIZE - MediaLayout.TEXT_SIZE) * nt
        val bandW = nameHomeWidth + (PAGE_TEXT_WIDTH - nameHomeWidth) * nt
        val tBase = nameHomeTitle + (titleBase - nameHomeTitle) * nt
        val aBase = nameHomeArtist + (artistBase - nameHomeArtist) * nt

        extras.textSize = typed(titleSize)
        extras.color = solid(tint, 0xFF)
        line(canvas, "page:title", pageTitle, nt, cx, tBase, titleSize, bandW)
        if (m.artist.isNotBlank()) {
            extras.textSize = typed(artistSize)
            extras.color = solid(tint, MediaLayout.ARTIST_ALPHA)
            line(canvas, "page:artist", pageArtist, nt, cx, aBase, artistSize, bandW)
        }
        extras = wasPen
        extras.textAlign = Paint.Align.LEFT
        extras.textSize = typed(18f)

        // ---- the transport row, centred ---------------------------------------
        //
        // Its own geometry rather than MediaLayout's, which lays out from the right
        // against a strip. Here the three are a group centred under a record, which is a
        // different arrangement of the same three things.
        val gh = MediaPage.TRANSPORT_H
        val widths = listOf(
            gh * Glyphs.aspect(PanelIcons.PREV, config.theme),
            gh * Glyphs.aspect(PanelIcons.PAUSE, config.theme),
            gh * Glyphs.aspect(PanelIcons.NEXT, config.theme),
        )
        val total = widths.sum() + MediaPage.TRANSPORT_GAP * 2f
        var x = cx - total / 2f
        val top = MediaPage.TRANSPORT_CY - gh / 2f
        val centres = mutableListOf<Float>()

        // THE BUTTONS LEAVE WHEN THE LOCK ARRIVES, on the lock's own drum — down and out
        // through the bottom, the same movement the readout's row makes as the slider
        // takes its place.
        //
        // Covering them was not enough. The slider is opaque and sits over this row, but
        // it is a stadium 70 units tall and these glyphs are centred at 529 on a panel
        // 572 deep, so their bottom edge showed underneath it. A control half-visible
        // beneath the thing that exists to say "you cannot press this" is worse than
        // either state on its own.
        val lockOut = lockSwap(frameNowMs)
        canvas.save()
        if (lockOut > 0.001f) {
            canvas.clipRect(0f, top - gh, Panel.WIDTH.toFloat(), Panel.HEIGHT.toFloat())
            canvas.translate(0f, lockOut * (Panel.HEIGHT - top + gh))
        }

        // WHERE THE THREE ACTUALLY ARE THIS FRAME. The arrangement above is where they
        // are GOING; transportHomeX is where the readout's row left them. They travel
        // between the two on the scroll, growing from the row's glyph height to this
        // page's as they go, and they no longer fade — a fade is for something arriving,
        // and nothing arrives here. They were already on the panel.
        val pageCx = floatArrayOf(
            x + widths[0] / 2f,
            x + widths[0] + MediaPage.TRANSPORT_GAP + widths[1] / 2f,
            x + widths.sum() - widths[2] / 2f + MediaPage.TRANSPORT_GAP * 2f,
        )
        val pageCy = top + gh / 2f
        val t = if (transportHomeKnown) paging else 1f
        val bh = transportHomeH + (gh - transportHomeH) * t
        val bcy = transportHomeY + (pageCy - transportHomeY) * t
        val bTop = bcy - bh / 2f
        fun travelled(i: Int) =
            if (!transportHomeKnown) pageCx[i]
            else transportHomeX[i] + (pageCx[i] - transportHomeX[i]) * t

        extras.color = solid(tint, 0xFF)
        val prevCx = travelled(0)
        val prevX = prevCx - bh * Glyphs.aspect(PanelIcons.PREV, config.theme) / 2f
        glassButton(canvas, prevCx, bcy, bh) { c ->
            extras.color = solid(tint, 0xFF)
            drawIcon(c, PanelIcons.PREV, prevX, bTop, bh)
        }
        centres += prevCx

        // The play/pause drum again. Same clip-and-slide as the row's.
        val playCx = travelled(1)
        val playX = playCx - bh * Glyphs.aspect(PanelIcons.PAUSE, config.theme) / 2f
        glassButton(canvas, playCx, bcy, bh) { c ->
            c.save()
            c.clipRect(playX, bTop - bh, playX + bh, bTop + bh * 2f)
            drawPlayPause(c, playX, bTop, bh, solid(tint, 0xFF))
            c.restore()
        }
        centres += playCx

        val nextCx = travelled(2)
        val nextX = nextCx - bh * Glyphs.aspect(PanelIcons.NEXT, config.theme) / 2f
        glassButton(canvas, nextCx, bcy, bh) { c ->
            extras.color = solid(tint, 0xFF)
            drawIcon(c, PanelIcons.NEXT, nextX, bTop, bh)
        }
        centres += nextCx
        canvas.restore()

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

        extras.color = shade(
            tint,
            ((if (isMuted) MediaLayout.ARTIST_ALPHA else 0xFF) * paging).toInt()
                .coerceIn(0, 255),
        )
        canvas.save()
        // Turned to the arc's own angle, so the speaker points out of the ring wherever
        // it has got to. The glyph's natural direction is +x, which is the angle's own
        // direction, so the rotation IS the angle.
        canvas.rotate(head, hx, hy)

        // THE WAVES ARE WORKED OUT FIRST, because the button is sized from them.
        //
        // Each has its own leg — see the loop below — and the disc swells with the sum of
        // them, so a wave leaving the cone pushes the glass out as it goes. Reading them
        // here rather than in the loop is what lets one number serve both: `ease` is
        // driven by the frame clock and returns the same value twice in a frame, but
        // asking it twice for the same thing is the kind of coupling that quietly stops
        // being true when somebody changes the easing.
        for (i in 1..WAVE_MAX) {
            waveOut[i - 1] = ease("vol:wave:" + i, if (i <= waves) 1f else 0f, WAVE_MS)
        }
        // HOW FAR THE SOUND HAS GOT, measured from the cone's mouth. Not how MANY waves
        // there are — the outermost one is what the bubble has to contain, and a wave is
        // still travelling for the whole of its leg.
        var reach = 0f
        for (i in 1..WAVE_MAX) {
            val out = waveOut[i - 1]
            if (out > 0.01f) reach = maxOf(reach, (WAVE_FIRST + WAVE_STEP * (i - 1)) * out)
        }
        // The far edge of that wave, from the disc's centre: the mouth is offset from the
        // centre by half the cone, and the arc is a stroke with width of its own.
        // OPENS QUICKLY, THEN SETTLES. Sized to exactly contain the outermost wave, the
        // glass met the sound at its edge and read as a bubble straining to hold something
        // — worst at two waves, where the disc reached 1.51 of the glyph's cell and the
        // wave ended at 1.51. Scaling everything up instead fixed the low end and overshot
        // the top, which had been right all along.
        //
        // So the reach is curved rather than scaled. A root gives the most room where the
        // sound has travelled least and none at all at full volume, which lands the three
        // steps where they should be — and because a root of a fraction is always larger
        // than the fraction, the glass still holds the wave at every point in between.
        val fullReach = WAVE_FIRST + WAVE_STEP * (WAVE_MAX - 1)
        val opened =
            if (reach <= 0f) 0f
            else Math.sqrt((reach / fullReach).toDouble()).toFloat() * fullReach
        val soundEdge = speakerW / 2f + opened * mh +
            mh * WAVE_STROKE / 2f + mh * SPEAKER_WAVE_MARGIN

        // A BUTTON, because it is one. It mutes when you tap it, and everything else on
        // this panel that does something when tapped sits on a disc of glass. This was the
        // last control still drawn as a bare mark, which read as a label for the ring
        // rather than as a thing to press.
        //
        // Centred on the orbit point, which is where drawIcon's square cell lands anyway,
        // so the two agree without being told to. The disc is a circle turned about its own
        // centre, so the rotation the speaker rides makes no difference to its shape.
        // SIZED TO HOLD THE SOUND. A fixed swell was the first attempt and it was the
        // wrong quantity: at one wave the disc had grown by a third and the wave had
        // travelled further than that, so the thing the bubble was meant to be showing
        // was outside it. Sized from the wave's own reach it cannot happen — the glass
        // is always big enough for what is inside it, and it grows because the sound does.
        //
        // Never smaller than the glyph's own cell, so a muted speaker is a button the same
        // size as every other button rather than a shrunken one.
        // WHERE THE BUBBLE'S MIDDLE ACTUALLY IS.
        //
        // It was centred on the glyph, which is only right when the glyph is all there is.
        // The waves leave the cone's mouth and travel one way — outward, along the arc —
        // so what the glass has to hold is lopsided, and a bubble centred on the cone grows
        // as much empty space behind the speaker as it does room in front of it.
        //
        // The contents run from the glyph's own left edge to the far side of the outermost
        // wave, so the centre is the middle of THAT and the radius is half of it. Measured
        // along the direction the speaker points, then placed in the panel's coordinates.
        // Plus air all round. Sized to exactly enclose its contents the glass sat on them,
        // and a bubble that meets what it holds at every point reads as shrink-wrap rather
        // than as a vessel. Symmetric, so it does not undo the centring above.
        val need = (soundEdge + mh / 2f) / 2f * SPEAKER_BUBBLE_ROOM
        val shift = (soundEdge - mh / 2f) / 2f
        val bubbleX = hx + Math.cos(radians).toFloat() * shift
        val bubbleY = hy + Math.sin(radians).toFloat() * shift

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
                val out = waveOut[i - 1]
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

        // THE BUBBLE, OUTSIDE THE ROTATION. A circle does not need turning, and turning it
        // costs something real: a pane is noted by mapping its square through the canvas's
        // matrix, and the bounding box of a rotated square is up to half again as wide as
        // the square. That was invisible while the bubble sat on the rotation's own pivot;
        // centred anywhere else it would swing the glass off the speaker and breathe with
        // the angle. Placed unrotated, it is simply a circle where the circle belongs.
        // ON THE SCROLL, like the transport it sits beside. The speaker belongs to the
        // media page and was the one control still switching on when the page arrived
        // rather than coming in with it.
        glassButton(
            canvas, bubbleX, bubbleY, mh,
            discH = maxOf(mh, need / DISC_RADIUS),
            alpha = paging,
        ) { }
        canvas.restore()

        // Targets, and only once the page has arrived — the same rule the row follows.
        // A button that can be pressed while it is still fading in is a button in a place
        // it does not yet occupy.
        // And their targets go with them, from the first frame of the movement rather
        // than the last. A glyph on its way off the panel must not still answer.
        if (paging >= 0.999f && lockOut <= 0.001f) {
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
        extras.textSize = typed(MediaPage.LABEL_SIZE)
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
        val labelColor = extras.color

        // OVER THE GLASS, NOT UNDER IT.
        //
        // These belong to the rings, and the rings are bands of glass now, held back to
        // the overlay pass so they can bend the finished panel. A label drawn here in the
        // scene therefore ends up UNDER a lens — and a time read through a lens is a
        // texture rather than a reading.
        //
        // So it goes one step later, with the paint set inside rather than outside: by the
        // time this runs, `extras` has been through half the panel.
        val labels = { c: Canvas ->
            extras.textSize = typed(MediaPage.LABEL_SIZE)
            extras.textAlign = Paint.Align.CENTER
            extras.color = labelColor
            curvedRolling(
                c, "dial:elapsed", MediaPage.clock(shownElapsed), rings.personal,
                centreDeg = MediaPage.ELAPSED_AT, outward = true,
                animate = scrubTo == null,
            )
            if (m.durationMs > 0L) {
                curvedRolling(
                    c, "dial:total", MediaPage.clock(m.durationMs), rings.personal,
                    centreDeg = MediaPage.TOTAL_AT, outward = false,
                )
            }
            // Bottom-left of its ring rather than over the top of it. Outside at twelve
            // o'clock lands in the twenty units between the two rings, which is not
            // enough, and any further out is inside the ring above. Down here there is
            // open panel. VOLUME with its reading attached, so the percentage rolls in the
            // same wheel as everything else and the word acts as its unit.
            curvedRolling(
                c, "dial:volume",
                MediaPage.VOLUME_LABEL + " " + Math.round(volumeShown * 100f) + "%",
                rings.work, centreDeg = 135f, outward = false,
            )
            extras.textAlign = Paint.Align.LEFT
            extras.textSize = typed(18f)
        }
        if (!deferOver(canvas, labels)) labels(canvas)
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
            // ORIGINAL's clock rolls through here: a frozen always-on page thaws for it
            if (animate) clockRollUntil = maxOf(clockRollUntil, frameNowMs + WHEEL_MS.toLong())
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
    /**
     * One line of the track's name, wherever along its travel it happens to be.
     *
     * ANCHORED THE WAY EACH END ANCHORS IT, and getting this wrong is what made the name
     * shift about while it changed. A line that FITS is centred — which is what the media
     * page has always done, and it matters for more than symmetry: the wheel draws the
     * outgoing title and the incoming one at the same moment, and centring puts each on
     * its own middle. Pinning them to a left edge computed from the incoming string's
     * width drags the outgoing one sideways as the card grows, which is a layout shift
     * with nothing behind it. A line that does NOT fit is left-aligned at the band's
     * edge, because a marquee has to start somewhere definite.
     *
     * [t] runs 0 at the readout's row to 1 here. At 1 this is exactly what the page drew
     * before there was any travel at all.
     */
    private fun line(
        canvas: Canvas,
        key: String,
        text: String,
        t: Float,
        cx: Float,
        baseline: Float,
        size: Float,
        width: Float,
    ) {
        val seen = extras.measureText(text)
        val runs = seen > width
        extras.textAlign = if (runs) Paint.Align.LEFT else Paint.Align.CENTER
        // The row holds this line by its left edge and the page by its middle, so the
        // journey is expressed in whichever of the two the drawing is about to use.
        val from = if (runs) nameHomeLeft else nameHomeLeft + seen / 2f
        val to = if (runs) cx - width / 2f else cx
        val at = from + (to - from) * t
        val left = if (runs) at else at - width / 2f
        rolling(
            canvas, key, text, at, baseline,
            android.graphics.RectF(
                left, baseline - size, left + width, baseline + size * 0.35f,
            ),
            width = width,
        )
    }

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
        // NOT gated on the lock, though it was at first. Taking the page away walked the
        // panel back to the readout, which is a page change nobody asked for — and worse,
        // it is one the panel cannot undo: the saved page is restored once per process,
        // so unlocking left you on the readout having been put there by a notification.
        // The lock does not need it. Input is refused wherever you are standing and the
        // slider is drawn over both pages, so the page you were on is simply still the
        // page you are on when you get back.
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
        val end = band.left + (band.right - band.left) * fraction

        if (config.theme.isGlass) {
            // THE PLAY BAR IS THE SAME INSTRUMENT AS THE OTHERS, at the one size it has.
            // Capsule ends and a thumb, published as its own layer for the thumb to bend
            // — see the bars. It is four units tall, so the thumb is what carries it: at
            // this size a track is a hairline and a hairline has no reading in it.
            val r = (bottom - top) / 2f
            trackRect.set(band.left, top, band.right, bottom)
            val layer = localBackdrop(canvas, trackRect) { into ->
                into.drawRoundRect(trackRect, r, r, noticePaint)
                if (end > band.left + 0.5f) {
                    noticePaint.color = tint
                    noticePaint.alpha = 255
                    barRect.set(band.left, top, end, bottom)
                    into.drawRoundRect(barRect, r, r, noticePaint)
                }
            }
            if (layer == null) {
                canvas.drawRoundRect(trackRect, r, r, noticePaint)
                noticePaint.color = tint
                noticePaint.alpha = 255
                barRect.set(band.left, top, end, bottom)
                canvas.drawRoundRect(barRect, r, r, noticePaint)
            }
            val th = (bottom - top) * MEDIA_THUMB
            val tw = th * SLIDER_THUMB_W / SLIDER_THUMB_H
            val span = band.right - band.left
            val cx = band.left + SliderThumb.centre(span, tw, fraction)
            val cy = (top + bottom) / 2f
            barRect.set(cx - tw / 2f, cy - th / 2f, cx + tw / 2f, cy + th / 2f)
            drawGlassPane(
                canvas, barRect, th / 2f, Glass.THUMB_TINT,
                source = layer ?: sceneNode, held = true,
            )
            noticePaint.color = tint
            noticePaint.alpha = 255
        } else {
            canvas.drawRect(band.left, top, band.right, bottom, noticePaint)
            noticePaint.color = tint
            noticePaint.alpha = 255
            canvas.drawRect(band.left, top, end, bottom, noticePaint)
        }
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
        CONTENT_EDGE, mark.top, mediaRight(config.theme), mark.bottom,
    )

    /**
     * What the media row is clipped to: its content band plus the strip above it.
     *
     * Separate from [mediaRegion] rather than folded into it. The band's HEIGHT is what
     * makes the cover square and centres the two lines of text, so growing it to make
     * room for the progress bar would have quietly stretched both.
     */
    private fun mediaClip(mark: android.graphics.RectF) = android.graphics.RectF(
        CONTENT_EDGE, mark.top, mediaRight(config.theme),
        // ROOM FOR THE PLAY BAR'S THUMB. The clip used to stop at the progress bar's own
        // bottom edge, which is right for a four-unit line and wrong for a thumb standing
        // proud of it — the lower half was cut off against a panel that had twenty units
        // of room left underneath. The bar is the last thing in this band, so extending
        // the clip takes nothing from anything else.
        mark.bottom + MediaLayout.PROGRESS_TOTAL +
            if (config.theme.isGlass) mediaThumbOverhang() else 0f,
    )

    /** How far the play bar's thumb stands below the bar it rides. */
    private fun mediaThumbOverhang(): Float =
        (MediaLayout.PROGRESS_H * MEDIA_THUMB - MediaLayout.PROGRESS_H) / 2f

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
        // ROOM FOR THE CARD'S OWN EDGE. With a cover there is a gap already; without one
        // the name started exactly at the row's left, which left its card a choice between
        // reaching past the clip and having no padding down that side. Neither is a
        // margin — so the text moves in by one instead, and the card sits where the row
        // starts with the same air on both sides.
        val textLeft = when {
            hasCover -> band.left + band.height() + MediaLayout.COVER_GAP
            config.theme.isGlass -> band.left + ROW_CARD_PAD_X
            else -> band.left
        }

        // ROOM FOR THE DISCS. The row is laid out from its right edge leftwards, so the
        // last glyph's own box ends exactly there — and under GLASS each glyph sits on a
        // disc wider than the box it is in. The overhang is the difference between the
        // disc's radius and half the glyph, and without allowing for it the NEXT button's
        // disc is cut down its right-hand side by the row's clip.
        val discRoom =
            if (config.theme.isGlass) MediaLayout.GLYPH_H * (DISC_RADIUS - 0.5f) else 0f
        val row = MediaLayout.of(
            textLeft, band.right - discRoom, region.centerY(), config.theme,
        )
        val gh = MediaLayout.GLYPH_H
        val (prev, play, next) = Triple(row.prev, row.play, row.next)

        extras.color = tint
        val discCy = row.glyphTop + gh / 2f

        // WHERE THESE THREE REST, for the media page to travel them to. Mapped through
        // the live matrix rather than reasoned about: this row sits inside the readout,
        // which is drifting for burn-in, pushed by a notice and slid by the scroll all at
        // once, and the only honest way to say where it is on the panel is to ask. The
        // slide is then taken back off, because the destination wants the place the row
        // RESTS, not the place the scroll has already dragged it to.
        transportPoints[0] = prev + gh * Glyphs.aspect(PanelIcons.PREV, config.theme) / 2f
        transportPoints[2] = play + gh * Glyphs.aspect(PanelIcons.PAUSE, config.theme) / 2f
        transportPoints[4] = next + gh * Glyphs.aspect(PanelIcons.NEXT, config.theme) / 2f
        transportPoints[1] = discCy
        transportPoints[3] = discCy
        transportPoints[5] = discCy
        canvas.getMatrix().mapPoints(transportPoints)
        transportHomeX[0] = transportPoints[0] - dxNow
        transportHomeX[1] = transportPoints[2] - dxNow
        transportHomeX[2] = transportPoints[4] - dxNow
        transportHomeY = transportPoints[1] + pagedNow - dyNow
        transportHomeH = gh
        transportHomeKnown = true

        // AND THE ROW ONLY DRAWS THEM WHERE IT OWNS THEM. Off rest they belong to the
        // travelling set, which the media page draws — see drawMediaPage. Drawing both
        // would be the same three buttons twice, which is what this replaced.
        if (pagingNow <= 0.001f) {
            glassButton(
                canvas,
                prev + gh * Glyphs.aspect(PanelIcons.PREV, config.theme) / 2f, discCy, gh,
            ) { c -> extras.color = tint; drawIcon(c, PanelIcons.PREV, prev, row.glyphTop, gh) }
            glassButton(
                canvas,
                next + gh * Glyphs.aspect(PanelIcons.NEXT, config.theme) / 2f, discCy, gh,
            ) { c -> extras.color = tint; drawIcon(c, PanelIcons.NEXT, next, row.glyphTop, gh) }
        }

        // The middle glyph is its own little drum, clipped to one cell: pause rises out
        // of the top as play drops off the bottom. It is the same gesture as the row
        // arriving, one size down, so a track being paused and the controls appearing
        // read as the same machine.
        //
        // On a disc like the two beside it. It was the only one of the three without,
        // because it is drawn apart from them — the drum needs its own clip — and taking
        // it out of the row cost it the button it sits on.
        if (pagingNow <= 0.001f) {
            glassButton(
                canvas,
                play + gh * Glyphs.aspect(PanelIcons.PAUSE, config.theme) / 2f, discCy, gh,
            ) { c ->
                c.save()
                c.clipRect(play, region.top, play + gh, region.bottom)
                drawPlayPause(c, play, row.glyphTop, gh, tint)
                c.restore()
            }
        }

        val hasArtist = m.artist.isNotBlank()
        var baseline = MediaLayout.firstBaseline(region.centerY(), hasArtist)

        // The same card the media page's name gets. This row is the same information in a
        // smaller place, and over a photograph it needs something to sit on just as much.
        extrasMedia.textSize = typed(MediaLayout.TEXT_SIZE)
        val rowTitle = sequenced("row:title", m.title) { extrasMedia.measureText(it) }
        var rowWant = sequencedWidth
        val rowArtist =
            if (!hasArtist) ""
            else sequenced("row:artist", m.artist) { extrasMedia.measureText(it) }
        if (hasArtist) rowWant = maxOf(rowWant, sequencedWidth)
        if (config.theme.isGlass) {
            var w = ease("row:card:w", minOf(rowWant, row.textWidth), CARD_GROW_MS, back = true)
            val top = baseline - MediaLayout.TEXT_SIZE - ROW_CARD_PAD_Y
            val bottom =
                (if (hasArtist) baseline + MediaLayout.LINE else baseline) + ROW_CARD_PAD_Y
            // Never left of the row itself. With no cover the text starts AT the row's
            // left edge, so a card with padding around it reached past into the clip and
            // came back with a straight edge down one side.
            cardRect.set(
                maxOf(textLeft - ROW_CARD_PAD_X, band.left),
                top,
                textLeft + w + ROW_CARD_PAD_X,
                bottom,
            )
            // Where this card rests, in the media page's space. See nameHomeCard.
            nameHomeCard.set(cardRect)
            canvas.getMatrix().mapRect(nameHomeCard)
            nameHomeCard.offset(-dxNow, pagedNow - dyNow)
            if (pagingNow <= 0.001f) glassCard(canvas, cardRect, ROW_CARD_RADIUS)
        }

        // And where the two lines rest. Mapped, like the transport's, because this row is
        // drifting and pushed and slid all at once.
        namePoints[0] = textLeft
        namePoints[1] = baseline
        namePoints[2] = textLeft
        namePoints[3] = baseline + MediaLayout.LINE
        canvas.getMatrix().mapPoints(namePoints)
        nameHomeLeft = namePoints[0] - dxNow
        nameHomeTitle = namePoints[1] + pagedNow - dyNow
        nameHomeArtist = namePoints[3] + pagedNow - dyNow
        nameHomeWidth = row.textWidth
        nameHomeKnown = true

        // The same wheel the media page uses, for the same reason: a track changing is
        // news and should be seen changing. `rolling` hands each line to the marquee when
        // it does not fit, so a long title both turns over and then scrolls.
        // LIGHTER THAN THE PANEL, and put back afterwards. See PanelType.MEDIA:
        // this is the one string here that is somebody else's writing rather than a label
        // this app chose, and at the readings' weight it competes with them.
        //
        // A different PAINT, swapped before anything is set on it so the colour and size
        // land on the one that is going to draw. See extrasMedia.
        extras = extrasMedia
        extras.textAlign = Paint.Align.LEFT
        extras.textSize = typed(MediaLayout.TEXT_SIZE)
        extras.color = tint
        // Only where the row owns them. Off rest the name belongs to the travelling copy
        // the media page draws — see nameHomeCard.
        if (pagingNow <= 0.001f) rolling(
            canvas, "row:title", rowTitle, textLeft, baseline,
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
            // The artist goes with the title. Left at the panel's weight it would come
            // out bolder than the name of the track above it.
            if (pagingNow <= 0.001f) rolling(
                canvas, "row:artist", rowArtist, textLeft, baseline,
                android.graphics.RectF(
                    textLeft, baseline - MediaLayout.TEXT_SIZE,
                    textLeft + row.textWidth, baseline + MediaLayout.TEXT_SIZE * 0.35f,
                ),
                width = row.textWidth,
            )
        }
        // Back to the panel's weight. `extras` is shared with the clock, the battery and
        // half the media page, and a paint left lighter than it was found is a setting
        // that leaks into whatever draws next.
        extras = extrasBody
        extras.textSize = typed(18f)

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
    private fun customArt(): android.graphics.Bitmap? = artCache.of(config.art.imagePath)

    /**
     * A decoded picture, kept until the file behind it changes.
     *
     * There are two of these now — the maker's mark and the panel's backdrop — and they
     * are the same problem twice: decode once, notice when the file is replaced, and
     * never hold two copies of the same image. Keyed on the path AND the file's stamp,
     * because both live at a fixed filename and choosing a new picture overwrites it, so
     * the path alone would never look like it had changed.
     */
    private class CachedImage {
        private var key: String? = null
        private var bitmap: android.graphics.Bitmap? = null

        fun of(path: String?): android.graphics.Bitmap? {
            if (path == null) return null
            val file = java.io.File(path)
            val id = path + ":" + file.lastModified() + ":" + file.length()
            if (id != key) {
                key = id
                val decoded = try {
                    android.graphics.BitmapFactory.decodeFile(path)
                } catch (_: Exception) {
                    null
                }
                // A file that will not decode leaves what was there. A panel that went
                // blank because an image went missing would look like the panel broke.
                if (decoded != null) {
                    bitmap?.recycle()
                    bitmap = decoded
                }
            }
            return bitmap
        }
    }

    private val artCache = CachedImage()
    private val backdropCache = CachedImage()

    /**
     * The user's own picture behind the whole readout.
     *
     * CLIPPED TO THE PANEL and drawn straight after the ground colour, so everything else
     * sits over it — this is a backdrop, not a layer the readout has to compete with.
     * Under GLASS it is also the best thing that has ever been behind a pane: the lenses
     * have had almost nothing but flat black to bend until now.
     */
    /**
     * The ground, with whatever is falling on it.
     *
     * The colour and the picture go into a node of their own so a shader can be hung on
     * it — an effect can only be applied to a node, and the ground is drawn straight onto
     * a canvas otherwise. With no weather this is one extra display list holding two
     * operations, which is why it is not worth branching around.
     *
     * ONLY THE GROUND GOES IN. The record is drawn after this and the readout after that,
     * so the rain runs behind everything and in front of nothing: drops crossing the
     * account headings would make the numbers harder to read, and the numbers are why the
     * panel exists. The account cards refract this node, so they bend the rain too, which
     * is a window seen through a window and costs nothing — they were sampling it anyway.
     */
    /**
     * Record the weather's node, BEFORE anything else opens a recording of its own.
     *
     * TWO NODES CANNOT BE RECORDED AT ONCE. This built its node from inside the ground's
     * beginRecording — one recording opened while another was still open — and the panel
     * came out with flat horizontal bands torn across it, strips of display list that had
     * gone somewhere they did not belong. Prepared first and merely DRAWN later, the two
     * recordings never overlap.
     *
     * Returns false when there is no weather, or nowhere to put it.
     */
    private fun prepareWeather(): Boolean {
        if (weatherMode() == Weather.OFF || width <= 0 || height <= 0) return false
        // Nothing falls on a frozen page: the weather fades with the backdrop and is not
        // even prepared once always-on has settled — its shader is the dearest thing here.
        if (aodFade(frameNowMs) >= 0.999f) return false
        // A background that moves cannot be a cached bitmap: the weather's ground is then
        // the live shader itself, rebuilt as cheaply as a shader object per frame.
        val live = liveGround()
        if (live != null) {
            groundKey = null
            groundBitmap?.recycle(); groundBitmap = null
            groundShader = null
        }
        // THE GROUND, AS A TEXTURE. Rebuilt only when the background changes — it is a
        // colour and a photograph, and neither moves between frames.
        val key = config.effectiveBackdrop.name + "|" + (config.backdrop.imagePath ?: "-") + "|" + groundColor + "|" +
            config.backdrop.panX + "," + config.backdrop.panY + "," + config.backdrop.zoom +
            "|" + config.backdrop.opacity + "|" + width + "x" + height
        if (live == null && (key != groundKey || groundShader == null)) {
            groundKey = key
            groundBitmap?.recycle()
            val bmp = android.graphics.Bitmap.createBitmap(
                width, height, android.graphics.Bitmap.Config.ARGB_8888,
            )
            val onto = Canvas(bmp)
            onto.drawColor(groundColor)
            drawBackdrop(onto)
            groundBitmap = bmp
            groundShader = android.graphics.BitmapShader(
                bmp,
                android.graphics.Shader.TileMode.CLAMP,
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        // REBUILT EVERY FRAME, and it has to be.
        //
        // This cached the effect and updated the shader's clock afterwards, on the
        // reasoning that building a RenderEffect per frame is the churn that filled the
        // native heap once already. The reasoning is sound and the code did not work:
        // createRuntimeShaderEffect SNAPSHOTS the shader's uniforms. An effect made once
        // never sees a later setFloatUniform, so the clock advanced, nothing read it, and
        // the rain hung motionless on the glass.
        //
        // The leak it was avoiding was fifteen panes rebuilding an effect apiece every
        // frame. This is one, for the whole panel, and there is no version of an animated
        // shader that avoids it: the uniform is the animation.
        val shader = Weather.shader
        shader.setInputShader("ground", live ?: groundShader!!)
        shader.setFloatUniform("size", width.toFloat(), height.toFloat())
        shader.setFloatUniform("mode", if (weatherMode() == Weather.SNOW) 1f else 0f)
        shader.setFloatUniform("amount", Weather.AMOUNT)
        setLenses(shader)
        shader.setFloatUniform(
            "time", (frameNowMs % (Weather.PERIOD_S * 1000L).toLong()) / 1000f,
        )
        // DRAWN, NOT APPLIED. With the ground as a texture there is nothing left for a
        // RenderEffect to do: the shader produces the finished picture rather than
        // transforming one, so it is simply painted over the panel. That also disposes of
        // the per-frame effect allocation the animation used to require.
        weatherPaint.shader = shader
        // Rain that only moves when something else does is not falling.
        animating = true
        return true
    }

    /**
     * The ground, with whatever is falling on it.
     *
     * Draws the node [prepareWeather] recorded, or the plain ground when there is none —
     * which also covers the front screen's preview, where there is no recording canvas to
     * draw a node into at all.
     */
    private fun drawWeatheredGround(canvas: Canvas) {
        val keep = 1f - aodFade(frameNowMs)
        if (weatherReady && keep > 0.001f) {
            // On the way into always-on the weather thins out over the plain ground, which
            // is itself fading to black underneath; the weather's own ground is a cached
            // bitmap that knows nothing of the fade, so it must not be the only thing drawn.
            if (keep < 0.999f) {
                canvas.drawColor(groundColor)
                drawBackdrop(canvas)
            }
            panelRect.set(0f, 0f, Panel.WIDTH.toFloat(), Panel.HEIGHT.toFloat())
            weatherPaint.alpha = (255 * keep).toInt()
            canvas.drawRect(panelRect, weatherPaint)
            weatherPaint.alpha = 255
            drawMist(canvas, keep)
            drawAodBandVeil(canvas)
            return
        }
        canvas.drawColor(groundColor)
        drawBackdrop(canvas)
        drawAodBandVeil(canvas)
    }

    private val weatherPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var groundKey: String? = null
    private var groundBitmap: android.graphics.Bitmap? = null
    private var groundShader: android.graphics.BitmapShader? = null

    /** Whether this frame's weather node is recorded and can simply be drawn. */
    private var weatherReady = false

    private val weatherNode = android.graphics.RenderNode("weather")

    /**
     * The background over the ground colour, whichever it is: the picture, the video's
     * latest frame, or the silk. Every place that drew the picture draws this.
     */
    private fun drawBackdrop(canvas: Canvas) {
        when (config.effectiveBackdrop) {
            dev.kb896.aiusage.data.BackdropKind.IMAGE -> drawImageBackdrop(canvas)
            dev.kb896.aiusage.data.BackdropKind.SHADER -> drawSilkBackdrop(canvas)
            dev.kb896.aiusage.data.BackdropKind.VIDEO -> drawVideoBackdrop(canvas)
            dev.kb896.aiusage.data.BackdropKind.COLOR -> Unit
        }
    }

    /** The silk, at the front's settings, over the panel's units; its own clock. */
    private val silk by lazy {
        dev.kb896.aiusage.ui.glass.Silk.create().apply {
            setFloatUniform("size", Panel.WIDTH.toFloat(), Panel.HEIGHT.toFloat())
        }
    }
    private val silkPaint = Paint()
    private val silkSpeed = SpeedRamp()
    private val silkShift = android.graphics.Matrix()
    private var silkTime = 0f
    private var silkLastMs = 0L

    /**
     * The silk moving while the panel is awake and frozen in always-on, where it darkens to
     * black at the backdrop's pace as the picture does. Software canvases (the brightness
     * map, the weather's ground) never reach here — see refreshLuminance and liveGround.
     */
    /**
     * The silk's clock, once a frame, before anything draws it — the weather's ground is the
     * same shader, and under rain the backdrop itself is never drawn, so the clock cannot
     * live in the drawing.
     */
    private fun tickSilk() {
        if (config.effectiveBackdrop != dev.kb896.aiusage.data.BackdropKind.SHADER) { silkLastMs = 0L; return }
        // It slows to a stop going into always-on or the double tap's dim, and picks up
        // again coming out — always, as the videos do with their ease. See SpeedRamp.
        silkSpeed.toward(if (aod || videoPaused) 0f else 1f, frameNowMs)
        val speed = silkSpeed.at(frameNowMs)
        if (silkLastMs != 0L) silkTime += 0.1f * (frameNowMs - silkLastMs) / 1000f * speed
        val moving = speed > 0f || silkSpeed.turning(frameNowMs)
        silkLastMs = if (moving) frameNowMs else 0L
        silk.setFloatUniform("time", silkTime)
        silkShift.setTranslate(parallaxDx, parallaxDy)
        silk.setLocalMatrix(silkShift)
        if (moving) animating = true
    }

    private fun drawSilkBackdrop(canvas: Canvas) {
        val fade = aodFade(frameNowMs)
        if (fade >= 0.999f) return
        silkPaint.shader = silk
        silkPaint.colorFilter = if (fade > 0f) {
            val keep = (255 * (1f - fade)).toInt().coerceIn(0, 255)
            android.graphics.LightingColorFilter((keep shl 16) or (keep shl 8) or keep, 0)
        } else null
        panelRect.set(0f, 0f, Panel.WIDTH.toFloat(), Panel.HEIGHT.toFloat())
        canvas.drawRect(panelRect, silkPaint)
    }

    private val video = VideoPlayback(onFrame = { invalidate() }, onLost = { syncVideo(fresh = true) })

    /** Whether this panel's window is on screen — the front's preview, with the app in the background, is not. */
    private var windowShown = true

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        val shown = visibility == VISIBLE
        if (shown == windowShown) return
        windowShown = shown
        syncVideo(fresh = shown)
        syncTilt()
    }

    /**
     * The double tap's dim: the video stops on the frame it was showing. Set by the activity
     * around the dim; called off, the video starts over from that frame. See VideoState.
     */
    var videoPaused: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            syncVideo(fresh = !value)
        }

    /**
     * Brings the player in line with the panel; [fresh] when the panel has just come back,
     * [changed] when the video itself was changed. See VideoState.
     */
    private fun syncVideo(fresh: Boolean = false, changed: Boolean = false) {
        val v = config.video
        val clip = v.clipPath?.takeIf { java.io.File(it).canRead() }
        val action = VideoState.decide(
            isVideo = config.effectiveBackdrop == dev.kb896.aiusage.data.BackdropKind.VIDEO,
            hasClip = clip != null, attached = isAttachedToWindow, displayAwake = displayAwake, visible = windowShown,
            aod = aod, paused = videoPaused, locked = locked, mode = v.playable, preview = !interactive,
            fresh = fresh, changed = changed,
        )
        // The reversed copy only when the video goes backwards.
        val backwards = v.playable == dev.kb896.aiusage.data.VideoPlayMode.YOYO ||
            v.playable == dev.kb896.aiusage.data.VideoPlayMode.PING_PONG
        val created = when (action) {
            VideoAction.DROP -> { video.drop(); false }
            VideoAction.SLEEP -> { video.sleep(); false }
            else -> video.open(clip!!, v.reversePath?.takeIf { backwards && java.io.File(it).canRead() })
        }
        // The eases' seconds on screen, as the stretches of clip that take that long.
        val easeIn = EaseCurve.clipRampFor(v.easeInWallMs.toLong())
        val easeOut = EaseCurve.clipRampFor(v.easeOutWallMs.toLong())
        val (passIn, passOut) = VideoState.passEase(!interactive, v.playable, easeIn, easeOut)
        // On the back, the ease also softens every stop and start: the double tap, always-on, a wake.
        video.easeStops(into = if (interactive) easeIn else 0L, out = if (interactive) easeOut else 0L)
        when (VideoState.afterOpen(action, created)) {
            VideoAction.FREEZE -> video.freeze()
            VideoAction.LOOP -> video.loop(restart = false, passIn, passOut)
            VideoAction.RESTART_LOOP -> video.loop(restart = true, passIn, passOut)
            VideoAction.PLAY_ONCE -> video.playOnce(easeIn, easeOut)
            VideoAction.TO_END -> video.toEnd(easeIn, easeOut)
            VideoAction.TO_START -> video.toStart(easeIn, easeOut)
            VideoAction.PING_PONG -> video.pingPong(restart = false, passIn, passOut)
            VideoAction.RESTART_PING_PONG -> video.pingPong(restart = true, passIn, passOut)
            else -> Unit
        }
        invalidate()
    }

    private val videoPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /**
     * The video's latest frame over the whole panel, at its opacity, darkening to black in
     * always-on as the picture does. A hardware bitmap draws only on a hardware canvas;
     * the software ones (brightness, the weather's cached ground) take the stored values.
     */
    private fun drawVideoBackdrop(canvas: Canvas) {
        val frame = video.shown ?: return
        if (!canvas.isHardwareAccelerated) return
        val fade = aodFade(frameNowMs)
        if (fade >= 0.999f) return
        panelRect.set(0f, 0f, Panel.WIDTH.toFloat(), Panel.HEIGHT.toFloat())
        val now = SystemClock.uptimeMillis()
        videoRegion(videoDest)
        canvas.save()
        canvas.clipRect(panelRect)
        // A slowed run: the frame fading in over the one before it. A restart: the frame it
        // stopped on, fading out over the new run.
        val blend = video.blend(now)
        val previous = video.previous?.takeIf { blend < 1f }
        val from = video.fadeFrom
        val left = video.fadeLeft(now)
        val opacity = config.video.opacity.coerceIn(0f, 1f)
        // Stacked frames at the video's opacity would let more through mid-blend than at
        // either end; drawn whole into a layer that has the opacity, they cannot.
        val stacked = previous != null || (from != null && left > 0f)
        val layered = stacked && opacity < 0.999f
        if (layered) canvas.saveLayerAlpha(panelRect, (255 * opacity).toInt())
        val full = if (layered) 255f else 255f * opacity
        videoPaint.colorFilter = if (fade > 0f) {
            val keep = (255 * (1f - fade)).toInt().coerceIn(0, 255)
            android.graphics.LightingColorFilter((keep shl 16) or (keep shl 8) or keep, 0)
        } else null
        if (previous != null) {
            videoPaint.alpha = full.toInt()
            canvas.drawBitmap(previous, null, videoDest, videoPaint)
        }
        videoPaint.alpha = (full * if (previous != null) blend else 1f).toInt()
        canvas.drawBitmap(frame, null, videoDest, videoPaint)
        if (from != null && left > 0f) {
            videoPaint.alpha = (full * left).toInt()
            canvas.drawBitmap(from, null, videoDest, videoPaint)
        }
        if (layered) canvas.restore()
        canvas.restore()
        if (stacked) animating = true
    }

    private val videoDest = RectF()

    /**
     * Where the video is drawn: the panel, or past it by the clip's own margin of footage —
     * or, for a clip cut without one, enlarged by the parallax margin while parallax is on —
     * and moved by the parallax.
     */
    private fun videoRegion(out: RectF): RectF {
        val m = config.video.margin.takeIf { it > 0f } ?: if (parallaxOn) Parallax.MARGIN else 0f
        return backdropRegion(m, out)
    }

    // ---- parallax ----

    /** The phone's tilt, from the gravity sensor, while it is read. See syncTilt. */
    private val tilt = TiltTracker()
    private var parallaxDx = 0f
    private var parallaxDy = 0f

    private val parallaxOn: Boolean
        get() = (config.parallaxTilt || config.parallaxScroll) &&
            config.effectiveBackdrop != dev.kb896.aiusage.data.BackdropKind.COLOR

    /**
     * This frame's shift of the background, in panel units: away from the tilt, as a
     * picture behind glass moves, and up as the pages scroll down — by less than the pages
     * do, within the margin.
     */
    private fun updateParallax() {
        if (!parallaxOn) { parallaxDx = 0f; parallaxDy = 0f; return }
        val tx = if (config.parallaxTilt) tilt.x else 0f
        val ty = if (config.parallaxTilt) tilt.y else 0f
        val sy = if (config.parallaxScroll) Parallax.scroll(pager.offset, Panel.HEIGHT.toFloat(), pager.pages) else 0f
        val (x, y) = Parallax.combine(tx, ty, sy)
        parallaxDx = -Parallax.px(x, Panel.WIDTH.toFloat())
        parallaxDy = -Parallax.px(y, Panel.HEIGHT.toFloat())
    }

    /** The panel, [margin] bigger on every side, moved by this frame's parallax. */
    private fun backdropRegion(margin: Float, out: RectF): RectF {
        val w = Panel.WIDTH.toFloat()
        val h = Panel.HEIGHT.toFloat()
        out.set(-margin * w, -margin * h, w + margin * w, h + margin * h)
        out.offset(parallaxDx, parallaxDy)
        return out
    }

    private var tiltListening = false
    private var tiltAtNs = 0L
    private val tiltListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            val g = kotlin.math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
            if (g < 1f) return
            val dtMs = if (tiltAtNs == 0L) 16L else ((e.timestamp - tiltAtNs) / 1_000_000L).coerceIn(1L, 200L)
            tiltAtNs = e.timestamp
            // Measured by hand on the back: tipping it left or right swings gravity along the
            // phone's x, tipping it up or down along its y. Signed so the background moves
            // against the tip, as a picture behind glass does: left moves it right, up moves it down.
            val beforeX = tilt.x
            val beforeY = tilt.y
            tilt.update(
                roll = kotlin.math.asin((e.values[0] / g).coerceIn(-1f, 1f)),
                pitch = -kotlin.math.asin((e.values[1] / g).coerceIn(-1f, 1f)),
                dtMs = dtMs,
            )
            if (kotlin.math.abs(tilt.x - beforeX) > 0.002f || kotlin.math.abs(tilt.y - beforeY) > 0.002f) invalidate()
        }
        override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) = Unit
    }

    /**
     * Reads the tilt only while it can be seen to move something: tilt parallax on, a
     * background that moves, the panel on screen, awake and not always-on.
     */
    private fun syncTilt() {
        val want = config.parallaxTilt && parallaxOn && isAttachedToWindow && displayAwake && windowShown && !aod
        if (want == tiltListening) return
        val sensors = context.getSystemService(android.hardware.SensorManager::class.java) ?: return
        if (want) {
            val gravity = sensors.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY) ?: return
            tilt.reset()
            tiltAtNs = 0L
            sensors.registerListener(tiltListener, gravity, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        } else {
            sensors.unregisterListener(tiltListener)
        }
        tiltListening = want
    }

    /**
     * The ground as a shader when the background moves: the silk, or the video's frame over
     * the ground colour at its opacity. Null for a colour or a picture, whose ground is the
     * cached bitmap.
     */
    private fun liveGround(): android.graphics.Shader? = when (config.effectiveBackdrop) {
        dev.kb896.aiusage.data.BackdropKind.SHADER -> silk
        dev.kb896.aiusage.data.BackdropKind.VIDEO -> videoGround()
        // A picture that moves with the parallax is a live ground too: a cached one would be rebuilt every frame.
        dev.kb896.aiusage.data.BackdropKind.IMAGE -> if (parallaxOn) imageGround() else null
        else -> null
    }

    private fun imageGround(): android.graphics.Shader? {
        val bitmap = backdropCache.of(config.backdrop.imagePath) ?: return null
        val dest = ArtPlacement.destination(
            backdropRegion(Parallax.MARGIN, imageRegion), bitmap.width, bitmap.height,
            config.backdrop.panX, config.backdrop.panY, config.backdrop.zoom,
        )
        return pictureGround(bitmap, dest, config.backdrop.opacity)
    }

    /** The frame over the ground colour at the video's opacity, as one shader for the weather. */
    private fun videoGround(): android.graphics.Shader? {
        val frame = video.shown ?: return null
        return pictureGround(frame, videoRegion(videoDest), config.video.opacity)
    }

    /** A picture drawn into [dest] over the ground colour at [opacity], as one shader for the weather. */
    private fun pictureGround(bitmap: android.graphics.Bitmap, dest: RectF, opacity: Float): android.graphics.Shader {
        val picture = android.graphics.BitmapShader(bitmap,
            android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP,
        ).apply {
            setLocalMatrix(android.graphics.Matrix().apply {
                setRectToRect(RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()), dest, android.graphics.Matrix.ScaleToFit.FILL)
            })
        }
        val alpha = (255 * opacity.coerceIn(0f, 1f)).toInt() shl 24
        val thinned = android.graphics.ComposeShader(
            picture, solid(alpha), android.graphics.PorterDuff.Mode.DST_IN,
        )
        return android.graphics.ComposeShader(solid(groundColor), thinned, android.graphics.PorterDuff.Mode.SRC_OVER)
    }

    /** One colour as a shader: a gradient between the same two stops. */
    private fun solid(argb: Int) = android.graphics.LinearGradient(
        0f, 0f, 1f, 0f, argb, argb, android.graphics.Shader.TileMode.CLAMP,
    )

    private fun drawImageBackdrop(canvas: Canvas) {
        val bitmap = backdropCache.of(config.backdrop.imagePath) ?: return
        panelRect.set(0f, 0f, Panel.WIDTH.toFloat(), Panel.HEIGHT.toFloat())
        // Placed in the panel — or, with parallax, in the panel and its margin, moved.
        val dest = ArtPlacement.destination(
            backdropRegion(if (parallaxOn) Parallax.MARGIN else 0f, imageRegion), bitmap.width, bitmap.height,
            config.backdrop.panX, config.backdrop.panY, config.backdrop.zoom,
        )
        // always-on: the backdrop fades to black over a second and stays there. The picture
        // shows only as much as its opacity, the ground colour through the rest.
        //
        // DARKENED, NOT THINNED. The ground fades to black underneath on its own, so the
        // picture fading by its alpha let more of the ground through as it went: the panel
        // passed through the colour behind on its way to black — pink, under a picture with
        // no pink in it. The picture keeps its opacity and its colours go to black at the
        // ground's pace, so the two fade as the one picture they are.
        val fade = aodFade(frameNowMs)
        if (fade >= 0.999f) return
        val opacity = config.backdrop.opacity.coerceIn(0f, 1f)
        canvas.save()
        canvas.clipRect(panelRect)
        if (opacity < 1f) artPaint.alpha = (255 * opacity).toInt()
        if (fade > 0f) {
            val keep = (255 * (1f - fade)).toInt().coerceIn(0, 255)
            artPaint.colorFilter = android.graphics.LightingColorFilter((keep shl 16) or (keep shl 8) or keep, 0)
        }
        canvas.drawBitmap(bitmap, null, dest, artPaint)
        artPaint.alpha = 255
        artPaint.colorFilter = null
        canvas.restore()
    }

    private val panelRect = android.graphics.RectF()
    private val imageRegion = android.graphics.RectF()

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
        demoMood(account)
            ?: state.statuses.firstOrNull { it.account == account }?.mascot
            ?: MascotMood.WALK

    // ---- demo mode -------------------------------------------------------------------
    //
    // WHAT IT IS FOR. This panel lives on the back of a phone, so showing what it does
    // means either a hand in every shot or a surface that demonstrates itself — and a hand
    // reaching across a 904-unit display covers most of what it came to show. On a loop it
    // can be filmed in one take from a tripod.
    //
    // IN THE APP RATHER THAN OVER ADB, which is where the previous one lived — see
    // tools/rear-demo.py in the notes. That drove the panel by broadcasting synthetic
    // state, which works and has two costs: it needs a machine attached to film a phone,
    // and it can only push things the payload can carry. It cannot turn a page. This can.
    //
    // THE SEGMENTS, and why each is as long as it is. Every mood the mascots have gets a
    // turn, two at a time, because the two accounts are never in the same mood at once and
    // seeing them differ is half of what the moods are FOR. Walk gets a full pass of
    // WalkRig's own duration — a shorter window catches half a crossing and half the
    // mirrored return, which reads as the animal changing its mind. The rest get long
    // enough for the bars and rings under them to finish moving, since Tween.DURATION_MS
    // is 1.8s and a segment cut inside that shows a transition rather than a state.
    // THE READINGS MOVE WITH THE MOOD, because the mood IS a reading. A mascot is what an
    // account's 5-hour window has earned — see MascotMood — so a demo that flipped the
    // animal while the bars underneath it sat still would be showing the two disagreeing,
    // which is the one thing they never do.
    //
    // The colours come free. Every bar runs the same continuous green-to-red ramp against
    // its own fill, so driving the numbers drives the palette; there is nothing separate
    // to set, and a bar's colour in the film is the colour that number really produces.
    private val demoSegments = listOf(
        DemoStep(11.6f, PAGE_READOUT, MascotMood.WALK, MascotMood.CONFETTI,
            personalBars = floatArrayOf(0.35f, 0.42f, 0.30f),
            workBars = floatArrayOf(0.00f, 0.04f, 0.02f),
            personalTimes = arrayOf("4h03m", "6d13h", "6d13h"),
            workTimes = arrayOf("4h36m", "6d21h", "6d21h")),
        DemoStep(5f, PAGE_READOUT, MascotMood.FLAG, MascotMood.MUSCLE,
            personalBars = floatArrayOf(0.94f, 0.78f, 0.61f),
            workBars = floatArrayOf(0.52f, 0.33f, 0.24f),
            personalTimes = arrayOf("0h15m", "1d04h", "1d04h"),
            workTimes = arrayOf("3h00m", "3d06h", "3d06h")),
        DemoStep(5f, PAGE_READOUT, MascotMood.STAND, MascotMood.DEAD,
            personalBars = floatArrayOf(0.66f, 0.55f, 0.40f),
            workBars = floatArrayOf(1.00f, 1.00f, 1.00f),
            personalTimes = arrayOf("2h40m", "2d18h", "2d18h"),
            workTimes = arrayOf("0h01m", "0h01m", "0h01m")),
        DemoStep(5f, PAGE_READOUT, MascotMood.SLEEP, MascotMood.GHOST,
            personalBars = floatArrayOf(0.08f, 0.12f, 0.05f),
            workBars = floatArrayOf(0.77f, 0.88f, 0.70f),
            personalTimes = arrayOf("4h44m", "6d09h", "6d09h"),
            workTimes = arrayOf("1h06m", "0d19h", "0d19h")),
        // AN ACCOUNT THAT IS NOT ANSWERING, which is the one state where the panel stops
        // using an account's own colour: the heading, the plan bracket, the labels and the
        // bars all go to DIM, and AUTH puts SIGN IN where the bracket was. A film that
        // never shows it makes the panel look like it only ever has good news.
        DemoStep(5f, PAGE_READOUT, MascotMood.STAND, MascotMood.STAND,
            personalBars = floatArrayOf(0.51f, 0.44f, 0.36f),
            workBars = floatArrayOf(0.29f, 0.22f, 0.18f),
            personalTimes = arrayOf("2h11m", "4d02h", "4d02h"),
            workTimes = arrayOf("—", "—", "—"),
            workHealth = Health.AUTH),
        // The pages, once the animals have been through everything they can do.
        DemoStep(6f, PAGE_NOTICES, MascotMood.WALK, MascotMood.MUSCLE,
            personalBars = floatArrayOf(0.45f, 0.50f, 0.38f),
            workBars = floatArrayOf(0.60f, 0.41f, 0.33f),
            personalTimes = arrayOf("1h47m", "3d19h", "3d19h"),
            workTimes = arrayOf("2h58m", "1d22h", "1d22h")),
        // ---- the media page, three segments, because it does three things -------------
        //
        // A track playing, then the SAME track paused, then a different one: the change
        // is the point. The record crosses, the name rolls over on its wheel and the
        // transport shows what it is for. One long segment would have shown a record
        // turning and nothing happening to it.
        DemoStep(7f, PAGE_MEDIA, MascotMood.WALK, MascotMood.MUSCLE,
            personalBars = floatArrayOf(0.45f, 0.50f, 0.38f),
            workBars = floatArrayOf(0.60f, 0.41f, 0.33f),
            personalTimes = arrayOf("1h47m", "3d19h", "3d19h"),
            workTimes = arrayOf("2h58m", "1d22h", "1d22h"),
            track = "Windowlicker", artist = "Aphex Twin", volume = 0.30f),
        DemoStep(4f, PAGE_MEDIA, MascotMood.WALK, MascotMood.MUSCLE,
            personalBars = floatArrayOf(0.45f, 0.50f, 0.38f),
            workBars = floatArrayOf(0.60f, 0.41f, 0.33f),
            personalTimes = arrayOf("1h44m", "3d19h", "3d19h"),
            workTimes = arrayOf("2h55m", "1d22h", "1d22h"),
            track = "Windowlicker", artist = "Aphex Twin", playing = false, volume = 0.72f),
        DemoStep(7f, PAGE_MEDIA, MascotMood.WALK, MascotMood.MUSCLE,
            personalBars = floatArrayOf(0.46f, 0.50f, 0.38f),
            workBars = floatArrayOf(0.61f, 0.41f, 0.33f),
            personalTimes = arrayOf("1h41m", "3d19h", "3d19h"),
            workTimes = arrayOf("2h52m", "1d22h", "1d22h"),
            track = "Xtal", artist = "Aphex Twin", volume = 0.55f),
        // Back to the readout for the charging cell, which is drawn over whatever page is
        // showing but belongs with the readings.
        DemoStep(7f, PAGE_READOUT, MascotMood.WALK, MascotMood.CONFETTI, charge = true,
            personalBars = floatArrayOf(0.35f, 0.42f, 0.30f),
            workBars = floatArrayOf(0.00f, 0.04f, 0.02f),
            personalTimes = arrayOf("4h58m", "6d20h", "6d20h"),
            workTimes = arrayOf("4h58m", "6d20h", "6d20h")),
    )

    private class DemoStep(
        val seconds: Float,
        val page: Int,
        val personal: String,
        val work: String,
        val charge: Boolean = false,
        /** 5h, 7d and scoped fills, in the order the rows are configured. */
        val personalBars: FloatArray = floatArrayOf(0.4f, 0.4f, 0.4f),
        val workBars: FloatArray = floatArrayOf(0.4f, 0.4f, 0.4f),
        /** What each row says in its right-hand column — the time to its reset. */
        val personalTimes: Array<String> = arrayOf("4h03m", "6d13h", "6d13h"),
        val workTimes: Array<String> = arrayOf("3h21m", "2d08h", "2d08h"),
        /**
         * An account that is not answering.
         *
         * Worth a segment of its own, because it is the one state where the panel stops
         * using an account's own colour: everything that was the accent goes to DIM and
         * AUTH puts SIGN IN where the plan bracket was. A film that never shows it makes
         * the panel look like it only has good news.
         */
        val personalHealth: Health = Health.OK,
        val workHealth: Health = Health.OK,
        /** The track, so the demo can be seen changing one. Blank leaves it alone. */
        val track: String = "",
        val artist: String = "",
        val playing: Boolean = true,
        /** Where the volume arc sits, so it can be seen being moved. */
        val volume: Float = -1f,
    )

    /** [status] as the demo wants it seen, or unchanged when the demo is off. */
    private fun demoStatus(status: AccountStatus): AccountStatus {
        val step = demoNow() ?: return status
        val health =
            if (status.account == Account.WORK) step.workHealth else step.personalHealth
        return if (health == status.health) status else status.copy(health = health)
    }

    /** Held, not rebuilt: see demoWeather. */
    private var demoWeatherReading: dev.kb896.aiusage.data.WeatherReading? = null
    private val demoWeatherClock = java.util.Calendar.getInstance()

    /**
     * Demo mode's weather: a fixed light rain for "Somewhere", day or night by the clock,
     * so the line and the rain appear in the filmed loop without a network.
     *
     * HELD RATHER THAN MADE. Every frame asks for this — the line on one page, and the
     * glass on all of them — so a reading built each time is garbage at the display's
     * full rate for a fact that changes twice a day. Rebuilt when the sky flips between
     * day and night, and when the one being held has aged out, because Auto refuses a
     * reading older than two hours and a filmed loop can run longer than that.
     */
    private fun demoWeather(): dev.kb896.aiusage.data.WeatherReading? {
        demoNow() ?: return null
        val now = System.currentTimeMillis()
        demoWeatherClock.timeInMillis = now
        val day = demoWeatherClock.get(java.util.Calendar.HOUR_OF_DAY) in 7..19
        val held = demoWeatherReading
        if (held != null && held.isDay == day && held.isFresh(now)) return held
        return dev.kb896.aiusage.data.WeatherReading(
            12f, 61, isDay = day, place = "Somewhere", fetchedAt = now,
        ).also { demoWeatherReading = it }
    }

    /** The reset column's text for this row, or null when the demo is off. */
    private fun demoTime(account: Account, row: Int): String? {
        val step = demoNow() ?: return null
        val times = if (account == Account.WORK) step.workTimes else step.personalTimes
        return times.getOrNull(row)
    }

    /** Where the volume arc should sit, or null to read the phone's own. */
    private fun demoVolume(): Float? {
        val step = demoNow() ?: return null
        return if (step.volume < 0f) null else step.volume
    }

    /** The fill this row should show, or null when the demo is off. */
    private fun demoBar(account: Account, row: Int): Float? {
        val step = demoNow() ?: return null
        val bars = if (account == Account.WORK) step.workBars else step.personalBars
        return bars.getOrNull(row)
    }

    /**
     * The ten notifications the demo shows instead of the real ones.
     *
     * NOT SOMEBODY'S ACTUAL MESSAGES. The whole point of the notification page is that it
     * holds what has just come in, and the whole point of the demo is that it gets filmed
     * — so the honest default is the one that does not publish a stranger's WhatsApp to
     * whoever watches the video. Written rather than borrowed, and dull on purpose.
     */
    /**
     * REAL ICONS, WITHOUT IMPORTING ANY.
     *
     * drawNoticeRow already asks the PackageManager for an app's icon by package name —
     * that is how a real notification gets its badge — so a written notification only
     * needs to claim a package that is actually on the phone and the icon arrives by
     * itself. Nothing to bundle, nothing to keep in step with a rebrand, and nothing that
     * puts somebody else's trademark in this repository.
     *
     * Each entry names a first choice and falls back through the alternatives until one
     * is installed, because no two phones carry the same apps. If none of them are, the
     * row draws without a badge exactly as it does for an app that has been uninstalled
     * since its notification arrived — which is a real case, and already handled.
     */
    private val demoPackages = listOf(
        listOf("com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging"),
        listOf("com.google.android.calendar", "com.android.calendar", "com.miui.calendar"),
        listOf("com.google.android.gm", "com.android.email", "com.microsoft.office.outlook"),
        listOf("com.google.android.apps.photos", "com.miui.gallery", "com.android.gallery3d"),
        listOf("com.google.android.apps.maps", "com.waze"),
        listOf("com.google.android.deskclock", "com.android.deskclock"),
        listOf("com.spotify.music", "com.google.android.apps.youtube.music", "com.miui.player"),
        listOf("com.android.chrome", "com.brave.browser", "org.mozilla.firefox"),
        listOf("com.android.settings"),
        listOf("com.android.vending", "com.xiaomi.market"),
    )

    /** The first of [demoPackages]'s candidates that this phone actually has. */
    private val demoResolved: List<String> by lazy {
        val pm = context.packageManager
        demoPackages.map { candidates ->
            candidates.firstOrNull { pkg ->
                runCatching { pm.getApplicationInfo(pkg, 0) }.isSuccess
            } ?: candidates.first()
        }
    }

    private val demoNotices = listOf(
        Notice("Messages", "com.google.android.apps.messaging", "Sam", "on my way, 10 min"),
        Notice("Calendar", "com.google.android.calendar", "Standup", "in 15 minutes"),
        Notice("Mail", "com.google.android.gm", "Invoice #2231", "has been paid"),
        Notice("Photos", "com.google.android.apps.photos", "3 new memories", "from last August"),
        Notice("Maps", "com.google.android.apps.maps", "Traffic on the A3", "12 min slower than usual"),
        Notice("Weather", "com.google.android.apps.weather", "Rain at 4pm", "18 degrees, light wind"),
        Notice("Podcasts", "com.google.android.apps.podcasts", "New episode", "The Long Now, 42 min"),
        Notice("Bank", "com.google.android.apps.walletnfcrel", "Card payment", "GBP 4.20 at a coffee shop"),
        Notice("Clock", "com.google.android.deskclock", "Alarm set", "for 07:00 tomorrow"),
        Notice("Files", "com.google.android.documentsui", "Backup finished", "1.2 GB copied"),
    )

    /** The history the page should draw: invented while filming, real otherwise. */
    private fun shownHistory(): List<Notice> =
        if (demoNow() != null) demoNotices.mapIndexed { i, n ->
            n.copy(pkg = demoResolved.getOrElse(i) { n.pkg })
        } else history

    private var demoStartedAt = 0L
    private var demoStep = -1

    /** Which segment the loop is in, or null when the demo is off. */
    private fun demoNow(): DemoStep? {
        // BOTH ENDS, not just the switch. The setting is written to a file that survives
        // an upgrade, so a panel that had the demo on in a debug build and was then given
        // a release one would come up inventing its readings with no way to turn it off —
        // the switch that set it is not in that build. Refused here as well, which makes
        // the flag inert rather than merely unreachable.
        if (!BuildConfig.DEBUG || !config.demoMode) return null
        if (demoStartedAt == 0L) demoStartedAt = frameNowMs
        val total = demoSegments.sumOf { it.seconds.toDouble() }.toFloat()
        var t = ((frameNowMs - demoStartedAt) / 1000f) % total
        for (step in demoSegments) {
            if (t < step.seconds) return step
            t -= step.seconds
        }
        return demoSegments.last()
    }

    private fun demoMood(account: Account): String? {
        val step = demoNow() ?: return null
        return if (account == Account.WORK) step.work else step.personal
    }

    /**
     * Turn the page, and light the cell, on the loop's own schedule.
     *
     * Called once a frame from the draw. The page is SETTLED rather than jumped to, so the
     * demo shows the same movement a thumb would produce — the point is to film the
     * transition, not to arrive.
     */
    private fun driveDemo() {
        val step = demoNow() ?: run {
            demoStep = -1
            // Handed straight back, so switching the demo off does not leave the panel
            // showing a track nobody is playing.
            demoOwnsMedia = false
            return
        }
        // A demo that lets the display doze halfway through a take is not a demo.
        animating = true
        keepAwakeFor()
        val index = demoSegments.indexOf(step)
        if (index == demoStep) return
        demoStep = index
        pager.settleTo(step.page, frameNowMs)
        // Fired on ENTERING the segment only, or the cell would restart every frame and
        // never fill.
        if (step.charge) showCharging(DEMO_CHARGE)
        // THE TRACK, pushed through the same door a real one comes in by — so the record
        // crosses and the name rolls over on its wheel exactly as they do for anybody
        // else. A demo that set the fields directly would show the new title already in
        // place, which is the one thing worth filming about a track change.
        if (step.track.isNotEmpty()) {
            demoOwnsMedia = true
            demoPushing = true
            showMedia(
                active = true, title = step.track, artist = step.artist,
                playing = step.playing,
                durationMs = DEMO_TRACK_MS,
                positionMs = (DEMO_TRACK_MS * DEMO_TRACK_AT).toLong(),
                positionAtMs = SystemClock.elapsedRealtime(),
            )
            demoPushing = false
        }
    }

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
        const val AOD_FADE_MS = 500L
        /** the table lights' laps and painting quarter as the glow comes on */
        const val TABLE_INTRO_MS = 3_200L
        /** the battery figure's distance above the clock's figures — see clockBatteryBaseline */
        const val AOD_BATTERY_GAP = 18f
        /** how dark always-on makes everything but the slider */
        const val AOD_VEIL_ALPHA = 0.6f
        /** air either side of the clock inside its undimmed band, and how far under its baseline the band goes */
        const val AOD_BAND_BELOW = 4f
        const val AOD_BAND_AIR = 28f
        /** and the slider's own, lighter one */
        const val AOD_SLIDER_VEIL_ALPHA = 0.15f
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

        /** Room around a ring's band for its feathered edge. See the ring lens. */
        private const val RING_FEATHER = 2f

        /** What is behind the readout, for now. */
        private const val COMING_SOON = "COMING SOON"

        /** Big, because there is nothing else on this screen to be big next to. */
        private const val COMING_SIZE = 58f

        /** How far down the panel it sits — above the mascot's row, not across it. */
        private const val COMING_AT = 0.42f

        /** How much of the disc's radius the controller spans, across its widest. */
        private const val GAMEPAD_SPAN = 1.30f

        /**
         * How much bigger the glass bubble is than the filled disc it replaced.
         *
         * A filled disc ends where its colour ends and needs no more room than the mark
         * inside it. Glass ends at a RIM, and a rim drawn tight around a controller reads
         * as a lens clamped to it rather than as a button it is sitting on.
         */
        private const val GAMEPAD_DISC = 1.42f

        /**
         * How much room the speaker's bubble keeps around its contents.
         *
         * Between the two attempts either side of it: centred on the glyph it was far too
         * generous behind the cone, and sized to exactly enclose the contents it was too
         * tight all round. This is the middle, and it is air rather than waste because the
         * bubble is centred on what it holds now.
         */
        private const val SPEAKER_BUBBLE_ROOM = 1.40f

        /** Air between the outermost wave and the glass holding it. */
        private const val SPEAKER_WAVE_MARGIN = 0.12f


        /**
         * How long the record takes to swing from one side of its shake to the other.
         *
         * Half a second, which is slow enough to read as weight rather than as vibration.
         * At a fifth of a second the disc buzzed: the eye tracks the motion instead of the
         * record, and a turntable's play is a slow lean, not a shiver.
         */
        private const val VINYL_WOBBLE_MS = 500f

        /**
         * How far it goes, per step. See vinylWobble.
         *
         * A third of a degree at most, which is far less than it sounds like it should be
         * and took three goes to believe. Five degrees read as a record being shaken; one
         * still read as a record being nudged. At this diameter even a third of a degree
         * moves the rim by a couple of units, and that is the whole budget — a deck's play
         * is the kind of thing you only notice once it stops.
         */
        private const val VINYL_WOBBLE_MIN = 0.12f
        private const val VINYL_WOBBLE_MAX = 0.34f

        /** How many distinct pane shapes to keep lenses for. See lensFor. */
        private const val LENS_CACHE_MAX = 96


        /** The overlay groups, in the order they stack over the panel. See drawOverlays. */
        private const val GROUP_RINGS = 4
        private const val GROUP_OVER = 5
        private const val GROUP_BUTTONS = 0
        private const val GROUP_LOCK = 1
        private const val GROUP_NOTICE = 2
        private const val GROUP_CHARGE = 3
        private const val GROUP_TIP = 6

        /** The camera tip: the wait before its slider comes, unskippable. */
        private const val CAMERA_TIP_MS = 5_000L
        /** Nobody swiped in this long after it appeared: the tip gives up, the camera stays shut. */
        private const val CAMERA_TIP_GIVE_UP_MS = 90_000L
        private const val CAMERA_TIP_FADE_MS = 260f
        private const val CAMERA_TIP_LABEL = "I UNDERSTAND"
        private const val CAMERA_TIP_TITLE = 32f
        private const val CAMERA_TIP_BODY = 23f
        private const val CAMERA_TIP_TITLE_GAP = 22f
        private const val CAMERA_TIP_GAP = 28f
        private const val CAMERA_TIP_HEADING = "CLOSING THE CAMERA"
        private val CAMERA_TIP_LINES = listOf(
            "No close button on the back.",
            "Tap the flashing area on",
            "the left of this screen,",
            "grey in the camera, and",
            "the camera closes.",
        )
        /** The tap's breath — one slow swell and fade — and how far its glow reaches. */
        private const val CAMERA_TIP_BREATH_MS = 2_400L
        private const val CAMERA_TIP_GLOW = 54f
        /** The tap, in the accent the slider reveals. */
        private const val CAMERA_TIP_TAP = 0xFF0091FF.toInt()

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

        private const val CLOSE_R = 19f
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
        /** on the clock page with no readout: the column in the rings' bay the camera sits on, and the controller under it */
        private const val RING_BAY_X = 290f
        private const val CORNER_BUTTON_GAP = 16f




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
        private fun mediaRight(theme: dev.kb896.aiusage.data.PanelTheme): Float {
            val bottom = (Panel.HEIGHT - MARK_BOTTOM - MARK_H / 2f) + CAMERA_H / 2f
            val right = Panel.WIDTH - (Panel.HEIGHT - bottom)
            return right - CAMERA_H * Glyphs.aspect(PanelIcons.CAMERA, theme) - MEDIA_CAMERA_GAP
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

        /** A discharge's brightest point, and the halo around it. */
        private const val SURGE_CORE = 240f
        private const val SURGE_HALO = 90f

        /** How far the core is pushed toward white. */
        private const val SURGE_WHITEN = 0.72f
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
         * The lock's wording and type.
         *
         * Short enough to fit beside the knob at rest, and an instruction rather than a
         * state — "LOCKED" would say what is wrong where this says what to do, and the
         * panel already looks locked.
         */
        private const val LOCK_LABEL = "SWIPE TO UNLOCK"
        private const val LOCK_TEXT = 19f

        /** Dim. It is a hint under a knob, not a heading. */
        private const val LOCK_TEXT_ALPHA = 150f

        /** The chevron on the knob: how tall, and how heavy. */
        private const val LOCK_CHEV = 20f
        private const val LOCK_CHEV_W = 3f

        /**
         * How far above and below the slider's band a touch still counts.
         *
         * Wide, because it can be: while the panel is locked the slider is the only
         * target on it, so there is nothing for a generous band to take a press from.
         * The X axis carries the whole gesture and is not loosened.
         */
        private const val LOCK_BAND_SLOP = 60f

        /**
         * The colour the instruction is revealed in under the indicator.
         *
         * Blue for now, to see the mechanism working. It is the reference's own dark
         * accent, which is as good a placeholder as any and will be a setting if it
         * stays.
         */
        private const val LOCK_REVEAL = 0xFF0091FF.toInt()

        /** Slack around a faded corner glyph, covering its margin and the drift. */
        private const val FADE_PAD = 60f


        /**
         * The least time a lock may last before it can put the display out.
         *
         * Covers the gap between the lock being armed and the notification it was armed
         * for arriving — see the check in onDraw. Comfortably shorter than the shortest
         * thing it can be waiting on (a notice holds for three seconds, the charging cell
         * for five), so it never cuts anything short.
         */
        private const val LOCK_MIN_MS = 1_500L

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
        private const val KEY_HISTORY = "notices"
        /** The Info page's notifications, put away by a swipe to the right. */
        private const val KEY_NOTICES_STASHED = "notices_stashed"
        private const val FIELD_SEP = "\u0001"
        private const val RECORD_SEP = "\u0002"

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


        /** The lip at the edge of the disc, the brightest line on it. */
        private const val VINYL_RIM_W = 1.6f


        /** The cover on the label, held back so the buttons over it stay findable. */
        /** How far the empty part of the battery is held back. See the battery block. */
        private const val BATTERY_SHELL_ALPHA = 0x59

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

        // -- the glass notification ---------------------------------------------------


        /** The air between the headline and the group card below it. */
        private const val GLASS_NOTICE_GAP = 8f

        /** How much narrower the group card is, each side. Subordinate, not second. */
        private const val GLASS_MINI_INSET = 22f

        /** And how much shorter, so the two are not the same card twice. */
        private const val GLASS_MINI_SHRINK = 14f

        /** Rounded like a pane rather than like the display. */
        private const val GLASS_NOTICE_RADIUS = 22f

        /**
         * How opaque a glass card's ground is.
         *
         * Enough to read type against, little enough that the refraction underneath still
         * carries — an opaque pane is a card, and the whole point is that it is glass.
         */
        private const val GLASS_NOTICE_ALPHA = 0xB0

        /**
         * A target disc's radius, as a multiple of the glyph's height.
         *
         * Above a half, so there is glass visible around the mark rather than a rim
         * touching it. Below one, or three transport discs on 35-unit centres merge into
         * a single lozenge and stop reading as three things.
         */
        private const val DISC_RADIUS = 0.72f

        /** How tall one reading's row is. Shared with the mascots, which stand on it. */
        private const val BAR_ROW_H = 20f

        /**
         * How far an account's card reaches past the content inside it.
         *
         * Wider than it is tall, because the block already has air above and below it —
         * the gap that used to be the only thing separating two accounts — and none at
         * all at the sides: the rows run the full width of the content column.
         */
        private const val CARD_PAD_X = 14f
        private const val CARD_PAD_TOP = 10f
        private const val CARD_PAD_BOTTOM = 8f

        /** Its corner. The notification cards' own, so one panel has one radius. */
        private const val CARD_RADIUS = 18f

        /** Air between two cards that would otherwise touch. */
        private const val CARD_GAP = 8f

        /** How much further down the accounts start, to give the masthead room. */
        private const val BLOCKS_DROP = 6f

        // -- the glass bars, built as sliders -------------------------------------------
        //
        // Proportions taken from the reference component rather than invented: its track
        // is 6dp and its thumb 40x24, so a thumb is four times the track's height and
        // half again as wide as it is tall. Expressed against the row's height, which is
        // the only fixed thing here.

        /** How much of the row the track takes. The rest is the thumb standing proud. */
        private const val SLIDER_TRACK = 0.3f

        /** The thumb, as multiples of the row's height. */
        private const val SLIDER_THUMB_H = 1.05f
        private const val SLIDER_THUMB_W = 1.6f

        /**
         * The ring's thumb, as a multiple of the ring's own stroke.
         *
         * Above one, so it stands proud of the track the way the bar's does. The ring is
         * drawn around a camera lens and its stroke is 25 units, so this is a disc of
         * about 35 — a reading you can find at a glance from across a room.
         */
        private const val RING_THUMB = 1.4f

        /** How present the ring's unfilled track is. Muted, like the reference's. */
        private const val RING_TRACK_ALPHA = 0.36f

        /**
         * The play bar's thumb, as a multiple of its track height.
         *
         * Much larger than the bars' multiple, because the track it rides is much
         * thinner — four units against twenty. The thumb is what makes a four-unit line
         * into a control you can read a position off.
         */
        private const val MEDIA_THUMB = 4.5f

        private const val EXTRA_ICON_H = 20f
        /** the row's text: the extras paint's own size, which nothing else may leave behind */
        private const val EXTRA_TEXT_SIZE = 18f

        /** Line pitch when both extras are shown. */
        /** Short weekday names, Sunday first — Calendar.DAY_OF_WEEK is 1-based there. */
        private val DAYS = arrayOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")

        private const val EXTRA_LINE_H = 26f

        /** How far the corner block sits above where it used to. */
        private const val EXTRA_LIFT = 6f

        /** Air inside its card. */
        private const val EXTRA_PAD_X = 12f
        /** the air under the extras row in the bottom-right corner: the same as at its right */
        private const val CORNER_MARGIN = Panel.WIDTH - CONTENT_RIGHT - EXTRA_PAD_X
        private const val EXTRA_PAD_Y = 8f

        /** Between an icon and the text it introduces. */
        private const val EXTRA_ICON_GAP = 8f

        /** How many rows the corner block always reserves. See extrasCard. */
        private const val EXTRA_ROWS = 3

        /** Breathing room above and below a single-account preview, replacing the
         *  masthead and the maker's mark it does not draw. */
        private const val SINGLE_ACCOUNT_TOP = 24f

        private const val TITLE_BASELINE = 54f

        /** Half a cap height, near enough, for centring type on its ink. */
        private const val TITLE_OPTICAL = 0.36f

        /** Air inside the masthead's card. */
        private const val TITLE_PAD_X = 18f
        private const val TITLE_PAD_Y = 9f

        /** And inside the one behind a track's name. */
        private const val TRACK_PAD_X = 14f
        private const val TRACK_PAD_Y = 8f

        /** And inside the readout row's smaller one. */
        private const val ROW_CARD_PAD_X = 10f
        private const val ROW_CARD_PAD_Y = 6f
        private const val ROW_CARD_RADIUS = 12f

        /**
         * How long a name's card takes to spring to its new width.
         *
         * Short. It is not the movement anybody is meant to watch — it is the box getting
         * out of the way so the name can turn over inside it, and the roll is the part
         * with something to say.
         */
        private const val CARD_GROW_MS = 170f
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


        /**
         * How often the heartbeat looks up while the panel is dark.
         *
         * Not zero, because the runnable has to be somewhere to notice the panel is back
         * if every other signal failed; long, because that is the only thing it does. It
         * draws nothing at this interval — see redrawTick.
         */
        private const val DARK_TICK_MS = 60_000L

        /** How far back the notification page can be scrolled. */
        const val HISTORY_MAX = 10

        /** Where the readout sits in the pager now that something is above it. */
        const val PAGE_READOUT = 1
        const val PAGE_NOTICES = 0
        const val PAGE_MEDIA = 2

        /**
         * How much of the panel the record still occupies from the notification page.
         *
         * A slice along the bottom, and no more. Enough that the page reads as sitting
         * above something rather than floating on nothing; little enough that the clock
         * and the date have plain background under them.
         */
        private const val VINYL_PEEK = 0.15f

        /** How long the demo takes to walk the whole wheel once. */
        private const val DEMO_WHEEL_PERIOD = 6f

        /** Long enough that the playback ring is visibly part-way round, not near an end. */
        private const val DEMO_TRACK_MS = 254_000L
        private const val DEMO_TRACK_AT = 0.38f

        /** A charge worth filming: past the halfway mark, so the cell is visibly full. */
        private const val DEMO_CHARGE = 68

        /**
         * How far either side of the current card the carousel still draws.
         *
         * Comfortably past one, and that is the point: everything between one and here is
         * a card on its way out, and it needs somewhere to fade THROUGH. At 1.15 there was
         * barely a tenth of a card's travel to do it in, so it read as vanishing.
         */
        private const val CAROUSEL_REACH = 1.6f

        /** How much of a card's height its neighbour is offset by — half, behind it. */
        private const val CAROUSEL_STEP = 0.5f

        /** How much smaller a neighbour is, and how much of it has gone. */
        /** How far a neighbour's left edge is pulled toward the right, under ORIGINAL. */
        private const val ORIGINAL_SLIDE = 150f

        private const val CAROUSEL_SHRINK = 0.12f
        private const val CAROUSEL_FADE = 0.55f

        /** The notification page's clock: big, because the page is otherwise empty. */
        private const val CLOCK_SIZE = 92f
        private const val CLOCK_BASELINE = 330f
        private const val CLOCK_PAD_X = 20f
        private const val CLOCK_PAD_Y = 16f
        /**
         * How tall a numeral's cell is, as a multiple of its size — and the roll's travel.
         *
         * Both at once, and that is what sets the number. The cell has to be taller than
         * the FIELD or it cuts the figure's top and bottom off; a field is ascent to
         * descent plus a spread margin at each end, which for this face comes to about
         * 1.45 times the size. And the travel has to equal the cell exactly, or a figure
         * rolling out is still half in view when it stops.
         */
        private const val CLOCK_ROLL_CELL = 1.7f
        private const val CLOCK_ROLL_MS = 260f
        private const val DATE_SIZE = 26f
        private const val DATE_BASELINE = 372f // above the slider's band, so the weather under it stays

        /** How many measured glyphs to keep. Eleven for a clock, a hundred for a charge. */
        private const val SDF_CACHE_MAX = 160

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
