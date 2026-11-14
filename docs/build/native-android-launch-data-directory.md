# A launch that dies before the app: the data directory the platform could not mount

Noah's Galaxy S23 Ultra aborted the native client at launch, twice, and never reached its
own code. This records what the failure is, what proves it, what the safe repair is, and
what was deliberately not done.

## The report

Manual launch of `io.github.wwpol.brapmony.debug`, device log time 10:06:30 and 10:07:18:

```
F zygote64: JNI FatalError called: (io.github.wwpol.brapmony.debug)
  frameworks/base/core/jni/com_android_internal_os_Zygote.cpp:880: Failed to mount
  /data_mirror/data_de/null/0/io.github.wwpol.brapmony.debug
  to /data/user_de/0/io.github.wwpol.brapmony.debug: No such file or directory
F zygote64: runtime.cc: Runtime aborting...
SIGABRT in ch_zygote
```

The splash and the flash of a next page are the launcher and the previous process's
window, not a screen this app drew. `ch_zygote` is the platform's zygote thread, and
`nativeSpecializeAppProcess` is where it happens: the process the app would run in never
exists.

## What actually failed

Each app's private storage is given to it by zygote, not by the app. In AOSP
(`frameworks/base/core/jni/com_android_internal_os_Zygote.cpp`), `isolateAppData` calls
`isolateAppDataPerPackage` for every package the new process needs, and that function does
this:

```c
snprintf(mirrorDePath, PATH_MAX, "/data_mirror/data_de/%s/%d", volume_uuid.data(), userId);
createAndMountAppData(package_name, package_name, mirrorDePath, actualDePath, fail_fn, true);
```

with `actualDePath` = `/data/user_de/0`. `createAndMountAppData` then, in order:

```c
PrepareDir(actualAppDataPath, 0700, AID_ROOT, AID_ROOT, fail_fn);   // creates the target
BindMount(mirrorAppDataPath, actualAppDataPath, fail_fn);          // mounts the source on it
```

and `BindMount` is the only thing that prints the message:

```c
static void BindMount(const std::string& source_dir, const std::string& target_dir,
                      fail_fn_t fail_fn) {
  if (!BindMount(source_dir, target_dir)) {
    fail_fn(CREATE_ERROR("Failed to mount %s to %s: %s", source_dir.c_str(),
                         target_dir.c_str(), strerror(errno)));
  }
}
```

Two facts follow from the order, and they are what identify the fault:

1. The **target** cannot be the missing path. `PrepareDir` created it one line earlier,
   and it fails with its own message (`fs_prepare_dir failed on ...`) if it cannot.
2. The mount is `MS_BIND`, whose `ENOENT` is reported for a missing **source**. So the path
   that does not exist is the mirror entry,
   `/data_mirror/data_de/null/0/io.github.wwpol.brapmony.debug` (`null` is the volume
   uuid: internal storage).

`/data_mirror` is vold's mirror of the real data directories. Its entries are created by
`installd` when it creates app data, which the package manager asks for during install. A
mirror entry missing while the package is still recorded as installed means the two
disagree: the package manager believes the app has data, so nothing recreates the
directory, and zygote cannot mount what is not there. Every launch then aborts the same
way, which is exactly the repetition in the report.

### What this is not

- **Not a Kotlin crash.** The abort is in `zygote64`, before `HarmonyApp.onCreate`. There
  is no `FATAL EXCEPTION`, no app stack and no app frame: the app's own crash buffer is
  empty. Nothing the app does at startup can cause this, and nothing it does at startup can
  fix it.
- **Not the earlier emoji or author bugs.** Those were decode failures with app stacks;
  they are fixed and unrelated.
- **Not something `pm clear` or an uninstall is the right answer to.** Both destroy the
  session, drafts and cached data to repair a platform directory, and both are
  unnecessary: the directory can be recreated without losing anything.

## The repair, and why it is the safe one

Reinstalling the same build **in place** (`adb install -r`) runs the normal install path,
which asks `installd` to create the app's data directories again -- including the missing
mirror entry -- while leaving the contents of the existing data directory alone. An
in-place install replaces code, not data.

`android-native/doctor.sh` performs the diagnosis read-only and offers that repair:

```sh
# read-only: what is wrong, and what to do about it
bash android-native/doctor.sh --serial emulator-5558

# the repair it recommends, for the same build
bash android-native/doctor.sh --serial emulator-5558 --repair --apk app-debug.apk
```

It never uninstalls, never runs `pm clear`, never deletes a data directory, and refuses a
physical serial unless `--allow-physical` is passed, so it cannot become the destructive
fix it exists to avoid. `scripts/android-native-doctor.test.mjs` pins each of those
properties.

## How it was established

The mechanism above is read from the platform's own source, not inferred from the symptom.
It was then reproduced and repaired on an **emulator only** (explicit serial
`emulator-5558`; no physical device was attached, selected or written to), which is what
turns the reading of the source into a demonstrated cause:

| Step | Result |
| --- | --- |
| Install the debug APK, write a session file and a draft into the app's data | both present, md5 recorded |
| Remove only `/data_mirror/data_de/null/0/<pkg>` | app data still present; the mirror entry gone |
| Launch | **the reported abort**, same message, `pid` empty, 1 `JNI FatalError`, 0 `FATAL EXCEPTION` |
| `pm install-existing --user 0` | **does not repair it**: directory still missing, launch still aborts |
| `pm disable-user` then `pm enable` | **does not repair it**: same |
| `adb install -r` of the same build | **repairs it**: mirror entry back, app launches, 0 mount failures |
| Re-read the session file and draft | **identical md5**: the data survived the repair |

The two negative results matter as much as the positive one: they are why the
recommendation is a reinstall rather than "try re-enabling the app first".

The whole flow was then re-run through the shipped `doctor.sh` itself: the read-only run
finds the fault and changes nothing (the directories are byte-identical before and after),
a physical serial is refused, a missing `--serial` is refused, `--repair` restores the
directory, the app data is still identical, the app launches and is reported `Displayed`,
and a second read-only run then reports the device healthy.

## What was deliberately not done

- **No phone access.** The phone was not connected during this work. Every `adb` call named
  an emulator serial; nothing selected a physical device, installed to one, launched on
  one, or cleared a log on one.
- **No destructive repair, shipped or recommended.** `pm clear` and uninstall are named
  only to say why they are not the answer.
- **No app-side workaround.** The failure is below the app. Adding retries or a different
  launch path in Kotlin would not have changed it and would have hidden the real cause.

## For the phone this was reported from

Nothing here was run against it. The safe next action, once it is connected and the owner
agrees, is the read-only diagnosis and then the in-place reinstall:

```sh
bash android-native/doctor.sh --serial <the phone> --allow-physical
# if it reports the missing mirror entry, and the owner agrees to a write:
bash android-native/doctor.sh --serial <the phone> --allow-physical \
  --repair --apk <the same build that is installed>
```

The APK must be the same package and signing key as the installed build, or Android refuses
the in-place install and nothing is removed. That is the only write; it keeps the session,
drafts and cached data.
