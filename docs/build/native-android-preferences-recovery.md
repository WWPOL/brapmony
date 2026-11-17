# A preferences file that cannot be decoded used to brick the app

The native client could reach a screen it could never leave: **"Could not load this /
Unable to parse preferences proto. / Try again"**, where *Try again* re-read the same broken
file. Nothing the user could do recovered it — only a reinstall. This records the cause, the
fix, why this fix rather than the alternatives, and what proves it.

## The report

The last emulator run of the four UX reports installed the real debug APK, launched it, and
instead of the sign-in screen got:

```
Could not load this
Unable to parse preferences proto.
[Try again]
```

That run had not written the file itself; an earlier run had been killed mid-write. So the
state is not exotic — it is what a crash or a task-kill at the wrong moment leaves behind,
which on a phone happens without anyone doing anything unusual.

## What failed

`SessionStore` keeps the instance, the sealed session, the drafts and the device appearance
cache in one DataStore:

```kotlin
private val Context.sessionData by preferencesDataStore(name = "brapmony-session")
```

DataStore writes by writing a temporary file and renaming it, so a kill between those steps
leaves a truncated `brapmony-session.preferences_pb`. With no corruption handler, **every**
read of that file throws `androidx.datastore.core.CorruptionException`. The chain from there
is short and has no exit:

1. `SessionStore.sessionFlow` maps over `context.sessionData.data`, so the throw reaches every
   caller.
2. `HarmonyRoot`'s `LaunchedEffect` catches it in `recover(error)` and sets `startupError`.
3. `startupError != null` renders `ErrorState(...)` — the screen above.
4. Its **Try again** does `startupError = null; retry++`, and the retry re-runs the same
   `state.auth.restore(...)`, which reads the same file, which throws the same thing.

So the screen is a fixed point. The only way out was to clear the app's data, which is to say
to reinstall.

## The fix

Give the store a handler that replaces an undecodable file with an empty one:

```kotlin
private val Context.sessionData by preferencesDataStore(
    name = "brapmony-session",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)
```

What that costs is the right thing to lose. The file holds an instance URL and anon key, a
session sealed with a per-install Android Keystore key, draft text, and a cached appearance
setting. After the handler runs the user signs in again; the Keystore key is untouched, so
this is a session loss and not a data leak, and there is nothing in the file that a reinstall
would have kept. What it buys is an app that starts.

## Alternatives rejected

- **Catch it in `HarmonyRoot` and offer "reset the app".** The store is the layer that knows
  the file is broken; making the UI guess at it means every other reader of the store
  (`AppState`, the appearance panel, drafts) keeps its own copy of the same failure. It also
  leaves the broken file in place until the user finds the button.
- **`ReplaceFileCorruptionHandler` that keeps what it can.** DataStore hands the handler the
  exception, not a partially decoded store; there is nothing to salvage, and pretending
  otherwise would be a guess about which bytes survived.
- **Delete the file at startup when it fails.** Same outcome, but it happens on a path where a
  transient read error would also delete a good session, and it is not atomic with respect to
  the writer.
- **Do nothing, and document "clear app data" as the recovery.** That is the current
  behaviour, and it is the bug.

## What proves it

- **`SessionStoreRecoveryTest`** (Robolectric, 4 tests) writes the same truncated protobuf a
  killed write leaves, then asserts the production store reads it as empty, and that it can
  then save and restore a session. One test is a **mutation check**: it builds the same store
  *without* a handler and requires the read to throw a `CorruptionException`, so the other
  tests cannot pass because DataStore tolerates the file on its own. Removing the handler from
  `SessionStore` makes the two production tests fail with
  `androidx.datastore.core.CorruptionException: Value not set.`
- **`StartupRecoveryRenderTest`** drives the real `HarmonyRoot` — the composable the activity
  shows — with the same truncated file, and asserts the reported screen is not what the user is
  left with. Removing the handler makes it fail with *"the error screen must not be the
  outcome; found 1 nodes"*, which is the same failure a person reported.
- **On a device**, the pre-fix APK with the truncated file does show the reported screen
  (`20-brick.xml`, `20-brick.txt`). The **fixed build's recovery was not captured on a device**:
  the install of it was cut short by a tool-call timeout, printed no `Success`, and the build
  that then launched was still the old one. That run is not evidence for the fix and is not
  presented as such; the JVM tests above are. What would finish it is one run that installs the
  fixed APK and records `lastUpdateTime` before and after, which is
  `ux-recover5.sh` in the session's scratch directory. See
  [`evidence/android-native-ux/`](evidence/android-native-ux/README.md).

## What this does not cover

It does not make a corrupt file *recoverable* — the drafts and the cached appearance in it are
gone, and the user signs in again. It does not address why a write was interrupted; that is
the platform killing the process, which the app cannot prevent. And it is verified on an
emulator, not on a phone.
