package dev.kb896.aiusage.rear

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.util.Log

/**
 * The CRT screen: the panel drawn as if on a curved tube — the picture bent, scanlines,
 * a shadow mask of red, green and blue columns, a vignette and colour fringing. A port
 * of the terminal's shader on 896.ro, turned up for a panel seen from arm's length, and
 * minus the site's cursor, flicker and noise: those need a new frame every few
 * milliseconds, and the panel does not draw a frame it has no reason to (see
 * RearDialView.frozen). What is here is a function of the picture alone.
 *
 * Two places the tube must not move the picture, and the bend fades to nothing there:
 * the margin — a band along the frame, wide enough to hold the display's own rounded
 * corners, so the frame stays the frame and no corner goes black — and the rings, which
 * sit round the camera lenses under the glass; bent, they would no longer sit round
 * them. Those are the [Still] circles. Lines, mask and vignette apply everywhere.
 *
 * Off by default. It is a look, and it moves the picture: a touch lands on the pixel
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
        uniform float scanMix;
        uniform float margin;
        uniform float feather;
        uniform float4 stillA;
        uniform float4 stillB;

        float2 bend(float2 uv) {
            float2 q = uv * 2.0 - 1.0;
            float2 p;
            p.x = q.x * (1.0 + q.y * q.y * warp.x);
            p.y = q.y * (1.0 + q.x * q.x * warp.y);
            return (p + 1.0) * 0.5;
        }
        // how much of the bend applies here: none in the margin band and inside the
        // still circles, all of it further in, feathered between
        float give(float2 px) {
            float edge = min(min(px.x, res.x - px.x), min(px.y, res.y - px.y));
            float g = smoothstep(margin, margin + feather, edge);
            g *= smoothstep(stillA.z, stillA.w, length(px - stillA.xy));
            g *= smoothstep(stillB.z, stillB.w, length(px - stillB.xy));
            return g;
        }
        half3 toLinear(half3 c) { return pow(max(c, 0.0), half3(2.2)); }
        half3 toSrgb(half3 c) { return pow(max(c, 0.0), half3(1.0 / 2.2)); }

        half4 main(float2 coord) {
            float2 plain = coord / res;
            float2 uv = mix(plain, bend(plain), give(coord));
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
            float scan = 0.5 + 0.5 * cos(px.y * 2.0943951); // a line every three pixels
            col *= mix(1.0, pow(scan, beam) * 1.35, scanMix);
            float m = mod(coord.x, 3.0);
            half3 shadow = half3(
                m < 1.0 ? 1.0 : 1.0 - mask,
                (m >= 1.0 && m < 2.0) ? 1.0 : 1.0 - mask,
                m >= 2.0 ? 1.0 : 1.0 - mask
            );
            col *= mix(half3(1.0), shadow, 0.8);
            col *= 1.0 + mask * 0.35;
            float vig = pow(16.0 * plain.x * plain.y * (1.0 - plain.x) * (1.0 - plain.y), 0.3);
            col *= vig;
            col = toSrgb(col);
            return half4(col, 1.0);
        }
    """

    /** A circle the tube must not move: the ring round a lens, with its stroke. */
    data class Still(val cx: Float, val cy: Float, val r: Float)

    /** The site's bend, doubled; the rest of its numbers turned up. */
    const val WARP_X = 0.07f
    const val WARP_Y = 0.09f
    private const val MASK = 0.35f
    private const val BEAM_MIN = 0.35f
    private const val BEAM_MAX = 1.15f
    private const val ABERRATION = 0.0025f
    private const val SCAN_MIX = 0.8f
    /** the fixed band along the frame, and how far in the bend takes to arrive */
    const val MARGIN = 36f
    const val FEATHER = 70f
    /** past a still circle's edge, how far the bend takes to arrive */
    const val STILL_FEATHER = 40f
    private const val FAR = -100_000f

    /** Null when the program does not compile; logged once, and the panel draws flat. */
    private val shader: RuntimeShader? by lazy {
        try {
            RuntimeShader(SOURCE).apply {
                setFloatUniform("warp", WARP_X, WARP_Y)
                setFloatUniform("mask", MASK)
                setFloatUniform("beamMin", BEAM_MIN)
                setFloatUniform("beamMax", BEAM_MAX)
                setFloatUniform("aberration", ABERRATION)
                setFloatUniform("scanMix", SCAN_MIX)
                setFloatUniform("margin", MARGIN)
                setFloatUniform("feather", FEATHER)
            }
        } catch (e: IllegalArgumentException) {
            Log.e("AiUsageRear", "crt: the shader does not compile — the panel stays flat: ${e.message}")
            null
        }
    }
    private var effect: RenderEffect? = null
    private var effectKey: String? = null
    @Volatile private var stills: List<Still> = emptyList()

    /** The effect for a panel of this size with these still circles; one per shape, never one per frame. */
    fun effect(width: Float, height: Float, stills: List<Still>): RenderEffect? {
        val shader = shader ?: return null
        val key = "$width|$height|$stills"
        effect?.let { if (effectKey == key) return it }
        shader.setFloatUniform("res", width, height)
        val a = stills.getOrNull(0)
        val b = stills.getOrNull(1)
        shader.setFloatUniform("stillA", a?.cx ?: FAR, a?.cy ?: FAR, a?.r ?: 0f, (a?.r ?: 0f) + STILL_FEATHER)
        shader.setFloatUniform("stillB", b?.cx ?: FAR, b?.cy ?: FAR, b?.r ?: 0f, (b?.r ?: 0f) + STILL_FEATHER)
        this.stills = stills.take(2)
        return RenderEffect.createRuntimeShaderEffect(shader, "content").also {
            effect = it; effectKey = key
        }
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * Where on the flat panel a touch at ([x], [y]) on the tube lands: the tube shows,
     * at that spot, the picture from further out, so the touch is moved outwards the
     * same way — by as much of the bend as applies there. In the margin band and the
     * still circles nothing moves; a touch past the picture's edge is held at the edge.
     */
    fun sourcePoint(x: Float, y: Float, w: Float, h: Float, stills: List<Still> = this.stills): Pair<Float, Float> {
        val qx = x / w * 2f - 1f
        val qy = y / h * 2f - 1f
        val bx = (qx * (1f + qy * qy * WARP_X) + 1f) / 2f * w
        val by = (qy * (1f + qx * qx * WARP_Y) + 1f) / 2f * h
        var give = smooth(MARGIN, MARGIN + FEATHER, minOf(minOf(x, w - x), minOf(y, h - y)))
        for (s in stills) give *= smooth(s.r, s.r + STILL_FEATHER, Math.hypot((x - s.cx).toDouble(), (y - s.cy).toDouble()).toFloat())
        return (x + (bx - x) * give).coerceIn(0f, w) to (y + (by - y) * give).coerceIn(0f, h)
    }
}
