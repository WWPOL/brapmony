# Brapmony native Android client (in progress)

A Kotlin / Jetpack Compose Material 3 client for Harmony instances, alongside the existing
Tauri Android client. **This is not a parity-complete replacement.** Do not deploy it as one:
voice/video, encryption/recovery, push, uploads and several other flows are not implemented.
The [design](../docs/build/native-android.md) and [parity inventory](../docs/build/native-android-parity.md)
explain the boundaries. The existing Android client is unchanged.

## Build

Install JDK 21 and the official Android command-line tools, then:

```sh
sdkmanager 'platform-tools' 'platforms;android-37.0' 'build-tools;37.0.0'
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk21
cd android-native
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

AGP 9.5.1 supplies built-in Kotlin; the project deliberately does **not** apply the old
`org.jetbrains.kotlin.android` plugin. The wrapper pins Gradle 9.9.1 and verifies its official
SHA-256. Compose BOM is 2026.09.00. Compile and target SDK are 37; minimum SDK is 26. Android 37 behaviour changes are not yet emulator-verified.
The debug app ID is `io.github.wwpol.brapmony.debug`; it does not replace the official app.

No signing keys, SDK installations, credentials or generated APKs belong in Git. Release builds
are unsigned unless signing is configured externally; release distribution has not been verified.

## Run against the real local test stack

From the repository root:

```sh
npm ci
# up replaces ONLY the disposable Harmony e2e stack and its fixtures.
bash e2e/stack.sh up
bash e2e/stack.sh seed
bash e2e/stack.sh smoke
bash android-native/demo.sh
```

`demo.sh` needs an already booted emulator and a healthy stack on port 54480. Set
`ANDROID_SERIAL` if several devices are attached, and `ADB_SERVER_SOCKET` when using a
non-default adb server. It builds a debug APK configured for the emulator's host gateway
`http://10.0.2.2:54480`, installs and opens it. The e2e gateway does not validate `apikey`, so
`local-e2e` is a harmless public placeholder there; **authentication still goes through real
GoTrue and requests still use the user's signed JWT and RLS**. No service-role key is packaged.
To use another instance, leave build properties unset and use the instance picker, which
reads `/api/federation/instance-info` just like the official native client.

Use the throwaway `fx_alice` account in `e2e/fixture.json`, or register a new account and create
its profile. Do not use a real account for this preview. Password reset mail requires an SMTP
provider; the disposable stack has none. It also has no realtime/federation/voice services,
so it cannot substantiate claims about those capabilities.

An unaccelerated emulator is usable, but on this test host an AOSP API 30 image needed about
four to six minutes to boot and up to 95 seconds to draw a first Compose frame. Check exact
`sys.boot_completed=1`, disable display sleep, and inspect actual pixels as well as the UI tree.
The harness runtime also unexpectedly stopped several emulator jobs; this is distinct from a
verified Android crash or software emulation simply being slow.

## Tests

Unit/model/MockWebServer tests run without a server. The core messaging contract test can also
use the real seeded stack:

```sh
export NATIVE_CONTRACT_URL=http://localhost:54480
export NATIVE_CONTRACT_EMAIL=...       # fixture.users.alice.email
export NATIVE_CONTRACT_PASSWORD=...    # fixture.users.alice.password; never log it
./gradlew :app:testDebugUnitTest
```

That contract exercises actual Kotlin auth/profile/server/message repositories, including
fixtures whose auth and profile IDs differ, CRUD, reactions, pin RPCs, threads, DMs and read
markers. The account and Drive modules have separate opt-in integration tests documented in
their test sources; an unset environment variable skips them rather than claiming a pass.

Successful compilation and backend contracts are **not** visual or interaction verification.
See the verification ledger for the screens actually inspected and the remaining gaps.
