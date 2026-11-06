package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import dev.kb896.aiusage.data.PanelTheme

/**
 * A notification's card, drawn as the panel drew its own strip (RearDialView.drawNoticeRow,
 * drawGlassNotice, drawNotice): the app's name small and dim, the title, the text, the app's
 * icon on the right, in the notification's colour; under GLASS a pane of the notification's
 * ground at the panel's alpha, under ORIGINAL that ground flat. In panel units, [rect] one
 * card's height.
 */
class NoticeCard(
/** The panel's face; nothing is drawn before there is one. */
private val face: () -> PanelType.Face?,
/** The display's own corner, in panel units: a card up against the top follows it. */
private val corner: () -> Float,
/** An app's icon by package, or null for none. */
private val icon: (String) -> Bitmap?,
) {
    /**
     * The panel's look for a notification: the theme, the text's colour, and the card's body. Under
     * ORIGINAL that is the notification's ground; under GLASS there is no ground to choose (the
     * settings offer none), and the card is clear glass in Glass settings' tint, as every other pane
     * (SheetTint.of).
     */
    class Look(val theme: PanelTheme, val text: Int, val ground: Int, val glassTint: Int)

    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val heading = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val ground = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dest = RectF()
    private val shape = android.graphics.Path()
    /**
     * The face the paints were set to. ONLY WHEN IT CHANGES: PanelType's set clears the weight
     * and puts it back, and a paint then builds its font again — twice a row, every frame, it
     * was what held the list well under the display's rate while it moved.
     */
    private var faced: PanelType.Face? = null
    /** Lines already cut to their room, by text, paint and width: cut once, not every frame. */
    private val fitted = HashMap<String, String>()
    private var sheen: android.graphics.Shader? = null
    private var edge: android.graphics.Shader? = null
    private var gradientFor = 0L
    private val gradientShift = android.graphics.Matrix()

    private fun line(text: String, paint: TextPaint, room: Float, which: Char): String {
        val key = "$which${room.toInt()}|$text"
        fitted[key]?.let { return it }
        if (fitted.size > 256) fitted.clear()
        return fit(text, paint, room).also { fitted[key] = it }
    }

    /**
     * [square]: the corners that run off the display stay square, as the panel's strip's did.
     * [screenTop]: where the card's top is on the rear, in panel units, for its top-right corner.
     */
    /** [body]: how much of the message line shows, 0 to 1, for it fading in or out. */
    fun draw(canvas: Canvas, rect: RectF, item: NoticeShade.Item, alpha: Float, square: Boolean, screenTop: Float, look: Look, body: Float = 1f) {
        val l = look
        val f = face() ?: return
        if (f !== faced) {
            f.set(label, PanelType.BODY)
            f.set(heading, PanelType.BODY)
            label.textSize = 18f * f.scale
            heading.textSize = 22.5f * f.scale
            fitted.clear()
            faced = f
        }
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        val r = RADIUS
        shape.rewind()
        // Up against the top, the top-right corner follows the display's own, at the card's inset
        // (GlassCard.innerRadius, as the panel's strip's did), and turns back into the card's own
        // corner as the card moves down from there.
        val fitted = GlassCard.innerRadius(corner(), MARGIN, r)
        val away = ((screenTop - MARGIN) / CORNER_EASE).coerceIn(0f, 1f)
        val tr = if (square) 0f else fitted + (r - fitted) * away
        if (square) shape.addRoundRect(rect, floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, r, r), android.graphics.Path.Direction.CW)
        else shape.addRoundRect(rect, floatArrayOf(r, r, tr, tr, r, r, r, r), android.graphics.Path.Direction.CW)
        if (l.theme.isGlass) {
            pane(canvas, shape, rect, alpha, l)
        } else {
            ground.color = l.ground or (0xFF shl 24)
            ground.alpha = a
            canvas.drawPath(shape, ground)
        }
        val h = rect.height()
        val size = h * 0.56f
        val iconLeft = rect.right - (if (square) 48f else 34f) - size
        icon(item.pkg)?.let { icon ->
            dest.set(iconLeft, rect.top + (h - size) / 2f, iconLeft + size, rect.top + (h + size) / 2f)
            iconPaint.alpha = a
            canvas.drawBitmap(icon, null, dest, iconPaint)
        }
        val left = rect.left + 26f
        val room = iconLeft - 26f - left
        label.color = l.text
        heading.color = l.text
        label.alpha = (a * 0.6f).toInt()
        canvas.drawText(line(item.app.uppercase(), label, room, 'a'), left, rect.top + h * 0.30f, label)
        heading.alpha = a
        canvas.drawText(line(item.title.ifBlank { item.app }, heading, room, 't'), left, rect.top + h * 0.56f, heading)
        if (item.text.isNotBlank() && body > 0f) {
            label.alpha = (a * body).toInt()
            canvas.drawText(line(item.text, label, room, 'b'), left, rect.top + h * 0.80f, label)
        }
    }

    /**
     * GLASS WITHOUT SHADERS: the panel's lens on every card, every frame, held the list well
     * under the display's rate. A body in Glass settings' tint, one tint whatever is behind; a
     * sheen falling from the top left; an edge lit along the top, as the panel's rim is.
     * [shape] within [rect].
     */
    private fun pane(canvas: Canvas, shape: android.graphics.Path, rect: RectF, alpha: Float, l: Look) {
        val tint = l.glassTint
        ground.shader = null
        ground.style = Paint.Style.FILL
        ground.color = tint
        ground.alpha = (Color.alpha(tint) * alpha).toInt()
        canvas.drawPath(shape, ground)
        // Made once for the shape's size, at its origin, and moved to each.
        val size = (rect.width().toLong() shl 32) or rect.height().toLong()
        if (size != gradientFor || sheen == null) {
            gradientFor = size
            sheen = android.graphics.LinearGradient(
                0f, 0f, rect.width() * 0.45f, rect.height(),
                (SHEEN_ALPHA shl 24) or 0xFFFFFF, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP,
            )
            edge = android.graphics.LinearGradient(
                0f, 0f, 0f, rect.height(),
                (EDGE_TOP_ALPHA shl 24) or 0xFFFFFF, (EDGE_BOTTOM_ALPHA shl 24) or 0xFFFFFF,
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        gradientShift.setTranslate(rect.left, rect.top)
        sheen!!.setLocalMatrix(gradientShift)
        edge!!.setLocalMatrix(gradientShift)
        ground.color = 0xFFFFFFFF.toInt()
        ground.alpha = (255 * alpha).toInt()
        ground.shader = sheen
        canvas.drawPath(shape, ground)
        ground.shader = edge
        ground.style = Paint.Style.STROKE
        ground.strokeWidth = EDGE_WIDTH
        canvas.drawPath(shape, ground)
        ground.shader = null
        ground.style = Paint.Style.FILL
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var glow: android.graphics.Shader? = null
    private var light: android.graphics.Shader? = null
    private var glowFor = 0f
    private val glowShift = android.graphics.Matrix()

    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3f
    }

    /**
     * The clear button: a circle of the cards' own glass (or, under ORIGINAL, their ground) with
     * an X in the text's colour, no words. Its own card-sized shaders would be made again for a
     * circle every frame, so it keeps a NoticeCard of its own.
     */
    fun drawClear(canvas: Canvas, c: NoticeShade.Clear, look: Look) {
        val r = c.size / 2f
        // A soft bright halo round it, fading out, so it reads over the rows and the blur alike.
        val glowR = r + CLEAR_GLOW
        if (glowFor != c.size) {
            glowFor = c.size
            glow = android.graphics.RadialGradient(
                0f, 0f, glowR,
                intArrayOf((CLEAR_GLOW_ALPHA shl 24) or 0xFFFFFF, (CLEAR_GLOW_ALPHA shl 24) or 0xFFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, r / glowR, 1f), android.graphics.Shader.TileMode.CLAMP,
            )
            light = android.graphics.RadialGradient(
                0f, -r * 0.45f, r * 1.3f,
                (CLEAR_LIGHT_ALPHA shl 24) or 0xFFFFFF, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP,
            )
        }
        glowShift.setTranslate(c.cx, c.cy)
        glow!!.setLocalMatrix(glowShift)
        light!!.setLocalMatrix(glowShift)
        glowPaint.shader = glow
        glowPaint.alpha = (255 * c.alpha).toInt()
        canvas.drawCircle(c.cx, c.cy, glowR, glowPaint)
        dest.set(c.cx - r, c.cy - r, c.cx + r, c.cy + r)
        shape.rewind()
        shape.addOval(dest, android.graphics.Path.Direction.CW)
        if (look.theme.isGlass) pane(canvas, shape, dest, c.alpha, look)
        else {
            ground.shader = null
            ground.style = Paint.Style.FILL
            ground.color = look.ground or (0xFF shl 24)
            ground.alpha = (255 * c.alpha).toInt()
            canvas.drawPath(shape, ground)
        }
        // Lit from within, brightest under its top and fading down.
        glowPaint.shader = light
        canvas.drawPath(shape, glowPaint)
        glowPaint.shader = null
        val arm = r * 0.32f
        crossPaint.color = look.text
        crossPaint.alpha = (255 * c.alpha).toInt()
        canvas.drawLine(c.cx - arm, c.cy - arm, c.cx + arm, c.cy + arm, crossPaint)
        canvas.drawLine(c.cx - arm, c.cy + arm, c.cx + arm, c.cy - arm, crossPaint)
    }

    /** One line, cut to [width]. */
    private fun fit(text: String, paint: TextPaint, width: Float): String =
        TextUtils.ellipsize(text.replace('\n', ' '), paint, width, TextUtils.TruncateAt.END).toString()

    companion object {
        /** RearDialView's GLASS_NOTICE_RADIUS and GLASS_NOTICE_ALPHA, GlassCard.MIN: the panel's strip's. */
        const val RADIUS = 22f
        const val GLASS_ALPHA = 0xB0
        const val MARGIN = 14f
        /** The panel's own notification's height (RearDialView.NOTICE_H): a quarter of it. */
        const val HEIGHT = NoticeShade.ROW_H
        /** The made-up glass: its sheen at the top left, its edge lit at the top and fading down. */
        private const val SHEEN_ALPHA = 0x2E
        private const val EDGE_TOP_ALPHA = 0x66
        private const val EDGE_BOTTOM_ALPHA = 0x14
        private const val EDGE_WIDTH = 1.5f
        /** The clear button's halo: how far past its edge, and how bright at it; its light within. */
        private const val CLEAR_GLOW = 18f
        private const val CLEAR_GLOW_ALPHA = 0x40
        private const val CLEAR_LIGHT_ALPHA = 0x55
        /** Over how far down from the top a card's top-right corner turns from the display's into its own. */
        private const val CORNER_EASE = 60f
    }
}
