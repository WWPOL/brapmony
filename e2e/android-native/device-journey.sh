#!/usr/bin/env bash
# Drive the native APK on a booted emulator and keep what each step showed.
#
# This host has no /dev/kvm, so the guest's GL output never reaches `screencap`
# (every capture is a uniform black PNG) and the emulator dies with the sandbox
# that started it. The script therefore boots, installs and drives everything in
# one run, and records `uiautomator dump` after each tap instead of pictures.
# See docs/build/evidence/android-native-emulator/README.md.
#
#   bash e2e/android-native/device-journey.sh /path/to/evidence-dir
#
# Requires: ANDROID_HOME, an `aosp30` AVD, the local stack on :54480, and the
# debug APK already built at android-native/app/build/outputs/apk/debug/.
set -u

OUT=${1:?usage: device-journey.sh <evidence-dir>}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
APK="$ROOT/android-native/app/build/outputs/apk/debug/app-debug.apk"
UIX="$ROOT/e2e/android-native/uixml.py"
SERIAL=${SERIAL:-emulator-5558}
PORT=${PORT:-5558}
mkdir -p "$OUT"

ADB="${ANDROID_HOME:?ANDROID_HOME must be set}/platform-tools/adb"
unset DISPLAY XAUTHORITY

D() { "$ADB" -s "$SERIAL" "$@"; }

# A fresh dump before every action: the screen changes under us, and acting on a
# stale tree is how a journey silently tests the wrong screen.
dump() { D exec-out uiautomator dump /dev/tty 2>/dev/null | sed -e 's/></>\n</g' > "$OUT/$1.xml"; }
texts() { python3 "$UIX" "$OUT/$1.xml" | awk -F'\t' '$2 != "-" {print $2}'; }
has() { python3 "$UIX" "$OUT/$1.xml" "$2" >/dev/null 2>&1 && python3 "$UIX" "$OUT/$1.xml" "$2" | grep -q .; }

# tap NAME STEP: dump, find NAME's own centre, tap it, then dump as STEP.
tap() {
  local name=$1 step=$2 before="$OUT/.current.xml" c
  dump .current
  c=$(python3 "$UIX" "$OUT/.current.xml" "$name" | head -1 | awk -F'\t' '{print $4}')
  if [ -z "$c" ]; then echo "!! no node named '$name' on screen"; return 1; fi
  # shellcheck disable=SC2086
  set -- $c; D shell input tap "$1" "$2"; sleep 5
  dump "$step"
  rm -f "$before"
}

EMAIL=$(node -p "JSON.parse(require('fs').readFileSync('$ROOT/e2e/fixture.json')).users.alice.email")
PASSWORD=$(node -p "JSON.parse(require('fs').readFileSync('$ROOT/e2e/fixture.json')).users.alice.password")

echo '### booting'
for pid in $(ps -eo pid,args | awk '/qemu-system-x86_64/ && !/awk/ {print $1}'); do kill -9 "$pid" 2>/dev/null; done
sleep 2; rm -f "${ANDROID_AVD_HOME:-$HOME/.android/avd}"/aosp30.avd/*.lock 2>/dev/null
# One core keeps TCG from racing its own threads, which is what was killing zygote.
"$ANDROID_HOME/emulator/emulator" -avd aosp30 -port "$PORT" -accel off -cores 1 -memory 3072 \
  -gpu swiftshader_indirect -no-window -no-audio -no-boot-anim -no-snapshot \
  -camera-back none -camera-front none > "$OUT/emulator.log" 2>&1 &
for _ in $(seq 1 40); do
  sleep 15
  [ "$(D shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
done
D shell settings put system screen_off_timeout 2147483647
D shell input keyevent 224; D shell wm dismiss-keyguard

echo '### installing and launching'
D install -r -t "$APK" | tail -1
D shell pm clear io.github.wwpol.brapmony.debug >/dev/null 2>&1
D shell am start -n io.github.wwpol.brapmony.debug/lol.mony.harmony.MainActivity >/dev/null
sleep 28

echo '### signing in'
dump 00-start
if has 00-start signin-email; then
  tap signin-email 00-typed-email
  D shell input text "$EMAIL"; sleep 3; dump 00-typed-email
  tap signin-password 00-typed-password
  D shell input text "$PASSWORD"; sleep 3; dump 00-typed-password
  tap signin-submit 01-chats; sleep 25; dump 01-chats
else
  echo '  already signed in'
  cp "$OUT/00-start.xml" "$OUT/01-chats.xml"
fi
texts 01-chats

echo '### chats, DM, send'
tap 'Bob E2E' 02-direct-message; sleep 10; dump 02-direct-message
tap message-composer 02-direct-message
# `input text` stops at a space unless it is written as %s.
MARK="device journey $(date +%s)"
D shell input text "${MARK// /%s}"; sleep 4; dump 03-draft-typed
echo "  sending: $MARK"
tap message-send 04-message-sent; sleep 15; dump 04-message-sent

echo '### back to the shell'
D shell input keyevent 4; sleep 6; dump 04b-shell
echo '### server, channel, actions'
tap 'rail-server-E2E Server' 05-server-channels; sleep 12; dump 05-server-channels
tap channel-general 06-channel-room; sleep 12; dump 06-channel-room
tap 'Message actions' 07-message-actions; sleep 6; dump 07-message-actions

echo '### server management'
D shell input keyevent 4; sleep 5; D shell input keyevent 4; sleep 5
tap 'rail-server-E2E Server' 08-server-before; sleep 10; dump 08-server-before
tap 'Server settings and members' 09-server-management; sleep 18; dump 09-server-management
tap Members 10-server-members; sleep 12; dump 10-server-members

echo '### destinations'
# Unwind whatever the last step left open until the bottom bar is back.
for _ in 1 2 3 4; do
  dump .current
  if python3 "$UIX" "$OUT/.current.xml" nav-Chats | grep -q .; then break; fi
  D shell input keyevent 4; sleep 5
done
rm -f "$OUT/.current.xml"
tap nav-Social 11-social; sleep 12; dump 11-social
tap nav-Alerts 12-alerts; sleep 12; dump 12-alerts
tap nav-You 13-you; sleep 10; dump 13-you
tap Appearance 14-appearance; sleep 12; dump 14-appearance
tap Light 15-appearance-light; sleep 8; dump 15-appearance-light
tap Drive 16-drive; sleep 18; dump 16-drive

echo '### done; dumps are in' "$OUT"
