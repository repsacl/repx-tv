#!/bin/bash
# Undoes tools/debloat.sh: turns all the packages back on.
# Usage:  ./tools/restore-debloat.sh <ip:port>
DEV="$1"
[ -z "$DEV" ] && { echo "Usage: ./tools/restore-debloat.sh <ip:port>"; exit 1; }
case "$DEV" in *:*) ;; *) DEV="$DEV:5555" ;; esac
adb connect "$DEV"
sleep 2
PACKAGES=(
  com.google.android.apps.tv.dreamx
  com.google.android.tvrecommendations
  com.google.android.feedback
  com.google.android.tvtutorials
  com.google.android.play.games
  com.google.android.youtube.tvmusic
  com.google.android.marvin.talkback
  com.android.printspooler
  com.google.android.katniss
  com.google.android.speech.pumpkin
)
for p in "${PACKAGES[@]}"; do
  adb -s "$DEV" shell pm enable "$p" >/dev/null 2>&1 && echo "  ✓ Turned on: $p"
done
adb -s "$DEV" reboot
echo "Done – everything is back as before."
