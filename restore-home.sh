#!/bin/bash
# Brings Google's home screen back.
# Usage:  ./restore-home.sh <ip:port>
DEV="$1"; [ -z "$DEV" ] && { echo "Usage: ./restore-home.sh <ip:port>"; exit 1; }
case "$DEV" in *:*) ;; *) DEV="$DEV:5555" ;; esac
adb connect "$DEV" >/dev/null; sleep 1
adb -s "$DEV" shell pm enable com.google.android.apps.tv.launcherx
adb -s "$DEV" shell pm enable com.google.android.tungsten.setupwraith
adb -s "$DEV" shell input keyevent KEYCODE_HOME
echo "Google's home screen is back."
