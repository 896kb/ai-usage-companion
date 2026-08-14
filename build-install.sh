#!/bin/sh
# Build, install and launch on the Xiaomi 17 Pro.
set -e
ADB=/opt/homebrew/bin/adb
DEVICE=f940914b            # 25098PN5AC / pandora — NOT the glasses or the OnePlus
PKG=dev.kb896.aiusage

./gradlew :app:assembleDebug
"$ADB" -s "$DEVICE" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" -s "$DEVICE" shell am start -n "$PKG/.ui.MainActivity"
echo "✓ installed and launched on $DEVICE"
