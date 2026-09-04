# Samsung Galaxy Z Flip 6 port — handover

Branch: `samsung-flip`. Everything below is verified on real hardware
(SM-F741B, Android 16, One UI) unless it says otherwise.

---

## 1. The port works

The panel runs on the Flip 6's cover screen, driven by the app's own
Shizuku path, showing live account data. The core mechanism transferred
from the Xiaomi **unchanged**: the cover screen is logical display 1, and
`am display move-stack <task> 1` — the exact command in
`RearUserService.moveToDisplay()` — places a task there.

What changed is geometry and everything downstream of the rings.

---

## 2. Getting a working device from cold

### Build toolchain
This machine had no JDK and no Android SDK. Installed:

```bash
brew install openjdk@21
brew install --cask android-commandlinetools
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

`local.properties` holds `sdk.dir=/opt/homebrew/share/android-commandlinetools`
(gitignored). `build-install.sh`'s JBR default path is stale — there is no
Android Studio here — so export `JAVA_HOME` before calling it, and set
`ANDROID_SERIAL` to bypass its Xiaomi model guard.

### Shizuku
Not on the Play Store for this account. Install straight from GitHub
releases, then start the server from an already-authorised adb connection:

```bash
curl -sL -o shizuku.apk "$(curl -s https://api.github.com/repos/RikkaApps/Shizuku/releases/latest \
  | grep browser_download_url | cut -d'"' -f4)"
adb install -r shizuku.apk
# Path is per-install — read it off the app: "Start by connecting to a
# computer" -> "View command". Then, IMPORTANTLY, nohup it:
adb shell "nohup /data/app/~~<hash>/moe.shizuku.privileged.api-<hash>/lib/arm64/libshizuku.so \
  > /data/local/tmp/shizuku.log 2>&1 &"
```

- **`nohup` matters.** Started without it, `shizuku_server` is a child of
  the adb shell session and dies when that connection drops — which is
  exactly what happened mid-session when USB de-authorised. Check with
  `adb shell ps -A | grep shizuku`: **ppid must be 1**.
- `adb shell pm grant <pkg> moe.shizuku.manager.permission.API_V23` does
  **not** work. Shizuku v13 tracks grants itself. The only way in is the
  app's own "SHIZUKU" onboarding row → Shizuku's confirmation dialog →
  *Allow all the time*.
- The Shizuku **manager UI** can ANR independently of the server. "Shizuku
  isn't responding" → Close app is safe; it does not touch `shizuku_server`.
- Shizuku must be restarted after every reboot. Its own limitation.

### Driving the device
```bash
# Cover only powers on when the phone is FOLDED. Check per-display state:
adb shell dumpsys display | grep -E "mBaseDisplayInfo.*displayId (0|1)" \
  | grep -oE "displayId [0-9]+|state [A-Z]+"

# Screenshots need the PHYSICAL display id; -d 1 is rejected as invalid.
adb shell screencap -d 4630946638498144900 -p /sdcard/c.png   # cover
adb shell screencap -d 4630946564756762243 -p /sdcard/f.png   # front

# Calibration grid (measurement overlay) — via BROADCAST, not am start:
# RearActivity has no onNewIntent, so a running instance ignores a new
# am start's extras entirely.
adb shell "am broadcast -p dev.kb896.aiusage -a dev.kb896.aiusage.STATE \
  --es state '{\"mode\":\"calibrate\"}'"
```

A screenshot that looks solid black may not be — the panel's background is
`0x1D1F21` (RGB 29,31,33), near-black. Sample actual pixels before
concluding nothing rendered, and always check the *latest* capture.

---

## 3. Device facts (Flip 6 cover)

| | value |
|---|---|
| logical display | 1 |
| resolution | 748 × 720 |
| density | 340dpi |
| physical id | `4630946638498144900` (front: `…564756762243`) |
| install orientation | ROTATION_90 |
| reported cutout | bottom-right, `Rect(379,654 – 748,720)` |

**The reported cutout is misleading for layout.** A photo of the actual
hardware settled it: the lenses sit *below the glass*, in the case's bezel,
outside anything this canvas can draw. Do not design clearances around
that rect — it cost a wrong `cameraLift` before being caught.

---

## 4. Architecture added

### `DeviceProfile.kt` (new)
Per-device hardware facts behind one interface, selected by `Build.MODEL`.
`Panel` reads through to `DeviceProfiles.current`. Unknown devices and the
unit-test JVM get `XiaomiRearProfile`, which is why every pre-existing
geometry test still asserts 904×572 unchanged.

| field | Xiaomi | Flip 6 |
|---|---|---|
| `width` / `height` | 904 / 572 | 748 / 720 |
| `cutoutRight` | 296 | 0 |
| `physicalIdSuffix` | `332` | `900` |
| `hasRings` | true | false |
| `cameraLift` (px) | 0 | 18 |
| `cameraSize` | 30f | 45f |

Flip 7 (`SM-F766B`, cover ~948×1048) drops in as a third profile.

### `ActivityDump.kt` (new) — the watchdog fix
`RearUserService.panelWhere()` parses `dumpsys SurfaceFlinger` for
`Display <id> (active) HWC layers:`. **That text does not exist on this
Android version** (verified: zero matches anywhere in the dump). So
`panelWhere()` always hit its `return Where.NOWHERE` guard, `isAlive()`
always returned true, and the watchdog could never detect the one failure
it exists for — something else covering a live panel.

`ActivityDump.taskDisplay()` answers the same question from `dumpsys
activity activities` instead, reading a task's own `visible=` flag under
its `Display #<id>` section. `panelWhere()` falls back to it when the
SurfaceFlinger text is absent; **the Xiaomi path is untouched**. This feeds
the existing, already-correct `Where.DISPLACED -> replace()` branch — no
other logic needed changing.

Pure and unit-tested (`ActivityDumpTest`, 6 tests, fixtures are real
captured dumps from the live failure).

> **Not yet confirmed end-to-end.** Manual recovery works, but I never saw
> the watchdog fire on its own: `am start` from a shell doesn't reproduce
> the real trigger (a genuine lock/unlock's system-level "go home"
> transition carries different weight). **Verify by folding, locking,
> unlocking, then polling** `dumpsys activity activities` for the
> RearActivity task's `visible=` flag — it should recover within ~6–15s
> with no intervention.

### Samsung-specific, outside `DeviceProfile`
OS-vendor differences deliberately keyed on `Build.MANUFACTURER`, not the
display profile:
- **Autostart** (`Setup.kt`): the old code hardcoded MIUI's
  `com.miui.securitycenter/…AutoStartManagementActivity`, which throws
  `ActivityNotFoundException` on Samsung ("Could not open that settings
  page"). One UI has no equivalent screen — it folds this into the per-app
  Battery permission — so Samsung routes to
  `ACTION_APPLICATION_DETAILS_SETTINGS`.
- **Camera** (`RearUserService.openCamera`): MIUI's
  `com.android.camera/.Camera` doesn't exist on Samsung. Off MIUI it now
  launches the standard `android.media.action.STILL_IMAGE_CAMERA` action.
  (Samsung's real component is
  `com.sec.android.app.camera.executor.AssistantActionActivity` — nothing
  anyone would have guessed, which is why the implicit action is right.)

---

## 5. Layout work (Flip-only, all `!Panel.HAS_RINGS`-gated)

- **Rings dropped.** No lens under the cover to encircle; bars already
  carry every reading. Gated in `drawRing()` / `drawPendingRings()`.
- **Maker's mark**: 0.8× the bar column, flush to `Panel.CUTOUT_RIGHT`,
  `region.bottom + 13f`. Height still derives from the 405:102 artwork
  aspect.
  - *Bug fixed en route*: `mediaClip()`'s left bound was `CONTENT_EDGE`
    (=26 on the Flip — the bottom margin reused as a left one), silently
    clipping the mark regardless of its own bounds.
- **Camera button**: fixed at `(Panel.HEIGHT - MARK_BOTTOM)`, lifted 18px,
  45f glyph. **It must not follow the media row** — that was a bug, and
  `drawCamera`'s KDoc says so.
- **Work account hidden when never signed in** — an account with
  `health == AUTH` and no snapshot is dropped from `orderedAccounts()`
  entirely. Distinct from a signed-in account that's merely degraded (an
  expired token still carries its last snapshot and stays).
  Scoped to `!HAS_RINGS` so Xiaomi's long-standing behaviour is unchanged.
- **Calibration view**: ring rulers hidden without rings; labels every 50px
  on both axes, extended to the full canvas (they stopped at y=500 on a
  720-tall panel — missing exactly the region being measured).
- **Media page rebuilt with bars** (`drawMediaBars`): scrub bar under the
  transport row with elapsed/total, volume bar down the right with its
  speaker/mute glyph at its foot.
  - The **drag gestures were never ring-based** — `scrubBar`/`volumeBar`
    are plain rects. Only the drawing was missing. No touch handling was
    changed.
  - `scrubBarLeft/Right/Y()` are shared by the drawing *and* the hit
    region, after a bug where only the drawing moved and left a bar you
    could see but not drag.

---

## 6. KNOWN BROKEN — pick up here

### The media strip on the readout page collides with the camera
Transport buttons overlap the camera glyph. **Diagnosed, not fixed.**

Root cause: `mediaSlotBottom()` caps the relocated slot at the old fixed
position:

```kotlin
(firstBlockBottom + MARK_H).coerceAtMost(Panel.HEIGHT - MARK_BOTTOM.toFloat())
```

When the first account block is tall enough, that cap kicks in and the row
lands back at the bottom — right where the camera is. But `mediaMoved()`
is still true, so `mediaRight()` returns the full-width `CONTENT_RIGHT`
instead of the camera-derived edge, and the buttons run into the camera.

Suggested fix — make "moved" mean *actually* moved:

```kotlin
private fun mediaMoved(): Boolean =
    !Panel.HAS_RINGS && mediaPageAvailable() &&
        (firstBlockBottom + MARK_H) < (Panel.HEIGHT - MARK_BOTTOM)
```

Then the capped case falls back to the camera-derived width and the two
stop overlapping. Verify with a track playing on the readout page, both
with a short account list and a long one.

### Other open items
- **Watchdog end-to-end confirmation** (§4) — highest value, it's the
  difference between the panel self-healing and needing a manual relaunch.
- **Vinyl art style**: `MediaLayout.vinyl()` computes its own position from
  `panelHeight` and does not follow `mediaSlotBottom()`. Only affects
  `config.mediaArt == VINYL`; the SQUARE path is what's in use.
- **`build-install.sh`**: still guards on the Xiaomi model, and the release
  story (`.gitlab-ci.yml`, version tags) has no Flip story yet.
- **Battery/autostart on device**: "NO BATTERY RESTRICTIONS" was still grey
  in onboarding. See dontkillmyapp.com/samsung — Samsung keeps its own
  sleep list on top of Android's standard exemption.

---

## 7. Conventions worth keeping

- **One definition per piece of geometry.** Two bugs this session came from
  the same shape being computed in two places that then drifted: the camera
  following a slot it shouldn't, and a scrub bar drawn where its touch
  region wasn't. `mediaMoved()` and `scrubBarLeft/Right/Y()` exist to stop
  that recurring.
- **Gate Flip changes on `!Panel.HAS_RINGS`** rather than changing shared
  behaviour, unless the change is genuinely wanted on both devices. The
  Xiaomi build is tuned and shipped; this branch shouldn't quietly alter it.
- **Photograph the hardware before designing around a diagnostic.** The
  cutout rect said one thing; the phone said another.
