#!/usr/bin/env node
// A stand-in for the slice of the GitHub releases API this workflow uses, for running the
// publish step locally: `node scripts/android-native-fake-api.mjs [port]`.
//
// It holds its state in memory and prints what it received, so a step can be run against it
// exactly as it runs on a runner. Nothing here is used by the workflow.
import http from 'node:http';

const port = Number(process.argv[2] || 8099);
const repo = process.env.GITHUB_REPOSITORY || 'WWPOL/brapmony';
const releases = [];
let nextId = 100;

const server = http.createServer(async (req, res) => {
  const base = `http://127.0.0.1:${server.address().port}`;
  const url = new URL(req.url, base);
  const chunks = [];
  for await (const c of req) chunks.push(c);
  const body = Buffer.concat(chunks);
  const send = (status, data, headers = {}) => {
    res.writeHead(status, { 'content-type': 'application/json', ...headers });
    res.end(data === undefined ? '' : JSON.stringify(data));
  };
  const page = (items) => {
    const n = Number(url.searchParams.get('page') || 1);
    const size = 2;
    const headers = {};
    if (n * size < items.length) {
      const next = new URL(url);
      next.searchParams.set('page', String(n + 1));
      headers.link = `<${next}>; rel="next"`;
    }
    send(200, items.slice((n - 1) * size, n * size), headers);
  };
  if (req.method === 'GET' && url.pathname === `/repos/${repo}/releases`) return page(releases);
  if (req.method === 'POST' && url.pathname === `/repos/${repo}/releases`) {
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
      upload_url: `${base}/repos/${repo}/releases/${id}/assets{?name,label}`,
    };
    releases.push(release);
    console.log(`created release ${id} ${release.tag_name} prerelease=${release.prerelease}`);
    return send(201, release);
  }
  let m = /^\/repos\/(.+)\/releases\/(\d+)$/.exec(url.pathname);
  if (m && req.method === 'GET') {
    const release = releases.find((r) => r.id === Number(m[2]));
    return release ? send(200, release) : send(404, { message: 'Not Found' });
  }
  m = /^\/repos\/(.+)\/releases\/(\d+)\/assets$/.exec(url.pathname);
  if (m && req.method === 'GET') {
    const release = releases.find((r) => r.id === Number(m[2]));
    return release ? page(release.assets) : send(404, { message: 'Not Found' });
  }
  if (m && req.method === 'POST') {
    const release = releases.find((r) => r.id === Number(m[2]));
    const name = url.searchParams.get('name');
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
      browser_download_url: `${base}/${repo}/releases/download/${release.tag_name}/${name}`,
    };
    release.assets.push(asset);
    console.log(`uploaded ${name} (${body.length} bytes, label ${asset.label || '-'})`);
    return send(201, asset);
  }
  m = /^\/repos\/(.+)\/releases\/assets\/(\d+)$/.exec(url.pathname);
  if (m && req.method === 'PATCH') {
    const { name } = JSON.parse(body.toString());
    for (const r of releases) {
      const a = r.assets.find((x) => x.id === Number(m[2]));
      if (a) {
        a.name = name;
        console.log(`renamed asset ${a.id} to ${name}`);
        return send(200, a);
      }
    }
    return send(404, { message: 'Not Found' });
  }
  if (m && req.method === 'DELETE') {
    for (const r of releases) r.assets = r.assets.filter((a) => a.id !== Number(m[2]));
    console.log(`deleted asset ${m[2]}`);
    return send(204);
  }
  return send(404, { message: `unhandled ${req.method} ${url.pathname}` });
});

server.listen(port, '127.0.0.1', () => {
  console.log(`fake GitHub API on http://127.0.0.1:${port} for ${repo}`);
});
