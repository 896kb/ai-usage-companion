package dev.kb896.aiusage.rear

import android.graphics.RuntimeShader

/**
 * Weather on the glass, in front of whatever the panel's background is.
 *
 * THE PANEL IS ALREADY A WINDOW. Everything in the glass theme is built on looking
 * THROUGH something — the cards refract what is behind them, the buttons are lenses, the
 * numerals on the clock bend the panel through their own strokes. A pane with rain
 * running down it is the same idea taken outdoors, and it needs no new vocabulary to
 * explain: water on a window is the most ordinary refraction there is.
 *
 * IT GOES ON THE BACKGROUND, not over the readout. Drops that crossed the account
 * headings would make the numbers hard to read, and the numbers are the reason the panel
 * exists. Applied to the ground — the colour and the photograph — the effect sits behind
 * every reading and in front of nothing.
 *
 * There is a second reason, and it is the better one: the account cards refract the
 * ground. Put the rain there and the cards bend the rain too, which is a window seen
 * through a window, and it costs nothing extra because the sampling was happening anyway.
 */
object Weather {

    const val OFF = "off"
    const val RAIN = "rain"
    const val SNOW = "snow"

    /**
     * DROPS ARE DRAWN AS DISPLACEMENT, not as shapes.
     *
     * A raindrop on glass is not a white blob — it is a lens. What makes it read as water
     * is that the world behind it moves: a drop magnifies and inverts a little circle of
     * whatever it is sitting on, and the eye recognises that long before it notices any
     * highlight. So each drop offsets the coordinate the backdrop is sampled at, pushing
     * outward from the drop's centre, and only then adds a faint lit edge on top.
     *
     * Drawn flat — as a colour with a shape — they look like dirt on the lens.
     *
     * TWO LAYERS FOR RAIN, THREE FOR SNOW, and the difference is measured rather than
     * chosen. Rain refracts and snow does not: rain's sample coordinate varies per pixel,
     * which is a dependent read the GPU cannot prefetch, and it does more arithmetic per
     * drop besides. Measured on the panel at three layers each, snow cost about a
     * millisecond a frame and rain cost sixteen. Snow keeps its third layer because it is
     * free; rain gives one up because it is not.
     *
     * LAYERS, at different scales and speeds. One grid of drops all the same size
     * falling at the same rate reads as a pattern, because it is one; the depth comes from
     * the near ones being larger and quicker than the far ones, which is parallax and the
     * cheapest convincing thing a shader can do.
     *
     * Snow refracts nothing on purpose. A flake is opaque and dry — it sits ON the glass
     * rather than wetting it — so the backdrop behind it is left alone and nothing comes
     * into focus. Making snow refract like rain was tried first and looked like rain that
     * had forgotten how to fall.
     *
     * THE SNOWFALL IS ADAPTED FROM LUMINE'S MULTILAYER SNOWFALL, CC0, on Godot Shaders:
     * https://godotshaders.com/shader/multilayer-snowfall-shader/ — public domain, so no
     * obligation attaches, but it is worth saying where it came from. Three ideas in it
     * are better than what was here: flakes shaped as diamonds rather than dots, a fall
     * that slants differently per layer so the layers do not read as one, and a focal
     * depth that drifts so the sharp plane moves through the fall. Fourteen layers rather
     * than its forty, which is a desktop budget; the rest is as written, retimed for a
     * panel the size of a postcard.
     */
    private val WEATHER = """
        uniform shader content;
        uniform shader blurred;
        uniform float2 size;
        uniform float time;
        uniform float mode;
        uniform float amount;

        const float3x3 NOISE = float3x3(
            13.323122, 23.5112, 21.71123,
            21.1212, 28.7312, 11.9312,
            21.8112, 14.7212, 61.3934
        );

        const float SNOW_SIZE = 0.55;
        const float SNOW_SCALE = 2.6;
        const float SNOW_SPREAD = 0.5;
        const float SNOW_WIND = 0.12;
        const float SNOW_FALL = 0.22;
        const float SNOW_WEIGHT = 1.25;

        float hash1(float2 p) {
            return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
        }

        half4 main(float2 pos) {
            float2 uv = pos / size;
            float2 off = float2(0.0, 0.0);
            float lit = 0.0;
            float wet = 0.0;

            if (mode < 0.5) {
                for (float L = 0.0; L < 2.0; L += 1.0) {
                    float2 aspect = float2(1.7, 1.0);
                    float cells = 5.0 + L * 3.0;
                    float2 g = uv * cells * aspect + float2(L * 4.3, 0.0);
                    float2 id = floor(g);
                    float2 gv = fract(g) - 0.5;
                    float n = hash1(id + L * 19.0);
                    float t = time * (0.55 + L * 0.25) + n * 6.2831;

                    // IT STICKS, THEN SLIDES. A drop on a window does not fall at a
                    // constant rate: it clings, gathers, breaks loose, runs, and catches
                    // again. A sine of a sine of a sine is a cheap way to say that — the
                    // rate is never constant and never repeats on an obvious beat, which
                    // is the whole difference between water and a bead on a wire.
                    float y = -sin(t + sin(t + sin(t) * 0.5)) * 0.42;

                    // AND IT WANDERS. As it runs it is pulled sideways by whatever it
                    // meets. The wander is strongest mid-cell and pinned at the edges, so
                    // neighbouring drops do not all lean the same way.
                    float w = uv.y * 9.0;
                    float x = (n - 0.5) * 0.75;
                    x += (0.4 - abs(x)) * sin(3.0 * w) * pow(sin(w), 6.0) * 0.45;

                    float2 dropPos = (gv - float2(x, y)) / aspect;
                    // Squashed along the direction of travel: a running drop is not round.
                    dropPos.y *= 0.85;
                    float drop = smoothstep(0.06, 0.028, length(dropPos));

                    // THE TRAIL IS BEADS, NOT A STREAK. Water left behind does not stay a
                    // smooth line; it breaks into a chain of small drops. Folding the
                    // trail's y into eight repeats per cell is what makes that chain, and
                    // it is the detail that reads as rain rather than as scratches.
                    float2 trailPos = (gv - float2(x, time * 0.25 * (1.0 + L * 0.3))) / aspect;
                    trailPos.y = (fract(trailPos.y * 8.0) - 0.5) / 8.0;
                    float trail = smoothstep(0.035, 0.012, length(trailPos));

                    // The wet path itself — where the drop has been, the glass is clear.
                    // Bounded above by the drop so the trail cannot run ahead of it.
                    float path = smoothstep(-0.05, 0.05, dropPos.y) * smoothstep(0.5, y, gv.y);
                    trail *= path;
                    float clear = path * smoothstep(0.055, 0.04, abs(dropPos.x));

                    off += (drop * dropPos + trail * trailPos) * amount * (1.0 - L * 0.25);
                    wet = max(wet, max(drop, max(clear * 0.75, trail * 0.6)) * amount);
                    lit += drop * amount * 0.30;
                }
            } else {
                wet = 1.0;
                for (float i = 0.0; i < 14.0; i += 1.0) {
                    float layer = 1.0 + i * 0.5 / (SNOW_SIZE * 2.0);
                    float2 sc = uv * layer * SNOW_SCALE;
                    float2 mv = float2(
                        sc.y * (SNOW_SPREAD * fract(i * 7.238917) - SNOW_SPREAD * 0.5)
                            - SNOW_WIND * time * 0.5,
                        -time * SNOW_FALL / (1.0 + i * 0.015)
                    );
                    float2 fc = sc + mv;
                    float3 ni = float3(floor(fc), 31.189 + i);
                    float3 nv = floor(ni) * 0.00001 + fract(ni);
                    float3 rnd = fract((31415.9 + nv) / fract(NOISE * nv));
                    float2 sh = abs(fract(fc) - 0.5 + 0.9 * rnd.xy - 0.45);
                    sh += 0.01 * abs(2.0 * fract(10.0 * fc.yx) - 1.0);
                    float focal = 5.0 * sin(time * 0.1);
                    float soft = 0.005 + 0.05 * min(0.5 * abs(i - 5.0 - focal), 1.0);
                    float v = 0.6 * max(sh.x - sh.y, sh.x + sh.y) + max(sh.x, sh.y) - 0.01;
                    lit += smoothstep(soft, -soft, v)
                        * (rnd.x / (1.0 + 0.01 * i)) * amount * SNOW_WEIGHT;
                }
                lit = clamp(lit, 0.0, 1.4);
            }

            // THE PANE IS OUT OF FOCUS AND THE WATER IS NOT. Looking through a wet window
            // the glass is soft — you are not focused on it — and every drop and every
            // bead of its trail is a small sharp lens. That contrast is the effect; the
            // displacement alone only smudges. The blur is prepared once when the
            // background changes and handed in, so it costs one lookup rather than many.
            float water = clamp(wet, 0.0, 1.0);
            half4 sharp = content.eval(pos + off * size);
            half4 soft = blurred.eval(pos);
            half4 c = mix(soft, sharp, half(water));
            return half4(c.rgb + half3(lit, lit, lit) * c.a, c.a);
        }
    """

    val shader: RuntimeShader by lazy { RuntimeShader(WEATHER) }

    /**
     * How hard it comes down.
     *
     * Tuned UP from where it started. The first pass was deliberately restrained, on the
     * argument that this sits behind a readout somebody reads at a glance and must not
     * compete with the numbers — which is still true, and was still the wrong starting
     * point: at 0.85, with drops a fifth the size and a third the bend they have now, the
     * effect was not restrained, it was invisible. Something nobody notices is not subtle.
     *
     * It should be the thing you see second. Not the thing you have to be told is there.
     */
    const val AMOUNT = 1.0f

    /** Seconds per full cycle of the shader's clock, kept small so floats stay precise. */
    const val PERIOD_S = 600f
}
