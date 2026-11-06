package dev.kb896.aiusage.ui.glass

import android.graphics.Bitmap
import android.graphics.RuntimeShader
import kotlin.math.cos
import kotlin.math.sin

/**
 * The front screen's background: "Silk", from Vue Bits (vue-bits.dev/backgrounds/silk,
 * DavidHDev, after React Bits), its fragment shader translated to AGSL line for line,
 * with the settings it was chosen at: speed 3.3, scale 1.3, noise intensity 3, rotation 45°,
 * colour #5B21B6.
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
    /** 45°, in radians — the original's rotation control, which takes radians. */
    const val ROTATION = 0.7853982f
    /** The app's purple: the settings above are as chosen, the colour was #49701D then. */
    const val COLOR = 0xFF5B21B6.toInt()

    private const val SRC = """
uniform float2 size;
uniform float time;
uniform half3 color;
uniform half3 color2;
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

    // From color2 in the folds to color on the crests: the original's color * pattern when color2 is black.
    half4 col = half4(mix(color2, color, half(pattern)), 1.0) - half(rnd / 15.0 * noiseIntensity);
    return half4(clamp(col.rgb, 0.0, 1.0), 1.0);
}"""

    /** Shader time, in the original's units. */
    var time = 0f; private set

    val shader: RuntimeShader by lazy { create() }

    /**
     * A silk of its own, at the same settings, for a canvas that keeps its own time — the
     * panel's background on the back, which must not move the front's (both live in one
     * process when the preview draws the panel).
     */
    fun create(): RuntimeShader =
        RuntimeShader(SRC).apply {
            setFloatUniform("color",
                ((COLOR shr 16) and 0xFF) / 255f, ((COLOR shr 8) and 0xFF) / 255f, (COLOR and 0xFF) / 255f)
            setFloatUniform("color2", 0f, 0f, 0f)
            setFloatUniform("speed", SPEED)
            setFloatUniform("scale", SCALE)
            setFloatUniform("rotation", ROTATION)
            setFloatUniform("noiseIntensity", NOISE)
            setFloatUniform("time", 0f)
            setFloatUniform("size", 1f, 1f)
        }

    /**
     * [shader] set to a look of its own (the back's choice): [color] on the crests, [color2] in
     * the folds, [scale] for how often the folds repeat, turned by [rotationDeg] degrees.
     */
    fun look(shader: RuntimeShader, color: Int, color2: Int, scale: Float, rotationDeg: Int) {
        fun rgb(name: String, c: Int) = shader.setFloatUniform(
            name, ((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f,
        )
        rgb("color", color)
        rgb("color2", color2)
        shader.setFloatUniform("scale", scale)
        shader.setFloatUniform("rotation", Math.toRadians(rotationDeg.toDouble()).toFloat())
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

    /**
     * The silk's brightness in [side]×[side] cells at shader time 0, row by row, worked out
     * on the CPU as [still] is: what the back's glass reads when the silk is behind it.
     * Rec.709, as GlassLuminance.of weighs a colour.
     */
    /** The brightness map at a [look]: the back's chosen one; the front's own by default. */
    fun cells(
        side: Int, color: Int = COLOR, color2: Int = 0xFF000000.toInt(),
        scale: Float = SCALE, rotationDeg: Int = 45,
    ): FloatArray {
        fun ch(c: Int, shift: Int) = ((c shr shift) and 0xFF) / 255f
        val grain = 0.5f / 15f * NOISE
        val a = Math.toRadians(rotationDeg.toDouble()).toFloat()
        val c = cos(a); val s = sin(a)
        return FloatArray(side * side) { i ->
            val u = ((i % side) + 0.5f) / side * scale
            val v = (1f - ((i / side) + 0.5f) / side) * scale
            val tx = (c * u + s * v) * scale
            var ty = (-s * u + c * v) * scale
            ty += 0.03f * sin(8f * tx)
            val pattern = 0.6f + 0.4f * sin(5f * (tx + ty + cos(3f * tx + 5f * ty)) + sin(20f * (tx + ty)))
            fun mix(shift: Int) = (ch(color2, shift) + (ch(color, shift) - ch(color2, shift)) * pattern - grain).coerceIn(0f, 1f)
            0.2126f * mix(16) + 0.7152f * mix(8) + 0.0722f * mix(0)
        }
    }
}
