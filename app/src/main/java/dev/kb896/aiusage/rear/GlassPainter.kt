package dev.kb896.aiusage.rear

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RecordingCanvas
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode

/**
 * Panes of glass, drawn into any canvas that can hold render nodes — the panel's view, or
 * a surface's hardware canvas in the shell, which has no View, no Activity and no
 * Resources of ours. Everything a pane needs is handed in: where it is, its tint, and
 * the node it bends. Moved out of RearDialView so the portal sheet can be drawn by both.
 *
 * One per drawing surface: it holds that surface's node pool and lens cache. Call
 * [beginFrame] at the start of every frame.
 *
 * Not thread-safe; all painters in a process must draw one at a time (ShellSheet
 * serialises with its DRAW lock) — they share Glass's shaders (uniforms, highlight matrix).
 */
class GlassPainter {

    /** The root canvas's size this frame: a source that spans it from its origin is root-space. */
    private var rootWidth = 0
    private var rootHeight = 0

    /** A new frame on a root canvas [width] by [height]: every node in the pool is free again. */
    fun beginFrame(width: Int, height: Int) {
        nodesUsed = 0
        rootWidth = width
        rootHeight = height
    }

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
    private val nodePool = ArrayList<RenderNode>(16)
    private var nodesUsed = 0

    fun nextNode(): RenderNode {
        if (nodesUsed == nodePool.size) {
            nodePool.add(RenderNode("glass-${nodePool.size}"))
        }
        return nodePool[nodesUsed++]
    }

    /** A pane's rect in root coordinates, for sampling a root-space backdrop. */
    private val paneAbs = RectF()

    /** Whether [node] covers the whole root canvas from its origin, and so is in root space. */
    fun rootSpace(node: RenderNode): Boolean =
        node.left == 0 && node.top == 0 &&
            node.width == rootWidth && node.height == rootHeight

    /**
     * Draw a rounded rectangle as a piece of glass over [source].
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
    fun drawPane(
        canvas: Canvas,
        rect: RectF,
        radius: Float,
        /** Its body, as it is to be drawn: the caller has already chosen it. */
        tint: Int = Glass.TINT,
        /**
         * The shape to fill, when it is not the rounded rectangle itself.
         *
         * For a ring or a partly-filled bar, [rect] is still what the lens is computed
         * against — it is the pane's extent — but only part of it is glass. A ring's
         * annulus cannot be expressed as a rounded rect, and giving one its bounding box
         * would put a pane of glass across both camera lenses.
         */
        shape: Path? = null,
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
         * What the pane bends: the whole panel, or something of its own. Null — or a node
         * with nothing recorded in it — when there is nothing it may sample.
         *
         * THE REFERENCE SLIDER DOES EXACTLY THIS. Its track is published as its own
         * backdrop layer and the thumb's backdrop is that layer combined with the page
         * behind — because a thumb is not lensing the wallpaper, it is lensing the track
         * it is riding. Which also solves the ordering problem here: a bar is drawn inside
         * the scene recording, so it can never sample the scene, but it can perfectly well
         * sample a node holding its own track.
         */
        source: RenderNode?,
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
        /**
         * [source] is in the canvas's current space, as [rect] is, and is never mapped to
         * root space even when it spans the root canvas from its origin (see [rootSpace]).
         * For a backdrop drawn in the same space as the panes over it — the portal sheet's.
         */
        local: Boolean = false,
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
        //
        // No source, or one with nothing recorded in it, is the same case: nothing to bend.
        val recorder = canvas as? RecordingCanvas
        if (recorder == null || source == null || !source.hasDisplayList()) {
            return drawUnbacked(canvas, rect, radius, tint, shape, rim)
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
            if (!local && rootSpace(source)) {
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
            paneClip.addRoundRect(rect, radius, radius, Path.Direction.CW)
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
    private fun drawUnbacked(
        canvas: Canvas,
        rect: RectF,
        radius: Float,
        tint: Int,
        shape: Path?,
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
    ): RenderEffect {
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

        val lens = RenderEffect.createRuntimeShaderEffect(shader, "content")
        // A CEILING, and a blunt one. The panel has a few dozen distinct pane shapes in
        // normal use; anything past that is a control mid-animation minting shapes it
        // will not ask for again, and holding those is the same mistake in slow motion.
        if (lensCache.size >= LENS_CACHE_MAX) lensCache.clear()
        lensCache[key] = lens
        return lens
    }

    private val lensCache = HashMap<Long, RenderEffect>()

    private val paneClip = Path()
    private val panePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimShift = Matrix()

    companion object {
        /** How many distinct pane shapes to keep lenses for. See lensFor. */
        const val LENS_CACHE_MAX = 96
    }
}
