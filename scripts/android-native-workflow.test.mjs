// node --test scripts/android-native-workflow.test.mjs
//
// The properties of the native Android workflows that make publishing safe, checked
// rather than described in a comment: which events can reach a write token, that every
// action is pinned, that the two Android APKs cannot collide, and that a tag's APK goes
// to the release that already exists instead of creating a second one.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import YAML from 'yaml';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..');
const read = (p) => readFileSync(join(root, p), 'utf8');
const parse = (p) => YAML.parse(read(p));

const NATIVE = '.github/workflows/android-native.yml';
const RELEASE = '.github/workflows/release.yml';

// Every `uses:` in a document, with the file and job it is in.
function uses(doc, file) {
  const out = [];
  for (const [job, spec] of Object.entries(doc.jobs || {})) {
    for (const step of spec.steps || []) if (step.uses) out.push({ file, job, uses: step.uses });
  }
  return out;
}

test('every action is pinned to a full commit SHA', () => {
  for (const file of [NATIVE, RELEASE]) {
    for (const u of uses(parse(file), file)) {
      assert.match(u.uses, /^[^@]+@[0-9a-f]{40}$/, `${u.file} ${u.job}: ${u.uses} is not pinned to a SHA`);
    }
  }
});

test('no workflow runs untrusted code with a write token', () => {
  const native = parse(NATIVE);
  // pull_request_target runs the base repository's token against a pull request's code.
  assert.deepEqual(Object.keys(native.on).sort(), ['pull_request', 'push', 'workflow_dispatch']);
  // The workflow default is read-only; only jobs that publish opt in.
  assert.deepEqual(native.permissions, { contents: 'read' });
  for (const [job, spec] of Object.entries(native.jobs)) {
    if (spec.permissions && spec.permissions.contents === 'write') {
      assert.equal(job, 'publish', `only the publishing job may write, not ${job}`);
      // A pull request, and a pull request from a fork in particular, must never reach it.
      assert.match(String(spec.if), /github\.event_name == 'push'/, `${job} must be push-only`);
      assert.match(String(spec.if), /github\.repository == 'WWPOL\/brapmony'/, `${job} must be this repository`);
      assert.ok(spec.needs, `${job} must build before it publishes`);
    }
  }
  // The build job that pull requests run holds no permission of its own.
  assert.equal(native.jobs.build.permissions, undefined);
});

test('every push to a branch builds the native client', () => {
  const native = parse(NATIVE);
  assert.deepEqual(native.on.push.branches, ['**'], 'every branch builds, not only master');
  // Literally every push: a path filter would leave the previous commit's APK
  // standing after a push that touched nothing under android-native/.
  assert.equal(native.on.push.paths, undefined, 'push has no path filter');
  assert.ok(native.on.pull_request, 'pull requests are still checked');
  assert.ok('workflow_dispatch' in native.on, 'a branch can be built by hand');
  // A push and a pull request from the same branch must not fight over one concurrency group.
  assert.match(native.concurrency.group, /github\.ref/);
});

test('a branch build publishes an immutable artifact and a rolling prerelease, in that order', () => {
  const native = parse(NATIVE);
  const build = native.jobs.build;
  const upload = build.steps.find((s) => String(s.uses || '').startsWith('actions/upload-artifact@'));
  assert.ok(upload, 'the build uploads its APK');
  assert.match(String(upload.with.name), /github\.run_id/, 'the artifact name is per run, so it is never overwritten');
  assert.match(String(upload.with.name), /github\.run_attempt/);
  assert.match(String(upload.with.path), /sha256|sha256sum|\.sha256/, 'the artifact carries a checksum');
  // The APK's own metadata file is written beside it.
  const digest = build.steps.find((s) => s.id === 'digest');
  assert.ok(digest, 'the build records the APK checksum');
  assert.match(digest.run, /apksigner verify/, 'the signature is verified, not assumed');
  assert.match(digest.run, /aapt2" dump badging|aapt2 dump badging/, 'the package and version are read from the APK');
  const publish = native.jobs.publish;
  assert.ok(
    publish.steps.some((s) => String(s.uses || '').startsWith('actions/download-artifact@')),
    'publishing reads back the built bytes instead of rebuilding',
  );
  const run = publish.steps.find((s) => s.run && s.run.includes('android-native-release.mjs'));
  assert.ok(run, 'publishing goes through the release script');
  assert.match(run.run, /--sha256|sha256/, 'publishing checks the bytes it downloaded');
});

test('a tag builds the native APK into the release that already exists', () => {
  const release = parse(RELEASE);
  const job = release.jobs['native-android'];
  assert.ok(job, 'release.yml builds the native client');
  assert.deepEqual(job.needs, 'release', 'it runs after the draft release exists, so it cannot race its creation');
  assert.deepEqual(job.permissions, { contents: 'write' });
  const run = job.steps.map((s) => s.run || '').join('\n');
  assert.match(run, /android-native-release\.mjs upload/, 'it uploads through the release script');
  assert.match(run, /--release-id "\$RELEASE_ID"/, 'it attaches to the draft by id, creating no second release');
  assert.ok(!/github-release\.mjs ensure/.test(run), 'it never drafts a release of its own');
  assert.ok(!/--tag/.test(run), 'it does not name a tag for a create path to pick up');
  // The APK is built from the tag's tree, and the helper comes from the workflow's own commit.
  assert.ok(job.steps.some((s) => String(s.with && s.with.ref) === "${{ github.event.inputs.tag || github.ref }}"));
  assert.ok(job.steps.some((s) => String(s.with && s.with.path) === '.ci'));
  assert.match(run, /\.ci\/scripts\/name-artifact\.sh/, 'a tag predating --native still gets a distinct name');
});

test('the two Android APKs never share a name', () => {
  const release = parse(RELEASE);
  const tauri = release.jobs.android.steps.map((s) => s.run || '').join('\n');
  const native = release.jobs['native-android'].steps.map((s) => s.run || '').join('\n');
  assert.match(tauri, /name-artifact\.sh --release Android/, 'the Tauri APK keeps its name');
  assert.match(native, /name-artifact\.sh --release --native Android/, 'the native APK has its own');
});

test('a release APK is never published under a name that hides its signing', () => {
  const release = parse(RELEASE);
  const job = release.jobs['native-android'];
  const name = job.steps.find((s) => s.id === 'name');
  // The profile passed to the namer is the signing state, so an unsigned build is _debug.
  assert.match(name.run, /profile=\$\(/);
  assert.match(name.run, /HAS_KEYSTORE/);
  assert.match(name.run, /--native Android "\$profile"/);
  const check = job.steps.find((s) => s.run && s.run.includes('apksigner verify'));
  assert.ok(check, 'the release APK is signature-checked');
  assert.match(check.run, /HAS_KEYSTORE.*CN=Android Debug|CN=Android Debug.*HAS_KEYSTORE/s,
    'a keystore-signed build that came out debug-signed fails');
});

test('the rolling release is a prerelease, so it is never offered as a release', () => {
  // Comments name endpoints; only code paths count.
  const source = read('scripts/android-native-release.mjs')
    .split('\n')
    .filter((line) => !/^\s*(\/\/|\*|\/\*)/.test(line))
    .join('\n');
  assert.match(source, /prerelease: true/, 'the rolling release is a prerelease');
  assert.match(source, /is not a prerelease; refusing to publish into it/, 'an existing full release is refused');
  assert.ok(!/releases\/latest/.test(source), 'nothing reads or writes /releases/latest');
  assert.ok(!/\/releases\/tags\//.test(source), 'no release is looked up by tag URL');
});

test('the build the app reads its version from is the repository VERSION file', () => {
  const gradle = read('android-native/app/build.gradle.kts');
  assert.match(gradle, /rootProject\.file\("\.\.\/VERSION"\)/, 'android-native reads VERSION');
  assert.match(gradle, /1_000_000/, 'versionCode uses the same x*1e6 + y*1e3 + z scheme as the Tauri build');
  assert.match(gradle, /keystore\.properties/, 'production signing comes from an ignored file, never the tree');
  const ignored = read('android-native/.gitignore');
  assert.match(ignored, /keystore\.properties/);
  assert.match(ignored, /\*\.keystore/);
});

test('the test signing key is configured from a secret, and its absence is reported', () => {
  const native = parse(NATIVE);
  const steps = native.jobs.build.steps;
  const configure = steps.find((s) => s.name === 'Configure the test signing key');
  assert.ok(configure, 'the build can use a stable test key');
  assert.match(String(configure.if), /HAS_TEST_KEYSTORE/);
  assert.match(String(configure.env.KEYSTORE_B64), /secrets\.ANDROID_TEST_KEYSTORE_BASE64/);
  assert.match(configure.run, /test-keystore\.properties/, 'it writes the file the build reads');
  assert.match(configure.run, /keytool -list/, 'the key is checked before it is used');
  // Without it, the run says exactly what that costs: no cross-run installs.
  const note = steps.find((s) => s.name === 'Note the test signing key');
  assert.ok(note, 'the absence of a stable key is reported');
  assert.match(note.run, /will not install over one from another run/);
  // And the APK is checked against the key that was configured.
  const digest = steps.find((s) => s.id === 'digest');
  assert.match(digest.run, /apksigner verify/);
  assert.match(digest.run, /HAS_TEST_KEYSTORE|certificate DN/);
  // The repository contains no key: both keystore files are ignored.
  const ignored = read('android-native/.gitignore');
  assert.match(ignored, /test-keystore\.properties/);
  assert.match(ignored, /\*\.keystore/);
});

test('the release APK is checked against the stable test key too', () => {
  const release = parse(RELEASE);
  const job = release.jobs['native-android'];
  const run = job.steps.map((s) => s.run || '').join('\n');
  assert.match(run, /ANDROID_TEST_KEYSTORE_BASE64/, 'the tag build can use the stable test key');
  assert.match(run, /test-keystore\.properties/);
  // An APK that is not signed at all is refused, whatever the secrets say.
  assert.match(run, /certificate SHA-256 digest/);
});

test('signing is compared by certificate fingerprint, not by subject', () => {
  // Two different keys can both say CN=Android Debug; only the SHA-256 digest says
  // whether one APK will install over another.
  const native = parse(NATIVE);
  const release = parse(RELEASE);
  const digest = native.jobs.build.steps.find((s) => s.id === 'digest');
  assert.match(digest.run, /certificate SHA-256 digest/, 'the fingerprint is read from the APK');
  assert.match(digest.run, /certificate-sha256=/, 'and recorded with the artifact');
  assert.ok(!/grep -q 'CN=Android Debug'/.test(digest.run), 'the subject is not what decides');
  assert.match(String(native.jobs.build.env.TEST_KEY_SHA256), /ANDROID_TEST_KEY_SHA256/);
  const nativeRelease = release.jobs['native-android'].steps.map((s) => s.run || '').join('\n');
  assert.match(nativeRelease, /certificate SHA-256 digest/);
  assert.match(nativeRelease, /normalize\(\)/, 'keytool and apksigner print the digest differently');
  assert.ok(!/grep -q 'CN=Android Debug'/.test(nativeRelease), 'the tag job does not decide by subject');
});

test('the rolling download link is tag-specific and is fetched back', () => {
  const native = parse(NATIVE);
  const publish = native.jobs.publish.steps;
  const check = publish.find((s) => s.name === 'Check the published download');
  assert.ok(check, 'the published URL is verified, not just printed');
  // A prerelease is excluded from /releases/latest, so only the tag names the file.
  assert.match(check.run, /releases\/download\/\$tag\//);
  assert.ok(!/releases\/latest\/download/.test(check.run), 'never the latest-release path');
  assert.match(check.run, /curl -fsSL/, 'the file is downloaded');
  assert.match(check.run, /aapt2" dump badging|aapt2 dump badging/, 'and its package and versionCode read');
  assert.match(check.run, /apksigner" verify|apksigner verify/, 'and its certificate');
  assert.match(check.run, /sha256sum/, 'and its checksum');
  // The summary link is the same tag-specific URL.
  const report = publish.find((s) => s.name === 'Report the download links');
  assert.match(report.run, /releases\/download\/\$tag\/Harmony_Android_Native_latest\.apk/);
  assert.ok(!/releases\/latest\/download/.test(report.run));
});

test('the upgrade test compares package, fingerprint and versionCode', () => {
  const script = read('android-native/upgrade-test.sh');
  assert.match(script, /certificate\(\)/, 'it reads the signing certificate');
  assert.match(script, /first_cert.*second_cert|first_cert" = "\$second_cert/s, 'the two certificates must match');
  assert.match(script, /first_pkg.*second_pkg|first_pkg" = "\$second_pkg/s, 'the two packages must match');
  assert.match(script, /versionCode did not increase/);
  // It installs in place: -r, never an uninstall between the two builds.
  assert.match(script, /D install -r "\$second"/);
  const between = script.slice(script.indexOf('installing the second build'), script.indexOf('D install -r'));
  assert.ok(!/D uninstall/.test(between), 'the second install does not uninstall first');
  assert.equal((script.match(/D uninstall/g) || []).length, 1, 'exactly one uninstall, before the first build');
  assert.ok(
    script.indexOf('D uninstall') < script.indexOf('D install "$first"'),
    'the uninstall is only there to start from nothing',
  );
  assert.match(script, /did not survive the upgrade/, 'the app data is checked, not assumed');
});

test('a publish is not cancelled half way through moving the stable name', () => {
  const native = parse(NATIVE);
  // The workflow-level group cancels a superseded build, which is right; the publish
  // job has its own group, so a publish that is moving assets always finishes.
  assert.equal(native.concurrency['cancel-in-progress'], true);
  const publish = native.jobs.publish.concurrency;
  assert.ok(publish, 'the publish job has its own concurrency group');
  assert.equal(publish['cancel-in-progress'], false, 'and a publish is never cancelled');
  assert.notEqual(publish.group, native.concurrency.group);
});

// `android-actions/setup-android` defaults to the package list `tools platform-tools`.
// cmdline-tools 16.0 no longer carries `tools`, so sdkmanager exits 1 inside the action and
// the job fails before any of its own steps run -- which is how run 43641479379 died with
// only deprecation warnings to read. Every use in this repository must name its packages.
test('setup-android names its packages, because the default list includes the removed tools', () => {
  const files = [NATIVE, RELEASE, '.github/workflows/tauri.yml'];
  let seen = 0;
  for (const file of files) {
    const doc = parse(file);
    for (const [job, spec] of Object.entries(doc.jobs || {})) {
      for (const step of spec.steps || []) {
        if (!String(step.uses || '').startsWith('android-actions/setup-android@')) continue;
        seen += 1;
        const packages = step.with && step.with.packages;
        assert.ok(packages, `${file} ${job}: setup-android must pass packages, not take the default`);
        const list = String(packages).split(/\s+/).filter(Boolean);
        assert.ok(!list.includes('tools'), `${file} ${job}: \`tools\` is not in cmdline-tools 16.0`);
        assert.ok(list.includes('platform-tools'), `${file} ${job}: adb comes from platform-tools`);
      }
    }
  }
  assert.ok(seen >= 3, `expected every setup-android use to be covered, saw ${seen}`);
});
