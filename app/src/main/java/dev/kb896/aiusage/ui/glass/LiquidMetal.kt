package dev.kb896.aiusage.ui.glass

import android.graphics.RuntimeShader
import dev.kb896.aiusage.data.MetalConfig

/**
 * The back's third shader background: liquid metal, as libraries.dev/metal (metal-fx, Jakub
 * Antalik, MIT) draws it, which is Paper Shaders' `liquidMetal` (paper-design/shaders 0.0.80,
 * Apache-2.0) — its fragment shader translated to AGSL line for line, with the vertex stage's
 * sizing worked in. Only the full-frame shape ("none", Paper's "Backdrop" preset) is kept:
 * metal-fx cuts a ring out of that sheet for a button's margin; here the sheet is the whole back.
 *
 * Changes from the original, as AGSL needs them:
 *  - no derivatives in AGSL: fwidth(edge) is a fixed [EDGE_AA], and a stripe's fwidth is worked
 *    out from how fast the stripes run across a pixel (about cycleWidth / height);
 *  - the frame's bevel is [EDGE_PX] wide rather than Paper's 250, which on the back's 904×572
 *    would leave almost nothing flat;
 *  - a [pointer], for the panel's parallax, moves the stripes (not the bevel) a little, as the
 *    cursor bends the ring on the web.
 */
object LiquidMetal {
    const val EDGE_PX = 110f
    const val EDGE_AA = 0.02f
    const val SOFTNESS = 0.05f
    const val SHIFT = 0.3f
    const val CONTOUR = 0.4f

    /** The presets of metal-fx, dark side: a colour-burn tint (its alpha the amount) and an opacity. */
    data class Preset(val tint: Int, val opacity: Float, val shift: Float = SHIFT, val softness: Float = SOFTNESS)
    val PRESETS = mapOf(
        "silver" to Preset(0x66FFFFFF, 0.88f),
        "gold" to Preset(0xCCFFCC55.toInt(), 0.92f),
        "chromatic" to Preset(0x2E88CCFF, 1f, shift = 0.75f, softness = 0.09f),
    )

    private const val SRC = """
uniform float2 resolution;
uniform float time;
uniform float4 colorTint;
uniform float softness;
uniform float repetition;
uniform float shiftRed;
uniform float shiftBlue;
uniform float distortion;
uniform float contourAmount;
uniform float angleDeg;
uniform float rotationDeg;
uniform float opacityScale;
uniform float2 pointer;
uniform float edgePx;
uniform float edgeAa;

const float PI = 3.14159265358979323846;

float2 rotate(float2 uv, float th) {
    return float2x2(cos(th), sin(th), -sin(th), cos(th)) * uv;
}

float3 permute(float3 x) { return mod(((x * 34.0) + 1.0) * x, 289.0); }
float snoise(float2 v) {
    const float4 C = float4(0.211324865405187, 0.366025403784439,
        -0.577350269189626, 0.024390243902439);
    float2 i = floor(v + dot(v, C.yy));
    float2 x0 = v - i + dot(i, C.xx);
    float2 i1 = (x0.x > x0.y) ? float2(1.0, 0.0) : float2(0.0, 1.0);
    float4 x12 = x0.xyxy + C.xxzz;
    x12.xy -= i1;
    i = mod(i, 289.0);
    float3 p = permute(permute(i.y + float3(0.0, i1.y, 1.0))
        + i.x + float3(0.0, i1.x, 1.0));
    float3 m = max(0.5 - float3(dot(x0, x0), dot(x12.xy, x12.xy),
        dot(x12.zw, x12.zw)), 0.0);
    m = m * m;
    m = m * m;
    float3 x = 2.0 * fract(p * C.www) - 1.0;
    float3 h = abs(x) - 0.5;
    float3 ox = floor(x + 0.5);
    float3 a0 = x - ox;
    m *= 1.79284291400159 - 0.85373472095314 * (a0 * a0 + h * h);
    float3 g = float3(0.);
    g.x = a0.x * x0.x + h.x * x0.y;
    g.yz = a0.yz * x12.xz + h.yz * x12.yw;
    return 130.0 * dot(m, g);
}

float getColorChanges(float c1, float c2, float stripe_p, float3 w, float blur, float bump, float tint) {
    float ch = mix(c2, c1, smoothstep(.0, 2. * blur, stripe_p));

    float border = w[0];
    ch = mix(ch, c2, smoothstep(border, border + 2. * blur, stripe_p));

    border = w[0] + .4 * (1. - bump) * w[1];
    ch = mix(ch, c1, smoothstep(border, border + 2. * blur, stripe_p));

    border = w[0] + .5 * (1. - bump) * w[1];
    ch = mix(ch, c2, smoothstep(border, border + 2. * blur, stripe_p));

    border = w[0] + w[1];
    ch = mix(ch, c1, smoothstep(border, border + 2. * blur, stripe_p));

    float gradient_t = (stripe_p - w[0] - w[1]) / w[2];
    float gradient = mix(c1, c2, smoothstep(0., 1., gradient_t));
    ch = mix(ch, gradient, smoothstep(border, border + .5 * blur, stripe_p));

    // Tint color is applied with color burn blending
    ch = mix(ch, 1. - min(1., (1. - ch) / max(tint, 0.0001)), colorTint.a);
    return ch;
}

half4 main(float2 coord) {
    const float firstFrameOffset = 2.8;
    float t = .3 * (time + firstFrameOffset);

    // The vertex stage's responsive UV at world size 0, fit contain: -.5 to .5, y up,
    // turned by the rotation in the frame's own proportions.
    float ratio = resolution.x / resolution.y;
    float2 responsiveUV = float2(coord.x / resolution.x - .5, .5 - coord.y / resolution.y);
    responsiveUV.x *= ratio;
    float r0 = rotationDeg * PI / 180.;
    responsiveUV = float2x2(cos(r0), sin(r0), -sin(r0), cos(r0)) * responsiveUV;
    responsiveUV.x /= ratio;

    float cycleWidth = repetition;
    float edge = 0.;

    // full-fill on canvas
    float2 borderUV = responsiveUV + .5;
    float2 mask = min(borderUV, 1. - borderUV);
    float2 pixel_thickness = min(edgePx / resolution, float2(.5));
    float maskX = smoothstep(0.0, pixel_thickness.x, mask.x);
    float maskY = smoothstep(0.0, pixel_thickness.y, mask.y);
    maskX = pow(maskX, .25);
    maskY = pow(maskY, .25);
    edge = clamp(1. - maskX * maskY, 0., 1.);

    float2 uv = responsiveUV;
    if (ratio > 1.) {
        uv.y /= ratio;
    } else {
        uv.x *= ratio;
    }
    uv += .5;
    uv.y = 1. - uv.y;
    // The parallax's lean: the stripes move, the bevel stays with the frame.
    uv += pointer * float2(.05, -.05);

    cycleWidth *= 2.;

    float2 rotatedUV = uv - float2(.5);
    float angle = (-angleDeg + 70.) * PI / 180.;
    float cosA = cos(angle);
    float sinA = sin(angle);
    rotatedUV = float2(
        rotatedUV.x * cosA - rotatedUV.y * sinA,
        rotatedUV.x * sinA + rotatedUV.y * cosA
    ) + float2(.5);

    edge = mix(smoothstep(.9 - 2. * edgeAa, .9, edge), edge, smoothstep(0.0, 0.4, contourAmount));

    float opacity = 1. - smoothstep(.9 - 2. * edgeAa, .9, edge);
    edge = 1.2 * edge;

    float diagBLtoTR = rotatedUV.x - rotatedUV.y;
    float diagTLtoBR = rotatedUV.x + rotatedUV.y;

    float3 color = float3(0.);
    float3 color1 = float3(.98, 0.98, 1.);
    float3 color2 = float3(.1, .1, .1 + .1 * smoothstep(.7, 1.3, diagTLtoBR));

    float2 grad_uv = uv - .5;

    float dist = length(grad_uv + float2(0., .2 * diagBLtoTR));
    grad_uv = rotate(grad_uv, (.25 - .2 * diagBLtoTR) * PI);
    float direction = grad_uv.x;

    float bump = pow(1.8 * dist, 1.2);
    bump = 1. - bump;
    bump *= pow(max(uv.y, 0.), .3);

    float thin_strip_1_ratio = .12 / cycleWidth * (1. - .4 * bump);
    float thin_strip_2_ratio = .07 / cycleWidth * (1. + .4 * bump);
    float wide_strip_ratio = (1. - thin_strip_1_ratio - thin_strip_2_ratio);

    float thin_strip_1_width = cycleWidth * thin_strip_1_ratio;
    float thin_strip_2_width = cycleWidth * thin_strip_2_ratio;

    float noise = snoise(uv - t);

    edge += (1. - edge) * distortion * noise;

    direction += diagBLtoTR;
    float contour = 0.;
    direction -= 2. * noise * diagBLtoTR * (smoothstep(0., 1., edge) * (1.0 - smoothstep(0., 1., edge)));
    direction *= mix(1., 1. - edge, smoothstep(.5, 1., contourAmount));
    direction -= 1.7 * edge * smoothstep(.5, 1., contourAmount);
    direction += .2 * pow(contourAmount, 4.) * (1.0 - smoothstep(0., 1., edge));

    bump *= clamp(pow(max(uv.y, 0.), .1), .3, 1.);
    direction *= (.1 + (1.1 - edge) * bump);

    direction *= (.4 + .6 * (1.0 - smoothstep(.5, 1., edge)));
    direction += .18 * (smoothstep(.1, .2, uv.y) * (1.0 - smoothstep(.2, .4, uv.y)));
    direction += .03 * (smoothstep(.1, .2, 1. - uv.y) * (1.0 - smoothstep(.2, .4, 1. - uv.y)));

    direction *= (.5 + .5 * pow(max(uv.y, 0.), 2.));
    direction *= cycleWidth;
    direction -= t;

    float colorDispersion = (1. - bump);
    colorDispersion = clamp(colorDispersion, 0., 1.);
    float dispersionRed = colorDispersion;
    dispersionRed += .03 * bump * noise;
    dispersionRed += 5. * (smoothstep(-.1, .2, uv.y) * (1.0 - smoothstep(.1, .5, uv.y))) * (smoothstep(.4, .6, bump) * (1.0 - smoothstep(.4, 1., bump)));
    dispersionRed -= diagBLtoTR;

    float dispersionBlue = colorDispersion;
    dispersionBlue *= 1.3;
    dispersionBlue += (smoothstep(0., .4, uv.y) * (1.0 - smoothstep(.1, .8, uv.y))) * (smoothstep(.4, .6, bump) * (1.0 - smoothstep(.4, .8, bump)));
    dispersionBlue -= .2 * edge;

    dispersionRed *= (shiftRed / 20.);
    dispersionBlue *= (shiftBlue / 20.);

    float blur = softness / 15. + .3 * contour;
    // fwidth of a stripe, away from its wrap: how far the stripes run across a pixel.
    float fw = 1.5 * cycleWidth / resolution.y;

    float3 w = float3(thin_strip_1_width, thin_strip_2_width, wide_strip_ratio);
    w[1] -= .02 * smoothstep(.0, 1., edge + bump);
    float stripe_r = fract(direction + dispersionRed);
    float r = getColorChanges(color1.r, color2.r, stripe_r, w, blur + fw, bump, colorTint.r);
    float stripe_g = fract(direction);
    float g = getColorChanges(color1.g, color2.g, stripe_g, w, blur + fw, bump, colorTint.g);
    float stripe_b = fract(direction - dispersionBlue);
    float b = getColorChanges(color1.b, color2.b, stripe_b, w, blur + fw, bump, colorTint.b);

    color = float3(r, g, b);
    color *= opacity * opacityScale;

    color += 1. / 256. * (fract(sin(dot(.014 * coord, float2(12.9898, 78.233))) * 43758.5453123) - .5);

    // Over black, as the back is under it.
    return half4(half3(clamp(color, 0., 1.)), 1.0);
}"""

    fun create(): RuntimeShader =
        RuntimeShader(SRC).apply {
            setFloatUniform("resolution", 1f, 1f)
            setFloatUniform("time", 0f)
            setFloatUniform("contourAmount", CONTOUR)
            setFloatUniform("rotationDeg", 0f)
            setFloatUniform("pointer", 0f, 0f)
            setFloatUniform("edgePx", EDGE_PX)
            setFloatUniform("edgeAa", EDGE_AA)
        }

    /** The preset [look] stands at: one of [PRESETS], or its own colour as the tint. */
    fun preset(look: MetalConfig): Preset =
        PRESETS[look.preset] ?: Preset(look.tint, 1f)

    /** [shader] set to the back's chosen look. */
    fun look(shader: RuntimeShader, look: MetalConfig) {
        val p = preset(look)
        fun ch(shift: Int) = ((p.tint shr shift) and 0xFF) / 255f
        shader.setFloatUniform("colorTint", ch(16), ch(8), ch(0), ch(24))
        shader.setFloatUniform("opacityScale", p.opacity)
        shader.setFloatUniform("shiftRed", p.shift)
        shader.setFloatUniform("shiftBlue", p.shift)
        shader.setFloatUniform("softness", p.softness)
        shader.setFloatUniform("repetition", look.repetition)
        shader.setFloatUniform("angleDeg", look.angle.toFloat())
        shader.setFloatUniform("distortion", look.distortion)
    }

    /** Where the stripes lean, as the bends' pointer: -1 to 1 on each axis, y up. */
    fun pointer(shader: RuntimeShader, x: Float, y: Float) = shader.setFloatUniform("pointer", x, y)

    /**
     * The metal's brightness for the back's glass, all one: its bright and dark stripes come
     * to about half, under the tint's burn and the opacity. Working the stripes out on the
     * CPU would cost more than the glass gains.
     */
    fun cells(side: Int, look: MetalConfig): FloatArray {
        val p = preset(look)
        val a = ((p.tint ushr 24) and 0xFF) / 255f
        fun ch(shift: Int) = ((p.tint shr shift) and 0xFF) / 255f
        val tintLum = 0.2126f * ch(16) + 0.7152f * ch(8) + 0.0722f * ch(0)
        val v = 0.5f * p.opacity * (1f - a + a * tintLum)
        return FloatArray(side * side) { v }
    }
}
