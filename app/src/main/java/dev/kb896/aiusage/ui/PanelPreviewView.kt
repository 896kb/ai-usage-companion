package dev.kb896.aiusage.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.data.DashboardState
import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.rear.RearDialView

/**
 * What the back of the phone is showing, on the front of it.
 *
 * Holds a REAL [RearDialView] at its true 904x572 and scales it down, rather than
 * reimplementing the panel at preview size. Two things follow from that, and both are
 * the reason it is built this way:
 *
 *  * It cannot drift. A second implementation would be a second thing to keep in step,
 *    and the whole point of a preview is to be trusted — one that lies is worse than
 *    none, because a setting that looks wrong here would send you hunting for a bug on
 *    the panel that does not exist.
 *  * It animates. Bars ease, rings sweep, mascots walk, the burn-in drift drifts. This
 *    is the panel, just smaller.
 *
 * Scaling with a view transform rather than by drawing into a bitmap keeps it live and
 * costs nothing extra: the child was going to draw anyway.
 */
class PanelPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val dial = RearDialView(context)

    var state: DashboardState
        get() = dial.state
        set(value) { dial.state = value }

    var config: PanelConfig
        get() = dial.config
        set(value) { dial.config = value }
    /** The weather reading the back has; without it the preview had no weather line at all. */
    var weather: dev.kb896.aiusage.data.WeatherReading?
        get() = dial.weather
        set(value) { dial.weather = value; dial.invalidate() }

    /**
     * Mirror a notification onto the preview, exactly as the panel receives it.
     *
     * The preview and the panel are the same view class drawing the same config, but
     * they live in different processes and only `:rear` was listening — so the preview
     * showed the readout and none of the things that arrive by broadcast. Somebody
     * setting up notification mirroring or the media row on the front screen was
     * configuring features they could not see.
     */
    fun showNotification(
        app: String,
        pkg: String,
        title: String,
        text: String,
        moreTitle: String,
        moreText: String,
    ) = dial.showNotification(app, pkg, title, text, moreTitle, moreText)

    fun showMedia(
        active: Boolean,
        title: String,
        artist: String,
        playing: Boolean,
        artPath: String,
        durationMs: Long,
        positionMs: Long,
        positionAtMs: Long,
        speed: Float,
        lyricsLrc: String = "",
        lyricsFor: String = "",
    ) = dial.showMedia(
        active, title, artist, playing, artPath,
        durationMs, positionMs, positionAtMs, speed, lyricsLrc, lyricsFor,
    )

    /** The back's corner radius in panel pixels, until the back says otherwise. */
    private var cornerPx = DEFAULT_CORNER
    private var dimming = false
    private var last: RearDialView.Mirror? = null

    private val veil = android.graphics.Paint()

    init {
        addView(dial, LayoutParams(Panel.WIDTH, Panel.HEIGHT))
        clipChildren = true
        dial.interactive = false
        // The slider has its own timeout; the back re-locks it under always-on, so the
        // preview does the same from the last mirror rather than letting it leave alone.
        dial.onLockExpired = { last?.let { if (it.locked) dial.lock() } }
        // The real display's rounded corners, so the preview is the shape of the back.
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerPx * view.width / Panel.WIDTH)
            }
        }
    }

    /** The back's state: page, always-on, slider, rings, table lights, corner — and its dim. */
    fun mirror(m: RearDialView.Mirror, dim: Boolean) {
        last = m
        if (cornerPx != m.corner) { cornerPx = m.corner; invalidateOutline() }
        dial.mirror(shown(m))
        if (dimming != dim) { dimming = dim; invalidate() }
    }

    /**
     * What the settings' bottom bar has chosen to look at: a page of the panel, or
     * always-on — shown here over whatever the back is doing, so each tab previews the
     * screen its settings are for. The rest of the back's state (rings, table lights,
     * the corner) is still mirrored. Null and false: the back as it is.
     */
    private var pageShown: Int? = null
    private var aodShown = false

    fun show(page: Int?, aod: Boolean) {
        pageShown = page
        aodShown = aod
        val m = last ?: RearDialView.Mirror(page ?: RearDialView.PAGE_READOUT, false, false, false, false, cornerPx)
        dial.mirror(shown(m))
    }

    private fun shown(m: RearDialView.Mirror): RearDialView.Mirror = when {
        aodShown -> m.copy(aod = true, locked = true)
        pageShown != null -> m.copy(page = pageShown!!, aod = false, locked = false)
        else -> m
    }

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        super.dispatchDraw(canvas)
        // The double tap's dim is the window's brightness and a veil on the back; here
        // only a veil can stand for both.
        if (dimming) {
            veil.color = (255 * DIM_VEIL).toInt() shl 24
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), veil)
        }
    }

    private companion object {
        /** The 17 Pro's rear display corner, in its own pixels, until measured. */
        const val DEFAULT_CORNER = 97f
        const val DIM_VEIL = 0.7f
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        val height = (width * Panel.HEIGHT / Panel.WIDTH.toFloat()).toInt()
        setMeasuredDimension(width, height)
        dial.measure(
            MeasureSpec.makeMeasureSpec(Panel.WIDTH, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(Panel.HEIGHT, MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        dial.layout(0, 0, Panel.WIDTH, Panel.HEIGHT)
        // Pivot at the top-left so the scaled child lands exactly on this view's origin;
        // the default pivot is the centre, which would leave it offset by half the
        // difference between the panel's size and the preview's.
        val scale = width / Panel.WIDTH.toFloat()
        dial.pivotX = 0f
        dial.pivotY = 0f
        dial.scaleX = scale
        dial.scaleY = scale
    }
}
