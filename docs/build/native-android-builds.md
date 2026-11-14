---
title: Native Android builds
description: How the Jetpack Compose Android client is built and published — every-push artifacts, a rolling prerelease per branch, the APK on each version tag, what is signed with what, and which build updates which.
---

# Native Android builds

The native client in `android-native/` is a second Android app, alongside the Tauri Android
build in `src-tauri/gen/android/`. They share a repository and a version, and nothing else:
different packages, different code, different asset names. This page is about how the native
one is built and where its APKs are published. The [release process](/contribute/releases)
covers the rest of a release.

## What each push publishes

[`android-native.yml`](https://github.com/WWPOL/brapmony/blob/master/.github/workflows/android-native.yml)
runs on **every push to any branch**, whatever the push changed, and on pull requests
that touch the client. A push is not filtered by path: a branch build is what somebody
downloads to try the client, and a commit that changed nothing here still has to produce the
APK of that commit rather than leave an older one standing.

| Where | What | Lifetime |
| --- | --- | --- |
| Actions artifact `native-android-apk-<run id>-<attempt>` | The debug APK, its `.sha256` and a `.txt` naming the commit, version, versionCode and signing | 90 days, never overwritten |
| Prerelease `android-native-rolling-<branch>` | This run's APK under its own name, plus `Harmony_Android_Native_latest.apk` and that file's `.sha256` | The release stays; builds older than the newest ten of that branch are pruned |
| The run's summary | Links to both | With the run |

Each run's APK is also named after its version and commit
(`Harmony_Android_Native_V1.9.6_debug-<branch>-<sha7>.apk`), so a link to a particular
build keeps working after later pushes. `Harmony_Android_Native_latest.apk` is the
stable name a person should bookmark: it is replaced on every push to that branch, from
the same bytes as that run's own APK.

A rolling release is a **prerelease**, so it is excluded from
`releases/latest` and from the app's own update notice, which reads that endpoint. A
branch build is never offered to anybody as a release.

The download link the run prints is the tag-specific one,
`https://github.com/<owner>/<repo>/releases/download/android-native-rolling-<branch>/Harmony_Android_Native_latest.apk`.
It has to be tag-specific: `/releases/latest/download/...` resolves only to the newest
**published** release, and a rolling release is a prerelease, so that path would never reach
it. The publish job then fetches its own link back over the same path a browser uses and
fails unless what arrives has this app's package, the versionCode that was just built, the
checksum the build reported, and a signing certificate. A link that is not independently
downloadable is a broken link, and the run says so instead of publishing a summary that
looks fine.

Publishing needs no secret of its own: the APK is test-signed, and the workflow's
`GITHUB_TOKEN` writes the prerelease. Whether two runs' APKs install over each other depends
on a stable test key, which is optional; see [Signing](#signing) and
[Which build installs over which](#which-build-installs-over-which).

## What a tag publishes

A version tag (`vX.Y.Z`) runs [`release.yml`](https://github.com/WWPOL/brapmony/blob/master/.github/workflows/release.yml),
whose `native-android` job builds the native client from the tag's tree and attaches it
to the draft release the workflow's first job created:

| Signing configured | Asset |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` and its three companions | `Harmony_Android_Native_VX.Y.Z.apk` |
| Not configured | `Harmony_Android_Native_VX.Y.Z_debug.apk` |

Both are accompanied by a `.sha256`. The names are distinct from the Tauri Android APK
(`Harmony_Android_VX.Y.Z.apk`), which the same release carries: the two apps can be
installed side by side, and neither download link can be confused for the other.

The job runs after the draft exists, uploads by release id, and replaces only assets of
its own two names. It does not create, publish or delete a release, and it never touches
another release's assets.

## Signing

| Build | Key | Installs over |
| --- | --- | --- |
| Debug, every push, stable test key configured | The repository's test key, from a secret | Any other build this workflow signed with that key |
| Debug, every push, no test key | A key generated for this checkout | Nothing signed by another run |
| Release from a tag, keystore secret configured | The project's release key | The previous release-signed build |
| Release from a tag, no keystore secret, stable test key | The repository's test key | Any other build signed with that key |
| Release from a tag, neither secret | A key generated for that run | Nothing signed by another run |

An APK is named for the key it carries: `_debug` means test-signed. It installs, and it
is what to try a branch build with, but Android refuses to install it over a
release-signed app (a signature mismatch), and it cannot be published to a store.

**A generated key does not survive the runner.** The build uses the conventional debug
key in `android-native/.gradle/`, which a fresh checkout does not have and
`createLocalDebugKeystore` generates. A generated key has a random certificate, so two
CI runs, or a CI run and a local build, sign with different keys and each APK refuses
to install over the other. To make APKs that install over one another, configure one
throwaway test key as repository secrets:

| Secret | Contents |
| --- | --- |
| `ANDROID_TEST_KEYSTORE_BASE64` | `base64 -w 0 test.keystore` |
| `ANDROID_TEST_KEYSTORE_PASSWORD` | Its store password |
| `ANDROID_TEST_KEY_ALIAS` | The key's alias |
| `ANDROID_TEST_KEY_PASSWORD` | That key's password |

The certificate's SHA-256 fingerprint goes in the repository variable
`ANDROID_TEST_KEY_SHA256`, as `keytool -list -v` prints it (uppercase, colon-separated; the
workflow normalises it). It is a digest, not the key. Generate the key once, offline, and keep
the file out of the repository:

```sh
keytool -genkeypair -keystore test.keystore -storepass "$PASS" -keypass "$PASS" \
  -alias harmony-test -keytype RSA -keysize 2048 -validity 10000 \
  -dname "CN=Harmony Test, O=Harmony, C=US"
```

It is a **test** key, not a release key: it never signs a release-signed install, and
the APK stays named `_debug`. Without the secrets CI generates nothing to keep and stores no
key; each run makes its own, and the run's summary and warning say that its APK will not
install over another run's. The build reads the certificate's fingerprint out of the finished
APK and fails when a configured key is not the one that signed it, so a name cannot claim a
key the file does not carry, and a subject like `CN=Android Debug` is never mistaken for
identity.

The repository contains no signing key and no secret. Production signing is the same
four secrets the Tauri Android build uses; without them nothing here generates, guesses or
stores a release key, and no release APK pretends to be signed with one.

**Missing setup, stated plainly:** with neither `ANDROID_KEYSTORE_BASE64` nor
`ANDROID_TEST_KEYSTORE_BASE64` configured, each run signs with a key of its own, so its APKs
are not updates of anything else — not even of the previous run's. The run's log says so.
That is the repository's state until somebody adds the test key or the release key. It is not
a seamless update path, and this page does not describe it as one.

## What a publish will not do

A published download link is somebody's, so the publishing script refuses to do
things that would quietly change what one means. Each row is a test in
`scripts/android-native-release.test.mjs`:

| Situation | What happens |
| --- | --- |
| The release for the computed tag is not a prerelease | Refused; nothing is written |
| An asset was not uploaded by this workflow | Never replaced or pruned; refused |
| A re-run of the same commit, same bytes | Nothing changes, not even an asset id |
| A re-run of the same commit, different bytes | The new bytes get their own name; the old name keeps the old bytes |
| The run's own upload fails | Nothing else is touched; the stable name still points at the last good build |
| The stable name's upload fails | The previous stable build is still there and still downloadable |
| An older build is pruned | Only builds this workflow uploaded, keeping the newest ten |

Ownership is recorded in each asset's label (`harmony-native-android sha256:<hash>`),
so a file somebody attached by hand is not mistaken for this workflow's. The swap of
the stable name goes through a staging asset and a rename, and the previous file is
deleted only after the new one is in place.

### Why ten builds

A rolling release is a window on a branch, not an archive: it keeps the newest ten
builds of its branch and deletes older ones it uploaded itself. Without a bound,
every push to a long-lived branch would add an asset for ever and the release page
would grow without limit. Ten is roughly a day of pushes on an active branch, and the
links people use — `latest`, and the newest few commits — are inside it. A link to a
build older than that stops resolving; the version-tag release is where a build is
kept, and nothing here touches it.

## Which build installs over which

Android installs an APK over an installed app when three things hold: the same
package name, a certificate that matches the installed app's, and a versionCode that
is not lower. A **certificate** is not a subject: two keys can both say
`CN=Android Debug` and be different keys. What decides is the certificate's SHA-256
digest, and that is what this workflow records and compares.

| From | To | Installs in place |
| --- | --- | --- |
| Debug build, run A | Debug build, run B, same stable test key | Yes, if versionCode does not go down |
| Debug build, run A | Debug build, run B, no stable key | **No**: each run generates a key with a new certificate |
| Debug build | Release build of a tag | **No**: the packages differ (`io.github.wwpol.brapmony.debug` and `io.github.wwpol.brapmony`), so these are two apps |
| Release build, vX | Release build, vY (X < Y) | Yes, when both carry the same release key |
| Test-signed release build | Release-signed release build | **No**: different certificates |

The packages are deliberately different: a debug build and a release build can be
installed side by side, and neither replaces the other. An in-place upgrade is only
ever between two builds of the same package and the same certificate.

`android-native/upgrade-test.sh` checks this on an emulator, with two consecutive
builds of the debug package:

```sh
bash android-native/upgrade-test.sh prepare /tmp/upgrade   # build both, check package/cert/versionCode
bash android-native/upgrade-test.sh run /tmp/upgrade emulator-5554   # install in place, verify data
```

`prepare` builds the version in `VERSION` and then the same tree with the patch number
raised by one, so the second APK is what a later release would produce. It fails unless
the two have the same package, the same certificate SHA-256 and an increasing
versionCode. `run` installs the first, leaves a marker in the app's own data
directory, installs the second with `-r` (no uninstall), and fails unless the marker
is still there, the installed versionCode is the second build's, and the app did not
crash. It touches an emulator only; a person's phone is never used.

### What has and has not been observed

On this project's emulator, with a stable test key configured, two consecutive builds
were installed one over the other: versionCode 1009006 then 1009007, same package
`io.github.wwpol.brapmony.debug`, same certificate SHA-256, the marker in the app's data
unchanged, and no crash in logcat.

A person's own phone has **not** been tested, by design. Whether a CI APK installs over
an app already on somebody's device therefore depends on the certificate that app was
signed with: it works when the installed build came from the same stable test key, and
fails with a signature mismatch when it did not. No uninstall or data wipe is offered
as a workaround; if the certificates differ, the APK is a different app to Android.

## Version and upgrades

`android-native/app/build.gradle.kts` reads the repository's `VERSION` file, which
`scripts/set-version.mjs` stamps from the release tag. `versionName` is that version and
`versionCode` is `X * 1_000_000 + Y * 1_000 + Z`, the same scheme the Tauri Android build
uses, so a later release always carries a higher versionCode than an earlier one and no
counter is maintained by hand. A higher versionCode is necessary but not sufficient: Android
also requires the same signing key, so an install is only ever upgraded in place by a build
signed with the key the installed one carries (see [Signing](#signing)). A test-signed build
and a release-signed build are different apps to Android even when they share a package name.

The debug variant appends `-debug` to the version name and `.debug` to the package, so a
debug build and a release build of the same version are different apps and neither
replaces the other.

## Working on the workflow

Three Node scripts exist for checking the workflow without a runner:

| Script | What it does |
| --- | --- |
| `android-native-release.test.mjs` | Runs the publishing script against an in-process stand-in for the GitHub releases API |
| `android-native-workflow.test.mjs` | Parses both workflows and asserts the properties that make publishing safe |
| `android-native-steps.mjs` | Runs one job's `run:` steps with `bash -eo pipefail`, as GitHub does, on this machine |

The first two run in CI with the unit tests:

```sh
node --test scripts/android-native-release.test.mjs scripts/android-native-workflow.test.mjs
```

The publisher tests cover what a published download depends on: one prerelease per
branch, the previous builds kept, only the stable names replaced, a re-run that changes
nothing, a rebuild that gets its own name, a foreign asset that is never overwritten, a
failed upload that leaves the last good build downloadable, retention bounded to this
workflow's own builds, and a version release left entirely alone.

The workflow tests assert that every action is pinned to a commit SHA, that
`pull_request_target` is not used, that `contents: write` appears only on the publishing
job, which runs only for a push to this repository, that a push has no path filter, that
the stable test key is configured from a secret and its absence reported, that the tag
job depends on the draft release and uploads by id, and that the two Android APKs cannot
share a name.

To run a step as GitHub would, after a local build:

```sh
ANDROID_HOME=/path/to/sdk \
  node scripts/android-native-steps.mjs .github/workflows/android-native.yml build \
  --step 'Check the APK and write its checksum' --set 'path=<the named apk>'
```

`scripts/android-native-fake-api.mjs` is the same stand-in as a process, for running the
publish step itself:

```sh
GITHUB_REPOSITORY=owner/repo node scripts/android-native-fake-api.mjs 8099 &
node scripts/android-native-steps.mjs .github/workflows/android-native.yml publish \
  --step 'Publish' --cwd <a directory holding native-android/<the apk>> \
  --set 'GITHUB_TOKEN=t' --set 'GITHUB_API_URL=http://127.0.0.1:8099' \
  --set 'github.ref_name=master' --set 'needs.build.outputs.sha256=<sha256sum of the apk>'
```

A change to either workflow should keep those tests passing and their assertions honest:
if an assertion stops describing what the workflow needs, change the workflow or the
assertion deliberately, not by loosening it until it passes.

## What is not verified here

The workflows are checked by parsing them and by running their shell steps on a machine.
They have not been run by GitHub Actions from this repository: the branch that added them
was not pushed, so no run, artifact or release created by them has been observed. The
first push is what proves the runner's own environment — the `sdkmanager` package names,
the `aapt2` and `apksigner` paths, and the token's permission to create a prerelease.
Until then, treat the workflow as reviewed and locally exercised, not as a demonstrated
CI run.
