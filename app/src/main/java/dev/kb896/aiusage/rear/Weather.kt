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
        uniform shader ground;
        uniform float2 size;
        uniform float time;
        uniform float mode;
        uniform float amount;

        const float3x3 NOISE = float3x3(
            13.323122, 23.5112, 21.71123,
            21.1212, 28.7312, 11.9312,
            21.8112, 14.7212, 61.3934
        );
        // HOW HARD THE WATER BENDS WHAT IS UNDER IT. This is most of how visible a drop
        // is: there is no blur behind it any more — a downscaled one went blocky and was
        // taken out — so the bending has to carry the whole illusion on its own.
        // Halved with the drops. A smaller lens bends less: kept where it was, the
        // displacement would have been reaching further than the drop it belongs to,
        // which smears rather than refracts.
        const float REFRACT = 30.0;

        // How much of a fall's cycle a wet path survives. The cycle runs about four and a
        // half seconds, so this is roughly two.
        const float TRAIL_LIFE = 0.45;
        const float SNOW_SIZE = 0.55;
        const float SNOW_SCALE = 2.6;
        const float SNOW_SPREAD = 0.5;
        const float SNOW_WIND = 0.12;
        const float SNOW_FALL = 0.22;
        const float SNOW_WEIGHT = 1.25;

        float hash1(float2 p) {
            return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
        }

        float3 hash3(float n) {
            return fract(sin(float3(n, n + 1.0, n + 2.0)) * float3(43758.5453, 22578.1459, 19642.3490));
        }

        // A LIFE, not a fade. Rises over the first [rise] of it and falls over the rest —
        // so a small rise is the thing a drop does when it lands: there, at once, and then
        // a long slow going. One function, two behaviours, chosen by one number.
        float life(float rise, float t) {
            return smoothstep(0.0, rise, t) * smoothstep(1.0, rise, t);
        }

        // THE ONES THAT DO NOT GO ANYWHERE. Most of what is on a window is not moving: it
        // landed, it is sitting there, and it will dry or be taken by a passing drop. They
        // pop in fast, hold, and fade — and every third one barely shows at all, because a
        // window is not evenly wet.
        float clinging(float2 uv, float t) {
            float2 g = uv * 21.0;
            float2 id = floor(g);
            float2 f = fract(g) - 0.5;
            float3 n = hash3(id.x * 107.45 + id.y * 3543.654);
            float2 at = (n.xy - 0.5) * 0.7;
            float d = length(f - at);
            return smoothstep(0.15, 0.0, d) * fract(n.z * 10.0) * life(0.06, fract(t + n.z));
        }

        // THE ONES THAT RUN. Returns how much water is here, and how much of it is trail.
        float2 running(float2 uv, float t) {
            float2 UV = uv;
            // NO SCROLLING GRID. The whole field slid at one rate, and that one rate was
            // every drop's speed. The per-column offset underneath it — which the comment
            // here used to claim gave them different speeds — staggers when a drop STARTS,
            // not how fast it goes, so they arrived in step however much the phases were
            // shuffled. Uniform motion is the thing that gives a shader away, and it was
            // baked in at the top of this function.
            //
            // The lanes are still staggered, just not by the clock: a fixed offset per
            // column, so the cells do not line up as rows.
            float2 grid = float2(8.0, 1.5);
            uv.y += hash1(float2(floor(uv.x * grid.x), 0.0)) * 0.7;
            float2 id = floor(uv * grid);
            float3 n = hash3(id.x * 35.2 + id.y * 2376.1);
            float2 st = fract(uv * grid) - float2(0.5, 0.0);

            // AND IT WANDERS ON THE WAY DOWN. A drop does not run straight: it catches,
            // slips sideways, and carries on. The wiggle is a sine of a sine, so the
            // sideways bumps do not land on a regular beat, and it is scaled per drop so
            // no two wander alike — and pinned at the edges so none leaves its column.
            float x = n.x - 0.5;
            float y = UV.y * 20.0;
            // WIDER WANDER. This was barely a tenth of a cell — true to the reference and
            // invisible on a panel this size. A drop catches on something, slips sideways,
            // and carries on; if that cannot be seen it may as well be falling straight.
            x += sin(y + sin(y)) * (0.5 - abs(x)) * (n.z - 0.5) * 2.4;
            x *= 0.7;

            // MONOTONIC, so it can only ever go down. The position came from the same
            // rise-and-fall curve the fading uses — and a curve that comes back DOES come
            // back: for the last sixth of every cycle each drop climbed to where it
            // started. Fast enough to look like a reset most of the time, which is why it
            // read as "some of them float up" rather than as all of them doing it.
            //
            // A plain ramp instead, with the arrival and departure handled by fading
            // rather than by moving. In flipped space the top of a cell is 1, so falling
            // is counting down.
            // ITS OWN SPEED, spanning about eight to one — the slowest barely creeping,
            // the fastest crossing while you watch it. SQUARED, so the spread leans slow:
            // most water on a window is hardly moving and the occasional one runs. A
            // linear spread puts too many in the middle, and a field of drops all going
            // at roughly one speed is what this was trying to escape.
            float speed = 0.38 + n.y * n.y * 2.9;
            float ti = fract(t * speed + n.z);

            // IT SLOWS WHERE IT SLIPS. A drop does not wander for no reason — it catches
            // on something, hangs, tears sideways off it and drops again. So the sideways
            // movement and the loss of speed are the SAME event, and driving both from one
            // wobble is what makes it read as a cause rather than as two effects.
            //
            // The amplitude is bounded by the maths, not by taste: the vertical position
            // must never decrease, so the wobble's slope has to stay under the ramp's. At
            // three cycles a fall that puts the ceiling near 0.053, and 0.045 sits under
            // it with room to spare.
            float wob = sin(ti * 18.85 + n.z * 6.2831);
            y = 1.0 - (ti + 0.045 * wob);
            float vis = smoothstep(0.0, 0.07, ti) * smoothstep(1.0, 0.90, ti);

            float d = length((st - float2(x, y)) * float2(1.0, 6.0));
            float head = smoothstep(0.2, 0.0, d) * vis;

            // The trail narrows behind the drop and only exists above it.
            float r = sqrt(smoothstep(1.0, y, st.y));
            float cd = abs(st.x - x);
            // Wider and wetter than it was. The trail is the part that lingers after the
            // drop has gone, so it is most of what a wet window actually looks like, and
            // it was drawn as though it were an afterthought of the drop.
            float trail = smoothstep(0.15 * r, 0.08 * r * r, cd)
                * smoothstep(-0.02, 0.02, st.y - y) * r * r * vis * 1.35;

            // THE PATTERN IT LEAVES. Water breaks up behind a runner into a chain of small
            // beads; a smooth streak reads as a scratch. Folding the height into repeats is
            // what makes the chain.
            // MORE OF THEM, and smaller. What a runner leaves is not a stripe, it is a
            // broken line of beads with gaps — and the gaps are what say it is water
            // rather than a scratch. Ten repeats over a cell was a dotted line; fourteen
            // with the drop's own wander threaded through reads as a path.
            float by = fract(UV.y * 14.0) + (st.y - 0.5);
            float beads = smoothstep(0.15, 0.0, length(st - float2(x, by)))
                * r * smoothstep(-0.02, 0.02, st.y - y);

            // IT STAYS WET BEHIND THEM. A drop leaves a path and the path takes a while
            // to go — but the trail was tied to where the drop is NOW, so it vanished the
            // instant the drop reached the bottom, taking the whole streak with it.
            //
            // A shader has no memory, and does not need one here: how long ago the drop
            // passed THIS pixel is just how far above it the pixel is, in cycle units. One
            // subtraction gives every pixel its own age, and fading over TRAIL_LIFE of a
            // cycle is a trail that lasts about two seconds.
            float since = max(0.0, st.y - y);
            float linger = smoothstep(TRAIL_LIFE, 0.0, since);
            return float2(head + beads * linger, trail * linger);
        }

        // Everything on the glass at this point: coverage, and trail.
        float2 water(float2 uv, float t) {
            float still = clinging(uv, t);
            float2 a = running(uv, t);
            float2 b = running(uv * 1.85 + float2(3.1, 0.0), t * 1.17);
            float c = still + a.x + b.x;
            // A LOWER SILL, so the faint ones survive. This asked coverage to reach 0.3
            // before it counted for anything and 1.0 before it counted fully — which threw
            // away every drop that was merely present, and most of them are merely
            // present. What was left was the handful of direct hits, which is why there
            // seemed to be so little water.
            return float2(smoothstep(0.16, 0.85, c), max(a.y, b.y));
        }

        half4 main(float2 pos) {
            float2 uv = pos / size;
            float lit = 0.0;
            float dim = 0.0;
            float2 off = float2(0.0, 0.0);

            if (mode < 0.5) {
                float t = time * 0.22;
                // Y IS UPSIDE DOWN BETWEEN THE TWO WORLDS. Every reference for this is
                // written in GL, where the origin is bottom-left and y counts upward.
                // AGSL puts the origin top-left and counts down, so the maths was carried
                // over inverted and a fall came out as a rise. Flipped once here, so
                // everything downstream reads the way it was written.
                float2 wuv = float2(uv.x, 1.0 - uv.y);
                float2 c = water(wuv, t);
                // THE SURFACE OF THE WATER, BY DIFFERENCE. A drop bends light along its
                // slope, and the slope is how fast coverage changes — so the normal is the
                // gradient of the coverage field, taken by sampling it a step to each side.
                // It is three evaluations rather than one, and it is what makes the drops
                // read as rounded rather than as holes.
                float e = 1.0 / max(size.x, 1.0);
                float cx = water(wuv + float2(e, 0.0), t).x;
                float cy = water(wuv + float2(0.0, e), t).x;
                // The y gradient is measured in flipped space, so it is negated on the way
                // out — or the drops would bend light upward while falling down.
                float2 nrm = float2(cx - c.x, -(cy - c.x));
                off = nrm * REFRACT;
                // LIT BY A DIRECTION, NOT BY HOW MUCH WATER IS THERE.
                //
                // Every drop looked like every other drop because the light was coverage
                // times a constant: the middle of a drop is the middle of a drop, so they
                // all came out the same shade of white with the same shape. Nothing in
                // that says which way any surface is facing, which is the only thing that
                // makes one bead look unlike its neighbour.
                //
                // The gradient computed above IS the surface normal — it is already being
                // used to decide which way to bend the light — so the highlight is what it
                // costs to ask where that normal points. A drop's left shoulder catches
                // the light and its right does not; a fat slow one and a thin quick one
                // face differently and now look it.
                float slope = length(nrm) * 260.0;
                float2 L = float2(-0.55, -0.83);
                float ndl = dot(normalize(nrm + float2(1e-6, 1e-6)), L);
                // Tight power, so it is a glint rather than a wash.
                float spec = pow(max(ndl, 0.0), 5.0) * smoothstep(0.03, 0.25, slope);
                // And no two regions of the glass are lit quite alike.
                float vary = 0.55 + hash1(floor(uv * 7.0)) * 0.9;
                lit = (spec * 1.15 * vary + c.x * 0.05) * amount;
                // THE FAR SIDE GOES DARK. A steep edge throws light away from the eye, and
                // a drop with a bright side and no dark side is a sticker.
                dim = smoothstep(0.08, 0.45, slope) * 0.34 * amount
                    * (1.0 - max(ndl, 0.0));
            } else {
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

            half4 c = ground.eval(pos + off * amount);
            half3 rgb = c.rgb * half(1.0 - dim) + half3(lit, lit, lit) * c.a;
            return half4(rgb, c.a);
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
