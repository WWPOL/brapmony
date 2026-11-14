#!/usr/bin/env bash
# Names a CI build output. One helper for tauri.yml, release.yml and
# android-native.yml, so dev builds and releases share one scheme:
#
#   bash scripts/name-artifact.sh [--release] [--native] <Windows|macOS|Android|Linux|iOS> <release|debug> [file]
#
# With <file>, renames it in place to <stem>.<ext>, prints the new path and
# appends `path=<new path>` to GITHUB_OUTPUT when that is set. Without <file>,
# prints <stem>.
#
# Dev build (tauri.yml):  Harmony_<Platform>_V<version>_<kind>-<ref>-<sha7>
# --release (v* tag):     Harmony_<Platform>_V<version>, `_debug` appended for
#                         a debug build
#
# --native names the Jetpack Compose client in android-native/. It ships from the same
# release as the Tauri Android APK and must not collide with it:
#
#   dev build:  Harmony_Android_Native_V<version>_<kind>-<ref>-<sha7>.apk
#   --release:  Harmony_Android_Native_V<version>.apk, `_debug` appended for a
#               test-signed build
#
# `_debug` is a signing statement, not only a build type: it says the APK carries the
# repository's test key, so Android refuses it as an update of a release-signed install.
#
# <kind>     `dev` for a release-profile build, `debug` for a debug one.
# <version>  src-tauri/tauri.conf.json `version`, the version of every app here.
# <ref>      GITHUB_REF_NAME, each run of characters outside [A-Za-z0-9.-]
#            replaced by one `-`, cut to 40 characters.
# <sha7>     first 7 characters of GITHUB_SHA.
#
# Runs from the root of the tree being built. Bash 3.2 and BSD userland (macOS
# runners) compatible.
set -euo pipefail

usage() {
  echo "usage: $0 [--release] [--native] <Windows|macOS|Android|Linux|iOS> <release|debug> [file]" >&2
  exit 2
}

channel=dev
native=0
while :; do
  case "${1:-}" in
    --release) channel=release; shift ;;
    --native) native=1; shift ;;
    *) break ;;
  esac
done
[ $# -eq 2 ] || [ $# -eq 3 ] || usage
platform=$1
profile=$2
file=${3:-}

case $platform in
  Windows | macOS | Android | Linux | iOS) ;;
  *) usage ;;
esac
case $profile in
  release | debug) ;;
  *) usage ;;
esac
if [ -n "$file" ] && [ ! -f "$file" ]; then
  echo "$0: no build output at $file" >&2
  exit 1
fi
# The native client is the Android one; naming it any other platform would produce a
# stem that says nothing about where the APK came from.
if [ "$native" = 1 ] && [ "$platform" != Android ]; then
  echo "$0: --native is the Android client only, not $platform" >&2
  exit 2
fi

version=$(node -p "require('./src-tauri/tauri.conf.json').version")
if ! printf '%s\n' "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
  echo "$0: src-tauri/tauri.conf.json version is not x.y.z: $version" >&2
  exit 1
fi

label=$platform
[ "$native" = 0 ] || label="${platform}_Native"
stem="Harmony_${label}_V${version}"
if [ "$channel" = release ]; then
  [ "$profile" = release ] || stem="${stem}_debug"
else
  if [ -z "${GITHUB_REF_NAME:-}" ] || [ -z "${GITHUB_SHA:-}" ]; then
    echo "$0: GITHUB_REF_NAME and GITHUB_SHA must be set" >&2
    exit 1
  fi
  case $profile in
    release) kind=dev ;;
    debug) kind=debug ;;
  esac
  ref=$(printf '%s' "$GITHUB_REF_NAME" | sed -e 's/[^A-Za-z0-9.-][^A-Za-z0-9.-]*/-/g' -e 's/--*/-/g')
  ref=${ref:0:40}
  ref=${ref#-}
  ref=${ref%-}
  stem="${stem}_${kind}-${ref}-${GITHUB_SHA:0:7}"
fi

if [ -z "$file" ]; then
  printf '%s\n' "$stem"
  exit 0
fi

dest="$(dirname "$file")/$stem.${file##*.}"
[ "$file" -ef "$dest" ] || mv -- "$file" "$dest"

printf '%s\n' "$dest"
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  printf 'path=%s\n' "$dest" >>"$GITHUB_OUTPUT"
fi