# Emulator evidence: functional journeys on the APK

These files are the accessibility tree (`uiautomator dump`) of the **real
debug APK** running on the Android emulator, one file per step of the journeys
driven below. They are kept because the host cannot produce screenshots from the
guest (see `docs/build/native-android-verification.md`), and the tree is still
direct evidence from the device: it names what the app put on screen, with the
bounds each node occupied.

## How it was produced

- Emulator: AOSP API 30 `aosp_atd` x86_64, `-accel off -cores 1 -memory 3072
  -gpu swiftshader_indirect -no-window`, no snapshot. Boot to
  `sys.boot_completed=1` took 120-180 s.
- APK: `android-native/app/build/outputs/apk/debug/app-debug.apk`, installed
  with `adb install -r -t`, launched with
  `am start -n io.github.wwpol.brapmony.debug/lol.mony.harmony.MainActivity`.
- Backend: the repository's disposable stack on `http://localhost:54480`,
  reached from the guest as `http://10.0.2.2:54480` (the debug build's baked
  default instance).
- Every step is a real `adb shell input tap` at the centre of the node's own
  reported bounds, so the sequence is what a finger would do, not a direct call
  into a composable.

## The journeys, in order

Each line is one step: the dump, the tap that produced it, and what it
shows. They are in the order the journeys were driven.

- `01-chats.xml` -- after signing in as `fx_alice`: the rail, the DM list with
  `Bob E2E`, and the bottom bar
- `02-direct-message.xml` -- tapped `Bob E2E`: the DM room, with `dm message
  from alice` and message actions
- `03-draft-typed.xml` -- tapped the composer and typed: `message-composer`
  holds the text and `Send message` is enabled
- `04-message-sent.xml` -- tapped `Send message`: a `message-<uuid>` node
  holds the text; the composer is empty
- `05-server-channels.xml` -- tapped the rail's server tile: the channel
  column lists `general` and `voice chat`
- `06-channel-room.xml` -- tapped `channel-general`: the channel room, with
  its own messages
- `07-message-actions.xml` -- tapped `Message actions`: reactions, reply,
  copy, thread, pin and edit are offered
- `08-server-management.xml` -- tapped `Server settings and members`: the
  management screen, with its tabs and the server's fields
- `09-server-members.xml` -- tapped `Members`: both members with their handles
  and per-member actions
- `10-social.xml` -- tapped `Social`: the Social destination, with timeline
  tabs and empty-feed text
- `11-alerts.xml` -- tapped `Alerts`: the inbox, with filters, `20 loaded`,
  and real notification rows
- `12-you.xml` -- tapped `You`: the account screen, with the profile form and
  its tabs
- `13-appearance.xml` -- tapped `Appearance`: the four themes, the live
  preview and the text controls
- `14-appearance-light.xml` -- tapped `Light`: the panel after the choice; its
  tree keeps the same shape, so the repaint is measured by the JVM pixel test
- `15-drive.xml` -- tapped `Drive`: quota, `My Drive`, the type filters and
  the empty state

Every dump was produced by
`e2e/android-native/device-journey.sh`, which boots, installs, signs in and drives
this sequence in one run -- the emulator does not outlive the sandbox that starts
it, so a journey cannot be split across invocations.

## The message really reached the database

After the send, the conversation was read back over PostgREST as the same
account:

```json
[{"id":"3d4add38-d798-41c7-b760-6ced4143cf64",
  "content":[{"text": "device journey 1794595699", "type": "text"}]},
 {"id":"56b1b792-...", ...}]
```

The first row is the newest message in the conversation, and its id is the
`message-3d4add38-...` node in `04-message-sent.xml`: the composable's row and
the instance's row are the same row.

## What is still missing, and why

There are **no screenshots** in this directory. Every capture path returns a
uniform black 480x960 image (0 of 460800 pixels non-black): `adb exec-out
screencap -p`, `screencap` to a file then `adb pull`, the emulator console's
`screenrecord screenshot`, and `screenrecord` video (which refuses to start).
SurfaceFlinger lists the app's layers and `dumpsys window` names the activity as
focused, so the app is drawing; the guest's GL output simply never reaches the
host without KVM. A black PNG is not evidence of a screen, so none is included.

Visual verification therefore rests on the JVM render captures in
`android-native/app/src/test/java/lol/mony/harmony/ScreenRenderHarnessTest.kt`,
which draw the same composables and were reviewed by eye, and on this tree for
the device-side structure. The honest status is recorded in
`docs/build/native-android-parity.md`.
