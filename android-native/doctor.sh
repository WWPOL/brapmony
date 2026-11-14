#!/usr/bin/env bash
# Diagnose a launch that aborts in zygote before any app code runs.
#
#   android-native/doctor.sh --serial emulator-5558
#   android-native/doctor.sh --serial emulator-5558 --repair --apk <same build>.apk
#
# The failure is the platform's, not the app's: zygote64 cannot bind-mount the
# app's device-protected data directory, so it aborts the child process before
# any app code runs:
#
#   Failed to mount /data_mirror/data_de/null/0/<pkg> to /data/user_de/0/<pkg>:
#   No such file or directory
#
# docs/build/native-android-launch-data-directory.md explains the mechanism.
#
# Read-only unless --repair. --repair reinstalls a build you name with `adb install
# -r`, which recreates the directory through the normal install path and keeps the
# app's files, session and drafts. It never uninstalls and never clears app data. A
# physical serial is refused unless --allow-physical is given.
set -uo pipefail

SERIAL=
PACKAGE=io.github.wwpol.brapmony.debug
APK=
REPAIR=no
ALLOW_PHYSICAL=no

usage() { sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//' >&2; }

while [ $# -gt 0 ]; do
  case $1 in
    --serial) SERIAL=${2:-}; shift 2 ;;
    --package) PACKAGE=${2:-}; shift 2 ;;
    --apk) APK=${2:-}; shift 2 ;;
    --repair) REPAIR=yes; shift ;;
    --allow-physical) ALLOW_PHYSICAL=yes; shift ;;
    -h|--help) usage; exit 0 ;;
    *) printf 'doctor.sh: unknown argument: %s\n' "$1" >&2; usage; exit 2 ;;
  esac
done

# Never guess a device. An unset serial is an error, not 'the only one attached':
# on a machine with a phone plugged in, guessing means writing to the phone.
[ -n "$SERIAL" ] || { echo 'doctor.sh: --serial is required; no device is guessed' >&2; exit 2; }
case $SERIAL in
  emulator-*|127.0.0.1:*|localhost:*) ;;
  *)
    if [ "$ALLOW_PHYSICAL" != yes ]; then
      printf 'doctor.sh: %s is not an emulator; pass --allow-physical to inspect it\n' "$SERIAL" >&2
      exit 2
    fi
    ;;
esac
if [ "$REPAIR" = yes ] && [ -z "$APK" ]; then
  echo 'doctor.sh: --repair needs --apk (the same build, reinstalled in place)' >&2
  exit 2
fi

if [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
  ADB=$ANDROID_HOME/platform-tools/adb
elif command -v adb >/dev/null 2>&1; then
  ADB=adb
else
  echo 'doctor.sh: no adb; set ANDROID_HOME or put adb on PATH' >&2
  exit 2
fi

D() { "$ADB" -s "$SERIAL" "$@"; }
has_dir() { D shell "test -d '$1'" >/dev/null 2>&1; }
FAILED=no

printf '== device %s\n' "$SERIAL"
if ! D get-state >/dev/null 2>&1; then
  printf 'doctor.sh: %s is not attached\n' "$SERIAL" >&2
  exit 1
fi
printf '  model: %s\n' "$(D shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
printf '  sdk:   %s\n' "$(D shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"

printf '== package %s\n' "$PACKAGE"
if ! D shell pm path "$PACKAGE" 2>/dev/null | grep -q '^package:'; then
  printf 'doctor.sh: %s is not installed for user 0 on %s\n' "$PACKAGE" "$SERIAL" >&2
  exit 1
fi
printf '  version: %s\n' "$(D shell dumpsys package "$PACKAGE" 2>/dev/null | sed -n 's/.*versionName=\([^ ]*\).*/\1/p' | head -1)"

# The paths the abort names. The mirror entry is the bind mount's source; the
# /data/user_de path is the target, which zygote creates itself just before mounting,
# so a missing source is the only way this message can name 'No such file'.
MIRROR=/data_mirror/data_de/null/0/$PACKAGE
USERDE=/data/user_de/0/$PACKAGE
CE=/data/user/0/$PACKAGE

printf '== data directories\n'
for path in "$MIRROR" "$USERDE" "$CE"; do
  if has_dir "$path"; then printf '  present: %s\n' "$path"; else printf '  MISSING: %s\n' "$path"; fi
done

printf '== the abort, if the device already logged one\n'
ABORT=$(D logcat -d -b main,system,crash 2>/dev/null | grep -m1 -F "Failed to mount $MIRROR to $USERDE")
if [ -n "$ABORT" ]; then printf '  %s\n' "$ABORT"; else echo '  none in the current buffers'; fi

printf '== verdict\n'
if ! has_dir "$MIRROR"; then
  FAILED=yes
  echo '  the device-protected mirror entry is missing.'
  if has_dir "$CE"; then
    echo "  The app's own data is still there, so nothing is lost yet: this is the state"
    echo '  that aborts every launch in zygote64, before any app code runs.'
  else
    echo "  The app data directory is missing too. A reinstall in place is still the"
    echo '  safe first step; only if that fails is there nothing left to preserve.'
  fi
  echo '  Safe repair, keeps app data: reinstall the same build in place.'
  printf '    %s --serial %s --repair --apk <the same build>.apk\n' "$0" "$SERIAL"
else
  echo '  both data directories are present: this is not the reported failure.'
fi

if [ "$REPAIR" = yes ]; then
  if has_dir "$MIRROR"; then
    echo '== repair: not needed, the directory is present'
  else
    printf '== repair: reinstalling %s in place\n' "$APK"
    if [ ! -f "$APK" ]; then printf 'doctor.sh: no such APK: %s\n' "$APK" >&2; exit 2; fi
    # -r is the whole safety property: replace the code, keep the data. There is no
    # uninstall and no pm clear anywhere in this script.
    # The status has to come from adb, not from tail, so the install is captured
    # first and judged afterwards.
    INSTALL_LOG=$(D install -r "$APK" 2>&1)
    INSTALL_STATUS=$?
    printf '%s\n' "$INSTALL_LOG" | tail -2
    if [ "$INSTALL_STATUS" -ne 0 ]; then
      echo 'doctor.sh: the in-place install failed; nothing was removed' >&2
      exit 1
    fi
    if has_dir "$MIRROR"; then
      echo '  repaired: the mirror entry is back, and the app data was not touched'
      echo '  launch the app; if it starts, the report is resolved'
      FAILED=no
    else
      echo '  still missing after the in-place install.'
      echo '  Next safe step: back up what you need, then uninstall and install again.'
      FAILED=yes
    fi
  fi
fi

[ "$FAILED" = yes ] && exit 1
exit 0
