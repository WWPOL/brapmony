#!/usr/bin/env bash
# Install the native preview against the already running disposable Harmony stack.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if command -v adb >/dev/null 2>&1; then ADB="$(command -v adb)"
elif [ -n "$SDK" ] && [ -x "$SDK/platform-tools/adb" ]; then ADB="$SDK/platform-tools/adb"
else printf 'Install Android platform-tools and set ANDROID_HOME first.\n' >&2; exit 1; fi
if ! curl -fsS http://localhost:54480/health >/dev/null; then
    printf 'Start and seed the disposable stack first (see android-native/README.md).\n' >&2
    exit 1
fi
"$ADB" get-state >/dev/null
cd "$ROOT/android-native"
./gradlew :app:assembleDebug --console=plain \
    -PharmonyInstanceUrl="${NATIVE_INSTANCE_URL:-http://10.0.2.2:54480}" \
    -PharmonyAnonKey="${NATIVE_ANON_KEY:-local-e2e}"
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" shell am start -n io.github.wwpol.brapmony.debug/lol.mony.harmony.MainActivity
printf '\nNative preview opened. Sign in with a disposable e2e account from e2e/fixture.json.\n'
printf 'This preview is not feature-parity complete. See docs/build/native-android-parity.md.\n'
