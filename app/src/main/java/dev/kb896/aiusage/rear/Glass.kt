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

    // -- the numbers, in one place ----------------------------------------------------

    /**
     * How deep into the pane the bending reaches, as a fraction of the SHORT side.
     *
     * A fraction rather than a fixed depth so a notification card and a slider knob look
     * like the same material. Too large and the whole pane is lens with no clear middle,
     * which reads as a fisheye; too small and the edge is a hairline.
     */
    const val REFRACTION_HEIGHT = 0.42f

    /** How far the backdrop is displaced at the very edge, in panel units. */
    const val REFRACTION_AMOUNT = -14f

    /**
     * How much the bend follows the pane's centre rather than its nearest edge.
     *
     * Zero is a pure edge lens. Above zero the whole pane behaves a little like a dome,
     * which is what stops a long thin element — the slider is 486 units by 70 — from
     * looking like two separate curved ends with a flat strip between them.
     */
    const val DEPTH = 0.28f

    /** Where the light comes from, in radians. Up and slightly left, as it always is. */
    const val LIGHT_ANGLE = -2.2f

    /** How tightly the rim gathers. Higher is a narrower, brighter edge. */
    const val LIGHT_FALLOFF = 2.4f

    /** The rim's colour before it is modulated by the angle. */
    const val LIGHT_COLOR = 0x59FFFFFF.toInt()

    /**
     * The pane's own tint, over the refracted backdrop.
     *
     * Glass is not clear, and on a panel that is largely black a wholly clear pane is an
     * invisible one. This is what gives a notification a body to read text against.
     */
    const val TINT = 0x26FFFFFF

    /** How far the backdrop is blurred before it is bent, in panel units. */
    const val BLUR = 6f

    /** How thick the lit rim is drawn, in panel units. */
    const val RIM_WIDTH = 2f

    /**
     * The tint behind a pressable glyph.
     *
     * Lighter than a card's. A disc is small and mostly edge, so at the card's opacity it
     * reads as a solid button with a glyph punched out of it rather than as a lens the
     * glyph is sitting on.
     */
    const val DISC_TINT = 0x1FFFFFFF

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
     * A slider thumb's body, brighter than a card's.
     *
     * A thumb is a small solid object sitting ON a reading, not a sheet lying over the
     * panel — at a card's opacity it disappears into the fill it is meant to be riding.
     */
    const val THUMB_TINT = 0x8CFFFFFF.toInt()

    // -- the held look ----------------------------------------------------------------
    //
    // The reference's thumb, pressed: no blur at all, the lens fully on with chromatic
    // aberration, and an ambient rim. Its numbers are 10dp of refraction height and 14dp
    // of amount on a 40x24 thumb, which is 42% and 58% of the thumb's height — stated
    // that way here so a thumb of any size is made of the same glass.

    /** How deep the lens reaches into a thumb, as a fraction of its short side. */
    const val THUMB_HEIGHT = 0.42f

    /** How far the backdrop is displaced at a thumb's edge, likewise. */
    const val THUMB_AMOUNT = 0.58f

    /** How strongly a thumb's edges split into colour. One is the reference's own. */
    const val THUMB_ABERRATION = 1f

    /** How present the ambient rim is. The reference's Highlight.Ambient intensity. */
    const val AMBIENT_ALPHA = 0.38f

    /**
     * The charging cell's own body.
     *
     * Fainter than a card's, because this pane has something in it. A tint heavy enough
     * to read type against would also be heavy enough to mute the liquid, and the liquid
     * is the whole of what the cell is for.
     */
    const val CHARGE_TINT = 0x1AFFFFFF
}
