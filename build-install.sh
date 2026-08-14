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
DEVICE=f940914b            # 25098PN5AC / pandora — NOT the glasses or the OnePlus
PKG=dev.rgarjoaba.claudestatus
STAGE=/data/local/tmp/claude-status.apk

# This machine has no system Java — `java -version` fails outright. Android
# Studio's bundled JBR (21) is the only JDK present, so point Gradle at it here
# rather than making every caller remember to export it.
: "${JAVA_HOME:=/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export JAVA_HOME

./gradlew :app:assembleDebug
"$ADB" -s "$DEVICE" push app/build/outputs/apk/debug/app-debug.apk "$STAGE" >/dev/null
"$ADB" -s "$DEVICE" shell pm install -r "$STAGE"
"$ADB" -s "$DEVICE" shell rm -f "$STAGE"
"$ADB" -s "$DEVICE" shell am start -n "$PKG/.ui.MainActivity" >/dev/null
echo "✓ installed and launched on $DEVICE"
