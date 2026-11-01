#!/bin/bash
# Makes Repsac Launcher the home screen by turning off Google's, and gives it the permissions it needs.
# Nothing is deleted: undo with ./restore-home.sh
#
# Usage:  ./set-as-home.sh <ip:port>     (shown under Developer options → Wireless debugging on the TV)
DEV="$1"; [ -z "$DEV" ] && { echo "Usage: ./set-as-home.sh <ip:port>"; exit 1; }
case "$DEV" in *:*) ;; *) DEV="$DEV:5555" ;; esac
PKG=no.repsac.launcher

adb connect "$DEV" >/dev/null; sleep 1
if ! adb -s "$DEV" shell pm list packages | tr -d '\r' | grep -qx "package:$PKG"; then
  echo "Repsac Launcher isn't installed yet. Install it from Android Studio first."; exit 1
fi
adb -s "$DEV" shell pm disable-user --user 0 com.google.android.apps.tv.launcherx && echo "  ✓ Google's home screen is turned off"
adb -s "$DEV" shell pm disable-user --user 0 com.google.android.tungsten.setupwraith >/dev/null 2>&1 && echo "  ✓ Fallback home screen is turned off"
adb -s "$DEV" shell pm grant $PKG android.permission.WRITE_SECURE_SETTINGS && echo "  ✓ Bluetooth button and screensaver have access"
adb -s "$DEV" shell appops set $PKG WRITE_SETTINGS allow && echo "  ✓ Screensaver can change the wait time"
adb -s "$DEV" shell input keyevent KEYCODE_HOME
echo "Done! Press Home on the remote. Undo with ./restore-home.sh $DEV"
