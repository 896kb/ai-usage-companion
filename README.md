# Magic Rear

**English** · [简体中文](README.zh-CN.md)

![Everything on the back of a Xiaomi 17 Pro: the clock and its live backgrounds, notifications, the control centre, an app with the keyboard, media with lyrics, always-on](assets/rear-panel.mp4)

Xiaomi's rear display does a little. Magic Rear replaces it with a panel that does a lot, on the
**Xiaomi 18 Pro / MAX & Xiaomi 17 Pro / MAX**. It used to be called AI Usage: the AI usage readout
is still here, as one feature among many.

Website, guide and news: **[rear.896.ro](https://rear.896.ro)**

> Read [Please read this first](#please-read-this-first) before you install it. It's a personal
> project that needs Shizuku, and for the AI usage readout, your account's sign-in.

## What it does

- **Clock page**: time, date, battery and a weather line, with weather on the glass when it rains,
  storms or snows.
- **Media**: cover art (square or a spinning record), progress, scrubbing, volume and synced lyrics.
- **Notifications on the back**: peek, open the list, swipe them away or dismiss them all.
- **App Portal**: pull up from the bottom edge to open your pinned apps on the back, each with its
  own DPI. They can keep right of the cameras, Back works, and the keyboard opens on the back.
- **Control centre**, **always-on**, **pocket mode**, **table lights** when the phone lies face
  down, the **camera** on the back, a small **game**, and a charging animation.
- **Looks**: glass or flat, fonts, a CRT screen, and backgrounds of a colour, an image, a video or a
  live shader (Silk, Color Bends, Liquid Metal), with parallax as the phone tilts.
- **AI usage**: Claude Code usage limits as rings, with a mascot per account.

Everything is set from the app on the front screen, with a live preview of the back.

## Requirements

- A **Xiaomi 18 Pro / MAX & Xiaomi 17 Pro / MAX**. It does nothing useful on another phone.
- **HyperOS 3 / 4 and newer, Android 16 / 17 and newer.** It depends on system internals, so a new
  HyperOS version may need checking again.
- **[Shizuku](https://shizuku.rikka.app/)**, running. Putting anything on the rear display needs
  shell-level calls an ordinary app can't make; Shizuku lends them to the apps you allow.

## Install

The easiest way is **[896 Store](https://store.896.ro)**: it installs Magic Rear and keeps it
updated. Or download the APK from [rear.896.ro](https://rear.896.ro) or the
[releases](https://gitlab.com/896kb/ai-usage-companion/-/releases).

Updating from AI Usage keeps your settings: it's the same app (`dev.kb896.aiusage`) with a new name.

## Setup

1. **Start Shizuku**: Developer options → Wireless debugging on, then **Start via Wireless debugging**
   in Shizuku. It doesn't survive a reboot, so start it again after each one (its notification is
   the quickest way back). With a computer:
   `adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh`.
2. **Open Magic Rear** and work down the setup list at the top of its settings. Each row opens the
   right screen and ticks itself when it's done:
   - allow Magic Rear in Shizuku;
   - move Xiaomi's own rear-screen app out of the way (hidden for your user, nothing deleted; one
     tap restores it);
   - allow notifications, and notification access if you want notifications and media on the back;
   - turn off battery restrictions and allow autostart, or HyperOS will stop it.
3. **Lock it in recents**, so "clear all" doesn't close it.

The panel is drawn by the app, so it has to keep running in the background.

## Please read this first

**Not affiliated with Anthropic, OpenAI or Xiaomi.** It's a personal project by one person.
"Claude" and the Claude mascot belong to Anthropic.

**No data is collected**: no analytics, no telemetry, no crash reports, no server of this project's
own. The only places the app talks to:

```
https://claude.ai/oauth/authorize       sign-in, for AI usage
https://api.anthropic.com/              your usage figures
https://platform.claude.com/            your plan tier
https://lrclib.net/                     lyrics, if you turn them on
https://api.open-meteo.com/             weather, if you turn it on
https://geocoding-api.open-meteo.com/   the place you type, turned into coordinates
https://gitlab.com/api/v4/projects/…    checking for updates
```

**Your sign-in stays on your phone**, encrypted with a key in the Android Keystore. On a rooted or
compromised phone it can still be read. Using a token outside the official client may go against
your provider's terms; nobody's account has been suspended for it that I know of, but **I take no
responsibility for a suspended account, lost credentials or lost data.** See the
[privacy policy](https://rear.896.ro/en/privacy/).

**No warranty.** See [LICENSE](LICENSE).

## Building

```bash
./build-install.sh    # debug build, installed on a connected phone
```

The debug build is its own app, `dev.kb896.aiusage.dev` ("Magic Rear (dev)"), so it installs next
to the release. It's debuggable: anything with ADB access can read its files, sign-in included, so
use the release on the phone you carry. Releases are built by CI from version tags.

## Credits

- **[u/Over_Squash3318](https://www.reddit.com/user/Over_Squash3318/)** got the panel working on
  the Xiaomi 17 Pro Max.

## Attribution

- The mascot is **Anthropic's Claude mascot**, rebuilt from
  [ayotomcs.me/claude-mascot](https://ayotomcs.me/claude-mascot). The maker's mark is **ACRNM**'s.
  Neither is covered by this repository's licence; if you own either and want it removed, open an
  issue.
- Fonts: [Departure Mono](https://departuremono.com/) by Helena Zhang and
  [Outfit](https://github.com/Outfitio/Outfit-Fonts) by Smartsheet, SIL Open Font License 1.1.
- Glass: [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) by Kyant0, Apache 2.0
  (shaders vendored, controls ported from its catalog app).
- Icons: Material Symbols Rounded and Material Icons, Apache 2.0.
- Shaders: Silk, Color Bends and Electric Border from [Vue Bits](https://vue-bits.dev) (DavidHDev);
  Liquid Metal from [Paper Shaders](https://github.com/paper-design/shaders) (Apache 2.0) as
  [metal-fx](https://libraries.dev/metal) by Jakub Antalik (MIT) uses it; snow adapted from
  Lumine's multilayer snowfall on [Godot Shaders](https://godotshaders.com/shader/multilayer-snowfall-shader/) (CC0).
- Weather data by [Open-Meteo](https://open-meteo.com/) (CC BY 4.0). Lyrics from
  [LRCLIB](https://lrclib.net/).

## Licence

MIT, for the code: see [LICENSE](LICENSE). The third-party artwork above isn't covered by it.
