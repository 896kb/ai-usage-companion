# Claude mascot animations

Extracted from the inline SVG on https://ayotomcs.me/claude-mascot (animation by
Wale-Durojaye Ayotomiwa; the mascot is Anthropic's character). Saved for later use on
the rear panel. Not currently rendered by the app.

The page's four mascot rigs are **not** the four `/videos/*.webm|mp4` files — those are
marketing scenes with the mascot composited into a UI mockup on a cream background,
opaque and unusable over the panel. The real assets are inline SVG, animated by GSAP,
which only toggles `display` between pre-drawn frame groups. There is no morphing or
path interpolation to reproduce.

Every rig is built from **axis-aligned `<rect>`s in exactly two fills** — `#DD775B`
body, `black` eyes. That is why this is cheap to render natively: a `drawRect` loop
over a static table, no decoder, no bitmap, resolution-independent, and the movement
helps rather than hurts burn-in on an always-on OLED.

Recoloured here to the panel palette: body `#B5BD68` (Palette.WORK), eyes `#1D1F21`
(Palette.BG) so they read as cut-outs against the panel the way the masthead mark does.

| file | what it actually is | frames | rects |
|---|---|---|---|
| `walk-8frame.svg` | **confetti** — mascot celebrating under falling confetti | 8 | 204 |
| `cycle-11frame.svg` | **muscle** — mascot lifting dumbbells | 36 | 718 |
| `detailed.svg` | **flag** — static mascot waving a checkered flag | 12 | 829 |
| `pose.svg` | the **stand** rig, and the rig the walk is animated from — see below | 1 | 9 |

The filenames are the names I first guessed and are now wrong; the table is the truth.

## Layers, and why a flat frame list drops content

An animation is a set of LAYERS, not a flat list of frames, and each of the three
animated clips proved it differently:

* **confetti** keeps its confetti in TWO sibling top-level `<g>` layers, outside the
  mascot's own `l001`-`l008` frames. Reading only the mascot layer silently lost 114 of
  204 rects — the entire confetti.
* **flag** is a static mascot plus a 12-frame flag sequence nested inside the scene.
  Treating the whole thing as one frame produced a still image.
* **muscle** is the simple case: 36 sibling frames, each already carrying mascot and
  dumbbells.

Composite frame *i* = every static rect, plus frame *i* of each sequence (short
sequences wrap). `MascotFrames.kt` is generated on that model.

## The walk is procedural, not frames

There is no walk animation in any SVG, and that is not an oversight — the site builds it
in JavaScript, as a GSAP timeline over the same nine-rect rig as the `stand` clip. So
there is nothing to bake, and `WalkRig.kt` animates the timeline directly instead.

The timeline lives in chunk `5ce46cbd0cd22c82.js`. Parsing its `.to()` chain into
absolute times — resolving `"<"`, `delay:`, and `"walk+=N"` positions the way GSAP does —
gives an **11.51s** loop:

| at | what |
|----|------|
| 0.00 | lean left: body rotates -3° about (53,65), eyes look left, legs stretch and tilt |
| 1.90 | lean right, eyes track across and down |
| 3.50 | settle upright, eyes drop further |
| 3.70 | the hop (`y:-18`, then back) |
| 4.03 | walk 55% of the span, 20 steps on a 0.1s beat |
| 6.53 | lean right again, look down |
| 9.43 | crouch, then jump the remaining 45% — `y:-90`, well outside the rig's box |
| 10.51 | land, hold one second, snap back to the start |

The gait itself: leg pivots move from the foot (`y=86`) to the hip (`y=60`) for the walk
and back afterwards, each pair oscillating between full height and 45%, legs 1+3
antiphase to 2+4 — a diagonal quadruped gait.

### Why not bake it

An earlier version baked 8 samples of the 0.2s leg cycle. It was wrong in three ways at
once: it dropped the lean, the look, the hop and the jump — five sixths of the
performance; it ran the gait at a third speed, playing a 0.2s cycle over 8 frames of
83ms; and it marched on the spot, because the translation is a separate tween.

Baking the whole thing would need ~290 frames of 9 rects and still could not express the
rotations: `MascotFrames.Rect` is axis-aligned by construction, and the lean rotates the
body 3° and each leg 7-9°.

### Two things that are easy to get wrong

* **GSAP's power names are off by one from the exponent.** `Power1` is quadratic,
  `Power2` **cubic**, `Power3` quartic. Reading `power2.out` as a quadratic makes every
  ease in this timeline visibly lazier than the site's.
* **The loop is 11.51s, not 10.51s.** The last tween ends at 10.51, but the callback that
  resets the mascot to the start is scheduled at `"+=1"`, so that is GSAP's total
  duration and where `repeat: -1` restarts. That second is the pause at the far end.

The walk is shown only in the app's mascot strip. `MascotMood` never selects it: the
panel's idle state is the still `stand` pose, because a mascot walking back and forth
forever is motion with nothing to say on a display that is lit 24/7 — and it would mean
redrawing the whole view at 25fps to say it.

## Colour

Tinted to a green monochrome that preserves each source colour's relative luminance, so
shaded artwork keeps its shading instead of flattening to a silhouette. Two exceptions,
both learned on the panel:

* the **confetti keep their real colours** (`#7CA4D0` blue, `#DD8361` orange,
  `#C87392`/`#CB708F` pink) — recolouring confetti to one green turns a celebration into
  a shrug. Possible only because they sit in their own layers and can be identified
  without guessing by hue.
* the **dumbbells are forced white**. `#1A4C81` and `#5D5B56` land at a luminance the
  green tint renders as dark olive — visible in theory, invisible against `#1D1F21`, so
  the mascot appeared to be lifting nothing.

The tint's floor also sits well above the background; an earlier version mapped
luminance onto a range starting at the panel colour and mid-tones disappeared.

`*.original.svg` keeps the untouched source next to each recoloured copy.

## Using the walk cycle

`walk-8frame.frames.json` is the useful artifact: per frame, a list of
`{id, x, y, w, h, fill}` with all `transform`s already resolved (including the mirror
matrix on frames 6-8), in viewBox coordinates. Rendering is a loop over that table.
`walk-8frame/frame-NN.svg` are the same frames as standalone files, and
`walk-8frame.animated.svg` self-animates via SMIL for eyeballing in a browser.

Note frames `l003` and `l007` reach `y = -8`, above the viewBox — the per-frame files
and the preview pad the top by 10 to compensate. Account for that when placing it.

## Open decisions

Where it goes on the panel (band under the WORK bars, or walking the bottom edge), and
whether it loops continuously or only animates when a value changes. On an always-on
display the second is the more defensible default, but it has not been decided.
