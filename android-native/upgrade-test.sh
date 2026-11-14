#!/usr/bin/env bash
# Two consecutive CI APKs, installed one over the other on an emulator, with the
# app's own stored data kept between them.
#
# This is the check that a downloadable build can replace the build somebody already
# has: same package, same signing certificate (by SHA-256 fingerprint, not by
# subject), and a higher versionCode. It runs against an emulator only; a person's
# phone is never touched.
#
#   android-native/upgrade-test.sh prepare <out-dir>
#   android-native/upgrade-test.sh run <out-dir> [serial]
#
# prepare builds the version in VERSION, then the same tree with the patch number
# raised by one, so the second is what a later release would produce. Both are signed
# with whatever test-keystore.properties configures (or the generated debug key) and
# named as the workflow names them. VERSION is restored afterwards.
set -euo pipefail
MODE=${1:?usage: upgrade-test.sh <prepare|run> <out-dir> [serial]}
OUT=${2:?usage: upgrade-test.sh <prepare|run> <out-dir> [serial]}
SERIAL=${3:-emulator-5554}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
HERE=$ROOT/android-native
OUT=$(mkdir -p "$OUT" && cd "$OUT" && pwd)
ADB=$ANDROID_HOME/platform-tools/adb
D() { "$ADB" -s "$SERIAL" "$@"; }
APKTOOLS=$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)
PACKAGE=io.github.wwpol.brapmony.debug

version_code() { "${APKTOOLS}aapt2" dump badging "$1" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -1; }
package_name() { "${APKTOOLS}aapt2" dump badging "$1" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -1; }
certificate() { "${APKTOOLS}apksigner" verify --print-certs "$1" | sed -n 's/^.*certificate SHA-256 digest: //p' | head -1; }

describe() {
  local apk=$1 label=$2
  {
    echo "$label"
    echo "  file: $(basename "$apk")"
    echo "  $("${APKTOOLS}aapt2" dump badging "$apk" | head -1)"
    echo "  certificate SHA-256: $(certificate "$apk")"
    echo "  sha256: $(sha256sum "$apk" | cut -d' ' -f1)"
  } | tee "$OUT/$label.txt"
}

if [ "$MODE" = prepare ]; then
  version=$(tr -d '[:space:]' < "$ROOT/VERSION")
  major=${version%%.*}; rest=${version#*.}; minor=${rest%%.*}; patch=${rest#*.}
  next="$major.$minor.$((patch + 1))"
  cp "$ROOT/VERSION" "$OUT/VERSION.orig"
  trap 'cp "$OUT/VERSION.orig" "$ROOT/VERSION" 2>/dev/null || true' EXIT
  echo "### first build: $version"
  cd "$HERE"
  ./gradlew :app:assembleDebug --console=plain --max-workers=2 > "$OUT/build-1.log" 2>&1
  cp app/build/outputs/apk/debug/app-debug.apk "$OUT/first.apk"
  echo "### second build: $next"
  printf '%s\n' "$next" > "$ROOT/VERSION"
  ./gradlew :app:assembleDebug --console=plain --max-workers=2 > "$OUT/build-2.log" 2>&1
  cp app/build/outputs/apk/debug/app-debug.apk "$OUT/second.apk"
  cp "$OUT/VERSION.orig" "$ROOT/VERSION"
  # name-artifact.sh takes the version from tauri.conf.json, which a release stamps from the
  # tag, so both files would get one name. Each is renamed here to the version its own manifest
  # reports, which is the number Android compares.
  sha=$(git rev-parse --short=7 HEAD)
  mv "$OUT/first.apk" "$OUT/Harmony_Android_Native_V$version-debug-master-$sha.apk"
  mv "$OUT/second.apk" "$OUT/Harmony_Android_Native_V$next-debug-master-$sha.apk"
  first="$OUT/Harmony_Android_Native_V$version-debug-master-$sha.apk"
  second="$OUT/Harmony_Android_Native_V$next-debug-master-$sha.apk"
  describe "$first" first
  describe "$second" second
  first_pkg=$(package_name "$first"); second_pkg=$(package_name "$second")
  first_cert=$(certificate "$first"); second_cert=$(certificate "$second")
  first_code=$(version_code "$first"); second_code=$(version_code "$second")
  [ "$first_pkg" = "$second_pkg" ] || { echo "::error::different packages"; exit 1; }
  [ "$first_cert" = "$second_cert" ] || { echo "::error::different certificates: $first_cert and $second_cert"; exit 1; }
  [ "$second_code" -gt "$first_code" ] || { echo "::error::versionCode did not increase"; exit 1; }
  echo "### same package $first_pkg, same certificate, versionCode $first_code -> $second_code"
  echo "### prepare OK"
  exit 0
fi
if [ "$MODE" = run ]; then
  # The two files `prepare` wrote, oldest version first; sort -V orders the version
  # numbers, not the letters of their names.
  first=$(ls "$OUT"/Harmony_Android_Native_V*-debug-master-*.apk | sort -V | head -1)
  second=$(ls "$OUT"/Harmony_Android_Native_V*-debug-master-*.apk | sort -V | tail -1)
  second_code=$(version_code "$second")
  D wait-for-device
  echo "### installing the first build"
  D uninstall "$PACKAGE" > /dev/null 2>&1 || true
  D install "$first" | tail -1
  D shell am start -n "$PACKAGE/lol.mony.harmony.MainActivity" > /dev/null 2>&1 || true
  sleep 25
  # The data directory exists only once the app has run once, and a first start on a cold
  # emulator can take a while; wait for it rather than racing it.
  for i in $(seq 1 24); do
    D shell "run-as $PACKAGE ls data" > /dev/null 2>&1 && break
    sleep 5
  done
  # `adb shell` splits its arguments, so the script is one quoted word.
  D shell "run-as $PACKAGE mkdir -p files" > /dev/null 2>&1 || true
  D shell "run-as $PACKAGE sh -c 'echo upgrade-probe > files/upgrade-probe.txt'"
  D shell "run-as $PACKAGE ls -l files shared_prefs 2>/dev/null" > "$OUT/data-before.txt" 2>&1 || true
  probe_before=$(D shell "run-as $PACKAGE cat files/upgrade-probe.txt" 2>/dev/null | tr -d '\r')
  code_before=$(D shell dumpsys package "$PACKAGE" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1)
  echo "  probe before: ${probe_before:-<absent>}"; echo "  versionCode before: ${code_before:-?}"
  echo "### installing the second build over it, with no uninstall"
  D install -r "$second" | tail -1
  sleep 15
  D shell am start -n "$PACKAGE/lol.mony.harmony.MainActivity" > /dev/null 2>&1 || true
  sleep 20
  D shell "run-as $PACKAGE ls -l files shared_prefs 2>/dev/null" > "$OUT/data-after.txt" 2>&1 || true
  probe_after=$(D shell "run-as $PACKAGE cat files/upgrade-probe.txt" 2>/dev/null | tr -d '\r')
  code_after=$(D shell dumpsys package "$PACKAGE" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1)
  echo "  probe after: ${probe_after:-<absent>}"; echo "  versionCode after: ${code_after:-?}"
  status=0
  if [ -z "$probe_after" ] || [ "$probe_after" != "$probe_before" ]; then echo "::error::the app's data did not survive the upgrade"; status=1; fi
  if [ "${code_after:-0}" != "$second_code" ]; then echo "::error::installed versionCode ${code_after:-?}, expected $second_code"; status=1; fi
  D logcat -d -b crash 2>/dev/null | tail -40 > "$OUT/crash.txt"
  if grep -q 'FATAL EXCEPTION' "$OUT/crash.txt"; then echo '::error::the app crashed'; cat "$OUT/crash.txt"; status=1; fi
  D shell dumpsys package "$PACKAGE" > "$OUT/package-after.txt" 2>&1 || true
  if [ $status -eq 0 ]; then echo '### upgrade in place: OK'; else echo '### upgrade in place: FAILED'; fi
  exit $status
fi
echo "usage: upgrade-test.sh <prepare|run> <out-dir> [serial]" >&2
exit 2