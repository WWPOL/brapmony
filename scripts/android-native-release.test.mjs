// node --test scripts/android-native-release.test.mjs
//
// Runs scripts/android-native-release.mjs against an in-process stand-in for the GitHub
// releases REST API. The properties checked here are the ones that protect a published
// download: which releases and assets a publish may touch, that a re-run is safe, that a
// collision or a failed upload cannot destroy the build somebody is downloading, and that
// retention only ever removes this workflow's own older builds.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { execFile } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';

import {
  LATEST_ASSET,
  LATEST_SHA256_ASSET,
  ROLLING_TAG_PREFIX,
  client,
  ensureRollingRelease,
  pruneRollingAssets,
  publishBuild,
  replaceStable,
  rollingTag,
  rollingName,
  ownerLabel,
} from './android-native-release.mjs';

const run = promisify(execFile);
const here = dirname(fileURLToPath(import.meta.url));
const OWNER_REPO = 'acme/harmony';
const sha = (buf) => createHash('sha256').update(buf).digest('hex');

// Release REST subset: list/create/get releases, list assets, upload with a name and
// label, rename (PATCH) and delete assets. Two items per page, so pagination is
// exercised. failUploads makes an upload whose name matches fail with a 500, which is how
// a network or quota failure during a publish is reproduced.
const fails = (matcher, name) =>
  typeof matcher === 'string' ? name.includes(matcher) : !!matcher && matcher.test(name);

async function fakeGitHub({ releases: seed = [], failUploads = null } = {}) {
  const releases = structuredClone(seed);
  const calls = [];
  const state = { failUploads };
  let nextId = 900;
  const server = http.createServer(async (req, res) => {
    const base = `http://127.0.0.1:${server.address().port}`;
    const url = new URL(req.url, base);
    const chunks = [];
    for await (const c of req) chunks.push(c);
    const body = Buffer.concat(chunks);
    calls.push({ method: req.method, path: url.pathname, name: url.searchParams.get('name') });
    const send = (status, data, headers = {}) => {
      res.writeHead(status, { 'content-type': 'application/json', ...headers });
      res.end(data === undefined ? '' : JSON.stringify(data));
    };
    const page = (items) => {
      const n = Number(url.searchParams.get('page') || 1);
      const slice = items.slice((n - 1) * 2, n * 2);
      const headers = {};
      if (n * 2 < items.length) {
        const next = new URL(url);
        next.searchParams.set('page', String(n + 1));
        headers.link = `<${next}>; rel="next"`;
      }
      send(200, slice, headers);
    };
    const match = (re) => re.exec(url.pathname);
    if (req.method === 'GET' && url.pathname === `/repos/${OWNER_REPO}/releases`) return page(releases);
    if (req.method === 'POST' && url.pathname === `/repos/${OWNER_REPO}/releases`) {
      const payload = JSON.parse(body.toString());
      const id = nextId++;
      const release = {
        id,
        tag_name: payload.tag_name,
        name: payload.name,
        body: payload.body,
        draft: !!payload.draft,
        prerelease: !!payload.prerelease,
        assets: [],
        upload_url: `${base}/repos/${OWNER_REPO}/releases/${id}/assets{?name,label}`,
      };
      releases.push(release);
      return send(201, release);
    }
    let m = match(new RegExp(`^/repos/${OWNER_REPO}/releases/(\\d+)$`));
    if (m && req.method === 'GET') {
      const release = releases.find((r) => r.id === Number(m[1]));
      return release ? send(200, release) : send(404, { message: 'Not Found' });
    }
    m = match(new RegExp(`^/repos/${OWNER_REPO}/releases/(\\d+)/assets$`));
    if (m && req.method === 'GET') {
      const release = releases.find((r) => r.id === Number(m[1]));
      return release ? page(release.assets) : send(404, { message: 'Not Found' });
    }
    if (m && req.method === 'POST') {
      const release = releases.find((r) => r.id === Number(m[1]));
      if (!release) return send(404, { message: 'Not Found' });
      const name = url.searchParams.get('name');
      if (fails(state.failUploads, name)) return send(500, { message: 'upload failed' });
      if (release.assets.some((a) => a.name === name)) {
        return send(422, { message: 'Validation Failed', errors: [{ code: 'already_exists' }] });
      }
      const asset = {
        id: nextId++,
        name,
        label: url.searchParams.get('label') || '',
        size: body.length,
        state: 'uploaded',
        content: body.toString('base64'),
        browser_download_url: `${base}/${OWNER_REPO}/releases/download/${release.tag_name}/${name}`,
      };
      release.assets.push(asset);
      return send(201, asset);
    }
    m = match(new RegExp(`^/repos/${OWNER_REPO}/releases/assets/(\\d+)$`));
    if (m && req.method === 'PATCH') {
      const { name } = JSON.parse(body.toString());
      for (const r of releases) {
        const a = r.assets.find((x) => x.id === Number(m[1]));
        if (a) {
          if (r.assets.some((x) => x.name === name && x.id !== a.id)) {
            return send(422, { message: 'Validation Failed', errors: [{ code: 'already_exists' }] });
          }
          a.name = name;
          return send(200, a);
        }
      }
      return send(404, { message: 'Not Found' });
    }
    if (m && req.method === 'DELETE') {
      for (const r of releases) r.assets = r.assets.filter((a) => a.id !== Number(m[1]));
      return send(204);
    }
    return send(404, { message: `unhandled ${req.method} ${url.pathname}` });
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const url = `http://127.0.0.1:${server.address().port}`;
  // A seeded release's upload_url names this server, as GitHub's would name its own.
  for (const r of releases) r.upload_url = `${url}/repos/${OWNER_REPO}/releases/${r.id}/assets{?name,label}`;
  return {
    url,
    releases,
    calls,
    state,
    ...client({ token: 'test-token', repository: OWNER_REPO, apiUrl: url }),
    close: () => new Promise((resolve) => server.close(resolve)),
  };
}

// A published version release with assets, which nothing in this workflow may touch.
function versionRelease() {
  return {
    id: 41,
    tag_name: 'v1.9.6',
    name: 'Harmony 1.9.6',
    draft: false,
    prerelease: false,
    assets: [
      { id: 4101, name: 'Harmony_Android_V1.9.6.apk', label: '', size: 12, state: 'uploaded', browser_download_url: 'x' },
      { id: 4102, name: 'Harmony_Android_Native_V1.9.6.apk', label: '', size: 13, state: 'uploaded', browser_download_url: 'y' },
      { id: 4103, name: 'latest.json', label: '', size: 34, state: 'uploaded', browser_download_url: 'z' },
    ],
    upload_url: '',
  };
}

function workspace(commit = 'abc1234', bytes = null) {
  const dir = mkdtempSync(join(tmpdir(), 'harmony-native-test-'));
  const apk = join(dir, `Harmony_Android_Native_V1.9.6_debug-master-${commit}.apk`);
  writeFileSync(apk, bytes ?? Buffer.from(`PK apk bytes for ${commit}`));
  return { dir, apk, cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

async function publish(args, { gh: existing, releases, failUploads } = {}) {
  const gh = existing ?? (await fakeGitHub({ releases, failUploads }));
  // A reused server may be told to fail this publish's uploads.
  if (existing && failUploads !== undefined) existing.state.failUploads = failUploads;
  const out = join(mkdtempSync(join(tmpdir(), 'harmony-native-out-')), 'output');
  writeFileSync(out, '');
  const { stdout } = await run(process.execPath, [join(here, 'android-native-release.mjs'), 'rolling', ...args], {
    env: {
      ...process.env,
      GITHUB_TOKEN: 'test-token',
      GITHUB_REPOSITORY: OWNER_REPO,
      GITHUB_API_URL: gh.url,
      GITHUB_OUTPUT: out,
    },
  });
  return { gh, stdout, output: readFileSync(out, 'utf8') };
}

test('rolling tag and name are derived from the branch and cannot collide with a version tag', () => {
  assert.equal(rollingTag('master'), `${ROLLING_TAG_PREFIX}master`);
  assert.equal(rollingTag('task/build-native-android-app-with-parity-v8hy4zr4'),
    'android-native-rolling-task-build-native-android-app-with-parity-v8hy4zr4');
  assert.equal(rollingName('master'), 'Native Android (rolling) master');
  for (const branch of ['v1.9.6', 'v2.0.0', 'release/1.9.6']) {
    assert.ok(!/^v\d/.test(rollingTag(branch)), `a branch named ${branch} must not make a version tag`);
  }
  assert.throws(() => rollingTag('///'), /no characters a tag can carry/);
  assert.throws(() => rollingTag(''), /--branch is required/);
});

test('a publish creates one prerelease and uploads the build plus the stable names', async () => {
  const ws = workspace();
  try {
    const { gh, stdout, output } = await publish(['--apk', ws.apk, '--branch', 'master', '--commit', 'abc1234']);
    assert.match(stdout, /Created rolling prerelease/);
    assert.equal(gh.releases.length, 1);
    const release = gh.releases[0];
    assert.equal(release.tag_name, `${ROLLING_TAG_PREFIX}master`);
    assert.equal(release.prerelease, true);
    assert.equal(release.draft, false);
    const names = release.assets.map((a) => a.name).sort();
    assert.deepEqual(names, [
      'Harmony_Android_Native_V1.9.6_debug-master-abc1234.apk',
      LATEST_ASSET,
      LATEST_SHA256_ASSET,
    ].sort());
    // Every asset this workflow uploaded says so, and says which bytes it holds.
    const expected = sha(readFileSync(ws.apk));
    for (const a of release.assets) assert.equal(a.label, ownerLabel(expected));
    const stable = release.assets.find((a) => a.name === LATEST_ASSET);
    assert.equal(stable.content, release.assets.find((a) => a.name.endsWith('-abc1234.apk')).content);
    const checksum = release.assets.find((a) => a.name === LATEST_SHA256_ASSET);
    assert.equal(Buffer.from(checksum.content, 'base64').toString('utf8'), `${expected}  ${LATEST_ASSET}\n`);
    assert.match(output, /tag=android-native-rolling-master/);
    assert.match(output, /release-id=\d+/);
    assert.match(output, new RegExp(`sha256=${expected}`));
  } finally {
    await ws.cleanup();
  }
});

test('a second push keeps the first build and moves the stable name to the new one', async () => {
  const first = workspace('abc1234');
  const second = workspace('def5678');
  try {
    const firstRun = await publish(['--apk', first.apk, '--branch', 'master', '--commit', 'abc1234']);
    const again = await publish(['--apk', second.apk, '--branch', 'master', '--commit', 'def5678'], { gh: firstRun.gh });
    assert.match(again.stdout, /Found rolling prerelease/);
    assert.equal(again.gh.releases.length, 1, 'the same branch keeps one rolling release');
    const release = again.gh.releases[0];
    const names = release.assets.map((a) => a.name);
    assert.ok(names.includes('Harmony_Android_Native_V1.9.6_debug-master-abc1234.apk'), 'the first build is still there');
    assert.ok(names.includes('Harmony_Android_Native_V1.9.6_debug-master-def5678.apk'));
    assert.equal(names.filter((n) => n === LATEST_ASSET).length, 1, 'one stable asset, not two');
    const stable = release.assets.find((a) => a.name === LATEST_ASSET);
    const newest = release.assets.find((a) => a.name.endsWith('-def5678.apk'));
    assert.equal(stable.content, newest.content, 'the stable name follows the newest build');
    // The staging name the swap went through is gone, not left behind.
    assert.ok(!names.some((n) => n.includes('.staging-')), `left a staging asset: ${names.join(', ')}`);
  } finally {
    await first.cleanup();
    await second.cleanup();
  }
});

test('a re-run of the same commit with the same bytes changes nothing', async () => {
  const ws = workspace();
  try {
    const firstRun = await publish(['--apk', ws.apk, '--branch', 'master', '--commit', 'abc1234']);
    const before = firstRun.gh.releases[0].assets.map((a) => `${a.name}:${a.id}`).sort();
    const rerun = await publish(['--apk', ws.apk, '--branch', 'master', '--commit', 'abc1234'], { gh: firstRun.gh });
    assert.match(rerun.stdout, /Kept Harmony_Android_Native_V1\.9\.6_debug-master-abc1234\.apk/);
    const after = rerun.gh.releases[0].assets.map((a) => `${a.name}:${a.id}`).sort();
    assert.deepEqual(after, before, 'a re-run neither re-uploads nor renames anything');
  } finally {
    await ws.cleanup();
  }
});

test('a rebuild of the same commit gets its own name, leaving the earlier bytes where they were', async () => {
  const first = workspace('abc1234', Buffer.from('first build of abc1234'));
  const rebuilt = workspace('abc1234', Buffer.from('second build of abc1234'));
  try {
    const firstRun = await publish(['--apk', first.apk, '--branch', 'master', '--commit', 'abc1234']);
    const before = firstRun.gh.releases[0].assets.find((a) => a.name.endsWith('-abc1234.apk'));
    const again = await publish(['--apk', rebuilt.apk, '--branch', 'master', '--commit', 'abc1234', '--attempt', '2'], { gh: firstRun.gh });
    const names = again.gh.releases[0].assets.map((a) => a.name);
    assert.ok(names.includes('Harmony_Android_Native_V1.9.6_debug-master-abc1234.apk'), 'the first name is unchanged');
    assert.ok(
      names.includes('Harmony_Android_Native_V1.9.6_debug-master-abc1234.r2.apk'),
      `the rebuilt bytes have their own name: ${names.join(', ')}`,
    );
    const after = again.gh.releases[0].assets.find((a) => a.name.endsWith('-abc1234.apk'));
    assert.equal(after.id, before.id, 'the earlier asset was not replaced');
    assert.equal(after.content, before.content, 'the earlier bytes are what they were');
    // And the stable name follows the newest bytes.
    const stable = again.gh.releases[0].assets.find((a) => a.name === LATEST_ASSET);
    assert.equal(stable.content, Buffer.from('second build of abc1234').toString('base64'));
  } finally {
    await first.cleanup();
    await rebuilt.cleanup();
  }
});

test('an asset this workflow did not upload is never replaced', async () => {
  const ws = workspace();
  try {
    const gh = await fakeGitHub();
    const { id } = await ensureRollingRelease(gh, { branch: 'master', commit: 'abc1234', body: '' });
    // Somebody attached a file under the stable name by hand.
    await gh.json('POST', `/releases/${id}/assets?name=${encodeURIComponent(LATEST_ASSET)}`, {
      body: Buffer.from('hand made'),
      contentType: 'application/octet-stream',
    });
    await assert.rejects(
      replaceStable(gh, { releaseId: id, file: ws.apk, name: LATEST_ASSET, label: ownerLabel(sha(readFileSync(ws.apk))) }),
      /was not uploaded by this workflow; refusing to replace it/,
    );
    const asset = gh.releases[0].assets.find((a) => a.name === LATEST_ASSET);
    assert.equal(Buffer.from(asset.content, 'base64').toString('utf8'), 'hand made');
    await gh.close();
  } finally {
    await ws.cleanup();
  }
});

test('a collision with a foreign build name is refused rather than overwritten', async () => {
  const ws = workspace();
  try {
    const gh = await fakeGitHub();
    const { id } = await ensureRollingRelease(gh, { branch: 'master', commit: 'abc1234', body: '' });
    const name = 'Harmony_Android_Native_V1.9.6_debug-master-abc1234.apk';
    await gh.json('POST', `/releases/${id}/assets?name=${encodeURIComponent(name)}`, {
      body: Buffer.from('not ours'),
      contentType: 'application/octet-stream',
    });
    await assert.rejects(
      publishBuild(gh, { releaseId: id, file: ws.apk, name }),
      /was not uploaded by this workflow; refusing to replace it/,
    );
    const asset = gh.releases[0].assets.find((a) => a.name === name);
    assert.equal(Buffer.from(asset.content, 'base64').toString('utf8'), 'not ours');
    await gh.close();
  } finally {
    await ws.cleanup();
  }
});

test('a failed build upload leaves the stable build people are downloading intact', async () => {
  const first = workspace('abc1234');
  const second = workspace('def5678');
  try {
    const firstRun = await publish(['--apk', first.apk, '--branch', 'master', '--commit', 'abc1234']);
    const before = firstRun.gh.releases[0].assets.find((a) => a.name === LATEST_ASSET);
    // The next build's own upload fails: the stable name must not move.
    await assert.rejects(
      publish(['--apk', second.apk, '--branch', 'master', '--commit', 'def5678'], {
        gh: firstRun.gh,
        failUploads: /def5678/,
      }),
      /upload failed/,
    );
    const after = firstRun.gh.releases[0].assets.find((a) => a.name === LATEST_ASSET);
    assert.equal(after.id, before.id, 'the stable asset is the same one');
    assert.equal(after.content, before.content, 'and it still holds the last good build');
    assert.ok(!firstRun.gh.releases[0].assets.some((a) => a.name.endsWith('-def5678.apk')));
  } finally {
    await first.cleanup();
    await second.cleanup();
  }
});

test('a failed stable upload leaves the previous stable build in place', async () => {
  const first = workspace('abc1234');
  const second = workspace('def5678');
  try {
    const firstRun = await publish(['--apk', first.apk, '--branch', 'master', '--commit', 'abc1234']);
    const before = firstRun.gh.releases[0].assets.find((a) => a.name === LATEST_ASSET);
    // The build uploads, but the stable copy's upload fails.
    await assert.rejects(
      publish(['--apk', second.apk, '--branch', 'master', '--commit', 'def5678'], {
        gh: firstRun.gh,
        failUploads: `${LATEST_ASSET}.staging`,
      }),
      /upload failed/,
    );
    const after = firstRun.gh.releases[0].assets.find((a) => a.name === LATEST_ASSET);
    assert.equal(after.id, before.id);
    assert.equal(after.content, before.content, 'the previous stable build is still downloadable');
  } finally {
    await first.cleanup();
    await second.cleanup();
  }
});

test('pruning keeps the newest builds and only ever deletes this workflow own assets', async () => {
  const gh = await fakeGitHub();
  try {
    const { id } = await ensureRollingRelease(gh, { branch: 'master', commit: 'abc1234', body: '' });
    const label = ownerLabel('a'.repeat(64));
    for (let i = 0; i < 12; i += 1) {
      const name = `Harmony_Android_Native_V1.9.6_debug-master-${String(i).padStart(7, '0')}.apk`;
      await gh.json('POST', `/releases/${id}/assets?name=${encodeURIComponent(name)}&label=${encodeURIComponent(label)}`, {
        body: Buffer.from(`build ${i}`),
        contentType: 'application/octet-stream',
      });
    }
    // A hand-attached APK, the stable names, and a stray file: none is a pruning candidate.
    for (const name of ['Harmony_Android_Native_V1.9.6_handmade.apk', LATEST_ASSET, LATEST_SHA256_ASSET, 'notes.txt']) {
      await gh.json('POST', `/releases/${id}/assets?name=${encodeURIComponent(name)}`, {
        body: Buffer.from('x'),
        contentType: 'application/octet-stream',
      });
    }
    const pruned = await pruneRollingAssets(gh, { releaseId: id, keep: 10 });
    assert.equal(pruned.length, 2);
    assert.deepEqual(pruned.sort(), [
      'Harmony_Android_Native_V1.9.6_debug-master-0000000.apk',
      'Harmony_Android_Native_V1.9.6_debug-master-0000001.apk',
    ]);
    const names = gh.releases[0].assets.map((a) => a.name);
    for (const kept of ['Harmony_Android_Native_V1.9.6_handmade.apk', LATEST_ASSET, LATEST_SHA256_ASSET, 'notes.txt']) {
      assert.ok(names.includes(kept), `${kept} must survive pruning`);
    }
    assert.equal(names.filter((n) => /_debug-master-\d{7}\.apk$/.test(n)).length, 10);
  } finally {
    await gh.close();
  }
});

test('a rolling publish leaves a version release and its assets alone', async () => {
  const ws = workspace();
  try {
    const { gh } = await publish(['--apk', ws.apk, '--branch', 'master', '--commit', 'abc1234'], {
      releases: [versionRelease()],
    });
    const version = gh.releases.find((r) => r.tag_name === 'v1.9.6');
    assert.deepEqual(
      version.assets.map((a) => a.name).sort(),
      ['Harmony_Android_Native_V1.9.6.apk', 'Harmony_Android_V1.9.6.apk', 'latest.json'],
    );
    assert.equal(version.prerelease, false);
    assert.equal(version.draft, false);
    const deletes = gh.calls.filter((c) => c.method === 'DELETE');
    assert.ok(deletes.every((c) => !String(c.path).includes('/41/')), 'nothing of the version release is deleted');
  } finally {
    await ws.cleanup();
  }
});

test('a full release that happens to carry the rolling tag is refused, not rewritten', async () => {
  const ws = workspace();
  try {
    await assert.rejects(
      publish(['--apk', ws.apk, '--branch', 'master', '--commit', 'abc1234'], {
        releases: [{ ...versionRelease(), id: 55, tag_name: `${ROLLING_TAG_PREFIX}master`, prerelease: false }],
      }),
      /is not a prerelease; refusing to publish into it/,
    );
  } finally {
    await ws.cleanup();
  }
});

test('a dry run contacts nothing and says what it would publish', async () => {
  const ws = workspace();
  try {
    const { stdout } = await run(process.execPath, [join(here, 'android-native-release.mjs'), 'rolling',
      '--apk', ws.apk, '--branch', 'master', '--dry-run'], {
      env: { ...process.env, GITHUB_TOKEN: '', GITHUB_REPOSITORY: OWNER_REPO },
    });
    assert.match(stdout, /Would publish Harmony_Android_Native_V1\.9\.6_debug-master-abc1234\.apk to prerelease android-native-rolling-master/);
  } finally {
    await ws.cleanup();
  }
});

test('an upload into a version release replaces only the names it owns', async () => {
  const ws = workspace();
  const dir = mkdtempSync(join(tmpdir(), 'harmony-native-up-'));
  try {
    const gh = await fakeGitHub({ releases: [versionRelease()] });
    const apk = join(dir, 'Harmony_Android_Native_V1.9.6.apk');
    writeFileSync(apk, Buffer.from('native release apk'));
    const { stdout } = await run(process.execPath, [join(here, 'android-native-release.mjs'), 'upload', '--release-id', '41', apk], {
      env: { ...process.env, GITHUB_TOKEN: 't', GITHUB_REPOSITORY: OWNER_REPO, GITHUB_API_URL: gh.url },
    });
    assert.match(stdout, /Uploaded Harmony_Android_Native_V1\.9\.6\.apk to release 41/);
    const names = gh.releases[0].assets.map((a) => a.name).sort();
    assert.deepEqual(names, ['Harmony_Android_Native_V1.9.6.apk', 'Harmony_Android_V1.9.6.apk', 'latest.json']);
    await gh.close();
  } finally {
    rmSync(dir, { recursive: true, force: true });
    await ws.cleanup();
  }
});

test('an upload refuses to replace a release asset it did not put there', async () => {
  const ws = workspace();
  const dir = mkdtempSync(join(tmpdir(), 'harmony-native-up-'));
  try {
    const gh = await fakeGitHub({ releases: [versionRelease()] });
    // Same name as the Tauri APK, which this workflow does not own.
    const apk = join(dir, 'Harmony_Android_V1.9.6.apk');
    writeFileSync(apk, Buffer.from('not ours to replace'));
    await assert.rejects(
      run(process.execPath, [join(here, 'android-native-release.mjs'), 'upload', '--release-id', '41', apk], {
        env: { ...process.env, GITHUB_TOKEN: 't', GITHUB_REPOSITORY: OWNER_REPO, GITHUB_API_URL: gh.url },
      }),
      /was not uploaded by this workflow; refusing to replace it/,
    );
    const asset = gh.releases[0].assets.find((a) => a.name === 'Harmony_Android_V1.9.6.apk');
    assert.equal(asset.id, 4101, 'the Tauri APK was not touched');
    await gh.close();
  } finally {
    rmSync(dir, { recursive: true, force: true });
    await ws.cleanup();
  }
});
