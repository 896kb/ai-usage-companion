# Making the rear-panel demo video

`tools/rear-demo.py` drives the panel through every mascot state, records the rear
display, and composites the result into a photo of the phone's back.

```bash
python3 tools/rear-demo.py            # record + compose
python3 tools/rear-demo.py record     # drive the panel and capture only
python3 tools/rear-demo.py compose    # re-composite an existing capture
```

Output lands in this directory: `rear-panel.mp4`, `rear-panel-poster.png`. The source
photo is `phone-back.png` — a HyperOS lock-screen render of the phone's own back, which
is where the geometry below comes from. **Replace that photo and every number below is
wrong.**

## The geometry

There are **three** nested shapes in the photo, and treating the first two as one is the
mistake that cost four takes:

| shape | rect | corner radius |
|---|---|---|
| green bezel | — | — |
| **black cutout** | `(94,914)–(1038,1523)` | **117** |
| **grey display** | `(118,934)–(1016,1501)` | **97** |

The active display is inset roughly 20px inside the black cutout, and that band is
black — it is inactive panel, not bezel. Fitting the video to the *cutout* stretches it
by 1.044×, erases the band, and squares off the corners against the body. Fit the
**display**.

The panel itself is 904×572, and the measured display extent is 898×567 — so the render
is essentially **1:1**, not scaled. That is the strongest evidence the measurement is
right, and the quickest check if these numbers ever look suspect.

### How each number was found

- **Cutout rect and radius.** Luma profiles across each edge (`<45` is the cutout, the
  bezel is `>150`), then a circle fitted to the corner's left-edge samples: radius 117 at
  1.6px rms.
- **Display rect.** The grey background is `#1D1F21`, luma 31, against the cutout's 0.
  Profiling the same rows and columns finds the grey starting at x=118 and ending at
  x=1016. Independently confirmed by brute-force aligning a real 904×572 panel capture
  against the photo — best match at `(118,934)`, scale `1.0000`.
- **Display radius.** 117 minus the ~20px band = 97, checked by A/B against the photo's
  corners at 84 / 97 / 110.
- **Lens holes.** `RingGeometry`'s lens centres, `(150,144)` and `(150,420)` in panel
  units, at radius **112** — outside the 108-unit lens, inside the ring's 115.5-unit
  inner edge, so the camera shows through and the ring survives intact.

  Do **not** size these to the grey annulus visible around each lens in the photo. That
  annulus is `Panel.Palette.TRACK` (`#2A2C2E`, luma 43.7) — our own ring track, not the
  camera housing. Measuring it and clearing it eats the ring.

## The timeline

Account A plays two clips, account B three, and they are never the same at once.

| | | |
|---|---|---|
| **A** (personal) | walk `11.51s` | flag `4.0s` |
| **B** (work) | confetti `5.17s` · muscle `5.17s` · dead `5.17s` | |

`11.51 + 4.0 = 15.51`, split three ways gives B's `5.17s`. B ends on **dead with all
three windows at 100%**.

`11.51s` is `WalkRig.DURATION_MS`, one complete left-to-right pass. `PERIOD_MS` is twice
that — the there-and-back including the mirrored return — and using it makes the video
drag.

Every segment is longer than the 1.8s `Tween.DURATION_MS`, so each bar and ring
transition completes rather than being cut off mid-move.

## Two traps

**The walk's phase runs off the wall clock**, not off when the clip is selected. Recording
an arbitrary 11.51s window catches half a forward pass and half the mirrored return, and
reads as the mascot changing its mind. The script waits for `now mod 23020 < 120ms` before
starting, then trims the video to that instant.

**`adb shell` hands argv to the *remote* shell**, which re-parses it. An unquoted JSON
payload arrives mangled and the broadcast silently does nothing — which looks exactly like
the app ignoring it. The payload must be single-quoted for the device shell. This one has
now been rediscovered twice.

## Pre-arming

The opening state is pushed **before** recording starts and given `PREARM_S` to settle.
Without it the first seconds of the video show whatever was previously on the panel easing
away. Trimming that off afterwards is not equivalent — it would also eat the start of the
phase-aligned walk.
