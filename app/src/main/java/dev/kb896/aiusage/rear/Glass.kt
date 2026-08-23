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
    val highlight: RuntimeShader by lazy { RuntimeShader(HIGHLIGHT) }

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
}
