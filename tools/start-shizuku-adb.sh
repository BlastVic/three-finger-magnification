#!/usr/bin/env bash
# Start the Shizuku server from a computer over USB, then (optionally) rebind
# this app's accessibility service. Useful after a reboot on non-rooted devices
# and on ROMs that do not support wireless-debugging startup.
#
#   bash tools/start-shizuku-adb.sh                 # official start.sh
#   bash tools/start-shizuku-adb.sh SERIAL         # pick one device
#   bash tools/start-shizuku-adb.sh --native       # use libshizuku.so instead
#   ADB=/path/to/adb bash tools/start-shizuku-adb.sh
#
# See https://shizuku.rikka.app/guide/setup/ for the official instructions.
set -u

ADB="${ADB:-adb}"
SHIZUKU_PKG="moe.shizuku.privileged.api"
A11Y="com.zoomtap.app/.ZoomAccessibilityService"
USE_NATIVE=0
SERIAL=""

for arg in "$@"; do
  case "$arg" in
    --native) USE_NATIVE=1 ;;
    *) SERIAL="$arg" ;;
  esac
done

if ! command -v "$ADB" >/dev/null 2>&1 && [ ! -x "$ADB" ]; then
  echo "adb not found. Install Android SDK Platform Tools or set ADB=/path/to/adb"
  exit 1
fi

"$ADB" start-server >/dev/null 2>&1

if [ -z "$SERIAL" ]; then
  COUNT=$("$ADB" devices | grep -c $'\tdevice$' || true)
  if [ "$COUNT" -eq 0 ]; then
    echo "No device in 'device' state. Check the USB cable and the debugging authorisation dialog."
    "$ADB" devices -l
    exit 1
  elif [ "$COUNT" -gt 1 ]; then
    echo "More than one device connected - pass its serial as the first argument."
    "$ADB" devices -l
    exit 1
  fi
  SERIAL=$("$ADB" devices | grep $'\tdevice$' | head -1 | cut -f1)
fi

DEV=("$ADB" -s "$SERIAL")
if ! "${DEV[@]}" get-state >/dev/null 2>&1; then
  echo "Device $SERIAL is not ready."
  "$ADB" devices -l
  exit 1
fi

APK=$("${DEV[@]}" shell pm path "$SHIZUKU_PKG" 2>/dev/null | tr -d '\r' | sed -n 's/^package://p' | head -1)
if [ -z "$APK" ]; then
  echo "Shizuku ($SHIZUKU_PKG) is not installed on $SERIAL."
  echo "Install it from https://shizuku.rikka.app/ first."
  exit 1
fi

echo "[1/3] Starting the Shizuku server on $SERIAL ..."
if [ "$USE_NATIVE" -eq 1 ]; then
  # Some ROMs kill the start.sh shell before it detaches. Running the bundled
  # native launcher directly leaves shizuku_server with ppid=1 instead.
  LIB="$(dirname "$APK")/lib/arm64/libshizuku.so"
  "${DEV[@]}" shell "timeout 8 $LIB" >/dev/null 2>&1 || true
else
  "${DEV[@]}" shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh 2>&1 | head -20
fi
sleep 2

echo "[2/3] Status ..."
"${DEV[@]}" shell "ps -A 2>/dev/null | grep shizuku_server || echo 'WARNING: shizuku_server not visible'"

echo "[3/3] Accessibility service ..."
# Some OEMs roll back this secure setting while the app is in the stopped
# state. Launch it once from the launcher before writing the setting.
"${DEV[@]}" shell "monkey -p com.zoomtap.app -c android.intent.category.LAUNCHER 1" >/dev/null 2>&1
"${DEV[@]}" shell "settings put secure enabled_accessibility_services $A11Y; settings put secure accessibility_enabled 1; settings put secure touch_exploration_enabled 1" >/dev/null 2>&1
sleep 2
"${DEV[@]}" shell "dumpsys accessibility 2>/dev/null | grep -E 'Bound services' | head -2"

echo
echo "Done. If Triple-Tap Zoom asks for Shizuku permission, choose 'Always allow'."
echo "Remember: on non-rooted devices this has to be repeated after every reboot."
