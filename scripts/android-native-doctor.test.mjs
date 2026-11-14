// node --test scripts/android-native-doctor.test.mjs
//
// The properties of android-native/doctor.sh that make it safe to hand to somebody
// whose app will not start: it diagnoses without changing anything, it repairs only
// when asked and only in place, and it will not write to a physical phone by
// accident. The failure it diagnoses is the platform's, so these are the checks that
// the tool cannot become the destructive fix it exists to avoid.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..');
const script = readFileSync(join(root, 'android-native/doctor.sh'), 'utf8');

// The commands, with comment lines dropped: the script explains in prose why it does
// not run these, and that prose must not read as the command.
const code = script
  .split('\n')
  .filter((line) => !/^\s*#/.test(line))
  .join('\n');

// Commands that destroy app data. The doctor exists because the obvious answer to a
// data-directory fault is one of these, and it is the wrong answer: the directory can
// be recreated without losing anything.
const DESTRUCTIVE = [
  [/\bpm clear\b/, 'pm clear'],
  [/\b(pm|adb|D)\s+uninstall\b/, 'an uninstall'],
  [/\brm\s+-rf\s+\/data\//, 'deleting a data directory'],
];

test('the doctor never destroys app data', () => {
  for (const [pattern, what] of DESTRUCTIVE) {
    assert.ok(!pattern.test(code), `doctor.sh must not run ${what}: the repair keeps the data`);
  }
});

test('the repair is an in-place install, never a replacement of the app', () => {
  assert.match(script, /D install -r "\$APK"/, 'the repair is `install -r`: replace the code, keep the data');
  assert.equal((code.match(/D install /g) || []).length, 1, 'there is exactly one install, and it is the in-place one');
});

test('it changes nothing unless --repair is given', () => {
  const guard = 'if [ "$REPAIR" = yes ]; then';
  const guardAt = script.indexOf(guard);
  assert.ok(guardAt > 0, 'the writing is guarded by the flag');
  const before = script.slice(0, guardAt);
  assert.ok(!/D install/.test(before), 'no install happens before the --repair guard');
  assert.ok(!/D shell (mkdir|rm|chmod|mv|cp)\b/.test(before), 'no device write happens before the guard');
  assert.match(before, /D logcat -d/, 'the diagnosis does read the log');
  // --repair is parsed, and asking for it without a build to install is refused.
  assert.match(script, /--repair\) REPAIR=yes/, '--repair is parsed');
  assert.match(script, /--repair needs --apk/, 'and it will not repair without the build');
});

test('it never guesses a device, and refuses a phone by default', () => {
  assert.match(script, /\[ -n "\$SERIAL" \] \|\|/, 'an unset serial is an error, not a guess');
  assert.match(script, /emulator-\*/, 'emulator serials are recognised');
  assert.match(script, /^ALLOW_PHYSICAL=no$/m, 'physical access is off by default');
  assert.match(script, /ALLOW_PHYSICAL" != yes/, 'and the refusal is what the default selects');
  assert.match(script, /not an emulator; pass --allow-physical/, 'the refusal says how to opt in');
});

test('it looks at the paths the reported abort names', () => {
  assert.match(script, /data_mirror\/data_de\/null\/0\/\$PACKAGE/, 'the bind mount source is checked');
  assert.match(script, /data\/user_de\/0\/\$PACKAGE/, 'the bind mount target is checked');
  assert.match(script, /data\/user\/0\/\$PACKAGE/, 'and the app data that must be preserved');
  assert.match(script, /Failed to mount \$MIRROR to \$USERDE/, 'it finds the exact abort line, not any error');
});

test('a healthy device is reported as healthy, and the exit status says which', () => {
  assert.match(script, /not the reported failure/, 'the no-fault branch is explicit');
  assert.match(script, /\[ "\$FAILED" = yes \] && exit 1/, 'the exit status carries the verdict');
  assert.match(script, /^exit 0$/m, 'and a clean device exits 0');
});

test('the repair is only recommended when it keeps the data', () => {
  const from = script.indexOf('== verdict');
  const to = script.indexOf('if [ "$REPAIR" = yes ]; then');
  assert.ok(from > 0 && to > from, 'the verdict block is where the test expects it');
  const verdict = script.slice(from, to);
  // The recommendation must be the in-place install, not the uninstall that would also
  // 'work' by deleting everything the person is trying to keep.
  assert.match(verdict, /reinstall the same build in place/, 'the safe repair is what it recommends');
  assert.match(verdict, /keeps app data/, 'and it says why that is the safe one');
  assert.ok(!/uninstall/.test(verdict), 'it does not recommend destroying the data');
});

test('it is a bash script that passes a syntax check', () => {
  assert.match(script, /^#!\/usr\/bin\/env bash/, 'it declares its shell');
  assert.match(script, /^set -uo pipefail$/m, 'it fails on unset variables and bad pipelines');
  const result = spawnSync('bash', ['-n', join(root, 'android-native/doctor.sh')], { encoding: 'utf8' });
  assert.equal(result.status, 0, `bash -n reported: ${result.stderr}`);
});

test('the design document records the mechanism and the safe next action', () => {
  const doc = readFileSync(join(root, 'docs/build/native-android-launch-data-directory.md'), 'utf8');
  assert.match(doc, /PrepareDir/, 'it names the step that creates the target');
  assert.match(doc, /BindMount/, 'and the mount that fails');
  assert.match(doc, /No such file or directory/, 'and quotes the reported failure');
  assert.match(doc, /not a Kotlin crash/i, 'it rules out the app');
  assert.match(doc, /pm clear/, 'it explains why the destructive repair is not the answer');
});
