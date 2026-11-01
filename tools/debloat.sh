#!/bin/bash
# Optional: turns off some background services on a Chromecast with Google TV to free up memory.
# Uses "disable-user": nothing is deleted, and tools/restore-debloat.sh turns everything back on.
#
# Usage:  ./tools/debloat.sh <ip:port>

DEV="$1"
[ -z "$DEV" ] && { echo "Usage: ./tools/debloat.sh <ip:port>"; exit 1; }
case "$DEV" in *:*) ;; *) DEV="$DEV:5555" ;; esac
command -v adb >/dev/null 2>&1 || { echo "adb not found. On a Mac: brew install --cask android-platform-tools"; exit 1; }

echo "Connecting to $DEV ... (accept 'Allow debugging' on the TV if asked)"
adb connect "$DEV"
sleep 2
adb -s "$DEV" get-state >/dev/null 2>&1 || { echo "Couldn't connect. Check that wireless debugging is on, the ip:port is right, and you've paired with 'adb pair'."; exit 1; }

# Safe to turn off (doesn't affect casting, Netflix, YouTube etc.)
PACKAGES=(
  com.google.android.apps.tv.dreamx        # Ambient mode / photo screensaver
  com.google.android.tvrecommendations     # Background recommendations (protected on some devices)
  com.google.android.feedback              # Sends feedback to Google
  com.google.android.tvtutorials           # Tutorial videos
  com.google.android.play.games            # Google Play Games
  com.google.android.youtube.tvmusic       # YouTube Music (remove this line if you use it)
  com.google.android.marvin.talkback       # TalkBack screen reader
  com.android.printspooler                 # Printing (pointless on a TV)
)
# Optional: uncomment if you DON'T use voice search
# PACKAGES+=( com.google.android.katniss )        # Google Assistant / voice search
# PACKAGES+=( com.google.android.speech.pumpkin ) # Offline speech recognition

INSTALLED=$(adb -s "$DEV" shell pm list packages | sed 's/package://' | tr -d '\r')
for p in "${PACKAGES[@]}"; do
  if echo "$INSTALLED" | grep -qx "$p"; then
    if adb -s "$DEV" shell pm disable-user --user 0 "$p" >/dev/null 2>&1; then
      echo "  ✓ Turned off: $p"
    else
      echo "  ✗ Couldn't turn off: $p"
    fi
  else
    echo "  – Not on this device: $p"
  fi
done

echo "Clearing app caches ..."
adb -s "$DEV" shell pm trim-caches 999G >/dev/null 2>&1
echo "Restarting ..."
adb -s "$DEV" reboot
echo "Done! Give it a minute to start up."
