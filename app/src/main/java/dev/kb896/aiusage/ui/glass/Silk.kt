package dev.kb896.aiusage.ui.glass

import android.graphics.Bitmap
import android.graphics.RuntimeShader
import kotlin.math.cos
import kotlin.math.sin

/**
 * The front screen's background: "Silk", from Vue Bits (vue-bits.dev/backgrounds/silk,
 * DavidHDev, after React Bits), its fragment shader translated to AGSL line for line,
 * with the settings it was chosen at: speed 3.3, scale 1.3, noise intensity 3, rotation 75°,
 * colour #49701D.
 *
 * One shader for every place the background is seen: the root draws it, and every pane of
 * glass refracts it (Backdrop.shader), so the glass bends the silk as it moves rather than
 * a still copy of it. Time runs as the original's does — a tenth of a second of shader
 * time per second — and only while the settings screen is on screen (Backdrop.install).
 *
 * The original's plane spans the canvas with UVs 0..1, y up; here the canvas is the root,
 * in pixels, y down, so the UV is taken from the coordinate and flipped. gl_FragCoord in
 * the grain is the pixel, as there.
 */
object Silk {
    const val SPEED = 3.3f
    const val SCALE = 1.3f
    const val NOISE = 3f
    /** 75°, in radians — the original's rotation control, which takes radians. */
    const val ROTATION = 1.3089969f
    const val COLOR = 0xFF49701D.toInt()

    private const val SRC = """
uniform float2 size;
uniform float time;
uniform half3 color;
uniform float speed;
uniform float scale;
uniform float rotation;
uniform float noiseIntensity;

const float e = 2.71828182845904523536;

float noise(float2 texCoord) {
    float G = e;
    float2 r = (G * sin(G * texCoord));
    return fract(r.x * r.y * (1.0 + texCoord.x));
}

float2 rotateUvs(float2 uv, float angle) {
    float c = cos(angle);
    float s = sin(angle);
    float2x2 rot = float2x2(c, -s, s, c);
    return rot * uv;
}

half4 main(float2 coord) {
    float rnd = noise(coord);
    float2 vUv = float2(coord.x / size.x, 1.0 - coord.y / size.y);
    float2 uv = rotateUvs(vUv * scale, rotation);
    float2 tex = uv * scale;
    float tOffset = speed * time;

    tex.y += 0.03 * sin(8.0 * tex.x - tOffset);

    float pattern = 0.6 +
                    0.4 * sin(5.0 * (tex.x + tex.y +
                                     cos(3.0 * tex.x + 5.0 * tex.y) +
                                     0.02 * tOffset) +
                             sin(20.0 * (tex.x + tex.y - 0.1 * tOffset)));

    half4 col = half4(color, 1.0) * half4(pattern) - half(rnd / 15.0 * noiseIntensity);
    return half4(clamp(col.rgb, 0.0, 1.0), 1.0);
}"""

    /** Shader time, in the original's units. */
    var time = 0f; private set

    val shader: RuntimeShader by lazy {
        RuntimeShader(SRC).apply {
            setFloatUniform("color",
                ((COLOR shr 16) and 0xFF) / 255f, ((COLOR shr 8) and 0xFF) / 255f, (COLOR and 0xFF) / 255f)
            setFloatUniform("speed", SPEED)
            setFloatUniform("scale", SCALE)
            setFloatUniform("rotation", ROTATION)
            setFloatUniform("noiseIntensity", NOISE)
            setFloatUniform("time", 0f)
            setFloatUniform("size", 1f, 1f)
        }
    }

    fun size(w: Int, h: Int) { shader.setFloatUniform("size", w.toFloat(), h.toFloat()) }

    /** Advances by [dtSeconds] of wall time: the original's `uTime += 0.1 * deltaTime`. */
    fun advance(dtSeconds: Float) {
        time += 0.1f * dtSeconds
        shader.setFloatUniform("time", time)
    }

    /**
     * The silk at the current time, worked out on the CPU at a quarter of the size and
     * scaled up: what the brightness map and the switch tracks read, which cannot run an
     * AGSL shader. The grain is left out and its average taken off instead.
     */
    fun still(w: Int, h: Int): Bitmap {
        val sw = (w / 4).coerceAtLeast(1); val sh = (h / 4).coerceAtLeast(1)
        val px = IntArray(sw * sh)
        val r = ((COLOR shr 16) and 0xFF) / 255f
        val g = ((COLOR shr 8) and 0xFF) / 255f
        val b = (COLOR and 0xFF) / 255f
        val grain = 0.5f / 15f * NOISE
        val c = cos(ROTATION); val s = sin(ROTATION)
        val tOffset = SPEED * time
        for (y in 0 until sh) for (x in 0 until sw) {
            val u = (x + 0.5f) / sw * SCALE
            val v = (1f - (y + 0.5f) / sh) * SCALE
            // column-major mat2(c, -s, s, c) * (u, v)
            val tx = (c * u + s * v) * SCALE
            var ty = (-s * u + c * v) * SCALE
            ty += 0.03f * sin(8f * tx - tOffset)
            val pattern = 0.6f + 0.4f * sin(5f * (tx + ty + cos(3f * tx + 5f * ty) + 0.02f * tOffset) +
                sin(20f * (tx + ty - 0.1f * tOffset)))
            fun ch(k: Float) = ((k * pattern - grain).coerceIn(0f, 1f) * 255f).toInt()
            px[y * sw + x] = (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
        }
        val small = Bitmap.createBitmap(px, sw, sh, Bitmap.Config.ARGB_8888)
        return Bitmap.createScaledBitmap(small, w, h, true)
    }
}
