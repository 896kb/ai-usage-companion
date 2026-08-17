#!/bin/sh
# Build, install and launch on the Xiaomi 17 Pro.
#
# Install method, deliberate: push to /data/local/tmp then `pm install` on the
# device, rather than `adb install`.
#
# HyperOS/MIUI blocks adb's *streamed* install with
#   INSTALL_FAILED_USER_RESTRICTED: Install canceled by user
# even with USB debugging authorised, ADB verification off and unknown sources
# allowed — and it does so intermittently, having worked for hours beforehand.
# A failed streamed replace can also leave the app *uninstalled*. Pushing the APK
# and invoking `pm install` device-side takes a different code path that MIUI
# permits. Verified working on 2026-08-14 immediately after `adb install -r`
# failed on the same device, same session, same APK.
set -e
ADB=/opt/homebrew/bin/adb
# Device selection. Was a hardcoded USB serial, which breaks the moment the phone is
# on wireless adb (the serial becomes an ip:port). Resolution order:
#   ANDROID_SERIAL if the caller set it, else the only attached device.
# Three devices have been attached to this machine at various times (this phone, the
# Rokid glasses, a OnePlus), so refuse to guess when more than one is present rather
# than installing onto whichever adb happens to list first.
resolve_device() {
  if [ -n "${ANDROID_SERIAL:-}" ]; then echo "$ANDROID_SERIAL"; return; fi
  # Prefer a wireless (ip:port) transport. While the phone is both plugged in AND on
  # wireless adb, `adb devices` lists it TWICE — once per transport — so "exactly one
  # device" is the wrong test. The wireless entry is also the one that keeps working
  # when the cable comes out, which is the whole point of using it.
  w=$("$ADB" devices | awk 'NR>1 && $2=="device" && $1 ~ /:[0-9]+$/ {print $1}')
  if [ "$(echo "$w" | grep -c .)" = "1" ]; then echo "$w"; return; fi
  n=$("$ADB" devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')
  if [ "$n" = "1" ]; then "$ADB" devices | awk 'NR>1 && $2=="device" {print $1}'; return; fi
  echo "" ; return
}
DEVICE=$(resolve_device)
if [ -z "$DEVICE" ]; then
  echo "✗ need exactly one attached device, or set ANDROID_SERIAL. Attached:" >&2
  "$ADB" devices | awk 'NR>1 && NF' >&2
  exit 1
fi
PKG=dev.rgarjoaba.claudestatus
STAGE=/data/local/tmp/claude-status.apk

# This machine has no system Java — `java -version` fails outright. Android
# Studio's bundled JBR (21) is the only JDK present, so point Gradle at it here
# rather than making every caller remember to export it.
: "${JAVA_HOME:=/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export JAVA_HOME

# Refuse anything that is not the phone.
#
# The glasses (Rokid RG-glasses) and a OnePlus have both been attached to this machine,
# and resolve_device picks "the only attached device" — so with the phone unplugged and
# the glasses plugged in, this script will cheerfully install the rear-panel app onto
# the glasses. That happened. The model check is the backstop; ANDROID_SERIAL still
# overrides for the rare case of deliberately targeting something else.
EXPECT_MODEL=25098PN5AC
if [ -z "${ANDROID_SERIAL:-}" ]; then
  GOT=$("$ADB" -s "$DEVICE" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
  if [ "$GOT" != "$EXPECT_MODEL" ]; then
    echo "✗ $DEVICE is a '$GOT', not the phone ($EXPECT_MODEL)." >&2
    echo "  Plug in the Xiaomi, or set ANDROID_SERIAL to install elsewhere on purpose." >&2
    exit 1
  fi
fi

./gradlew :app:assembleDebug
"$ADB" -s "$DEVICE" push app/build/outputs/apk/debug/app-debug.apk "$STAGE" >/dev/null
"$ADB" -s "$DEVICE" shell pm install -r "$STAGE"
"$ADB" -s "$DEVICE" shell rm -f "$STAGE"

# Launching the front UI is OPT-IN, and that matters more than it looks.
#
# This used to run unconditionally, so every install threw MainActivity onto the
# phone's main screen. During a tuning session that is one interruption per build,
# and it is indistinguishable — from the far side of the phone — from the rear
# panel's "override" jumping to the front screen, which is a real bug this project
# has chased separately. At least one report of that bug was in fact this line.
#
# The panel does not need it: UsagePollService is already running and its watchdog
# re-places the rear activity on its own. Pass LAUNCH=1 when you actually want the
# front UI, e.g. to tap Import or Sign in.
# UsagePollService is started from MainActivity.onCreate, and `pm install -r` kills
# the app — so something MUST bring the app up again or the install leaves it dead:
# no service, no watchdog, no panel, and nothing to notice or recover. An earlier
# revision skipped the launch entirely to stop installs stealing the main screen, and
# that is exactly what it caused.
#
# The service is exported=false, so `am start-foreground-service` from the shell is
# refused ("Requires permission not exported"). Launching the activity is the only
# route in. So launch it, then immediately go HOME: the service is running and the
# screen is back where the user left it, about a second later.
"$ADB" -s "$DEVICE" shell am start -n "$PKG/.ui.MainActivity" >/dev/null
if [ "${LAUNCH:-0}" = "1" ]; then
  echo "✓ installed and left open on $DEVICE"
else
  sleep 1
  "$ADB" -s "$DEVICE" shell input keyevent KEYCODE_HOME >/dev/null
  echo "✓ installed on $DEVICE (service started, front UI dismissed)"
fi
