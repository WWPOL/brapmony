# Verifying the native Android client

The task is to build a native Android client with feature parity with Harmony's
official mobile app, and to prove the result by driving it on an emulator. This
document records how that verification is done here, why it is split the way it
is, and what the split costs.

## A real phone is also part of this

The emulator below is the constrained host, but a physical phone is reachable over
wireless ADB, and it is the device the missing-author bug was reported from. A
build can therefore be installed over the existing app with `adb install -r -t` --
no uninstall, no `pm clear`, so the session and drafts survive -- and captured with
`adb exec-out screencap -p`, which does produce real pixels. The screenshots in
`docs/build/evidence/android-native-device/` were taken that way, against a real
instance with real message data.

## The constraint

This host has no `/dev/kvm`. The Android emulator therefore runs the guest CPU
under QEMU's TCG interpreter, and the AOSP API 30 image behaves like this:

- It boots to `sys.boot_completed=1` in roughly two to six minutes.
- Its framework used to die shortly after boot, with `binder: ... transaction
  failed 29189/-22` and `Service 'zygote' ... received signal 9` in the kernel
  log, after which `adb` reported `device not found`. Running the guest on
  **one core** stops the TCG thread races behind that: every run since has
  stayed up long enough to install and drive the APK.
- Screencaps taken while it is up are a uniform black 480x960 PNG (0 of 460800
  pixels non-black), even though SurfaceFlinger reports the display on and awake
  with the activity's layers present.
- The harness tears down the sandbox that owns the emulator process, so the
  emulator does not survive from one tool call to the next. A journey has to
  boot, install and drive in a single run.

So the emulator is usable for **function**, in one long call, and not for
**pixels**: every screenshot path returns black (see below). A task that
blocks on device screenshots never finishes, and recording an all-black
image as visual evidence would make the audit lie.

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

A second run drives the two screens that were crashing on a real phone: a
channel history seeded with the payload shape that broke it (an emoji part whose
`emoji` is an object, mixed with text) and the Threads and pinned sheets whose
deferred lists read a nullable the tap had just cleared. Both are in
`docs/build/evidence/android-native-emulator/` as `16`-`19`, and the run also
reads the crash buffer after each phase and requires it to hold no
`FATAL EXCEPTION`. It installs over the existing app without clearing its data.

It does **not** cover what the screen looked like. Every capture path was
tried and all of them fail the same way:

- `screencap` over `exec-out`, and `screencap` to a file then `adb pull`.
- The emulator console's own screenshot, and `screenrecord` (which refuses to
  start).
- The emulator's gRPC `getScreenshot`, which reads the host-side framebuffer
  instead of the guest's. `e2e/android-native/grpcshot.py` speaks enough HTTP/2
  and protobuf to call it. It answers, but returns the same 9942-byte black
  image every time; the only non-black frame it ever produced was the boot
  animation, before the app took the display. That is the channel working and
  the app's frames missing, not a tool that was never tried.

SurfaceFlinger lists the app's layers and `dumpsys window` names the activity as
focused, so the app is drawing; the guest's GL output never reaches the host
without KVM. A black PNG is not evidence of a screen, so none is kept as one.

### On the JVM: the composables, drawn

Robolectric's native graphics mode draws the app's **own composables** -- not
mocks, not previews -- into a real `Bitmap`, and `captureToImage()` writes it to
a PNG. Each capture signs in to the local stack as a fixture account first, so
the rows on screen come from PostgREST, and each waits for text that only appears
once that screen's own repositories have answered. That wait matters: without it
the first attempt captured a loading spinner as if it were the screen.

`ScreenRenderHarnessTest` covers the instance picker, Chats in the default dark
look and in light, midnight and a custom account look, Social, Alerts, You, the
appearance panel, Drive, and all six server-management tabs. The captures are
committed in `docs/build/evidence/android-native-render/`, with a README
naming each one.

`AccountAppearanceRenderTest` is the check that the client honours Harmony's
appearance settings. It reads `profiles.appearance_settings` for the signed-in
account -- the same JSON the web client writes -- renders Chats with it, then
re-renders with the app's default and requires the pixels to differ. Run
against an account set to a custom light look with a crimson accent, that pair
is `chats-account-appearance.png` and `chats-account-default.png`.

`MessagePartRenderTest` draws a history whose parts are the shapes the instance
really stores and requires each one in the tree, so a build that swallowed the
decode error instead of rendering the part would fail. `EmojiImageRenderTest`
goes further on the one part that is a picture: it points the emoji at a
`file://` PNG and waits until a pixel of that PNG's colour is in the capture, so
an image that never loads fails instead of passing on the alt text.

`ThreadSheetJourneyTest` drives the sheets that crashed: it opens Threads, taps
a thread and requires the thread's own messages, then dismisses and reopens the
sheet, and does the same for pinned messages and message actions. It is the
regression for `threads!!` (and its `pins!!` twin) being read inside a deferred
`LazyColumn` after the tap had already set the state to null.

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

208 tests pass in total, including the pre-existing model and repository tests.

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

# 2. the JVM checks (208 tests; the render and journey tests skip themselves
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

## When a launch dies before the app

A launch can abort in `zygote64` before any app code runs, because the platform
cannot mount the app's data directory. That is not a crash in this client and
`adb logcat` shows it as `JNI FatalError ... Failed to mount` with no app stack.
`android-native/doctor.sh` diagnoses it read-only, and repairs it with an in-place
reinstall that keeps the app's data:

```sh
bash android-native/doctor.sh --serial emulator-5558
bash android-native/doctor.sh --serial emulator-5558 --repair --apk app-debug.apk
```

It refuses a physical serial unless `--allow-physical` is passed, and never
uninstalls or clears app data. The mechanism, the reproduction and the two repair
attempts that do *not* work are in
[the data-directory notes](native-android-launch-data-directory.md).

## What was rejected

Committing an all-black PNG as "visual evidence", and inferring appearance from
the accessibility tree alone. The first is false. The second proves a screen
exists and exposes the right labels -- genuinely useful, and the reason the
dumps are kept -- but says nothing about layout, contrast, clipping or whether a
custom theme applied. Neither is what the task asked for, and recording either
as verification would have made the audit lie.

