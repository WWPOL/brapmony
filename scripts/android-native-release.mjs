#!/usr/bin/env node
// Publishes the native Android client's APK from CI. Two callers:
//
//   node scripts/android-native-release.mjs rolling --apk <file> --branch <ref> \
//       --commit <sha> [--keep <n>] [--body <markdown>] [--dry-run]
//     Publishes a rolling prerelease for one branch: `Native Android (rolling) <branch>`
//     tagged `android-native-rolling-<slug>`. Each run adds its APK under its own
//     versioned name and refreshes the stable `Harmony_Android_Native_latest.apk` (and
//     its `.sha256`) to the same bytes.
//
//   node scripts/android-native-release.mjs upload --release-id <id> <file>...
//     Attaches files to an existing release, replacing assets of the same name and
//     nothing else. Used by release.yml, which already has the draft release.
//
// What this refuses to do, because a published download link is somebody's:
//
//   * touch a release whose tag is not a rolling tag it computed itself;
//   * publish into a release that is not a prerelease;
//   * overwrite an asset this workflow did not upload (assets carry an ownership
//     label, and only labelled ones are ever replaced or pruned);
//   * replace a build asset whose bytes differ, which would silently change what an
//     existing name means (the new bytes go under a distinct name instead);
//   * delete the stable `latest` name before the replacement has been uploaded;
//   * prune when the newest build did not upload.
//
// Retention: a rolling release keeps the newest DEFAULT_KEEP builds of its branch and
// deletes older ones it uploaded itself. The release is a rolling window, not an
// archive: a link to a build older than that window stops resolving. The versioned
// release of a tag is the archive, and nothing here touches it.
//
// Environment: GITHUB_TOKEN, GITHUB_REPOSITORY, GITHUB_API_URL, GITHUB_OUTPUT.
import { appendFileSync, copyFileSync, existsSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { tmpdir } from 'node:os';
import { basename, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';

export const LATEST_ASSET = 'Harmony_Android_Native_latest.apk';
export const LATEST_SHA256_ASSET = 'Harmony_Android_Native_latest.apk.sha256';
// The prefix every rolling release tag carries. Nothing else in the repository's
// releases starts with it, so a lookup can never match a version release.
export const ROLLING_TAG_PREFIX = 'android-native-rolling-';
// How many of a branch's own builds a rolling release keeps.
export const DEFAULT_KEEP = 10;
// Written into every asset's label by this workflow. An asset without it was put there
// by somebody else, and is never replaced or deleted.
export const OWNER = 'harmony-native-android';
export const ownerLabel = (sha256) => `${OWNER} sha256:${sha256}`;
export function isOwned(asset) {
  return typeof asset.label === 'string' && asset.label.startsWith(`${OWNER} `);
}
export function ownedSha256(asset) {
  const m = /sha256:([0-9a-f]{64})/.exec(asset.label || '');
  return m ? m[1] : null;
}
// The asset names this workflow publishes. A tag's release also carries the Tauri
// APK and latest.json, which are not these; naming is how the two are told apart on a
// release whose assets predate the ownership label.
export function ownName(name) {
  return /^Harmony_Android_Native_/.test(name);
}
export function ownsAsset(asset) {
  return isOwned(asset) || ownName(asset.name);
}

export class PublishError extends Error {}

// A git ref is not a valid tag on its own: `feature/x` would make the tag look like a
// path, and a branch named `v1.0.0` would collide with a version release. The tag is the
// branch with every run of characters outside [A-Za-z0-9._-] replaced by `-`.
export function rollingTag(branch) {
  const name = String(branch || '').trim();
  if (!name) throw new PublishError('--branch is required');
  const slug = name.replace(/[^A-Za-z0-9._-]+/g, '-').replace(/-+/g, '-').replace(/^-|-$/g, '');
  if (!slug) throw new PublishError(`branch ${name} has no characters a tag can carry`);
  return `${ROLLING_TAG_PREFIX}${slug.slice(0, 80)}`;
}

export function rollingName(branch) {
  return `Native Android (rolling) ${String(branch || '').trim()}`;
}

export function sha256File(file) {
  return createHash('sha256').update(readFileSync(file)).digest('hex');
}

export function client({
  token = process.env.GITHUB_TOKEN,
  repository = process.env.GITHUB_REPOSITORY,
  apiUrl = process.env.GITHUB_API_URL || 'https://api.github.com',
} = {}) {
  if (!token) throw new PublishError('GITHUB_TOKEN is not set');
  if (!/^[^/]+\/[^/]+$/.test(repository || '')) {
    throw new PublishError(`GITHUB_REPOSITORY is not owner/repo: ${repository}`);
  }
  const base = apiUrl.replace(/\/$/, '');
  const repoUrl = `${base}/repos/${repository}`;
  const headers = {
    authorization: `Bearer ${token}`,
    'x-github-api-version': '2022-11-28',
    'user-agent': OWNER,
  };

  async function request(method, url, { body, accept = 'application/vnd.github+json', contentType } = {}) {
    return fetch(url.startsWith('http') ? url : `${repoUrl}${url}`, {
      method,
      headers: { ...headers, accept, ...(contentType ? { 'content-type': contentType } : {}) },
      body,
    });
  }

  async function json(method, url, opts = {}) {
    const res = await request(method, url, opts);
    if (!res.ok) {
      const error = new PublishError(`${method} ${url}: ${res.status} ${await res.text()}`);
      error.status = res.status;
      throw error;
    }
    return res.status === 204 ? null : res.json();
  }

  async function paginate(url) {
    const out = [];
    let next = `${repoUrl}${url}${url.includes('?') ? '&' : '?'}per_page=100`;
    while (next) {
      const res = await request('GET', next);
      if (!res.ok) throw new PublishError(`GET ${next}: ${res.status} ${await res.text()}`);
      out.push(...(await res.json()));
      next = /<([^>]+)>;\s*rel="next"/.exec(res.headers.get('link') || '')?.[1];
    }
    return out;
  }

  return { request, json, paginate };
}

// Drafts are not addressable by tag and the list is paged, so the list is read through
// paginate. A release is matched by exact tag only, and only ever a rolling tag.
async function findByTag(gh, tag) {
  if (!tag.startsWith(ROLLING_TAG_PREFIX)) {
    throw new PublishError(`refusing to look up ${tag}: not a rolling tag`);
  }
  return (await gh.paginate('/releases')).find((r) => r.tag_name === tag) || null;
}

export async function ensureRollingRelease(gh, { branch, commit, body }) {
  const tag = rollingTag(branch);
  const existing = await findByTag(gh, tag);
  if (existing) {
    // A rolling release that somehow became a full release would take the tag of a
    // download link people already have and start counting as a release; refuse rather
    // than quietly rewrite its meaning.
    if (!existing.prerelease) {
      throw new PublishError(`release ${tag} exists and is not a prerelease; refusing to publish into it`);
    }
    return { id: existing.id, tag, created: false };
  }
  const created = await gh.json('POST', '/releases', {
    contentType: 'application/json',
    body: JSON.stringify({
      tag_name: tag,
      name: rollingName(branch),
      body,
      draft: false,
      prerelease: true,
      ...(commit ? { target_commitish: commit } : {}),
    }),
  });
  return { id: created.id, tag, created: true };
}

async function listAssets(gh, releaseId) {
  return gh.paginate(`/releases/${releaseId}/assets`);
}

// GitHub's upload endpoint replaces nothing: a second upload of a name that is already
// there is a 422. Every upload here carries the ownership label, so a later run can tell
// its own assets from anyone else's.
async function uploadFile(gh, { releaseId, file, name, label }) {
  if (!existsSync(file)) throw new PublishError(`no such file: ${file}`);
  const release = await gh.json('GET', `/releases/${releaseId}`);
  const base = release.upload_url.replace(/\{.*\}$/, '');
  const query = new URLSearchParams({ name, ...(label ? { label } : {}) });
  try {
    return await gh.json('POST', `${base}?${query}`, {
      body: readFileSync(file),
      contentType: 'application/octet-stream',
    });
  } catch (error) {
    if (error.status === 422) {
      throw new PublishError(`asset ${name} is already on release ${releaseId}`);
    }
    throw error;
  }
}

// Adds this run's build under its own name. A re-run of the same commit with the same
// bytes is a no-op: the asset a link already points at keeps meaning the same thing. A
// re-run with different bytes uploads under a distinct name rather than changing what the
// existing name resolves to.
export async function publishBuild(gh, { releaseId, file, name, attempt = 1 }) {
  const sha256 = sha256File(file);
  const existing = (await listAssets(gh, releaseId)).filter((a) => a.name === name);
  if (existing.length) {
    const same = existing.find((a) => ownedSha256(a) === sha256);
    if (same) return { asset: same, uploaded: false, sha256, name };
    if (!existing.every(isOwned)) {
      throw new PublishError(`asset ${name} was not uploaded by this workflow; refusing to replace it`);
    }
    // Same commit, different bytes: a rebuild. It gets its own name, so the earlier
    // build's link keeps resolving to the earlier bytes.
    const suffix = `.r${attempt}`;
    const renamed = name.replace(/\.apk$/, `${suffix}.apk`);
    const asset = await uploadFile(gh, { releaseId, file, name: renamed, label: ownerLabel(sha256) });
    return { asset, uploaded: true, sha256, name: renamed };
  }
  const asset = await uploadFile(gh, { releaseId, file, name, label: ownerLabel(sha256) });
  return { asset, uploaded: true, sha256, name };
}

// Moves the stable name onto [file] without ever leaving the name absent: the new bytes
// are uploaded under a staging name first, then the names are swapped by rename, and only
// then is the previous file deleted. If anything fails before the swap, the previous
// `latest` is still there and still downloadable.
export async function replaceStable(gh, { releaseId, file, name, label }) {
  const assets = await listAssets(gh, releaseId);
  const current = assets.filter((a) => a.name === name);
  // Already these bytes: leave the asset alone, so a re-run changes nothing and the
  // id a download link resolves through stays the same. The label, not the file's own
  // hash, is what says which bytes are there: the checksum file's label names the APK.
  if (current.length === 1 && current[0].label === label) {
    return { asset: current[0], replaced: 0 };
  }
  for (const a of current) {
    if (!isOwned(a)) {
      throw new PublishError(`asset ${name} was not uploaded by this workflow; refusing to replace it`);
    }
  }
  const staging = `${name}.staging-${process.pid}`;
  const previous = `${name}.previous-${process.pid}`;
  const uploaded = await uploadFile(gh, { releaseId, file, name: staging, label });
  try {
    const moved = [];
    for (const a of current) {
      moved.push(a.id);
      await gh.json('PATCH', `/releases/assets/${a.id}`, {
        contentType: 'application/json',
        body: JSON.stringify({ name: `${previous}-${a.id}` }),
      });
    }
    await gh.json('PATCH', `/releases/assets/${uploaded.id}`, {
      contentType: 'application/json',
      body: JSON.stringify({ name }),
    });
    // Only now, with the new bytes in place, is the old one removed.
    for (const id of moved) await gh.json('DELETE', `/releases/assets/${id}`);
    return { asset: { ...uploaded, name }, replaced: moved.length };
  } catch (error) {
    // Put the previous file back under the stable name if the swap did not finish.
    for (const a of current) {
      try {
        await gh.json('PATCH', `/releases/assets/${a.id}`, {
          contentType: 'application/json',
          body: JSON.stringify({ name }),
        });
        break;
      } catch {
        /* try the next one; the rest of the error is what matters */
      }
    }
    try {
      await gh.json('DELETE', `/releases/assets/${uploaded.id}`);
    } catch {
      /* the staging asset is harmless and pruned next run */
    }
    throw error;
  }
}

// Deletes the oldest of a rolling release's own builds, keeping [keep] newest. Only
// assets this workflow uploaded under a versioned name are candidates: unlabelled assets
// (attached by hand), the stable names, and the checksum are all left alone, and so is
// every other release.
export async function pruneRollingAssets(gh, { releaseId, keep = DEFAULT_KEEP, protect = [] }) {
  if (!(keep > 0)) throw new PublishError(`--keep must be positive: ${keep}`);
  const safe = new Set([LATEST_ASSET, LATEST_SHA256_ASSET, ...protect]);
  const assets = await listAssets(gh, releaseId);
  const builds = assets.filter(
    (a) => isOwned(a) && /\.apk$/.test(a.name) && !safe.has(a.name) && !a.name.includes('.staging-'),
  );
  if (builds.length <= keep) return [];
  // Newest first by id: GitHub assigns a higher id to each later upload, and an asset
  // carries no timestamp of its own.
  const oldest = builds.sort((a, b) => b.id - a.id).slice(keep);
  const pruned = [];
  for (const a of oldest) {
    await gh.json('DELETE', `/releases/assets/${a.id}`);
    pruned.push(a.name);
  }
  return pruned;
}

function required(values, keys) {
  for (const k of keys) if (!values[k]) throw new PublishError(`--${k} is required`);
}

export async function main(argv, { gh: ghOverride, log = console.log } = {}) {
  const [command, ...rest] = argv;
  const { values, positionals } = parseArgs({
    args: rest,
    allowPositionals: command === 'upload',
    options: {
      apk: { type: 'string' },
      branch: { type: 'string' },
      commit: { type: 'string' },
      body: { type: 'string' },
      keep: { type: 'string' },
      attempt: { type: 'string' },
      'release-id': { type: 'string' },
      'dry-run': { type: 'boolean', default: false },
    },
  });

  if (command === 'rolling') {
    required(values, ['apk', 'branch']);
    const tag = rollingTag(values.branch);
    if (values['dry-run']) {
      log(`Would publish ${basename(values.apk)} to prerelease ${tag}`);
      return { tag, dryRun: true };
    }
    const gh = ghOverride ?? client();
    const { id, created } = await ensureRollingRelease(gh, {
      branch: values.branch,
      commit: values.commit,
      body: values.body ?? '',
    });
    log(`${created ? 'Created' : 'Found'} rolling prerelease ${tag} (${id})`);
    const attempt = Number(values.attempt ?? 1) || 1;
    const build = await publishBuild(gh, { releaseId: id, file: values.apk, name: basename(values.apk), attempt });
    log(`${build.uploaded ? 'Uploaded' : 'Kept'} ${build.name} on ${tag} (sha256 ${build.sha256})`);
    // The stable name is only touched once the run's own asset is safely there, so a
    // failed build upload cannot disturb the build people are already downloading.
    const sha256 = build.sha256;
    await replaceStable(gh, { releaseId: id, file: values.apk, name: LATEST_ASSET, label: ownerLabel(sha256) });
    log(`Updated ${LATEST_ASSET} on ${tag}`);
    const checksum = stableText(`${sha256}  ${LATEST_ASSET}\n`, LATEST_SHA256_ASSET);
    await replaceStable(gh, { releaseId: id, file: checksum, name: LATEST_SHA256_ASSET, label: ownerLabel(sha256) });
    log(`Updated ${LATEST_SHA256_ASSET} on ${tag}`);
    const pruned = await pruneRollingAssets(gh, {
      releaseId: id,
      keep: Number(values.keep ?? DEFAULT_KEEP),
      protect: [build.name],
    });
    if (pruned.length) log(`Pruned ${pruned.length} older build(s) from ${tag}: ${pruned.join(', ')}`);
    if (process.env.GITHUB_OUTPUT) {
      appendFileSync(process.env.GITHUB_OUTPUT, `tag=${tag}\nrelease-id=${id}\nsha256=${sha256}\n`);
    }
    return { id, tag, uploaded: build.uploaded ? build.name : null, sha256, pruned };
  }

  if (command === 'upload') {
    required(values, ['release-id']);
    if (!positionals.length) throw new PublishError('upload needs at least one file');
    const gh = ghOverride ?? client();
    const releaseId = values['release-id'];
    const assets = await listAssets(gh, releaseId);
    const uploaded = [];
    for (const file of positionals) {
      const name = basename(file);
      const same = assets.filter((a) => a.name === name);
      for (const a of same) {
        // A tag's release can carry this name from a run before assets were labelled,
        // which is still this workflow's own file; anything else is not.
        if (!isOwned(a) && !ownName(a.name)) {
          throw new PublishError(`asset ${name} was not uploaded by this workflow; refusing to replace it`);
        }
        await gh.json('DELETE', `/releases/assets/${a.id}`);
      }
      uploaded.push(await uploadFile(gh, { releaseId, file, name, label: ownerLabel(sha256File(file)) }));
    }
    for (const a of uploaded) log(`Uploaded ${a.name} to release ${releaseId}`);
    return uploaded;
  }

  throw new PublishError('usage: android-native-release.mjs <rolling|upload> [options]');
}

// A copy of [file] under one fixed name, so the upload names the asset rather than taking
// the file's basename. Written in the runner's temporary directory, never in the tree.
export function stableCopy(file, name = LATEST_ASSET) {
  const dir = mkdtempSync(join(tmpdir(), 'harmony-native-'));
  const copy = join(dir, name);
  copyFileSync(file, copy);
  return copy;
}

// The same, for a text asset written rather than copied.
export function stableText(text, name) {
  const dir = mkdtempSync(join(tmpdir(), 'harmony-native-'));
  const file = join(dir, name);
  writeFileSync(file, text);
  return file;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main(process.argv.slice(2)).catch((err) => {
    console.error(`::error::${err.message}`);
    process.exit(1);
  });
}