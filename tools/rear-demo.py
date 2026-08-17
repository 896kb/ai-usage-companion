#!/usr/bin/env python3
"""Record the rear panel and composite it into a photo of the phone's back.

    python3 tools/rear-demo.py record     # drive the panel, capture, trim
    python3 tools/rear-demo.py compose    # mask and overlay onto the photo
    python3 tools/rear-demo.py            # both

Everything measured is in GEOMETRY. See docs/media/README.md for how those numbers
were arrived at and what breaks if they drift.
"""
import json
import math
import pathlib
import subprocess
import sys
import time

REPO = pathlib.Path(__file__).resolve().parent.parent
MEDIA = REPO / "docs" / "media"
SCRATCH = REPO / ".rear-demo"       # working files; git-ignored

SERIAL = "f940914b"
ADB = ["adb", "-s", SERIAL]
REAR_DISPLAY = "4630946949513469332"     # physical id of the rear panel
PACKAGE = "dev.rgarjoaba.claudestatus"
ACTION = f"{PACKAGE}.STATE"

# --- measured geometry -------------------------------------------------------------
#
# The phone photo contains THREE nested shapes, not two, and conflating the first two
# is the mistake that cost several takes:
#
#   black cutout   (94,914)-(1038,1523)   corner radius 117
#   grey display   (118,934)-(1016,1501)  corner radius  97
#
# The display is inset ~20px inside the cutout and that band is BLACK. Fitting the
# video to the cutout stretches it 1.044x, erases the band, and squares the corners.
GEOMETRY = {
    "display_x": 118, "display_y": 934,     # top-left of the ACTIVE display in the photo
    "display_w": 898, "display_h": 567,     # its measured extent (panel is 904x572, ~1:1)
    "corner_radius": 97,                    # the display's own radius, not the cutout's
    "lens_r": 112,                          # panel units: outside the 108 lens, inside
                                            # the ring's 115.5 inner edge, so the ring
                                            # survives while the camera shows through
    "lenses": [(150, 144), (150, 420)],     # panel units; RingGeometry's lens centres
    "crop": (1080, 1720, 24, 680),          # w,h,x,y — trims the photo to the phone
    "out_width": 620,
}

# --- timeline ----------------------------------------------------------------------
#
# WalkRig.DURATION_MS is one full left-to-right pass; PERIOD_MS is twice that, the
# there-and-back including the mirrored return.
WALK_MS = 11_510
PERIOD_MS = WALK_MS * 2
FLAG_S = 4.0
TOTAL_S = WALK_MS / 1000 + FLAG_S
THIRD_S = TOTAL_S / 3

# Account A plays two clips, account B three, and they are never the same at once.
# (mascot, session%, weekly%, scoped%, hours-until-reset)
PERSONAL = [(0.0, ("walk", 0.35, 0.42, 0.30, 4.0)),
            (WALK_MS / 1000, ("flag", 0.94, 0.78, 0.61, 0.25))]
WORK = [(0.0, ("confetti", 0.00, 0.04, 0.02, 4.6)),
        (THIRD_S, ("muscle", 0.52, 0.33, 0.24, 3.0)),
        (2 * THIRD_S, ("dead", 1.00, 1.00, 1.00, 0.02))]

# Held after the timeline ends, so the closing state — the second account dead at
# 100/100/100 — sits still long enough to be read instead of being cut on the beat.
TAIL_S = 2.0

# How long before the walk's phase-0 boundary to push the opening state. The panel must
# already be showing the right numbers when recording starts, or the first seconds show
# whatever was on screen easing away. One tween (1.8s) plus slack.
PREARM_S = 4.0


def sh(*args):
    return subprocess.run(ADB + list(args), capture_output=True, text=True)


def payload(base, specs):
    d = json.loads(json.dumps(base))
    for a in d["accounts"]:
        mood, u5, u7, us, hours = specs[a["id"]]
        a["mascot"] = mood
        for w in a["windows"]:
            if w["kind"] == "session":
                w["utilization"] = u5
                w["resetsAt"] = int(time.time() * 1000) + int(hours * 3600_000)
            elif w["kind"] == "weekly_all":
                w["utilization"] = u7
            else:
                w["utilization"] = us
    return json.dumps(d, separators=(",", ":"))


def send(base, specs):
    p = payload(base, specs)
    # Single-quoted FOR THE DEVICE SHELL. `adb shell` hands argv to the remote shell,
    # which re-parses it — an unquoted JSON payload arrives mangled and silently does
    # nothing, which looks exactly like the app ignoring the broadcast.
    assert "'" not in p
    return sh("shell", f"am broadcast -a {ACTION} --es state '{p}'")


def state_at(seq, t):
    cur = seq[0][1]
    for at, v in seq:
        if t + 1e-6 >= at:
            cur = v
    return cur


def read_live_payload():
    """The panel's last real state, used as the base so labels and plans stay truthful."""
    xml = sh("shell", "run-as", PACKAGE, "cat", "shared_prefs/poll.xml").stdout
    import html
    import re
    m = re.search(r'<string name="lastState">(.*?)</string>', xml, re.S)
    if not m:
        raise SystemExit("no cached state on device — open the app and let it poll once")
    return json.loads(html.unescape(m.group(1)))


def record():
    SCRATCH.mkdir(exist_ok=True)
    base = read_live_payload()
    marks = sorted({t for t, _ in PERSONAL} | {t for t, _ in WORK})

    sh("shell", "input", "-d", "1", "keyevent", "KEYCODE_WAKEUP")
    time.sleep(1)

    # Pre-arm: push the opening state, settle, THEN wait for phase 0.
    send(base, {"personal": state_at(PERSONAL, 0.0), "work": state_at(WORK, 0.0)})
    print(f"  pre-armed, settling {PREARM_S}s")
    time.sleep(PREARM_S)

    rec = subprocess.Popen(
        ADB + ["shell", "screenrecord", "--display-id", REAR_DISPLAY,
               "--bit-rate", "16000000", "--time-limit", "40", "/sdcard/rear_demo.mp4"],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    time.sleep(2.5)
    if rec.poll() is not None:
        raise SystemExit(f"screenrecord died: {rec.stdout.read()[:200]}")
    rec_start = time.time()

    # The walk's phase comes from the wall clock, not from when the clip was selected,
    # so an arbitrary window catches half a forward pass and half the mirrored return.
    while int(time.time() * 1000) % PERIOD_MS >= 120:
        time.sleep(0.02)
    t0 = time.time()
    print(f"  walk phase 0 at {t0 - rec_start:.2f}s into the capture")

    for t in marks:
        while time.time() - t0 < t:
            time.sleep(0.02)
        specs = {"personal": state_at(PERSONAL, t), "work": state_at(WORK, t)}
        ok = "Broadcast completed" in send(base, specs).stdout
        print(f"  t={t:5.2f}  personal={specs['personal'][0]:8} work={specs['work'][0]:8} "
              f"{'ok' if ok else 'FAILED'}")

    while time.time() - t0 < TOTAL_S + TAIL_S:
        time.sleep(0.05)
    rec.terminate()
    time.sleep(5)
    sh("pull", "/sdcard/rear_demo.mp4", str(SCRATCH / "raw.mp4"))
    (SCRATCH / "trim.json").write_text(json.dumps({"ss": round(t0 - rec_start, 2),
                                                    "dur": round(TOTAL_S + TAIL_S, 2)}))
    print(f"  trim: start {t0 - rec_start:.2f}s length {TOTAL_S + TAIL_S:.2f}s "
          f"({TOTAL_S:.2f} timeline + {TAIL_S:.1f} tail)")


def build_mask():
    from PIL import Image, ImageDraw
    g = GEOMETRY
    w, h = g["display_w"], g["display_h"]
    sx = w / 904
    m = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(m)
    d.rounded_rectangle([0, 0, w - 1, h - 1], radius=g["corner_radius"], fill=255)
    for lx, ly in g["lenses"]:
        cx, cy, r = lx * sx, ly * (h / 572), g["lens_r"] * sx
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=0)
    m.save(SCRATCH / "mask.png")
    return SCRATCH / "mask.png"


def compose():
    g = GEOMETRY
    trim = json.loads((SCRATCH / "trim.json").read_text())
    mask = build_mask()
    cw, ch, cx, cy = g["crop"]
    out = MEDIA / "rear-panel.mp4"
    fc = (f"[1:v]scale={g['display_w']}:{g['display_h']},format=rgba[v];"
          f"[2:v]format=gray[m];[v][m]alphamerge[va];[0:v]format=rgba[bg];"
          f"[bg][va]overlay={g['display_x']}:{g['display_y']},"
          f"crop={cw}:{ch}:{cx}:{cy},scale={g['out_width']}:-2,format=yuv420p[out]")
    # Every input is bounded with -t. A looped image input with no duration generates
    # frames forever and the encode never finishes.
    subprocess.run([
        "ffmpeg", "-y", "-v", "error",
        "-loop", "1", "-framerate", "24", "-t", str(trim["dur"]), "-i", str(MEDIA / "phone-back.png"),
        "-ss", str(trim["ss"]), "-t", str(trim["dur"]), "-i", str(SCRATCH / "raw.mp4"),
        "-loop", "1", "-framerate", "24", "-t", str(trim["dur"]), "-i", str(mask),
        "-filter_complex", fc, "-map", "[out]", "-t", str(trim["dur"]),
        "-r", "24", "-c:v", "libx264", "-crf", "21", "-preset", "veryfast",
        "-movflags", "+faststart", str(out)], check=True)
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-ss", "12", "-i", str(out),
                    "-frames:v", "1", str(MEDIA / "rear-panel-poster.png")], check=True)
    print(f"  wrote {out.relative_to(REPO)}")


if __name__ == "__main__":
    what = sys.argv[1] if len(sys.argv) > 1 else "all"
    if what in ("record", "all"):
        record()
    if what in ("compose", "all"):
        compose()
