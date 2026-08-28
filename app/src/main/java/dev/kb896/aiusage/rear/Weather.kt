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
     * THREE LAYERS, at different scales and speeds. One grid of drops all the same size
     * falling at the same rate reads as a pattern, because it is one; the depth comes from
     * the near ones being larger and quicker than the far ones, which is parallax and the
     * cheapest convincing thing a shader can do.
     *
     * Snow refracts almost nothing on purpose. A flake is opaque and dry — it sits ON the
     * glass rather than wetting it — so it is drawn as a soft lit dot and the backdrop
     * behind it is left alone. Making snow refract like rain was tried first and looked
     * like rain that had forgotten how to fall.
     */
    private val WEATHER = """
        uniform shader content;
        uniform float2 size;
        uniform float time;
        uniform float mode;
        uniform float amount;

        float hash1(float2 p) {
            return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
        }

        half4 main(float2 pos) {
            float2 uv = pos / size;
            float2 off = float2(0.0, 0.0);
            float lit = 0.0;

            if (mode < 0.5) {
                for (float i = 0.0; i < 3.0; i += 1.0) {
                    float scale = 7.0 + i * 5.0;
                    float2 st = uv * float2(scale, scale * 0.62) + float2(i * 3.7, 0.0);
                    float2 id = floor(st);
                    float2 f = fract(st) - 0.5;
                    float n = hash1(id + i * 17.0);
                    // Only some cells hold a drop. A drop in every one is a grid, and a
                    // grid is the one thing rain never looks like.
                    if (n < 0.55) { continue; }
                    float fall = fract(time * (0.16 + n * 0.26) + n * 7.3);
                    float2 dp = float2((n - 0.5) * 0.55, 0.5 - fall);
                    float2 rel = f - dp;
                    float d = length(rel * float2(1.0, 1.35));
                    float drop = smoothstep(0.17, 0.02, d);
                    // The trail: narrow, behind the drop, and fading with distance from
                    // it. Water leaves the glass wet where it has been.
                    float trail = smoothstep(0.055, 0.0, abs(rel.x))
                        * smoothstep(0.45, 0.0, rel.y)
                        * step(0.0, rel.y) * 0.4;
                    float m = max(drop, trail) * amount;
                    float len = max(length(rel), 0.0001);
                    off += (rel / len) * m * (0.05 - i * 0.012);
                    lit += drop * amount * 0.22;
                }
            } else {
                for (float i = 0.0; i < 3.0; i += 1.0) {
                    float scale = 9.0 + i * 7.0;
                    float2 st = uv * scale + float2(i * 5.1, 0.0);
                    float2 id = floor(st);
                    float2 f = fract(st) - 0.5;
                    float n = hash1(id + i * 31.0);
                    if (n < 0.5) { continue; }
                    float fall = fract(time * (0.05 + n * 0.07) + n * 4.1);
                    // Flakes wander sideways as they come down. Falling straight reads as
                    // dust rather than snow.
                    float sway = sin(time * 0.6 + n * 6.283) * 0.22;
                    float2 rel = f - float2(sway, 0.5 - fall);
                    float d = length(rel);
                    lit += smoothstep(0.085 - i * 0.018, 0.0, d) * amount * (0.9 - i * 0.2);
                }
            }

            half4 c = content.eval(pos + off * size);
            return half4(c.rgb + half3(lit, lit, lit) * c.a, c.a);
        }
    """

    val shader: RuntimeShader by lazy { RuntimeShader(WEATHER) }

    /**
     * How hard it comes down.
     *
     * Deliberately short of what looks impressive in isolation. This sits behind a
     * readout somebody is trying to read at a glance from across a room, and the moment
     * the drops start competing with the numbers the panel has stopped doing its job. It
     * should be the sort of thing you notice second.
     */
    const val AMOUNT = 0.85f

    /** Seconds per full cycle of the shader's clock, kept small so floats stay precise. */
    const val PERIOD_S = 600f
}
