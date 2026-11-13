# Native Android journeys

The existing `e2e/android/` Playwright tests drive the **Tauri WebView** client. They do not
prove anything about this Compose client. Native screenshots and interaction automation must
use Android's accessibility tree, input events, Compose instrumentation, or equivalent native
controls—not browser DOM selectors or an HTML mock-up.

Prerequisites: a booted Android emulator/device, the debug APK built by `android-native/demo.sh`,
and the real disposable Harmony stack with `e2e/fixture.json`.

The full verification contract is in
[`docs/build/native-android-parity.md`](../../docs/build/native-android-parity.md). It is deliberately
larger than the current implementation. Unit tests and HTTP contracts do not mark a screen
visually verified. Missing flows, skipped tests, and emulator/runtime failures must remain visible
in the evidence ledger.

To repeat the initial native journey:

```sh
python3 e2e/android-native/journey.py \
  --serial emulator-5554 --output "$TMPDIR/brapmony-native-evidence"
```

Set `--adb` to an SDK adb executable if not on PATH. A non-default adb server can be selected
with `--adb-host`/`--adb-port`. Use a throwaway fixture account only: the journey signs in as
`fixture.users.alice`, opens the seeded server, sends a message, checks its actual stored row,
and visits the implemented top-level destinations. It is an **initial smoke journey**, not
exhaustive feature parity. No password is printed; accessibility dumps redact password fields.
Actual screenshots still need human/model inspection.
