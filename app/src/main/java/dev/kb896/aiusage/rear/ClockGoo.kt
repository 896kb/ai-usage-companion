package dev.kb896.aiusage.rear

import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RecordingCanvas
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader

/**
 * The clock changing face, digital to analog and back, as a gooey morph (the owner,
 * 2026-11-09): the CSS gooey-text trick. Each face is drawn flat in the clock's colour on a
 * layer of its own; the face leaving blurs away as its share falls ([blurOf]) and the face
 * coming sharpens out of a blur; both layers go into one whose alpha is cut hard at
 * [THRESHOLD], so where their blurs meet they come back as one shape with a crisp edge, and the
 * figures run into the pills like a liquid.
 *
 * NOT A CROSS-FADE. The first version blurred both alike and faded each by its share: halfway,
 * each was half there, the cut threw most of both away, and the change read as abrupt. Here each
 * face's opacity is its share to the 0.4th ([opacityOf]) — three quarters there at halfway, the
 * two together more than whole — so the goo holds together the whole way across.
 *
 * The real glass faces hand over to it at the ends: the figures' glass gone in the first quarter
 * ([digitalShown]), the pills' only in the last ([analogShown]), the goo carrying the rest.
 *
 * THE GOO IS GLASS TOO, where there is a ground to bend (the owner, 2026-11-10: "still be glass
 * while transitioning instead of going full solid and then transforming into glass again"). The
 * two blurred faces are then not the paint but the MASK: the ground is drawn and cut to them, so
 * one layer carries the ground in its colour and the goo's shape in its alpha, and [GLASS] makes
 * the glass from that alone — the cut, the ground bent in from the edge (the alpha's slope is the
 * way in, and it is steepest at the edge), the clock's tint, and a lit rim just inside the cut.
 * No distance field: the goo has no shape that could be written down. With no ground it is the
 * flat tinted liquid it was (the ORIGINAL theme, which has no glass).
 *
 * ONE EFFECT PER BLUR STEP, kept: the blur is quantised to [STEPS] radii and each is built once —
 * a RenderEffect a frame is the leak GlassPainter.lensFor ended.
 */
class ClockGoo {
    private val digitalNode = RenderNode("clock-goo-digital")
    private val analogNode = RenderNode("clock-goo-analog")
    private val node = RenderNode("clock-goo")
    private val blurs = arrayOfNulls<RenderEffect>(STEPS + 1)
    private val cut by lazy { RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(ColorMatrix(matrix()))) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** Cuts what is under it to what is drawn through it: the ground to the goo. */
    private val mask = Paint().apply { blendMode = android.graphics.BlendMode.DST_IN }
    private val glassShader: android.graphics.RuntimeShader? by lazy {
        runCatching { android.graphics.RuntimeShader(GLASS) }
            .onFailure { android.util.Log.w("AiUsageRear", "clock goo: the glass shader would not compile, the goo stays flat", it) }
            .getOrNull()
    }
    /** The glass for one colour and tint, kept: an effect is built when either changes, not a frame. */
    private var glass: RenderEffect? = null
    private var glassInk = 0
    private var glassTint = -1f
    private val under = android.graphics.Matrix()

    /**
     * The goo at [t] (0 the digital face, 1 the analog), [width] by [height] in the canvas's
     * space, in [ink]: [digital] and [analog] draw each face flat with the paint they are handed.
     * False where there is no hardware canvas to blur in; the faces then simply cross-fade.
     */
    fun draw(
        canvas: Canvas, t: Float, width: Int, height: Int, ink: Int,
        /** What the goo bends, panel-wide from the root's origin; null for the flat liquid. */
        ground: RenderNode? = null,
        /** How much of [ink] the glass takes, 0 clear to 1. */
        tint: Float = 0f,
        digital: (Canvas, Paint) -> Unit, analog: (Canvas, Paint) -> Unit,
    ): Boolean {
        val shown = gooShown(t)
        if (shown <= 0.004f) return true
        val recorder = canvas as? RecordingCanvas ?: return false
        face(digitalNode, 1f - t, width, height, ink, digital)
        face(analogNode, t, width, height, ink, analog)
        val lens = if (ground != null && ground.hasDisplayList() && canvas.matrix.invert(under)) glassFor(ink, tint) else null
        node.setPosition(0, 0, width, height)
        val c = node.beginRecording()
        try {
            if (lens != null) {
                // The ground where it lies under this canvas, then cut to the two faces.
                c.save()
                c.concat(under)
                c.drawRenderNode(ground!!)
                c.restore()
                c.saveLayer(null, mask)
            }
            c.drawRenderNode(digitalNode)
            c.drawRenderNode(analogNode)
            if (lens != null) c.restore()
        } finally {
            node.endRecording()
        }
        node.setRenderEffect(lens ?: cut)
        node.setAlpha(if (lens != null) shown else GOO_ALPHA * shown)
        recorder.drawRenderNode(node)
        return true
    }

    private fun glassFor(ink: Int, tint: Float): RenderEffect? {
        val shader = glassShader ?: return null
        glass?.takeIf { glassInk == ink && glassTint == tint }?.let { return it }
        shader.setColorUniform("ink", ink or (0xFF shl 24))
        shader.setFloatUniform("tint", tint.coerceIn(0f, 1f))
        return RenderEffect.createRuntimeShaderEffect(shader, "content").also {
            glass = it; glassInk = ink; glassTint = tint
        }
    }

    /** One face's layer at its [share]: as there as [opacityOf] says, as blurred as [blurOf] says. */
    private fun face(into: RenderNode, share: Float, width: Int, height: Int, ink: Int, draw: (Canvas, Paint) -> Unit) {
        into.setPosition(0, 0, width, height)
        val c = into.beginRecording()
        try {
            val a = (255 * opacityOf(share)).toInt().coerceIn(0, 255)
            if (a > 0) {
                paint.color = ink
                paint.alpha = a
                draw(c, paint)
            }
        } finally {
            into.endRecording()
        }
        into.setRenderEffect(blurFor(stepOf(blurOf(share))))
    }

    private fun blurFor(step: Int): RenderEffect? {
        if (step == 0) return null
        blurs[step]?.let { return it }
        val r = step * BLUR_CAP / STEPS
        return RenderEffect.createBlurEffect(r, r, Shader.TileMode.DECAL).also { blurs[step] = it }
    }

    companion object {
        /** The blur of a face half there, and the most any face is blurred, in the canvas's units. */
        const val BLUR_K = 9f
        const val BLUR_CAP = 48f
        /** How many steps the blur comes in. */
        const val STEPS = 24
        /** Where the cut falls in the alpha, and how hard it is. */
        const val THRESHOLD = 0.5f
        private const val SHARPNESS = 24f
        /** How strong the goo is at its fullest: a tinted liquid, not a paint. */
        const val GOO_ALPHA = 0.7f

        /**
         * The goo as glass. [content] is the ground, cut to the goo: its alpha is the two blurred
         * faces together. Where that alpha passes [THRESHOLD] is the edge, as hard as the flat
         * goo's; the alpha's slope points in from it, and the ground is taken from further along
         * that way the nearer the edge, as a lens bends; then the clock's tint, and a line of
         * light just inside the edge, brighter where it faces the panel's light (up and left).
         */
        const val GLASS = """
            uniform shader content;
            layout(color) uniform half4 ink;
            uniform float tint;

            half4 main(float2 xy) {
                float a = content.eval(xy).a;
                float cut = clamp((a - 0.5) * 24.0, 0.0, 1.0);
                if (cut <= 0.0) { return half4(0.0); }
                float d = 3.0;
                float2 g = float2(
                    content.eval(xy + float2(d, 0.0)).a - content.eval(xy - float2(d, 0.0)).a,
                    content.eval(xy + float2(0.0, d)).a - content.eval(xy - float2(0.0, d)).a);
                float gl = length(g);
                float2 gn = g / max(gl, 0.0001);
                float nearEdge = 1.0 - smoothstep(0.5, 0.9, a);
                half4 b = content.eval(xy + gn * min(gl * 60.0, 18.0) * nearEdge);
                float3 ground = float3(b.rgb) / max(float(b.a), 0.001);
                float3 c = float3(ink.rgb);
                float3 col = mix(ground, c, tint);
                float3 hot = mix(c, float3(1.0), 0.55);
                float line = smoothstep(0.5, 0.56, a) * (1.0 - smoothstep(0.56, 0.68, a));
                float facing = 0.5 + 0.5 * dot(gn, float2(0.6, 0.8));
                col = min(col + hot * line * (0.12 + 0.88 * facing * facing) * 0.7, float3(1.0));
                return half4(half3(col * cut), half(cut));
            }
        """

        /** A face's blur at its [share] of the morph: none when all there, [BLUR_K] at half, up to [BLUR_CAP]. */
        fun blurOf(share: Float): Float {
            val s = share.coerceIn(0f, 1f)
            if (s <= 0f) return BLUR_CAP
            return minOf(BLUR_K / s - BLUR_K, BLUR_CAP)
        }

        /** A face's opacity at its [share]: more than its share, so the two together hold. */
        fun opacityOf(share: Float): Float = Math.pow(share.coerceIn(0f, 1f).toDouble(), 0.4).toFloat()

        /** [blur] as one of [STEPS] kept radii. */
        fun stepOf(blur: Float): Int = Math.round(blur / BLUR_CAP * STEPS).coerceIn(0, STEPS)

        /** The alpha row of the cutoff: alpha' = [0] × alpha + [1]. */
        fun thresholdRow(): FloatArray = floatArrayOf(SHARPNESS, -SHARPNESS * THRESHOLD * 255f)

        private fun matrix(): FloatArray {
            val (k, off) = thresholdRow().let { it[0] to it[1] }
            return floatArrayOf(
                1f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1f, 0f, 0f,
                0f, 0f, 0f, k, off,
            )
        }

        private fun smooth(e0: Float, e1: Float, t: Float): Float {
            val x = ((t - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return x * x * (3f - 2f * x)
        }

        /** How much of the digital face's own glass is up at [t]. */
        fun digitalShown(t: Float): Float = 1f - smooth(0f, 0.25f, t)
        /** How much of the analog face's own glass is up at [t]. */
        fun analogShown(t: Float): Float = smooth(0.75f, 1f, t)
        /** How much of the goo is up at [t]: in as the digital glass goes, out as the analog comes. */
        fun gooShown(t: Float): Float = smooth(0f, 0.25f, t) * (1f - smooth(0.75f, 1f, t))
    }
}
