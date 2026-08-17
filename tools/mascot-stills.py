#!/usr/bin/env python3
"""Render each mascot state as a still for the README.

Reads the generated frame data straight out of MascotFrames.kt, so the stills cannot
drift from what the panel actually draws — regenerate them whenever the artwork changes:

    python3 tools/mascot-stills.py
"""
import pathlib
import re

from PIL import Image, ImageDraw

REPO = pathlib.Path(__file__).resolve().parent.parent
SRC = REPO / "app/src/main/java/dev/rgarjoaba/claudestatus/rear/MascotFrames.kt"
OUT = REPO / "docs/media/states"

BG = 0xFF1D1F21          # Panel.Palette.BG — the panel's field, AND the eye colour
SCALE = 5                # pixel art: integer scale, nearest neighbour, no smoothing
PAD = 10                 # in source units, so the figure is not flush to the edge

# The frame chosen per clip is the one carrying the MOST rects: the fullest confetti
# burst, the dumbbells at their peak, the flag extended. Frame 0 of the confetti is the
# mascot alone with nothing in the air — the frame that says nothing.
STATES = [
    ("walk",     "stand",    False, "idle — nothing to report"),
    ("muscle",   "muscle",   False, "burning through the window"),
    ("flag",     "flag",     False, "5-hour window past 90%"),
    ("confetti", "confetti", False, "window at 0% — a fresh start"),
    ("dead",     "stand",    True,  "the 7-day window is spent"),
]


def frames(name):
    body = SRC.read_text().split(f"private fun {name}Frames()")[1]
    cut = body.find("private fun ")
    body = body[:cut] if cut > 0 else body
    out, cur = [], []
    for line in body.splitlines():
        if "listOf(" in line and "Rect" not in line:
            if cur:
                out.append(cur)
                cur = []
        m = re.match(r"\s*Rect\(([-\d.]+)f, ([-\d.]+)f, ([-\d.]+)f, ([-\d.]+)f, (0x[0-9a-fA-F]+)", line)
        if m:
            cur.append((float(m[1]), float(m[2]), float(m[3]), float(m[4]), int(m[5], 16)))
    if cur:
        out.append(cur)
    return [f for f in out if f]


def aspect(name):
    m = re.search(rf'Clip\("{name}", ([\d.]+)f', SRC.read_text())
    return float(m.group(1))


def render(clip, dead):
    fr = max(frames(clip), key=len)
    a = aspect(clip)
    x0, y0 = min(r[0] for r in fr), min(r[1] for r in fr)
    x1, y1 = max(r[0] + r[2] for r in fr), max(r[1] + r[3] for r in fr)
    h = 120.0
    w = h * a
    W = int((x1 - x0) * w) + PAD * 2
    H = int((y1 - y0) * h) + PAD * 2
    if dead:                                  # rotated a quarter turn: swap the extents
        W, H = max(W, H), max(W, H)
    # Transparent field, so the stills sit on whatever background renders them.
    #
    # The eyes are NOT part of that transparency, even though they are painted in the
    # same colour as the panel's background. On the panel an eye drawn in the background
    # colour IS a hole and looks correct; punched out of a README still it would show the
    # page through the mascot's head, and on a light page leave it blank-faced. Kept
    # opaque, they read as eyes against the green body on any background.
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    ox = (W - (x1 - x0) * w) / 2 - x0 * w
    oy = (H - (y1 - y0) * h) / 2 - y0 * h
    opaque = lambda c: tuple((c >> s) & 255 for s in (16, 8, 0)) + (255,)

    for (rx, ry, rw, rh, c) in fr:
        if dead and c == BG:                    # skipped; re-drawn as an X below
            continue
        d.rectangle([ox + rx * w, oy + ry * h,
                     ox + (rx + rw) * w, oy + (ry + rh) * h], fill=opaque(c))

    if dead:
        for (rx, ry, rw, rh, c) in fr:          # X-ed eyes, on a 3x3 grid like the panel
            if c != BG:
                continue
            l, t = ox + rx * w, oy + ry * h
            cw, ch = (rw * w) / 3, (rh * h) / 3
            for i in range(3):
                for j in (i, 2 - i):
                    d.rectangle([l + j * cw, t + i * ch,
                                 l + (j + 1) * cw, t + (i + 1) * ch], fill=opaque(BG))
        im = im.rotate(-90, resample=Image.NEAREST, center=(W / 2, H / 2))
    return im.resize((W * SCALE // 2, H * SCALE // 2), Image.NEAREST)


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    for name, clip, dead, why in STATES:
        render(clip, dead).save(OUT / f"{name}.png")
        print(f"  {name:9} <- {clip}{' (dead)' if dead else '':8}  {why}")
