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
| `pose.svg` | a single static pose | 1 | 9 |

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
