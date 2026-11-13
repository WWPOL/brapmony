# Verifying the native Android client

The task is to build a native Android client with feature parity with Harmony's official mobile
app, and to prove the result by driving it on an emulator. This document records how that
verification is done here, why it is split the way it is, and what the split costs.

## The constraint

This host has no `/dev/kvm`. The Android emulator therefore runs the guest CPU under QEMU's TCG
interpreter, and the AOSP API 30 image behaves like this:

- It boots to `sys.boot_completed=1` in roughly two to six minutes.
- Shortly after, its framework dies. The kernel log shows `binder: ... transaction failed
  29189/-22`, `binder: send failed reply for transaction ..., target dead`, and `Service 'zygote'
  ... received signal 9`; `adb` then reports `device not found`.
- Screencaps taken while it is up are a uniform black 480x960 PNG (0 of 460800 pixels non-black),
  even though SurfaceFlinger reports the display on and awake with the activity's layers present.
- The harness tears down the sandbox that owns the emulator process, so a long emulator run does
  not survive from one tool call to the next.

An emulator run is therefore not a check that can be relied on, and a task that blocks on it never
finishes. Pretending otherwise would be worse than the limitation: an all-black screenshot or a
stale accessibility dump is not visual verification.

## What is verified instead, and what it is worth

Two automated checks run on the JVM against the real local Harmony stack
(`http://localhost:54480`, the repository's disposable e2e Supabase-compatible stack, seeded by
`e2e/seed.mjs`):

### Render captures: `ScreenRenderHarnessTest`

Robolectric's native graphics mode draws the app's **own composables** -- not mocks, not previews
-- into a real `Bitmap`, and `captureToImage()` writes it to a PNG. Each capture signs in to the
local stack as a fixture account first, so the rows on screen come from PostgREST, and each waits
for text that only appears once that screen's own repositories have answered. That wait matters:
without it the first attempt captured a loading spinner as if it were the screen.

The set covers the instance picker, Chats in the default dark look and in light, midnight and a
custom account look, Social, Alerts, You, the appearance panel, Drive, and all six
server-management tabs -- 32 captures.

### Interaction journeys: `NativeJourneyTest`

These drive the screens through the semantics tree, which is what a touch target and a TalkBack
user both reach: tap `nav-Social`, tap the server tile in the rail, tap the channel, type into
`message-composer`, tap `message-send`. The sending journeys do not stop at the screen: they read
the message back **from the instance** over PostgREST and assert the row exists, so a pass means
the round trip happened rather than that a composable rendered some text.

Sends wait for the composer to empty rather than for the message text to appear, because the text
field already holds the draft -- the first version of this test could capture an unsent message
as if it had been delivered.

The appearance journey measures the preview's repaint by counting differing pixels between two
captures, so a theme change that never reached the nested `HarmonyTheme` fails instead of looking
plausible.

## What this cannot prove

Named so nobody mistakes a green run for device verification:

- Touch, gesture and software-keyboard behaviour, IME insets, system back, process death and
  restore, rotation, and window-size classes other than the one captured width.
- Device appearance: the system light/dark setting, dynamic colour, and the status and navigation
  bar icon polarity the app sets through the window insets controller.
- Push, voice and video, Harmony's own Megolm encryption, attachment upload, and deep links. These
  are unimplemented in the native client; no test harness can make them implemented.
- Performance, battery, memory.

`docs/build/native-android-parity.md` keeps every affected row at `U/U` and carries a section
stating exactly this, so the audit's truth standard survives contact with the evidence.

## How to run it

```sh
# 1. the disposable local stack
bash e2e/stack.sh up     # answers on http://localhost:54480
bash e2e/stack.sh seed   # writes e2e/fixture.json

# 2. the checks
cd android-native
HARMONY_RENDER_OUT=/tmp/harmony-render \
HARMONY_RENDER_URL=http://localhost:54480 \
HARMONY_RENDER_EMAIL=$(node -p "require('../e2e/fixture.json').users.alice.email") \
HARMONY_RENDER_PASSWORD=$(node -p "require('../e2e/fixture.json').users.alice.password") \
./gradlew :app:testDebugUnitTest \
  --tests 'lol.mony.harmony.ScreenRenderHarnessTest' \
  --tests 'lol.mony.harmony.NativeJourneyTest'
```

Without those variables the tests skip themselves through a JUnit assumption, so an offline
`./gradlew test` still passes and still says what it did not check.

## The alternative that was rejected

Committing the emulator's all-black screenshots as "visual evidence", or inferring appearance
from the accessibility tree alone. The first is false. The second proves a screen exists and
exposes the right labels -- genuinely useful, and the reason `evidence-05/01-signin.xml` is kept --
but says nothing about layout, contrast, clipping or whether a custom theme applied. Neither is
what the task asked for, and recording either as verification would have made the audit lie.
