package dev.kb896.aiusage.ui.glass

import android.graphics.RuntimeShader
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The back's second shader background: "Color Bends", from Vue Bits
 * (vue-bits.dev/backgrounds/color-bends, DavidHDev), its fragment shader translated to AGSL
 * line for line, with one colour as the demo has it. Its defaults are the demo's.
 *
 * The original draws transparent over the page; here it is drawn as it looks over black,
 * the colour premultiplied by its cover. There is no mouse on the back: the pointer the
 * bends follow is the panel's parallax instead (the tilt, the scroll), from -1 to 1 on each
 * axis, y up, as the original's pointer is. Time is in seconds, as the original's clock.
 */
object ColorBends {
    const val SPEED = 0.2f
    const val MOUSE_INFLUENCE = 1f
    const val PARALLAX = 0.5f
    const val NOISE = 0.15f
    const val ITERATIONS = 1
    const val INTENSITY = 1.5f
    const val WARP = 1f

    private const val SRC = """
uniform float2 size;
uniform float time;
uniform float speed;
uniform float2 rot;
uniform half3 color;
uniform float scale;
uniform float frequency;
uniform float warpStrength;
uniform float2 pointer;
uniform float mouseInfluence;
uniform float parallax;
uniform float noiseAmount;
uniform int iterations;
uniform float intensity;
uniform float bandWidth;

half4 main(float2 coord) {
    float t = time * speed;
    float2 vUv = float2(coord.x / size.x, 1.0 - coord.y / size.y);
    float2 p = vUv * 2.0 - 1.0;
    p += pointer * parallax * 0.1;
    float2 rp = float2(p.x * rot.x - p.y * rot.y, p.x * rot.y + p.y * rot.x);
    float2 q = float2(rp.x * (size.x / size.y), rp.y);
    q /= max(scale, 0.0001);
    q /= 0.5 + 0.2 * dot(q, q);
    q += 0.2 * cos(t) - 7.56;
    float2 toward = pointer - rp;
    q += toward * mouseInfluence * 0.2;

    for (int j = 0; j < 5; j++) {
        if (j >= iterations - 1) break;
        float2 rr = sin(1.5 * (q.yx * frequency) + 2.0 * cos(q * frequency));
        q += (rr - q) * 0.15;
    }

    float2 s = q - 0.01;
    float2 r = sin(1.5 * (s.yx * frequency) + 2.0 * cos(s * frequency));
    float m0 = length(r + sin(5.0 * r.y * frequency - 3.0 * t) / 4.0);
    float kBelow = clamp(warpStrength, 0.0, 1.0);
    float kMix = pow(kBelow, 0.3);
    float gain = 1.0 + max(warpStrength - 1.0, 0.0);
    float2 disp = (r - s) * kBelow;
    float2 warped = s + disp * gain;
    float m1 = length(warped + sin(5.0 * warped.y * frequency - 3.0 * t) / 4.0);
    float m = mix(m0, m1, kMix);
    float w = 1.0 - exp(-bandWidth / exp(bandWidth * m));
    float3 col = clamp(float3(color) * w, 0.0, 1.0);
    float a = w;

    col *= intensity;

    if (noiseAmount > 0.0001) {
        float n = fract(sin(dot(coord + float2(time), float2(12.9898, 78.233))) * 43758.5453123);
        col += (n - 0.5) * noiseAmount;
        col = clamp(col, 0.0, 1.0);
    }

    return half4(half3(col * a), 1.0);
}"""

    fun create(): RuntimeShader =
        RuntimeShader(SRC).apply {
            setFloatUniform("size", 1f, 1f)
            setFloatUniform("time", 0f)
            setFloatUniform("speed", SPEED)
            setFloatUniform("warpStrength", WARP)
            setFloatUniform("pointer", 0f, 0f)
            setFloatUniform("mouseInfluence", MOUSE_INFLUENCE)
            setFloatUniform("parallax", PARALLAX)
            setFloatUniform("noiseAmount", NOISE)
            setIntUniform("iterations", ITERATIONS)
            setFloatUniform("intensity", INTENSITY)
        }

    /** Where the bends lean, as the original's pointer: -1 to 1 on each axis, y up. */
    fun pointer(shader: RuntimeShader, x: Float, y: Float) = shader.setFloatUniform("pointer", x, y)

    /** [shader] set to the back's chosen look. */
    fun look(shader: RuntimeShader, look: dev.kb896.aiusage.data.BendsConfig) {
        shader.setFloatUniform(
            "color", ((look.color shr 16) and 0xFF) / 255f, ((look.color shr 8) and 0xFF) / 255f, (look.color and 0xFF) / 255f,
        )
        val a = Math.toRadians(look.rotation.toDouble())
        shader.setFloatUniform("rot", cos(a).toFloat(), sin(a).toFloat())
        shader.setFloatUniform("scale", look.scale)
        shader.setFloatUniform("frequency", look.frequency)
        shader.setFloatUniform("bandWidth", look.bandWidth)
    }

    /**
     * The bends' brightness in [side]×[side] cells at time 0 over a [w]×[h] canvas, worked out
     * on the CPU as the shader does, the grain left out: what the back's glass reads behind it.
     */
    fun cells(side: Int, w: Int, h: Int, look: dev.kb896.aiusage.data.BendsConfig): FloatArray {
        val a = Math.toRadians(look.rotation.toDouble())
        val rc = cos(a).toFloat(); val rs = sin(a).toFloat()
        val f = look.frequency
        val r0 = ((look.color shr 16) and 0xFF) / 255f
        val g0 = ((look.color shr 8) and 0xFF) / 255f
        val b0 = (look.color and 0xFF) / 255f
        val aspect = w.toFloat() / h
        return FloatArray(side * side) { i ->
            val px = ((i % side) + 0.5f) / side * 2f - 1f
            val py = (1f - ((i / side) + 0.5f) / side) * 2f - 1f
            val rpx = px * rc - py * rs; val rpy = px * rs + py * rc
            var qx = rpx * aspect / max(look.scale, 0.0001f); var qy = rpy / max(look.scale, 0.0001f)
            val d = 0.5f + 0.2f * (qx * qx + qy * qy)
            qx = qx / d + 0.2f - 7.56f; qy = qy / d + 0.2f - 7.56f
            qx -= rpx * MOUSE_INFLUENCE * 0.2f; qy -= rpy * MOUSE_INFLUENCE * 0.2f
            val sx = qx - 0.01f; val sy = qy - 0.01f
            val rx = sin(1.5f * sy * f + 2f * cos(sx * f)); val ry = sin(1.5f * sx * f + 2f * cos(sy * f))
            val wob = sin(5f * ry * f) / 4f
            val m0 = sqrt((rx + wob) * (rx + wob) + (ry + wob) * (ry + wob))
            val k = WARP.coerceIn(0f, 1f)
            val wx = sx + (rx - sx) * k; val wy = sy + (ry - sy) * k
            val wob1 = sin(5f * wy * f) / 4f
            val m1 = sqrt((wx + wob1) * (wx + wob1) + (wy + wob1) * (wy + wob1))
            val m = m0 + (m1 - m0) * k.pow(0.3f)
            val wgt = 1f - exp(-look.bandWidth / exp(look.bandWidth * m))
            fun ch(c: Float) = ((c * wgt).coerceIn(0f, 1f) * INTENSITY).coerceIn(0f, 1f) * wgt
            0.2126f * ch(r0) + 0.7152f * ch(g0) + 0.0722f * ch(b0)
        }
    }
}
