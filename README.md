# Claude usage, on the back of the phone

Your Claude Code rate limits, live on the Xiaomi 17 Pro's rear display — so you can see
how much of your window is left without unlocking anything, opening anything, or asking.

![The panel running on the phone's rear display](assets/rear-panel.mp4)

Two accounts, three windows each, and a mascot that tells you the state at a glance from
across a desk.

> **Read [Please read this first](#please-read-this-first) before you sign in.** This is a
> personal project that handles account credentials and talks to an API on your behalf.

---

## The mascot

One per account, standing on its own 5-hour bar. Which one is playing tells you the state
without reading a single number.

### Walking — idle

![walking](assets/states/walk.png)

**Nothing to report.** The window is somewhere in the middle and you are not currently
burning through it. It walks the length of its own bar, jumps back to the start, and
walks it again — a full crossing takes **11.5 seconds**, and it never leaves the bar it
belongs to.

### Lifting — you are working

![lifting](assets/states/muscle.png)

**1% or more of the 5-hour window went in the last ten minutes.** It holds the pose for
five minutes after the last burst, and each new burst restarts that clock — so working
steadily keeps it lifting continuously rather than flickering between poses on every
poll.

### Flag — nearly out

![flag](assets/states/flag.png)

**The 5-hour window is past 90%.** This outranks the lifting pose deliberately: checking
"are you working" first would hide the warning at exactly the moment it matters most.

### Sleeping — the window is spent

![sleeping](assets/states/sleep.png)

**The 5-hour window is at 99% or above.** Nothing left to do until it rolls over, so the
mascot sleeps it off. This outranks the flag, which is the warning that this was coming:
past 99% there is nothing left to spend carefully, and the warning has no job. It
outranks the lifting pose too — a burst cannot be happening against a window with nothing
in it.

Distinct from the dead pose below, and the difference is the whole point: this window
refills in a few hours. The week does not.

### Confetti — a fresh start

![confetti](assets/states/confetti.png)

**The 5-hour window has not started.** Nothing spent, everything ahead of you. Only a
spent week outranks it — including a lifting pose still held from before the rollover,
which described what you were doing to a window that no longer exists.

Keyed on the window not running rather than on it reading 0%, and those are different
things. A 5-hour window begins at first use, so until you use something there is no
window at all. One that has started and rounds to 0% is a clock already ticking with a
few minutes gone — throwing confetti at it celebrates a fresh start that has quietly
already begun.

### Dead — the week is gone

![dead](assets/states/dead.png)

**The 7-day window is spent** (99% or above). The mascot lies on its side with X-ed eyes.

Keyed on the *weekly* limit specifically, because that is the one you cannot wait out,
and it outranks everything below. A 5-hour window refills every five hours, so the moment
one rolls over you have a brand new session and a week still at 100% — and without this
rule the mascot throws confetti at an account with nothing left to spend it on.

### Ghost — nobody home

![ghost](assets/states/ghost.png)

**The account has no usable credential** — never connected, signed out, or its refresh
token has expired. Grey and translucent, with white eyes: no legs, and a tail of four
blocks that tapers, fades out, and snakes as it falls, each one swinging wider and later
than the block above so the tip is still arriving where the head has already been.

It is the one pose that does not wear the account's colour. Every other mascot is
reporting on that account; this one is reporting that there is nothing to report, and
the palette saying "this is yours" about data that does not exist would be a small lie.

Not the same as dead, and keeping them apart matters. Dead is a fact about usage: the
week is spent, the numbers are real and all bad. The ghost is the *absence* of numbers —
nobody is answering, and there is nothing to report. Before it existed, a signed-out
account went on celebrating at 0% or waving the flag at 95%, replaying whatever reading
was last seen before it lost access.

### Which one wins

Several can be true at once, so they are ranked. The first line that applies is the one
you see:

| | when | why it sits here |
|---|---|---|
| **Dead** | the 7-day window is at 99% | the limit you cannot wait out — nothing below it matters |
| **Confetti** | the 5-hour window has not started | unambiguous: there is no burn to measure |
| **Sleeping** | the 5-hour window is at 99% | past this a warning has nothing left to warn about |
| **Flag** | the 5-hour window is past 90% | shown even while you are still burning through it |
| **Lifting** | 1% went in the last ten minutes | what you are doing, once nothing above is true |
| **Walking** | anything else | idling somewhere in the middle |

The three at the top describe **where you stand** and the two below them describe **what
you are doing**, which is the whole reason for the order: a warning you need is worth
more than an activity you can already feel.

## The rings and the bars

**The rings are clocks, not gauges** by default. Each one shows the time remaining until
that account's 5-hour window resets, drawn concentric with the phone's actual camera
lenses. They fill as the window runs down, so a full ring means a reset is imminent —
good news. That is also why they are accent-coloured rather than ramped: a green-to-red
ramp would say the opposite of what a full ring means.

A ring can be pointed at a different window, switched to show **utilisation** instead —
in which case it *is* a gauge, and takes the ramp like a bar — or set to show **nothing**,
which empties it and fades it away. A bare lens surround is a legitimate look.

**The bars are utilisation**, one row per window:

| row | window |
|---|---|
| `ALL 5H` | the 5-hour session limit |
| `ALL 7D` | the 7-day limit across all models |
| `<MODEL> 7D` | the 7-day limit for the scoped model |

Each bar carries its percentage and the time until that window resets. They ramp green
through amber to red as they fill.

Both animate with a 1.8s ease-in-out rather than snapping to new values, so a change
catches your eye without you watching for it — and never overshoots, because a bar that
punched past 100% and settled back would be reporting a number the data never held.

## On the front screen too

An ongoing notification carries both accounts, and on HyperOS it drives the
**HyperIsland** around the front camera:

```
CLAUDE USAGE
PERSONAL 100% · WORK 38% · ALL 5H resets 9m / 49m
```

Separate warnings fire at half of a limit, then at 90% and every 2% after that.

---

## Customisation

Almost everything on the panel is editable, and the settings screen shows a **live
preview of the real panel** pinned at the top — not a mock-up of one. It is an actual
instance of the same view at the same 904x572, scaled down, so it animates and it cannot
drift from what the back of the phone is doing.

| | |
|---|---|
| **Titles** | The masthead — `CLAUDE USAGE` by default — and every account name. All free text; the masthead is a label, not a brand mark. |
| **Colours** | Panel background; a per-account **accent** driving that account's ring, heading, row labels and plan tag together; each mascot's body and hardware colour separately; the clock and battery; and the ramp stops from mild to spent. |
| **Layout** | Which bar rows each account shows and in what order, whether it shows a mascot at all, and what each ring reports — which window, and whether it shows time-to-reset, utilisation, or nothing. |
| **Account order** | Reorder the accounts and the rings follow them: ring one belongs to whichever account is first, so there is nothing to keep in step by hand. |
| **Bottom artwork** | Replace the built-in mark with your own image, dragged and pinched into position within its region — you choose the crop, against a frame at the region's real shape. |
| **Extras** | A clock and a battery readout, stacked in the corner with pixel-traced glyphs. |
| **Accounts** | One or two, each with its own name, colours, and choice of browser sign-in or pasted token. |

Each account gets **its own settings screen**, reached from a row on the main one and
sliding in over it, with a preview showing just that account's ring and rows. Two
accounts already made a single screen a long scroll, and the panel is meant to grow past
two.

Colours are chosen on a **saturation/brightness square with a hue strip**, or typed as
hex, or taken from the panel's own palette. Every colour change eases in over 1.8s
exactly as the bars do, in the preview and on the panel, so a setting being dragged looks
like the panel responding rather than flickering.

### Who an account belongs to

Each account's settings can show the holder's name, email, organisation and plan — enough
to tell two accounts apart when you have forgotten which is which. It is **masked until
you unlock it** with your fingerprint or device PIN, the way a banking app does it, and it
re-hides when the screen locks, when you leave the screen, or after 30 seconds. A phone
handed to someone else should not show whose accounts these are.

### Starting over

**ADVANCED → Reset every setting to its default** puts everything back. It asks first,
and the confirm button stays shut for five seconds — the one action in the app with
nothing behind it and no undo. Your connected accounts stay signed in.

## Future plans

- **ChatGPT Codex** — a second provider beside Claude, so one panel covers both.
- **Android widget** — the same readings on the home screen, for phones with no rear display.
- **iOS widget.**
- **macOS widget.**
- **More than two accounts**, scrolled vertically on the panel rather than stacked — which
  is why colours are named per account rather than "primary" and "secondary". The panel
  overflows at four today: 516px of content into 396px of room.
- **Mascot states that enter and leave**, rather than looping whole. The lifting mascot
  should put its headband on once, lift for as long as you are working, and take it off
  when you stop — instead of restarting the whole performance every three seconds. And
  the cut between one state and the next should stop being a cut.
- **Automatic show-on-rear**, so the manual step below stops being manual.

---

## Requirements

- **A Xiaomi 17 Pro.** The rear display's size, position and lens geometry are measured
  into this app. It will not do anything useful on another phone.
- **HyperOS 3, Android 16.** Developed and verified against **`3.0.318.0`**, the current
  stable. **It has not been tested against the HyperOS 4 beta** — the placement mechanism
  depends on system internals that have already changed once between releases, so assume
  it needs re-verifying there.
- **[Shizuku](https://shizuku.rikka.app/)**, running. Non-negotiable — see below.
- A Claude account with Claude Code access.

## Shizuku, and why you cannot skip it

The rear display will not host an app you simply launch at it. Getting a window there
needs a privileged call that no ordinary app may make. **[Shizuku](https://shizuku.rikka.app/)**
is what bridges that: it runs a small service with shell-level privileges and lends them
to apps you approve. Without it running, this app has no way to put anything on the back
of your phone.

**Be warned that it is finnicky.** It does not survive a reboot on its own, some ADB
operations knock it over, and when it is down this app simply cannot place the panel.

### Starting it

1. Install **Shizuku** from [shizuku.rikka.app](https://shizuku.rikka.app/) or the Play
   Store.
2. On the phone, enable **Developer options** → **Wireless debugging** (Settings → About
   phone → tap *OS version* seven times, then Settings → Additional settings → Developer
   options).
3. Open Shizuku and use **Start via Wireless debugging**. It walks you through pairing.
4. Open this app and grant Shizuku permission when it asks.

**After every reboot, Shizuku must be started again.** Its own notification is the
quickest route back.

If you have a computer to hand, `adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh`
restarts it without touching the phone — useful when an ADB session has killed it, which
does happen.

## Please read this first

**THIS PROJECT IS NOT AFFILIATED WITH, ENDORSED BY, OR CONNECTED TO ANTHROPIC, OPENAI, OR
XIAOMI IN ANY WAY.** It is a personal project by one person. "Claude" and the Claude
mascot belong to Anthropic; "Codex" belongs to OpenAI. Their appearance here is
description and fidelity, not affiliation.

**THIS APP COLLECTS NO DATA. NONE.** There is no analytics, no telemetry, no crash
reporting, and no server belonging to this project. You can check that claim rather than
trust it — the only network destinations in the entire source are:

```
https://claude.ai/oauth/authorize     sign-in
https://api.anthropic.com/            your usage figures
https://platform.claude.com/          your plan tier
```

Your usage figures never leave your phone, and the panel's broadcast payload carries
readings and timestamps only — never a credential.

**YOUR CREDENTIALS ARE STORED ON YOUR DEVICE AND THEIR SAFETY IS YOUR RESPONSIBILITY.**
Tokens are encrypted with AES-GCM under a key held in the Android Keystore and kept in
this app's private storage. That is a reasonable standard and it is not a guarantee. **ON
A ROOTED, COMPROMISED, OR OUT-OF-DATE DEVICE, OR IN THE FACE OF AN ANDROID VULNERABILITY
OR MALICIOUS APP, THOSE CREDENTIALS CAN BE READ OR STOLEN.** If you lose a token, or a
device, that is on you. Revoke it from your Claude account and move on.

**USING A TOKEN OUTSIDE THE OFFICIAL CLIENT MAY BREACH YOUR PROVIDER'S TERMS, AND YOUR
ACCOUNT COULD BE SUSPENDED OR DISABLED FOR IT.** This has not happened to anyone using
this app so far as I know, and I do not expect it to — it reads your own usage figures
from the same endpoints the official tooling uses. But I cannot speak for Anthropic, they
may take a different view at any time, and **I ACCEPT NO LEGAL OR FINANCIAL
RESPONSIBILITY FOR A SUSPENDED, DISABLED, OR RESTRICTED ACCOUNT, FOR LOST OR STOLEN
CREDENTIALS, OR FOR LOST DATA OF ANY KIND.** Sharing a token across devices or people
makes that risk materially worse. Do not.

**NO WARRANTY.** See [LICENSE](LICENSE). Use it because you want this on the back of your
phone, understanding all of the above.

## Setup

1. **Get Shizuku running** — see [above](#shizuku-and-why-you-cannot-skip-it).
2. **Install the app.** Download the APK from
   [Releases](../../releases) and install it.

   Or build it yourself — the APK is nothing special, and you may reasonably prefer not
   to trust a stranger's binary with an API token:
   ```bash
   ./build-install.sh          # debug build, straight to a connected phone
   ```
   Note that `build-install.sh` produces a **debuggable** build. That is fine for
   development on your own cable, but it means anything with ADB access can `run-as` the
   app — reading its files *as the app*, which includes decrypting the stored tokens. The
   release APK is not debuggable. Prefer it for a phone you actually carry.
3. **Sign in.** Open the app and either log in through your browser, or paste a token if
   your account is managed and device login is impractical.
4. **Allow notifications.** Without them you lose both the limit warnings and the ongoing
   notification that carries the HyperIsland readout — and nothing on screen will tell you
   why.

   Steps 4 to 6 are also listed at the top of the app's own settings, each as a button
   that takes you to the right system screen and ticks itself once done. Once all of them
   are satisfied the list collapses into an **ONBOARDING** accordion and gets out of the
   way — it is instructions for getting started, and a wall of ticks should not sit above
   the settings you come back to. It reopens if a permission is later revoked, or if
   Shizuku is not restarted after a reboot.
5. **Turn off HyperOS battery restrictions** for the app, and allow background activity.
   HyperOS is aggressive here: with restrictions on it stops the poll service, and the
   panel freezes on its last reading — showing stale numbers as though they were current.
6. **Lock the app in recents.** Open recents, long-press the app, and lock it. Otherwise
   "close all" clears it, which kills the poll service and takes the panel down with it.
   A suggestion rather than a requirement, but it is the difference between the panel
   surviving your day and dying to a reflex. This is the one step the app cannot check
   for you — HyperOS does not expose whether an app is locked — so it is shown as advice
   rather than as a box that could never tick.
7. **Background the app, then tap its notification** to put the panel on the back.

Step 7 is the awkward one and it is known — see [Future plans](#future-plans).

**The app must stay running in the background.** The panel is drawn by this app; nothing
renders it on your behalf. If the app is killed, the back goes dark until you open it
again.

## How it works

The rear display refuses to host an activity launched directly at it. So the app launches
its panel on the main display, then asks Shizuku — running as shell — to move that task
across:

```
am start …                 launch on display 0
am display move-stack …    move the task to the rear display
input -d 1 keyevent WAKEUP wake the panel
```

That second command used to be a raw binder transaction number. Do not go back to it:
transaction numbers are positional in the AIDL and Android 16 moved this one, after which
the call kept reporting success while doing nothing at all.

The panel lives in its own `:rear` process, which the system destroys and recreates
constantly. It holds no state — everything it draws arrives in one payload, so a
respawned panel is never briefly wrong.

The display is always-on with no idle timeout, which is close to worst-case burn-in
geometry for OLED. The whole composition drifts by ±4px on a ten-minute cycle, and the
rings — which must stay concentric with the lenses — instead breathe their radius by
±3px. That is load-bearing, not decoration.

## Attribution

The mascot is **Anthropic's Claude mascot**. The animations were extracted from
[ayotomcs.me/claude-mascot](https://ayotomcs.me/claude-mascot) and rebuilt as pixel data
and, for the walk, as a reimplementation of its GSAP timeline. The maker's mark on the
panel is **ACRNM**'s. Neither is my work and neither is covered by this repository's
licence — they are included for fidelity to the design the panel was built around. If you
own either and would rather they were not here, open an issue and I will remove them.

The typeface is [Departure Mono](https://departuremono.com/) by Helena Zhang.

## Licence

MIT, for the code — see [LICENSE](LICENSE). The third-party artwork named above is
explicitly **not** covered by it.
