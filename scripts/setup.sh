#!/usr/bin/env bash
# One-time setup from a computer: pair wireless adb, install Shizuku 13.6+ and the
# Handheld Tweaks app, and grant the permissions only adb can grant. After this, the
# app keeps every tweak applied by itself (including after system updates).
#
# Usage: scripts/setup.sh <phone-ip> [pairing-port pairing-code]
#   Phone: Settings > System > Developer options > Wireless debugging > Pair device with
#   pairing code. Pass the IP, the pairing port and the 6-digit code it shows. Skip the
#   pairing args if this computer is already paired.
set -euo pipefail

HOST="${1:?usage: scripts/setup.sh <phone-ip> [pairing-port pairing-code]}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SHIZUKU_TAG="v13.6.0"   # first release that can start on boot without root
APP_PKG="local.handheld.tweaks"

step() { printf '\n==> %s\n' "$*"; }

if [[ $# -ge 3 ]]; then
  step "Pairing with $HOST:$2"
  adb pair "$HOST:$2" "$3"
fi

step "Finding the wireless-debugging connect port"
PORT="$(python3 -I "$ROOT/scripts/find-adb-port.py" "$HOST" | head -1)"
[[ -n "$PORT" ]] || { echo "No open port found. Is Wireless debugging on?" >&2; exit 1; }
DEV="$HOST:$PORT"
adb connect "$DEV"
a() { adb -s "$DEV" "$@"; }
echo "Connected to $(a shell getprop ro.product.model) (Android $(a shell getprop ro.build.version.release))"

step "Installing Shizuku $SHIZUKU_TAG (GitHub release)"
current="$(a shell dumpsys package moe.shizuku.privileged.api 2>/dev/null | grep -m1 -o 'versionName=[^ ]*' || true)"
if [[ "$current" == *"13.6"* || "$current" == *"13.7"* || "$current" == *"14."* ]]; then
  echo "Already installed: $current"
else
  tmp="$(mktemp -d)"
  gh release download "$SHIZUKU_TAG" -R RikkaApps/Shizuku -p '*.apk' -D "$tmp"
  a install -r "$tmp"/*.apk
  rm -rf "$tmp"
fi
a shell pm grant moe.shizuku.privileged.api android.permission.WRITE_SECURE_SETTINGS

step "Building and installing Handheld Tweaks"
if [[ -z "${JAVA_HOME:-}" ]] && command -v brew >/dev/null; then
  JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"; export JAVA_HOME
fi
(cd "$ROOT" && ./gradlew -q :core:test :app:assembleDebug)
a install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk"
a shell pm grant "$APP_PKG" android.permission.POST_NOTIFICATIONS
a shell cmd statusbar add-tile "$APP_PKG/.tile.LandscapeTileService" || true

cat <<'DONE'

==> Almost done. Finish on the phone:
  1. Open Shizuku > "Start via Wireless debugging" > Pairing (enter the code from
     Developer options > Wireless debugging > Pair device with pairing code), then Start.
  2. Shizuku > Settings > turn on "Start on boot". It says "(root)", but 13.6 also
     starts over wireless debugging on Android 13+ when Wi-Fi is connected.
  3. Open Handheld Tweaks and tap Grant to let it use Shizuku. It then applies
     120 Hz, the debloat, the cutout override and the rest, and keeps them applied.
DONE
