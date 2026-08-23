package dev.kb896.aiusage.rear

import android.graphics.RuntimeShader

/**
 * The GLASS theme's refraction, as shaders.
 *
 * ---------------------------------------------------------------------------------
 * The AGSL below is from AndroidLiquidGlass (the `backdrop` library) by Kyant0,
 * https://github.com/Kyant0/AndroidLiquidGlass, used under the Apache License 2.0:
 *
 *   Copyright 2025 Kyant
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 * ---------------------------------------------------------------------------------
 *
 * TAKEN AS SHADERS RATHER THAN AS THE LIBRARY, and that is not a shortcut. The library
 * is Compose Multiplatform; this app is Views and Canvas from top to bottom, with a
 * 6000-line onDraw that positions everything by hand against a 904x572 panel. Adding
 * Compose to reach two shader strings would be the tail wagging a fairly large dog. The
 * effect itself is self-contained AGSL and ports across untouched — which is the whole
 * argument for the effect having been written that way.
 *
 * HOW IT WORKS, because it is not a blur and reads oddly if you expect one. The shader
 * is handed the BACKDROP as `content` and, for every pixel inside a rounded rectangle,
 * works out how far it is from the edge using a signed distance field. Near the edge it
 * bends the sample position along the edge's own gradient, so what is behind the pane is
 * displaced outward the way it would be through a thick lens. In the middle, where the
 * distance exceeds `refractionHeight`, it samples straight through and the pane is
 * simply transparent. All of the effect therefore lives in a band around the border,
 * which is exactly where a real piece of glass shows you it is there.
 *
 * WHICH MEANS IT NEEDS SOMETHING BEHIND IT. Over flat black the refraction is invisible —
 * bending nothing gives you nothing — so a glass pane on this panel also carries a tint
 * and a lit edge, and the panes are placed where there is something to bend: the notices
 * over the readout, the slider over the bars, the charging cell over the record.
 */
object Glass {

    /**
     * Shared by both shaders: the distance to a rounded rectangle, and its gradient.
     *
     * The gradient is what the refraction bends ALONG and what the highlight measures its
     * angle against, so the two agree about where the edge is by construction.
     */
    private const val SDF = """
float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}"""

    /** The lens itself: bend the backdrop near the edge, pass it through in the middle. */
    private const val REFRACTION = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;

$SDF

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(coord, cornerRadii);

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));

    float2 refractedCoord = coord + d * grad;
    return content.eval(refractedCoord);
}"""

    /**
     * The lens again, split into colours.
     *
     * THE HELD LOOK. In the reference component a thumb at rest is blurred and barely
     * lensed; pressed and held, the blur goes to nothing and the lens comes fully on WITH
     * chromatic aberration — the edges split into red through purple the way a real
     * bevelled edge does. That is the state worth borrowing here, because these thumbs are
     * never actually held: they are readings, and a reading that permanently looked like
     * it was mid-gesture would be a lie, while one that permanently looks like the most
     * solid state of the control is just what the control is.
     *
     * The bands are sampled at fractions of one displacement rather than as a real
     * spectrum, which is the whole trick: seven taps, weighted so they sum back to white
     * in the middle and separate only where the displacement is large — at the edges.
     */
    private const val DISPERSION = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float chromaticAberration;

$SDF

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(coord, cornerRadii);

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));

    float2 refractedCoord = coord + d * grad;
    float dispersionIntensity = chromaticAberration * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));
    float2 dispersedCoord = d * grad * dispersionIntensity;

    half4 color = half4(0.0);

    half4 red = content.eval(refractedCoord + dispersedCoord);
    color.r += red.r / 3.5;
    color.a += red.a / 7.0;

    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));
    color.r += orange.r / 3.5;
    color.g += orange.g / 7.0;
    color.a += orange.a / 7.0;

    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));
    color.r += yellow.r / 3.5;
    color.g += yellow.g / 3.5;
    color.a += yellow.a / 7.0;

    half4 green = content.eval(refractedCoord);
    color.g += green.g / 3.5;
    color.a += green.a / 7.0;

    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));
    color.g += cyan.g / 3.5;
    color.b += cyan.b / 3.0;
    color.a += cyan.a / 7.0;

    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));
    color.b += blue.b / 3.0;
    color.a += blue.a / 7.0;

    half4 purple = content.eval(refractedCoord - dispersedCoord);
    color.r += purple.r / 7.0;
    color.b += purple.b / 3.0;
    color.a += purple.a / 7.0;

    return color;
}"""

    /**
     * The ambient rim: lit on one side, dark on the other, no colour of its own.
     *
     * What the reference puts on a thumb while it is held, where the flat white rim goes
     * on a card. It is the difference between a sheet of glass, which has an edge, and a
     * bevelled object, which has a lit side and a shaded one.
     */
    private const val AMBIENT = """
uniform float2 size;
uniform float4 cornerRadii;
uniform float angle;
uniform float falloff;

$SDF

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = coord - halfSize;
    float radius = radiusAt(coord, cornerRadii);

    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);
    float2 normal = float2(cos(angle), sin(angle));
    float d = dot(grad, normal);
    float intensity = pow(abs(d), falloff);
    float t = step(0.0, d);
    return half4(t, t, t, 1.0) * intensity;
}"""

    /**
     * The lit edge: a rim that catches the light from one direction.
     *
     * Drawn OVER the pane rather than being part of the refraction, because it is not
     * something the backdrop does — it is the pane's own surface catching a light that
     * does not otherwise exist on this panel. Without it a glass element over a dark
     * backdrop has no border at all, and reads as a smudge rather than as an object.
     */
    private const val HIGHLIGHT = """
uniform float2 size;
uniform float4 cornerRadii;
layout(color) uniform half4 color;
uniform float angle;
uniform float falloff;

$SDF

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = coord - halfSize;
    float radius = radiusAt(coord, cornerRadii);

    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);
    float2 normal = float2(cos(angle), sin(angle));
    float d = dot(grad, normal);
    float intensity = pow(abs(d), falloff);
    return color * intensity;
}"""

    /**
     * Glass in the shape of something, rather than in the shape of a rectangle.
     *
     * The panes elsewhere are rounded rectangles and know their edges from arithmetic.
     * This one is handed a TEXTURE that says, per pixel, how deep inside the shape it is
     * and which way the surface faces — so the shape can be anything, and here it is the
     * title's letterforms. The type is not painted at all: what you see is the panel
     * behind it, bent at the strokes and lit down one side.
     *
     * From AndroidLiquidGlass's catalog (its lock screen draws a clock this way), under
     * the same Apache 2.0 licence as the shaders above.
     */
    private const val SDF_TEXT = """
uniform shader content;
uniform shader sdfTex;

uniform float2 size;
uniform float2 sdfTexSize;
uniform float refractionHeight;
uniform float lightAngle;

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    half2 p = coord / size * sdfTexSize;
    if (p.x < 0.0 || p.y < 0.0 || p.x >= sdfTexSize.x || p.y >= sdfTexSize.y) {
        return half4(0.0);
    }
    half4 v = sdfTex.eval(p);
    float sd = v.r * 2.0 - 1.0;
    v.a = smoothstep(0.5, 1.0, v.a);
    if (v.a <= 0.0) {
        return half4(0.0);
    }
    if (v.a < 1.0) {
        sd = 0.0;
    }
    float2 normal = normalize(v.gb * 2.0 - 1.0);

    float intensity = circleMap(1.0 - min(1.0, -sd * 1.5));
    float2 refractedCoord = coord - intensity * refractionHeight * normal;

    half4 color = content.eval(refractedCoord) * v.a;
    float2 lightDir = float2(cos(lightAngle * 3.1415926 / 180.0), sin(lightAngle * 3.1415926 / 180.0));
    float bevelIntensity = clamp(dot(normal, lightDir), 0.0, 1.0);
    color.rgb *= 1.0 + 0.5 * intensity * bevelIntensity;
    bevelIntensity = clamp(dot(normal, -lightDir), 0.0, 1.0);
    color.rgb *= 1.0 + 0.5 * bevelIntensity * min(1.0, smoothstep(1.0, 0.0, abs(intensity - 0.25) * 6.0));
    return color;
}"""

    /**
     * Compiled once and reused.
     *
     * A RuntimeShader is a compile; doing it per frame per pane would put a shader
     * compiler on the draw path of an always-on display. Uniforms are set per use, which
     * is cheap, so one instance of each serves every pane.
     */
    val refraction: RuntimeShader by lazy { RuntimeShader(REFRACTION) }
    val dispersion: RuntimeShader by lazy { RuntimeShader(DISPERSION) }
    val highlight: RuntimeShader by lazy { RuntimeShader(HIGHLIGHT) }
    val ambient: RuntimeShader by lazy { RuntimeShader(AMBIENT) }
    val sdfText: RuntimeShader by lazy { RuntimeShader(SDF_TEXT) }

    // -- the numbers, in one place ----------------------------------------------------

    // -- the lens, at the example's own numbers ---------------------------------------
    //
    // Taken from the reference rather than tuned by eye, and the two components it draws
    // these from agree closely enough to be one set. Its slider thumb, PRESSED, is
    // lens(10dp, 14dp, chromaticAberration = true) on a 40x24dp thumb — 42% and 58% of
    // its short side. Its tab bar's container is lens(24dp, 24dp). So: the same fractions
    // for everything, capped where a container would otherwise take more than the
    // reference gives one.

    /** How deep into a pane the bending reaches, as a fraction of its short side. */
    const val HEIGHT = 0.42f

    /** And no deeper than the reference's container lens, in panel units. */
    const val HEIGHT_MAX = 21f

    /** How far the backdrop is displaced at the edge, likewise as a fraction. */
    const val AMOUNT = 0.58f

    /** And no further than the same. */
    const val AMOUNT_MAX = 21f

    /**
     * How much the edges split into colour.
     *
     * One, which is what `chromaticAberration = true` sets. It was 0.7 for a while on the
     * reasoning that full aberration might read as a fault on a large pane; the reference
     * does not think so, and it is the one that has been looked at.
     */
    const val ABERRATION = 1f

    /**
     * How hard a BUTTON splits light, which is a little less.
     *
     * Not a second opinion about the material — the same reason a bar and a card share
     * one figure still holds. It is about what is behind them: a button sits over the
     * record, which is the highest-contrast thing on this panel, and full dispersion on a
     * hard black-and-white edge is a rainbow rather than a bevel. The readings lie over
     * flat colour and never show it.
     */
    const val BUTTON_ABERRATION = 0.65f

    /**
     * How much the bend follows the pane's centre rather than its nearest edge.
     *
     * ZERO, because the reference's `depthEffect` defaults to false and neither the
     * slider nor the tab bar turns it on. It was 0.28 here on the theory that a long thin
     * pane needs help not to look like two curved ends with a flat strip between them —
     * which is a real effect, and not one the component this is modelled on accepts.
     */
    const val DEPTH = 0f

    /**
     * Where the light comes from, in radians.
     *
     * Forty-five degrees, which is what both of the reference's highlight styles use.
     */
    const val LIGHT_ANGLE = 0.7853982f

    /** How tightly the rim gathers. One, again the reference's own. */
    const val LIGHT_FALLOFF = 1f

    /** The flat rim's colour: Highlight.Default is white at a half. */
    const val LIGHT_COLOR = 0x80FFFFFF.toInt()

    /**
     * How thick a rim is drawn, in panel units.
     *
     * The reference's Highlight is 0.5dp and its thumb takes two thirds of that. Both are
     * hairlines; at this panel's scale that is about one unit.
     */
    const val RIM_WIDTH = 1.4f

    /** A thumb's rim, thinner again — the reference divides its width by one and a half. */
    const val THUMB_RIM_WIDTH = 0.9f

    /**
     * The pane's own tint, over the refracted backdrop.
     *
     * Glass is not clear, and on a panel that is largely black a wholly clear pane is an
     * invisible one. This is what gives a notification a body to read text against.
     */
    const val TINT = 0x26FFFFFF

    // NO BLUR CONSTANT, and that is deliberate rather than an omission. The glass here is
    // clear: what is behind a pane is bent, never softened. A blur is what a surface does
    // when you cannot see through it, and being able to see through it is the point.

    /**
     * The tint behind a pressable glyph: none, the same as a thumb's.
     *
     * A button and a held thumb are the same object here — a small piece of glass with
     * something on it — so they are made of the same nothing.
     */
    const val DISC_TINT = 0x00000000

    /**
     * How much of a bar or a ring's own colour survives once it is glass.
     *
     * A reading has to stay readable. Glass that took the accent down to a whisper would
     * be a panel that looked better and told you less, which is the wrong trade for the
     * one thing on here that is actually information.
     */
    const val READING_ALPHA = 0xD8

    /** How wide the sheen along a ring's outer edge is drawn, in panel units. */
    const val SHEEN_WIDTH = 3f

    /**
     * A thumb's body: NONE.
     *
     * This is the reference's pressed state, and it is emphatic about it — the thumb's
     * surface is drawn as `White.copy(alpha = 1 - pressProgress)`, so a thumb at rest is
     * solid white and a thumb held is not tinted at all. Pressed, it is purely a lens
     * with a rim: what you see through it IS the thumb.
     *
     * Which is also why the tint that was here read wrong. A white body at 55% turns the
     * lens into a frosted lozenge — the one thing the reference stops doing the moment
     * you touch it.
     */
    const val THUMB_TINT = 0x00000000

    /**
     * How present the ambient rim is. The reference's Highlight.Ambient intensity.
     *
     * Thumbs wear the ambient rim — lit on one side, shaded on the other, no colour of
     * its own — where the larger panes take the flat white edge. That is the difference
     * between a bevelled object and a sheet of glass, and a thumb is an object.
     */
    const val AMBIENT_ALPHA = 0.38f

    /**
     * The charging cell's own body.
     *
     * Fainter than a card's, because this pane has something in it. A tint heavy enough
     * to read type against would also be heavy enough to mute the liquid, and the liquid
     * is the whole of what the cell is for.
     */
    const val CHARGE_TINT = 0x1AFFFFFF

    // -- glass type --------------------------------------------------------------------

    /**
     * How far into a stroke the bevel reaches, in panel units.
     *
     * The whole effect lives in this number. Too small and the letters are a window with
     * no thickness; too large and every stroke is bevel with no clear middle, which on
     * type at forty units is unreadable. About a fifth of the cap height.
     */
    const val TEXT_SPREAD = 9f

    /** How far the backdrop is displaced under a stroke. */
    const val TEXT_REFRACTION = 14f

    /** Where the light is, in degrees. The same forty-five as everything else here. */
    const val TEXT_LIGHT = 45f

    /**
     * A wash over the letters, so type on a dark panel is still type.
     *
     * The reference lays white at a quarter over its clock for the same reason: a lens
     * over black is black, and a title nobody can read is not a title. This is the least
     * that keeps it legible while leaving the bend visible.
     */
    const val TEXT_WASH = 0x40FFFFFF
}
