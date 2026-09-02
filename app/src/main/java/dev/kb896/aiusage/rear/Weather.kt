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
    /**
     * The uniforms and the water field, shared by both shaders below.
     *
     * TWO SHADERS, ONE FIELD. The drops and the mist are drawn in different passes — the
     * drops behind the readout where they bend the background, the mist in front of
     * everything where nothing samples it — but they have to agree exactly about where the
     * water is, or the glass would be wiped clear somewhere the drops are not. Sharing the
     * source is how they agree; it is the same function compiled into both.
     */
    private val COMMON = """
        uniform shader ground;
        uniform float2 size;
        uniform float time;
        uniform float mode;
        uniform float amount;
        // THE CAMERAS. Centre and radius in panel pixels, y counted from the top, one
        // per lens. The water goes round them — see deflect — and nothing is lit or
        // bent on top of them.
        uniform float3 lensA;
        uniform float3 lensB;

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

        /**
         * How much of a cycle the glass stays clear before misting over again.
         *
         * A cycle is about four and a half seconds at the middle speed, so two thirds of
         * one is roughly the three seconds a wiped window takes to fog back up.
         */
        const float FOG_RETURN = 0.66;
        /**
         * How thick the mist gets where nothing has wiped it.
         *
         * Back on, now that it is drawn in front of the panes instead of underneath them
         * — see MIST for why that is what made the difference.
         */
        const float FOG = 0.26;
        // THE REFERENCE CELL, in pixels: what one column of the near layer measures.
        // Every size in the water is in units of the cell, and a cell is this divided
        // by the layer's scale — so the far layer is the same water, seen further off.
        // Eight across and one and a half down, as the grid used to be written.
        const float CELL_W = 113.0;
        const float CELL_H = 381.3;
        // How much further out than the lens a running drop is steered, centre to rim.
        // A drop's own radius is about twenty-three in pixels, so this puts its inner
        // edge on the lens's edge — riding the ring that is drawn there.
        const float CLEAR = 20.0;
        // How far to either side the going-round is felt. Wide on purpose: the columns
        // beside a lens are squeezed to make room, and squeezing over a short distance
        // piles the drops up at the rim.
        const float REACH = 450.0;
        const float SNOW_SIZE = 0.55;
        const float SNOW_SCALE = 2.6;
        const float SNOW_SPREAD = 0.5;
        const float SNOW_WIND = 0.12;
        const float SNOW_FALL = 0.22;
        const float SNOW_WEIGHT = 1.25;

        float hash1(float2 p) {
            return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
        }

        // A HASH THAT DOES NOT FALL APART. hash1 is a sine folded to its fraction, which
        // is fine for scattering drops around a grid and is not fine here: at larger
        // coordinates neighbouring cells start returning correlated values, and correlated
        // value noise is not noise, it is blocks. Over a fog that is being wiped by moving
        // water those blocks move too, which is exactly what was seen.
        //
        // This one is all multiplies and fractions with no trigonometry, so it has no
        // precision cliff to fall off.
        float hash2(float2 p) {
            float3 q = fract(float3(p.x, p.y, p.x) * 0.1031);
            q += dot(q, float3(q.y, q.z, q.x) + 33.33);
            return fract((q.x + q.y) * q.z);
        }

        // Smooth value noise, for the mottle in the condensation. Four corners and a
        // cubic blend — the cheapest thing that does not look like static.
        float vnoise(float2 p) {
            float2 i = floor(p);
            float2 f = fract(p);
            f = f * f * (3.0 - 2.0 * f);
            float a = hash2(i);
            float b = hash2(i + float2(1.0, 0.0));
            float c = hash2(i + float2(0.0, 1.0));
            float d = hash2(i + float2(1.0, 1.0));
            return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
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
            // NOT ON THE CAMERAS, decided once per drop from where its centre lands, so
            // a drop is either there or it is not. Cut off pixel by pixel instead, a
            // drop would have an edge, an edge is a slope, and a slope catches the
            // light — as a bright ring drawn round the lens.
            float2 c = ((id + 0.5 + at) / 21.0) * size;
            float keep = smoothstep(-4.0, 4.0,
                    length(c - float2(lensA.x, size.y - lensA.y)) - lensA.z - 10.0)
                * smoothstep(-4.0, 4.0,
                    length(c - float2(lensB.x, size.y - lensB.y)) - lensB.z - 10.0);
            float d = length(f - at);
            return smoothstep(0.15, 0.0, d) * fract(n.z * 10.0)
                * life(0.06, fract(t + n.z)) * keep;
        }

        // HOW FAR SIDEWAYS THIS PIXEL'S COLUMN HAS SLID to get past a lens. In pixels,
        // signed, y counted upward, and zero on every row the lens does not reach.
        //
        // A drop cannot go round a camera on its own: it lives in a cell a hundred
        // pixels wide and the lens is twice that, and a drop that leaves its cell is a
        // drop that is not drawn. So the drop does not move — the panel does. Each row
        // the lens crosses is folded along the lens's centre line by the lens's width
        // at that row: the column on the left slides left, the column on the right
        // slides right, and a straight fall through the grid comes out on the screen as
        // a curve that hugs the rim. The trail, the beads and the wiped path follow,
        // because they are drawn along the same route.
        //
        // The slide fades with distance, so it is a squeeze rather than a jump — a
        // column beside the lens is compressed toward the rim by about a quarter at the
        // widest row, and a column halfway across the panel barely moves. A row's
        // slide is also proportional to the rim's width there, and that width is what
        // the fold is measured by: the rim itself lands exactly on the fold, so the
        // lens's interior maps to a single line in the grid where no drop can be.
        //
        // The rim's tips are rounded off. A circle's edge is vertical at the top and
        // bottom, and a fold that steep shears whatever slides past it into a streak.
        float deflect(float2 p, float3 lens) {
            float rm = lens.z + CLEAR;
            float q = (p.y - (size.y - lens.y)) / rm;
            float w = rm * sqrt(max(1.0 - q * q, 0.0)) * smoothstep(1.0, 0.8, abs(q));
            float dx = p.x - lens.x;
            float side = dx < 0.0 ? -1.0 : 1.0;
            float f = smoothstep(REACH, 0.0, max(abs(dx) - w, 0.0));
            // Inside the rim the whole width folds to the centre line, rather than
            // mirroring the far column into the near one.
            return side * min(abs(dx), w * f);
        }

        // ONE ON THE GLASS, ZERO ON A CAMERA, fading across the clearance between. In
        // top-down pixels, as main receives them. Applied to what the water DOES —
        // the bend, the glint, the shadow, the wiping — and never to the water field
        // itself, which the gradient is taken from: a mask in the field is an edge,
        // and an edge would be lit as a ring.
        float glass(float2 pos) {
            return smoothstep(lensA.z, lensA.z + CLEAR, length(pos - lensA.xy))
                * smoothstep(lensB.z, lensB.z + CLEAR, length(pos - lensB.xy));
        }

        // THE ONES THAT RUN. Given a point in pixels — x already folded round the lenses,
        // y counted upward — and the layer's scale. Returns how much water is here, how
        // much of it is trail, how recently the glass was wiped, and the wake: where a
        // drop has just been, wide enough to take anything sitting there with it.
        float4 running(float2 p, float t, float s) {
            // NO SCROLLING GRID. The whole field slid at one rate, and that one rate was
            // every drop's speed. The per-column offset underneath it — which the comment
            // here used to claim gave them different speeds — staggers when a drop STARTS,
            // not how fast it goes, so they arrived in step however much the phases were
            // shuffled. Uniform motion is the thing that gives a shader away, and it was
            // baked in at the top of this function.
            //
            // The lanes are still staggered, just not by the clock: a fixed offset per
            // column, so the cells do not line up as rows.
            //
            // FROM THE LENSES' CENTRE LINE, NOT THE PANEL'S EDGE. The columns are laid
            // out so that the line through both lens centres is a boundary between two
            // of them, in every layer. That is what makes the fold in deflect safe: the
            // fold lies on that line, and a route that crossed it would be drawn on both
            // rims of the lens at once. Confined to a column, no route can reach it.
            float cw = CELL_W / s;
            float ch = CELL_H / s;
            float2 g = float2((p.x - lensA.x) / cw, p.y / ch);
            g.y += hash1(float2(floor(g.x), s)) * 0.7;
            float2 id = floor(g);
            float seed = id.x * 35.2 + id.y * 2376.1 + s * 17.3;
            float3 n = hash3(seed);
            float2 st = fract(g) - float2(0.5, 0.0);

            // AND IT WANDERS ON THE WAY DOWN. A drop does not run straight: it catches,
            // slips sideways, and carries on. The wiggle is a sine of a sine, so the
            // sideways bumps do not land on a regular beat, and it is scaled per drop so
            // no two wander alike — and pinned at the edges so none leaves its column.
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
            float tt = t * speed + n.z;
            float pass = floor(tt);
            float ti = fract(tt);

            // A DIFFERENT DROP EVERY TIME DOWN. Everything that shaped a drop — where it
            // started across the cell, how hard it wandered, where it shed beads — was
            // hashed from the CELL, so a cell ran the same drop down the same path at the
            // same speed forever. Cycle lengths here are a third of a second to under
            // three, which is exactly the "couple of seconds" that read as a video on
            // loop: not one animation repeating, eight hundred short ones each repeating
            // in place.
            //
            // So the seed takes the pass number too, and the drop is redrawn from scratch
            // each time round. The swap lands where vis is zero at both ends of a cycle,
            // so nothing is seen to change; what is seen is that the next one down is not
            // the last one again.
            //
            // Bounded by mod because the argument goes through sin: unbounded it climbs
            // past a hundred thousand within the ten-minute wrap and the hash degrades to
            // noise, which would make every drop identical again by a different route.
            // Forty-seven passes is a minute or two per cell, and no two cells are in
            // phase, so there is no moment where the field as a whole comes back round.
            float3 m = hash3(seed + mod(pass, 47.0) * 113.7);

            float x = m.x - 0.5;
            float y = p.y / size.y * s * 20.0;
            // WIDER WANDER. This was barely a tenth of a cell — true to the reference and
            // invisible on a panel this size. A drop catches on something, slips sideways,
            // and carries on; if that cannot be seen it may as well be falling straight.
            // Frequency and phase come from the pass as well, so a drop does not merely
            // start somewhere else — it takes a different route down.
            x += sin(y * (0.78 + m.z * 0.6) + sin(y) + m.y * 6.2831)
                * (0.5 - abs(x)) * (m.z - 0.5) * 2.4;
            x *= 0.7;


            // IT SLOWS WHERE IT SLIPS. A drop does not wander for no reason — it catches
            // on something, hangs, tears sideways off it and drops again. So the sideways
            // movement and the loss of speed are the SAME event, and driving both from one
            // wobble is what makes it read as a cause rather than as two effects.
            //
            // The amplitude is bounded by the maths, not by taste: the vertical position
            // must never decrease, so the wobble's slope has to stay under the ramp's. At
            // three cycles a fall that puts the ceiling near 0.053, and 0.045 sits under
            // it with room to spare.
            // AND IT DOES NOT ALWAYS FALL AT THE SAME RATE. The speed itself has to hold
            // for a whole cycle or the drop would jump when it changed, but the SHAPE of
            // the fall is free: bending the ramp gives one pass that breaks away and
            // slows near the bottom and the next that hangs then goes. Monotonic, and it
            // pins 0 to 0 and 1 to 1, so the cycle still starts and ends where it must.
            ti = pow(ti, 0.62 + m.y * 0.9);
            float wob = sin(ti * 18.85 + m.z * 6.2831);
            y = 1.0 - (ti + 0.045 * wob);
            // IT WAITS FOR ITS OWN WATER. The fade-out was crammed into the last tenth of
            // the cycle, so a drop blinked out while the trail it had just laid was still
            // draining down toward it — the head gone and its own stream still arriving.
            //
            // A long tail instead: it arrives quickly, runs, and then goes over most of a
            // quarter of the cycle, which is longer than the trail above it takes to
            // finish coming down. Nothing pops out while something is still feeding it.
            float vis = smoothstep(0.0, 0.05, ti) * smoothstep(1.0, 0.76, ti);

            // IT GROWS AS IT GOES. A runner gathers whatever it crosses — see the wake
            // below, and water — and a drop that has taken half a dozen others with it
            // should be bigger than one that just set off. A third again, by the bottom.
            float grow = 1.0 + 0.35 * clamp(1.0 - y, 0.0, 1.0);
            float d = length((st - float2(x, y)) * float2(1.0, 6.0));
            float head = smoothstep(0.2 * grow, 0.0, d) * vis;

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
            // BEADS ON THE DROP'S OWN SPACING, not on the panel's.
            //
            // The interval was a fixed multiple of screen height, so every drop dropped a
            // bead at the same heights — and since the drop is wandering sideways as it
            // passes each one, what came out looked like a fresh static droplet planted at
            // every turn. It was one grid pretending to be many trails.
            //
            // The spacing is the drop's own now, and sparser: fewer beads, further apart,
            // and no two runners leaving them at the same heights.
            float by = fract(p.y / size.y * s * (5.0 + m.x * 6.0) + m.z) + (st.y - 0.5);
            float beads = smoothstep(0.11, 0.0, length(st - float2(x, by)))
                * r * smoothstep(-0.02, 0.02, st.y - y) * 0.7;

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
            // HOW RECENTLY THIS PIXEL WAS WET, which outlasts the trail you can see: the
            // glass stays clear a while after the water has gone and only then mists over
            // again. Same subtraction as the trail, longer memory.
            //
            // AND IT HAS TO KNOW ABOUT X. This asked only how far ABOVE the drop a pixel
            // was — nothing about how far to the side — so it came out constant across the
            // full width of the cell and wiped the mist in a band an eighth of the panel
            // wide and two thirds tall. That is the rectangle that has been chased through
            // half a dozen wrong explanations: not tiling, not the effect, not the noise,
            // not the compositing. A missing term.
            //
            // A drop wipes a PATH. The same distance the trail is drawn against bounds the
            // clearing, so the glass is clear where the water actually went and misted
            // everywhere it did not.
            //
            // AND ONLY WHERE IT HAS ALREADY BEEN. `since` is clamped at zero, which is
            // right for asking how long ago — but it means every pixel the drop has NOT
            // reached yet also reads zero, and zero is "wet this instant". So the whole
            // route ahead was wiped clear the moment the drop appeared, and then fogged
            // back over as the drop caught up with it: the path arriving before the drop
            // and receding behind it, which is precisely backwards.
            //
            // The trail never had this because it carries a forward gate. This is that
            // gate, on the same subtraction: nothing is dry until the water has passed.
            //
            // AND SOFT AT THE TOP. A drop starts at the top of its cell, and the wiped
            // path started there too — at full strength, flush against the boundary, so
            // where it met the cell above it ended in a straight line across the glass.
            // The trail never showed this because it thins to nothing toward the top
            // (that is what r is for). The clearing thins the same way now, over the
            // top fifth of the cell, so a path fades in where the drop did.
            float top = smoothstep(1.0, 0.82, st.y);
            float behind = smoothstep(-0.02, 0.02, st.y - y);
            float dry = smoothstep(FOG_RETURN, 0.0, since)
                * behind * vis * top
                * smoothstep(0.26, 0.06, cd);
            // THE WAKE: everywhere this drop has been on this pass, a little wider than
            // the drop itself, and it does not fade — a drop that has been swallowed
            // stays swallowed until the runner is gone. Read in water, where it takes
            // the clinging drops and the far layer's runners out from under this one.
            float wake = behind * vis * smoothstep(0.32, 0.18, cd);
            return float4(head + beads * linger, trail * linger, max(head, dry), wake);
        }

        // Everything on the glass at this point: coverage, and trail.
        float3 water(float2 uv, float t) {
            float still = clinging(uv, t);
            // The runners work in pixels, y upward, with x folded round both lenses —
            // one fold, shared by both layers, so the near drops and the far ones part
            // round the same rim.
            float2 p = uv * size;
            float2 wp = float2(p.x - deflect(p, lensA) - deflect(p, lensB), p.y);
            float4 a = running(wp, t, 1.0);
            float4 b = running(wp, t * 1.17, 1.85);
            // THE BIG ONES EAT THE SMALL ONES. A runner does not pass over a drop that is
            // sitting in its way; it takes it. So the far layer's runners are gone where
            // a near runner has just been, and the clinging drops are gone where either
            // has — and the runner is a little bigger for it, which is grow in running.
            b.x *= 1.0 - a.w;
            b.y *= 1.0 - a.w;
            still *= (1.0 - a.w) * (1.0 - b.w);
            // THE TRAIL COUNTS AS WATER. It was computed by both layers, returned, and
            // then never read — coverage took only the heads and the beads, so the streak
            // a drop leaves had no way of reaching the screen at all. That is why there
            // was no trail: not too faint, simply not connected.
            //
            // Weighted below the heads, because a trail is a film rather than a bead: it
            // should bend the light and catch a little of it, not sit there looking like
            // a drop that has been stretched.
            float c = still + a.x + b.x + (a.y + b.y) * 0.65;
            // A LOWER SILL, so the faint ones survive. This asked coverage to reach 0.3
            // before it counted for anything and 1.0 before it counted fully — which threw
            // away every drop that was merely present, and most of them are merely
            // present. What was left was the handful of direct hits, which is why there
            // seemed to be so little water.
            // A clinging drop clears the glass under it for as long as it sits there.
            return float3(
                smoothstep(0.16, 0.85, c),
                max(a.y, b.y),
                clamp(max(still * 3.0, max(a.z, b.z)), 0.0, 1.0)
            );
        }

    """

    private val WEATHER = COMMON + """
        half4 main(float2 pos) {
            float2 uv = pos / size;
            float lit = 0.0;
            float dim = 0.0;
            // How clear the glass is here: 1 where water has just been, 0 where it has
            // had time to mist over. Snow leaves it alone entirely.
            float clear = 1.0;
            float2 off = float2(0.0, 0.0);

            if (mode < 0.5) {
                float t = time * 0.22;
                // Y IS UPSIDE DOWN BETWEEN THE TWO WORLDS. Every reference for this is
                // written in GL, where the origin is bottom-left and y counts upward.
                // AGSL puts the origin top-left and counts down, so the maths was carried
                // over inverted and a fall came out as a rise. Flipped once here, so
                // everything downstream reads the way it was written.
                float2 wuv = float2(uv.x, 1.0 - uv.y);
                float3 c = water(wuv, t);
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
                clear = c.z;
                // THE FAR SIDE GOES DARK. A steep edge throws light away from the eye, and
                // a drop with a bright side and no dark side is a sticker.
                dim = smoothstep(0.08, 0.45, slope) * 0.34 * amount
                    * (1.0 - max(ndl, 0.0));
                // Nothing on the cameras. See glass.
                float onGlass = glass(pos);
                off *= onGlass;
                lit *= onGlass;
                dim *= onGlass;
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

            half4 col = ground.eval(pos + off * amount);
            half3 rgb = col.rgb * half(1.0 - dim) + half3(lit, lit, lit) * col.a;

            // No fog here any more; it is drawn in front of the panes instead. See MIST.
            return half4(rgb, col.a);
        }
    """

    /**
     * The condensation, drawn in front of the panes rather than behind them.
     *
     * IT SAMPLES NOTHING, and that is the whole point of moving it. A pane bends what is
     * behind it by reading that node at a displaced coordinate, and anything flat and
     * wide underneath shows every place that read lands slightly wrong — which is what a
     * wash of mist is, and why putting the fog in the ground made blocks appear that a
     * detailed photograph had been hiding all along.
     *
     * Mist needs no backdrop. It is white with holes in it: opaque where the glass has
     * fogged, clear where water has run. So it is its own shader, painting its own alpha,
     * laid over the finished panel — which is also where condensation actually is, on the
     * outer face of the glass with everything else behind it.
     */
    private val MIST = COMMON + """
        half4 main(float2 pos) {
            if (mode > 0.5) { return half4(0.0, 0.0, 0.0, 0.0); }
            float2 uv = pos / size;
            float2 wuv = float2(uv.x, 1.0 - uv.y);
            // Misted over on the cameras and their clearance, where no drop can reach to
            // wipe it — see glass.
            float clear = water(wuv, time * 0.22).z * glass(pos);
            // Mottled, because breath does not settle evenly and a flat wash reads as a
            // filter over the panel rather than as something on the glass.
            float haze = 0.55 * vnoise(uv * 9.0) + 0.45 * vnoise(uv * 23.0);
            float a = FOG * (0.55 + 0.75 * haze) * (1.0 - clear) * amount;
            a = clamp(a, 0.0, 1.0);
            return half4(half3(0.88, 0.92, 0.96) * half(a), half(a));
        }
    """


    val shader: RuntimeShader by lazy { RuntimeShader(WEATHER) }

    val mist: RuntimeShader by lazy { RuntimeShader(MIST) }

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
