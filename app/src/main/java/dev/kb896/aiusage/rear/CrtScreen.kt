package dev.kb896.aiusage.rear

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.util.Log

/**
 * The CRT screen: the whole panel drawn as if on a curved tube — the picture bent
 * outwards, scanlines, a shadow mask of red, green and blue columns, a vignette and a
 * touch of colour fringing. A port of the terminal's shader on 896.ro, minus its
 * cursor, flicker and noise: those need a new frame every few milliseconds, and the
 * panel does not draw a frame it has no reason to (see RearDialView.frozen). What is
 * here is a function of the picture alone, so a still page costs nothing.
 *
 * Off by default. It is a look, and it bends the picture: a touch lands on the pixel
 * the tube shows there, not the one underneath, which is what [sourcePoint] is for.
 */
object CrtScreen {
    private const val SOURCE = """
        uniform shader content;
        uniform float2 res;
        uniform float2 warp;
        uniform float mask;
        uniform float beamMin;
        uniform float beamMax;
        uniform float aberration;

        float2 bend(float2 uv) {
            float2 q = uv * 2.0 - 1.0;
            float2 p;
            p.x = q.x * (1.0 + q.y * q.y * warp.x);
            p.y = q.y * (1.0 + q.x * q.x * warp.y);
            return (p + 1.0) * 0.5;
        }
        half3 toLinear(half3 c) { return pow(max(c, 0.0), half3(2.2)); }
        half3 toSrgb(half3 c) { return pow(max(c, 0.0), half3(1.0 / 2.2)); }

        half4 main(float2 coord) {
            float2 uv = bend(coord / res);
            float2 edge = abs(uv * 2.0 - 1.0);
            if (max(edge.x, edge.y) >= 1.0) return half4(0.0, 0.0, 0.0, 1.0);
            float2 off = (uv - 0.5) * aberration;
            float2 px = uv * res;
            half3 col;
            col.r = content.eval((uv + off) * res).r;
            col.g = content.eval(px).g;
            col.b = content.eval((uv - off) * res).b;
            col = toLinear(col);
            float lum = dot(col, half3(0.299, 0.587, 0.114));
            float beam = clamp(lum * beamMax, beamMin, beamMax);
            float scan = 0.5 + 0.5 * cos(px.y * 3.14159265);
            col *= mix(1.0, pow(scan, beam) * 1.35, 0.55);
            float m = mod(coord.x, 3.0);
            half3 shadow = half3(
                m < 1.0 ? 1.0 : 1.0 - mask,
                (m >= 1.0 && m < 2.0) ? 1.0 : 1.0 - mask,
                m >= 2.0 ? 1.0 : 1.0 - mask
            );
            col *= mix(half3(1.0), shadow, 0.8);
            col *= 1.0 + mask * 0.35;
            float vig = pow(16.0 * uv.x * uv.y * (1.0 - uv.x) * (1.0 - uv.y), 0.3);
            col *= vig;
            col = toSrgb(col);
            return half4(col, 1.0);
        }
    """

    /** The site's numbers: how far the tube bends each axis, and the rest as its themes set them. */
    const val WARP_X = 1f / 32f
    const val WARP_Y = 1f / 24f
    private const val MASK = 0.22f
    private const val BEAM_MIN = 0.35f
    private const val BEAM_MAX = 1.15f
    private const val ABERRATION = 0.0012f

    /** Null when the program does not compile; logged once, and the panel draws flat. */
    private val shader: RuntimeShader? by lazy {
        try {
            RuntimeShader(SOURCE).apply {
                setFloatUniform("warp", WARP_X, WARP_Y)
                setFloatUniform("mask", MASK)
                setFloatUniform("beamMin", BEAM_MIN)
                setFloatUniform("beamMax", BEAM_MAX)
                setFloatUniform("aberration", ABERRATION)
            }
        } catch (e: IllegalArgumentException) {
            Log.e("AiUsageRear", "crt: the shader does not compile — the panel stays flat: ${e.message}")
            null
        }
    }
    private var effect: RenderEffect? = null
    private var effectW = 0f
    private var effectH = 0f

    /** The effect for a panel of this size; one object per size, never one per frame. */
    fun effect(width: Float, height: Float): RenderEffect? {
        val shader = shader ?: return null
        effect?.let { if (effectW == width && effectH == height) return it }
        shader.setFloatUniform("res", width, height)
        return RenderEffect.createRuntimeShaderEffect(shader, "content").also {
            effect = it; effectW = width; effectH = height
        }
    }

    /**
     * Where on the flat panel a touch at ([x], [y]) on the tube lands: the tube shows,
     * at that spot, the picture from further out, so the touch is pushed outwards the
     * same way. The centre and the middles of the edges do not move; the corners move
     * most, and a touch past the picture's edge is held at the edge.
     */
    fun sourcePoint(x: Float, y: Float, w: Float, h: Float): Pair<Float, Float> {
        val qx = x / w * 2f - 1f
        val qy = y / h * 2f - 1f
        val px = qx * (1f + qy * qy * WARP_X)
        val py = qy * (1f + qx * qx * WARP_Y)
        return ((px + 1f) / 2f * w).coerceIn(0f, w) to ((py + 1f) / 2f * h).coerceIn(0f, h)
    }
}
