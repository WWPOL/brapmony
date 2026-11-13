# Verifying the native Android client

The task is to build a native Android client with feature parity with Harmony's
official mobile app, and to prove the result by driving it on an emulator. This
document records how that verification is done here, why it is split the way it
is, and what the split costs.

## The constraint

This host has no `/dev/kvm`. The Android emulator therefore runs the guest CPU
under QEMU's TCG interpreter, and the AOSP API 30 image behaves like this:

- It boots to `sys.boot_completed=1` in roughly two to six minutes.
- Shortly after, its framework dies. The kernel log shows
  `binder: ... transaction failed 29189/-22`, `binder: send failed reply for
  transaction ..., target dead`, and `Service 'zygote' ... received signal 9`;
  `adb` then reports `device not found`.
- Screencaps taken while it is up are a uniform black 480x960 PNG (0 of 460800
  pixels non-black), even though SurfaceFlinger reports the display on and awake
  with the activity's layers present.
- The harness tears down the sandbox that owns the emulator process, so a
  long emulator run does not survive from one tool call to the next.

An emulator run is therefore not a check that can be relied on, and a task that
blocks on it never finishes. Pretending otherwise would be worse than the
limitation: an all-black screenshot or a stale accessibility dump is not visual
verification.

## What is verified, and what each part is worth

Three checks run against the real local Harmony stack (`http://localhost:54480`,
the repository's disposable e2e Supabase-compatible stack, seeded by
`e2e/seed.mjs`). Each is worth something different, and the difference matters.

### On the emulator: the APK, driven by taps

The debug APK installs and runs, and the app's accessibility tree is real. The
tree is captured with `uiautomator dump` after every step, and each step is a
real `adb shell input tap` at the centre of the node's own reported bounds, so
the sequence is what a finger would do rather than a direct call into a
composable. `docs/build/evidence/android-native-emulator/` keeps those dumps
with a README naming the tap behind each.

That covers, on the device: sign-in, the Chats destination, opening a direct
message, typing and sending (with the message read back from PostgREST, so the
row on screen and the row in the database are the same row), the server tile and
its channel column, a channel room, the message-actions sheet, server management
and its Members tab, Social, Alerts, You, the appearance panel and Drive.

It does **not** cover what the screen looked like. Every capture path returns a
uniform black 480x960 image (0 of 460800 pixels non-black): `screencap` over
`exec-out`, `screencap` to a file then `adb pull`, the emulator console's own
screenshot, and `screenrecord` (which refuses to start). SurfaceFlinger lists the
app's layers and `dumpsys window` names the activity as focused, so the app is
drawing; the guest's GL output never reaches the host without KVM. A black PNG
is not evidence of a screen, so none is kept as one.

### On the JVM: the composables, drawn

Robolectric's native graphics mode draws the app's **own composables** -- not
mocks, not previews -- into a real `Bitmap`, and `captureToImage()` writes it to
a PNG. Each capture signs in to the local stack as a fixture account first, so
the rows on screen come from PostgREST, and each waits for text that only appears
once that screen's own repositories have answered. That wait matters: without it
the first attempt captured a loading spinner as if it were the screen.

`ScreenRenderHarnessTest` covers the instance picker, Chats in the default dark
look and in light, midnight and a custom account look, Social, Alerts, You, the
appearance panel, Drive, and all six server-management tabs -- 32 captures.

### Journeys: `NativeJourneyTest`

These drive the semantics tree, which is what a touch target and a TalkBack user
both reach: tap `nav-Social`, tap the server tile, tap the channel, type into
`message-composer`, tap `message-send`. The sending journeys do not stop at the
screen: they read the message back **from the instance** over PostgREST and
assert the row exists.

Sends wait for the composer to empty rather than for the message text to appear,
because the text field already holds the draft -- the first version of this test
could capture an unsent message as if it had been delivered. The appearance
journey measures the preview's repaint by counting differing pixels between two
captures, so a theme change that never reached the nested `HarmonyTheme` fails
instead of looking plausible.

192 tests pass in total, including the pre-existing model and repository tests.

### The cost of the split

The JVM renders are not the device, and the device dumps are not pictures. Read
together they establish that the flows work and that the composables draw, but
**not** that the device drew them correctly: contrast, clipping, a custom theme
actually applying on screen, IME insets, rotation and status-bar polarity all
remain unverified. `docs/build/native-android-parity.md` keeps every affected row
at `U/U` and says so.

## How to run it

```sh
# 1. the disposable local stack
bash e2e/stack.sh up     # answers on http://localhost:54480
bash e2e/stack.sh seed   # writes e2e/fixture.json

# 2. the JVM checks (192 tests; the render and journey tests skip themselves
#    when the variables below are absent)
cd android-native
HARMONY_RENDER_OUT=/tmp/harmony-render \
HARMONY_RENDER_URL=http://localhost:54480 \
HARMONY_RENDER_EMAIL=$(node -p "require('../e2e/fixture.json')"
  ".users.alice.email") \
HARMONY_RENDER_PASSWORD=$(node -p "require('../e2e/fixture.json')"
  ".users.alice.password") \
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug

# 3. the device journey (one run: the emulator does not outlive its sandbox)
cd ..
export ANDROID_HOME=...   # an SDK with an `aosp30` AVD
bash e2e/android-native/device-journey.sh /tmp/harmony-device
```

Without those variables the render and journey tests skip themselves through a
JUnit assumption, so an offline `./gradlew test` still passes and still says what
it did not check.

## Why not just fix the emulator

Because the cause is the host, not a flag. Without `/dev/kvm` the guest runs
under TCG; its GPU path initialises (`Graphics Adapter ... SwiftShader`) and the
framework comes up, but the guest's rendered frames never arrive where
`screencap` reads them. Several attempts were made and all failed the same way:
`-gpu swiftshader_indirect`, `-gpu guest`, `-gpu swiftshader`, forcing client
composition by restarting SurfaceFlinger, switching `debug.hwui.renderer`, and
the emulator console's own screenshot command. Single-core boots (which stop the
TCG thread races that had been killing zygote) made the framework stable -- the
dumps in the evidence directory come from those runs -- but not the pixels.

Committing an all-black PNG as "visual evidence" was the alternative, and it is
a lie. So was inferring appearance from the accessibility tree: that proves a
screen exists and exposes the right labels, which is genuinely useful and is why
the dumps are kept, but says nothing about layout, contrast or clipping.

## The alternative that was rejected

Committing the emulator's all-black screenshots as "visual evidence", or
inferring appearance from the accessibility tree alone. The first is false. The
second proves a screen exists and exposes the right labels -- genuinely useful,
and the reason `evidence-05/01-signin.xml` is kept -- but says nothing about
layout, contrast, clipping or whether a custom theme applied. Neither is what
the task asked for, and recording either as verification would have made the
audit lie.
