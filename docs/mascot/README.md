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

| file | viewBox | rects | what it is |
|---|---|---|---|
| `walk-8frame.svg` | 129x113 | 204 | 8-frame walk cycle. Frames `l001`-`l005` face one way, `l006`-`l008` are mirrored (`matrix(-1 0 0 1 tx ty)`). **The one to use.** |
| `cycle-11frame.svg` | 158x128 | 718 | 11 `body` groups; frame segmentation not yet resolved |
| `detailed.svg` | 140x146 | 654 | high-detail pixel-art piece, not frame-grouped |
| `pose.svg` | 107x86 | 10 | single static pose |

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
