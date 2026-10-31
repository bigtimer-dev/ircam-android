#!/bin/sh
# Reinstall the release APK on the emulator (stopping the app first) and launch it.
adb -e shell am force-stop io.github.bigtimer_dev.ircam 2>/dev/null
adb -e install -r "$(dirname "$0")/../app/build/outputs/apk/release/app-release.apk" | tail -1
adb -e shell am start -n io.github.bigtimer_dev.ircam/.MainActivity >/dev/null
