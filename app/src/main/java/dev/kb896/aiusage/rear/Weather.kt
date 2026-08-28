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
                for (float i = 0.0; i < 2.0; i += 1.0) {
                    float scale = 7.0 + i * 6.0;
                    float2 st = uv * float2(scale, scale * 0.62) + float2(i * 3.7, 0.0);
                    float2 id = floor(st);
                    float2 f = fract(st) - 0.5;
                    float n = hash1(id + i * 17.0);
                    // DOWNWARD. A cell's y runs the way the screen's does — positive is
                    // toward the bottom — and this had the drop at 0.5 minus its progress,
                    // which walks it up the glass. Rain that rises is the one mistake an
                    // audience does not need explaining.
                    float fall = fract(time * (0.16 + n * 0.26) + n * 7.3);
                    // IT ARRIVES, it does not enter. A drop on a window appears where it
                    // lands and swells to its size in an instant; one that slides in from
                    // the edge already full-size reads as a bead on a wire. The first
                    // twelfth of its life is the landing, and the rest is the slide.
                    float pop = smoothstep(0.0, 0.08, fall);
                    float2 dp = float2((n - 0.5) * 0.55, -0.5 + fall);
                    float2 rel = f - dp;
                    // ONE length, reused. It was measured twice per drop — once squashed
                    // for the drop's shape and once plain for the direction to push in —
                    // and a square root is not the thing to buy twice in the innermost
                    // loop of a full-panel shader.
                    float len = max(length(rel), 0.0001);
                    float d = len * (1.0 + 0.35 * abs(rel.y) / len);
                    float drop = smoothstep(0.23, 0.02, d);
                    // The trail: narrow, behind the drop, and fading with distance from
                    // it. Water leaves the glass wet where it has been.
                    // BEHIND it, which is now above it. Water leaves the glass wet where
                    // it has been, and where it has been is up.
                    float trail = smoothstep(0.075, 0.0, abs(rel.x))
                        * smoothstep(0.45, 0.0, -rel.y)
                        * step(0.0, -rel.y) * 0.45;
                    // MASKED, NOT BRANCHED. Only some cells hold a drop — a drop in every
                    // one is a grid, and a grid is the one thing rain never looks like —
                    // but `continue` makes neighbouring pixels take different paths, and a
                    // fragment shader runs them in lockstep: every pixel pays for the
                    // longest path any of its neighbours took. A multiply by zero costs
                    // the same for all of them.
                    float has = step(0.55, n) * pop;
                    float m = max(drop, trail) * amount * has;
                    off += (rel / len) * m * (0.095 - i * 0.03);
                    lit += drop * amount * has * 0.38;
                }
            } else {
                for (float i = 0.0; i < 3.0; i += 1.0) {
                    float scale = 9.0 + i * 7.0;
                    float2 st = uv * scale + float2(i * 5.1, 0.0);
                    float2 id = floor(st);
                    float2 f = fract(st) - 0.5;
                    float n = hash1(id + i * 31.0);
                    float has = step(0.5, n) * edge;
                    float fall = fract(time * (0.05 + n * 0.07) + n * 4.1);
                    // Flakes wander sideways as they come down. Falling straight reads as
                    // dust rather than snow.
                    float sway = sin(time * 0.6 + n * 6.283) * 0.22;
                    // Downward, for the reason the rain is. A flake does not pop: it is
                    // already falling when it crosses the window, so it fades at both ends
                    // of its travel instead of arriving at one of them.
                    float edge = smoothstep(0.0, 0.12, fall) * smoothstep(1.0, 0.88, fall);
                    float2 rel = f - float2(sway, -0.5 + fall);
                    float d = length(rel);
                    lit += smoothstep(0.125 - i * 0.022, 0.0, d) * amount * has * (1.25 - i * 0.25);
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
