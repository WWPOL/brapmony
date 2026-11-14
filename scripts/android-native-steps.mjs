#!/usr/bin/env node
// Runs one workflow job's shell steps the way GitHub does, so the workflow itself can be
// checked on a machine instead of only in CI.
//
//   node scripts/android-native-steps.mjs <workflow file> <job> [--step <name>] [--cwd <dir>]
//
// Each `run:` step is written to a script and executed with `bash -eo pipefail`, which is
// GitHub's default shell, in order, stopping at the first failure. Steps that need the
// runner's own actions (`uses:`) are skipped and named. `${{ ... }}` expressions are
// replaced from --set name=value and from the environment, so a step that reads
// `steps.name.outputs.path` gets a path the caller chose.
//
// This is a verification aid, not part of the workflow: it exists so that a change to a
// step's shell can be run locally before it is pushed.
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import YAML from 'yaml';

const [file, job, ...rest] = process.argv.slice(2);
if (!file || !job) {
  console.error('usage: android-native-steps.mjs <workflow> <job> [--step <name>] [--set k=v]...');
  process.exit(2);
}
const values = { ...process.env };
let only = null;
let cwd = process.cwd();
for (let i = 0; i < rest.length; i += 1) {
  if (rest[i] === '--step') only = rest[(i += 1)];
  else if (rest[i] === '--cwd') cwd = rest[(i += 1)];
  else if (rest[i] === '--set') {
    const [k, ...v] = rest[(i += 1)].split('=');
    values[k] = v.join('=');
  }
}

const doc = YAML.parse(readFileSync(file, 'utf8'));
const spec = doc.jobs[job];
if (!spec) {
  console.error(`${file} has no job ${job}`);
  process.exit(2);
}

// `${{ expr }}`: a known name, else the text itself. Good enough for the expressions these
// steps use, which are outputs and a handful of literals.
function expand(text) {
  return text.replace(/\$\{\{([^}]*)\}\}/g, (_, expr) => {
    const whole = expr.trim();
    // A caller can name the whole expression, `needs.build.outputs.sha256=...`, or its last
    // segment, `sha256=...`; the first is exact, the second convenient.
    if (values[whole] !== undefined) return values[whole];
    const segments = whole.split('.');
    const last = segments[segments.length - 1];
    if (values[last] !== undefined) return values[last];
    const joined = segments.slice(-2).join('.');
    if (values[joined] !== undefined) return values[joined];
    return '';
  });
}

const dir = mkdtempSync(join(tmpdir(), 'harmony-steps-'));
// The runner's own files: GITHUB_OUTPUT is appended to by the steps, and GITHUB_STEP_SUMMARY
// is where a step writes what the run page shows.
const outputs = join(dir, 'github-output');
const summary = join(dir, 'github-step-summary');
writeFileSync(outputs, '');
writeFileSync(summary, '');
let index = 0;
let ran = 0;
values.GITHUB_OUTPUT = outputs;
values.GITHUB_STEP_SUMMARY = summary;

for (const step of spec.steps || []) {
  const name = step.name || step.uses || 'run';
  if (only && name !== only) continue;
  if (!step.run) {
    console.log(`skip  ${name} (uses: ${step.uses})`);
    continue;
  }
  index += 1;
  const path = join(dir, `step-${index}.sh`);
  writeFileSync(path, expand(step.run));
  const env = { ...process.env, ...values };
  for (const [k, v] of Object.entries(step.env || {})) env[k] = expand(String(v));
  console.log(`run   ${name}`);
  try {
    const out = execFileSync('bash', ['-eo', 'pipefail', path], {
      env,
      cwd,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    if (out.trim()) console.log(out.trim());
    ran += 1;
  } catch (error) {
    console.error(`FAILED ${name}`);
    if (error.stdout) console.error(error.stdout);
    if (error.stderr) console.error(error.stderr);
    process.exit(1);
  }
}
console.log(`ran ${ran} step(s) of ${file} ${job}`);
const written = readFileSync(outputs, 'utf8').trim();
if (written) console.log(`GITHUB_OUTPUT:\n${written}`);
const shown = readFileSync(summary, 'utf8').trim();
if (shown) console.log(`GITHUB_STEP_SUMMARY:\n${shown}`);
