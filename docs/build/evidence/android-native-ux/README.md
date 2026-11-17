# UX evidence: what a device actually showed, and what it did not

This directory holds what the emulator runs on this host produced. There is one captured screen
and one set of measured insets. Everything else the four UX reports need is proven on the JVM,
and this README says plainly which is which, because the difference is the point of keeping
evidence at all.

## What each system image could and could not do

The host has no `/dev/kvm`, so the guest CPU runs under QEMU's TCG interpreter. Two images
behave very differently on it:

| image | top inset the app is given | `screencap` | boot | good for |
|---|---|---|---|---|
| `aosp_atd` (`aosp30`) | **0** — no `SystemUI` at all | uniform black PNG | ~150 s | trees, measurements, request logs |
| `ui30small` | **real**: 48 px status bar, 96 px emulated cutout | **real pixels** | ~300 s | pictures, and real inset numbers |

That is why report 1 could not be settled on `aosp_atd`: a rail at `y=0` there is *correct*,
because the app is given no top inset at all.

## Report 1: the insets, measured

The `ui30small` run did measure what the app is given, and those numbers are real:

- `InsetsSource type=ITYPE_STATUS_BAR frame=[0,0][768,48] visible=true` — a 48 px status bar.
- With cutout emulation on, `mDisplayCutout … insets=Rect(0, 96 - 0, 0)` with
  `boundingRect [288,0]-[480,96]` and `ITYPE_TOP_DISPLAY_CUTOUT frame=[0,0][768,96]` — a 96 px
  top inset.

What that run did **not** get is the rail's own bounds under those insets: it was blocked
before sign-in, first by `SystemUI`'s own ANR dialog and then by the AOSP keyboard's contacts
prompt. So on the device the insets are measured and the rail is not; `ShellRailTest` on the
JVM, with a synthetic notch, is what pins the rail. A future run that disables the IME first
and gets past sign-in would close this.

## The preferences brick

- `20-brick.xml`, `20-brick.txt` — a real debug APK, launched with a truncated
  `brapmony-session.preferences_pb` (`0a 40 0a 02 08 01`, byte-identical to what
  `SessionStoreRecoveryTest` writes). The screen is `Could not load this` / `Value not set.` /
  `Try again`, and `Try again` is the only control. This is the bug: the button re-reads the
  same file, so the screen is permanent.

**What is not here: a device run of the fixed build recovering.** It was attempted and it did
not produce honest evidence, so it is not shown. The install of the fixed APK was cut short by
a tool-call timeout, printed no `Success`, and the build that then launched was still the
previous one — so the screen it showed says nothing about the fix. That capture was deleted
rather than kept, because a picture of the old build under a caption about the new one is
worse than no picture.

The fix is proven on the JVM instead, at the level the user experiences it:
`StartupRecoveryRenderTest` drives the real `HarmonyRoot` with the same truncated file, and
fails with *"the error screen must not be the outcome; found 1 nodes"* when the corruption
handler is removed. `SessionStoreRecoveryTest` pins the store itself, with a mutation check
that requires the unhandled store to throw. The reasoning is in
[`native-android-preferences-recovery.md`](../native-android-preferences-recovery.md).

To finish the device half, the remaining step is one run that installs the fixed APK and
records `lastUpdateTime` before and after (`ux-recover5.sh` in the session scratch directory
does exactly that), on a host whose tool calls are not cut short.

## Why the emulator runs kept dying

Every attempt was a single long tool call or job that booted the emulator and drove it. On this
host those were torn down after roughly one to five minutes. The emulator does not outlive the
sandbox of the call that started it, so a journey has to boot, install and drive inside one
call — and a `ui30small` boot alone is 300 s. The `20-brick` capture is from a run that fitted;
the others did not.

## What this does not show

A physical phone. Every run is on an emulator under TCG, at one density and one screen size.
It does not exercise rotation, a real camera cutout of a particular shape, gesture navigation,
or the app's behaviour with a real instance's data volume.
