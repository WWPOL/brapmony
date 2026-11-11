# Bridge Attachment Mirror Queue Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In `mirror` mode, every Discord file on a bridge-bot message ends up in Harmony storage, up to the upload ceiling, including messages from before the switch, with flat memory and no slow requests.

**Architecture:** Small files keep mirroring inline. Large files, inline failures and every existing bridge-bot message with Discord links go into a Postgres queue (`bridge_mirror_queue`) drained by one worker in the bot-gateway process, which streams each file through a temp file into storage and rewrites the message with `update_message_content_silent`. Expired links are refreshed by sending the existing `REFRESH_ATTACHMENTS` op to the bridge bot.

**Tech Stack:** Postgres/plpgsql + pgTAP (`db_schema/`), bot-gateway (Node 24, Express, supabase-js 2, vitest), Vue 3 + vue-i18n (`src/`).

**Spec:** `docs/superpowers/specs/2026-11-10-bridge-mirror-queue-design.md` (read it, including "Calls made after review").

## Global Constraints

- Migration file `db_schema/migrations/20261112200001_bridge_mirror_queue.sql`; pgTAP `db_schema/tests/274_bridge_mirror_queue.sql`. Do not use 20261111950001–950006 or pgTAP 269–272 (open PR brapmony#3).
- Queue and state are `service_role` only: RLS on, no client policies, `REVOKE ALL ... FROM PUBLIC, anon, authenticated`, like `discord_bridge_threads` (`20261013300001_bridge_threads.sql:32-37`).
- Every new RPC the app or gateway calls must pass `scripts/check-rpc-coverage.sh`, `scripts/check-plpgsql.sh` and `check-fresh-install`; regenerate `SURFACE.tsv`/`REACHABILITY.tsv` with `scripts/generate-surface.sh` and `scripts/generate-reachability.sh`.
- Discord-CDN host test: reuse `isAllowedSourceUrl` / `hasDiscordCdnFilePart` in `bot-gateway/src/utils/mirrorExternalMedia.ts` (hosts `discordapp.com`, `discordapp.net`). Same storage path scheme: `c/<channel id lowercased>/bridge/<bot id>/<uuid>.<ext>` in `MESSAGE_MEDIA_BUCKET`, `cacheControl: '31536000'`, `upsert: false`.
- The size cap is `instance_config.upload_ceiling_bytes` (default 52428800 when absent). `MAX_BYTES` is removed.
- Env: `BRIDGE_MIRROR_INLINE_MAX_BYTES` (default 26214400), `BRIDGE_MIRROR_CONCURRENCY` (default 1), `BRIDGE_MIRROR_TMPDIR` (default `os.tmpdir()`), `BRIDGE_MIRROR_POLL_MS` (default 15000).
- Expiry: a Discord link is expired when its `ex` query param (hex Unix seconds) is missing-or-past, or under 10 minutes away.
- Backoff after a counted failed attempt: 1 min, 5 min, 30 min, 2 h, 12 h; the 5th failure sets `failed`. After sending a refresh: retry in 2 min. Bot not connected: retry in 15 min, attempt not counted.
- Rewrites are quiet (`update_message_content_silent`), never set edited, and never replace a part that has a storage `path`.
- User-facing strings in all 7 locales (`src/locales/{en,de,es,fr,ja,ko,zh}.json`).
- Commits: conventional messages ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Signing fallback per user CLAUDE.md (retry once, then `git -c commit.gpgsign=false commit`, report it). Never `--no-verify`. Do not push.

## Review Focus

1. **A message edited by its author while queued** (live bridge edit from Discord): the worker's compare-and-swap must lose, re-read and redo, never restore old text. Test in Task 4.
2. **The upload ceiling lowered or Storage refusing the upload (413) mid-file**: the message keeps its Discord link, row goes `too_large`, the temp file is deleted. Test in Task 2 and Task 4.
3. **A message deleted while its file is downloading**: the row disappears by cascade; the worker's rewrite affects 0 rows and it must not throw or re-insert the row. Test in Task 4.
4. **Mode switched away from mirror mid-backlog**: the worker stops claiming; rows stay `queued`; switching back resumes without re-walking from the start unless the switch is a new transition into mirror. Test in Task 4.
5. **A message with several Discord files where one is too large**: the others are mirrored and written; the message stays `too_large` with `largest_bytes` of the remaining one. Test in Task 4.

---

### Task 1: Queue schema and RPCs

**Files:**
- Create: `db_schema/migrations/20261112200001_bridge_mirror_queue.sql`
- Create: `db_schema/tests/274_bridge_mirror_queue.sql`
- Modify: `SURFACE.tsv`, `REACHABILITY.tsv` (regenerated)

**Interfaces:**
- Produces tables:
  - `public.bridge_mirror_queue(message_id uuid PK REFERENCES messages(id) ON DELETE CASCADE, bot_id uuid NOT NULL, channel_id uuid NOT NULL, state text NOT NULL DEFAULT 'queued' CHECK (state IN ('queued','done','too_large','failed')), attempts int NOT NULL DEFAULT 0, next_attempt_at timestamptz NOT NULL DEFAULT now(), largest_bytes bigint, last_error text, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now())`, index on `(next_attempt_at) WHERE state = 'queued'`.
  - `public.bridge_mirror_state` singleton (`id boolean PRIMARY KEY DEFAULT true CHECK (id)`), columns `last_mode text`, `cursor_created_at timestamptz`, `cursor_id uuid`, `walk_done boolean NOT NULL DEFAULT false`, `last_ceiling bigint`, `updated_at`. (This replaces the spec's `instance_config` keys: it avoids the `a_instance_config_operator_keys` guard and public config reads. Builder's call, recorded here.)
- Produces RPCs (all `SECURITY DEFINER`, `SET search_path = public, pg_temp`, EXECUTE granted to `service_role` only unless noted):
  - `bridge_mirror_enqueue(p_message_ids uuid[]) RETURNS integer`: inserts `queued` rows for those ids whose message has a `bot_id` that is some `discord_bridges.bot_id`; existing `queued`/`failed`/`too_large` rows are reset to `queued`, `attempts = 0`, `next_attempt_at = now()`; `done` rows are reset too (a later edit may have added links). Returns rows touched.
  - `bridge_mirror_claim(p_lease_seconds integer) RETURNS TABLE(message_id uuid, bot_id uuid, channel_id uuid, attempts integer)`: one due `queued` row, `FOR UPDATE SKIP LOCKED`, sets `next_attempt_at = now() + lease`.
  - `bridge_mirror_finish(p_message_id uuid, p_state text, p_attempts integer, p_retry_seconds integer, p_largest_bytes bigint, p_error text) RETURNS void`: sets the row's state; when `p_state = 'queued'`, `next_attempt_at = now() + p_retry_seconds`. No-op when the row is gone.
  - `bridge_mirror_backfill(p_limit integer) RETURNS integer`: continues the walk from `bridge_mirror_state` cursor over `messages` with `bot_id` in `discord_bridges.bot_id`, `is_deleted IS NOT TRUE`, ordered `(created_at, id)`, taking `p_limit` rows; enqueues (as `bridge_mirror_enqueue` would, but skipping messages already `done` or `queued`) those whose `content` has a `file` or `url` part with a Discord-CDN URL (`jsonb_path_exists` with `like_regex "^https?://([a-z0-9-]+\\.)*discordapp\\.(com|net)/"`); advances the cursor; sets `walk_done` when fewer than `p_limit` rows were read. Returns rows enqueued.
  - `bridge_mirror_observe(p_mode text, p_ceiling bigint) RETURNS jsonb`: called by the worker each poll. On a transition into `mirror` (previous `last_mode` not `mirror`) resets the cursor and `walk_done = false`; when `p_ceiling > last_ceiling`, requeues `too_large` rows with `largest_bytes <= p_ceiling`; stores both. Returns `{"walk_done": bool}`.
  - `bridge_mirror_restart_walk() RETURNS void`: resets cursor and `walk_done` (called once at gateway start while in mirror; spec call 3).
  - `bridge_mirror_status() RETURNS jsonb` `{"queued":n,"done":n,"too_large":n,"too_large_bytes":n,"failed":n}`: EXECUTE to `authenticated` too; raises `42501` unless `public.is_current_user_admin()`.

- [ ] **Step 1: Write the pgTAP tests** in `274_bridge_mirror_queue.sql` (follow `265_history_import_functions.sql` for fixtures: a server, channel, bridge bot in `discord_bridges`, a non-bridge bot). Assertions:
  - `authenticated` and `anon` cannot select either table; `bridge_mirror_status()` raises 42501 for a non-admin and returns counts for an admin.
  - enqueue ignores a non-bridge bot's message and a user's message; returns 1 for a bridge-bot message; re-enqueue of a `failed` row resets attempts to 0.
  - claim returns the due row once; a second claim inside the lease returns nothing; after `bridge_mirror_finish(..., 'queued', 1, 0, null, 'x')` it is claimable again.
  - backfill with `p_limit = 1` over three bridge-bot messages (one link-free, one Discord-CDN `file`, one Discord-CDN `url` part, the last backdated to 2023) enqueues exactly the two with links across three calls and sets `walk_done`.
  - observe: `link` then `mirror` resets the cursor; `mirror` then `mirror` does not; ceiling 100 → 200 requeues a `too_large` row with `largest_bytes = 150` and leaves one with 300.
  - deleting the message deletes its queue row.
- [ ] **Step 2: Run** the pgTAP file the way CI does (see `db_schema/tests/README*` or how 265–268 are run). Expected: FAIL (functions missing).
- [ ] **Step 3: Write the migration.**
- [ ] **Step 4: Run** the pgTAP file, `scripts/check-plpgsql.sh`, `scripts/check-rpc-coverage.sh`, and the fresh-install check. Expected: all pass. Regenerate `SURFACE.tsv` and `REACHABILITY.tsv`.
- [ ] **Step 5: Commit** `feat(db): bridge attachment mirror queue`.

### Task 2: Streaming mirror of one file

**Files:**
- Create: `bot-gateway/src/utils/mirrorFile.ts`
- Modify: `bot-gateway/src/utils/mirrorExternalMedia.ts` (remove `MAX_BYTES`, export `isAllowedSourceUrl`, `fileTypeOf`, `extensionFrom`, and add `getUploadCeilingBytes`)
- Modify: `bot-gateway/src/utils/stripMetadata.ts` (export a `fileSource(path)` ByteSource and `stripMediaFileInPlace(path)`)
- Test: `bot-gateway/src/utils/__tests__/mirrorFile.test.ts`, `bot-gateway/src/utils/__tests__/stripMetadata.test.ts`

**Interfaces:**
- Produces:
  - `discordLinkExpiresAt(url: string): Date | null` (from `ex`, hex seconds; null when absent or unparsable) and `isDiscordLinkExpired(url: string, now = Date.now()): boolean` (null or under 10 min left), in `mirrorFile.ts`.
  - `getUploadCeilingBytes(): Promise<number>` in `mirrorExternalMedia.ts`: reads `instance_config.upload_ceiling_bytes` (jsonb number or string), default 52428800.
  - `class TooLargeError extends Error { constructor(readonly bytes: number) }` and `class GoneError extends Error` (HTTP 403/404/410 from Discord), in `mirrorFile.ts`.
  - `mirrorFileToStorage(sourceUrl: string, opts: { botId: string; channelId: string; fileName?: string; contentType?: string; knownSize?: number; ceiling: number; tmpDir?: string; fetchImpl?: typeof fetch }): Promise<{ path: string; url: string; contentType: string; bytes: number }>` in `mirrorFile.ts`.
  - `fileSource(path: string): Promise<ByteSource & { close(): Promise<void> }>` and `stripMediaFileInPlace(path: string): Promise<boolean>` (true when bytes changed) in `stripMetadata.ts`.

- [ ] **Step 1: Write failing tests** (`mirrorFile.test.ts`, mock `supabase.storage` and `signMessageMediaPaths` like existing gateway tests do; serve bodies from a fake `fetchImpl`):
  - `discordLinkExpiresAt('https://cdn.discordapp.com/a/b.png?ex=6a000000&is=1&hm=2')` equals `new Date(0x6a000000 * 1000)`; no `ex` → null; `isDiscordLinkExpired` true at 9 min left, false at 11 min.
  - `knownSize` over `ceiling` → rejects `TooLargeError` without calling fetch.
  - `Content-Length` over ceiling → `TooLargeError`, no upload; a body that streams past the ceiling without `Content-Length` → `TooLargeError` and the temp file no longer exists.
  - 404 → `GoneError`; 500 → plain `Error`.
  - A 3 MB `video/mp4` body uploads with `contentType: 'video/mp4'`, path matching `^c/<channel>/bridge/<bot>/[0-9a-f-]{36}\.mp4$`, and the upload body is a stream (not a Buffer); the temp dir is empty afterwards (also after an upload error).
  - An APNG body (fixture already in `stripMetadata.test.ts` or a 3-chunk minimal one) yields `contentType: 'image/apng'` and `.apng`.
  - A disallowed host rejects before fetch.
  - In `stripMetadata.test.ts`: `stripMediaFileInPlace` on a temp copy of the existing ISOBMFF fixture produces bytes equal to `stripMediaBytes` of the same fixture.
- [ ] **Step 2: Run** `cd bot-gateway && npx vitest run src/utils/__tests__/mirrorFile.test.ts src/utils/__tests__/stripMetadata.test.ts`. Expected: FAIL.
- [ ] **Step 3: Implement.** Download with `fetch`, pipe `Readable.fromWeb(res.body)` through a byte counter into `fs.createWriteStream` under `mkdtemp(join(tmpDir, 'mirror-'))`, aborting past the ceiling. Images (`image/*`, at most `IMAGE_MAX_BYTES`): read the file into memory and use `stripMediaBytes` + `isAnimatedPng` as today. ISOBMFF (`video/mp4`, `video/quicktime`, `.mp4/.mov/.m4v/.3gp`): `stripMediaFileInPlace`. Otherwise unchanged. APNG check reads only the first 64 KB. Upload with `supabase.storage.from(MESSAGE_MEDIA_BUCKET).upload(path, createReadStream(file), { contentType, upsert: false, cacheControl: '31536000', duplex: 'half' })`; first verify against the local stack (Task 6 env) that a 60 MB stream upload succeeds through Kong/storage-api v1.90; if it doesn't, use TUS (`/storage/v1/upload/resumable`) from the file instead and note it in the commit. Always `rm -r` the temp dir in `finally`. `mirrorExternalMediaToStorage` keeps its signature and becomes a wrapper over `mirrorFileToStorage` with `ceiling = await getUploadCeilingBytes()`.
- [ ] **Step 4: Run** the tests from Step 2 and the full `npx vitest run` in `bot-gateway`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(gateway): stream Discord attachments to storage through disk, capped by the upload ceiling`.

### Task 3: Defer large files on the inline path; guard silent updates

**Files:**
- Modify: `bot-gateway/src/utils/mirrorExternalMedia.ts` (`applyBridgeAttachmentPolicy`)
- Modify: `bot-gateway/src/api/messageContent.ts` (`prepareBotMessageContent`)
- Modify: `bot-gateway/src/api/BotRestAPI.ts` (`createBotMessage` ~660, `editBotMessage` ~1009, `silentUpdateMessageContent` ~410)
- Modify: `bot-gateway/src/bridge/historyImport.ts` (~211, after `bridge_import_messages`)
- Create: `bot-gateway/src/bridge/mirrorEnqueue.ts`
- Test: existing `bot-gateway/src/api/__tests__/BridgeV2API.imports.test.ts`, plus `bot-gateway/src/utils/__tests__/mirrorExternalMedia.test.ts` (create if absent), `bot-gateway/src/api/__tests__/silentUpdate.test.ts`

**Interfaces:**
- Consumes: Task 1 `bridge_mirror_enqueue(p_message_ids uuid[])`; Task 2 `mirrorFileToStorage`, `getUploadCeilingBytes`.
- Produces:
  - `applyBridgeAttachmentPolicy(parts, botId, channelId): Promise<{ parts: any[]; deferred: boolean }>`. A Discord-CDN part is deferred (left as is, `deferred = true`) when `part.fileSize > BRIDGE_MIRROR_INLINE_MAX_BYTES`, when the response's `Content-Length` exceeds it (abort the download), or when the inline mirror fails for any reason other than `TooLargeError` over the ceiling.
  - `prepareBotMessageContent(...): Promise<{ parts: unknown[]; mirrorDeferred: boolean }>`.
  - `enqueueMirror(messageIds: string[]): Promise<void>` in `mirrorEnqueue.ts`: calls the RPC, logs and swallows errors (the startup walk covers misses).

- [ ] **Step 1: Write failing tests:**
  - policy: mode `mirror`, part with `fileSize` 30 MB → returned unchanged, `deferred: true`, fetch not called; 1 MB part → mirrored, `deferred: false`; mirror throwing a network error → part unchanged, `deferred: true`; mode `link` → unchanged, `deferred: false`.
  - import route: a batch of 3 where message B's content is deferred and C is rejected → `bridge_mirror_enqueue` called once with only B's inserted id (mapped through `inserted[].discord_message_id`).
  - `createBotMessage` with a deferred part → enqueue called with the new message id; `duplicate` result → not called. `editBotMessage` likewise.
  - silent update: current content has `{type:'file', url:'https://x/storage...', path:'c/..'}`, request replaces it with a Discord-CDN url without `path` → 409 `{ error: 'mirrored_part' }`, RPC not called; a request that swaps one Discord link for a fresh Discord link → 200.
- [ ] **Step 2: Run** `cd bot-gateway && npx vitest run src/api src/utils src/bridge`. Expected: the new tests FAIL.
- [ ] **Step 3: Implement.** Update every caller of `prepareBotMessageContent` to use `.parts`. The silent-update guard compares parts by index after `botContentParts`: refuse when any current part with a `path` would become a part without one.
- [ ] **Step 4: Run** full `npx vitest run` and `npx tsc --noEmit` in `bot-gateway`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(gateway): queue large or failed Discord attachments instead of mirroring them in the request`.

### Task 4: The mirror worker

**Files:**
- Create: `bot-gateway/src/bridge/mirrorWorker.ts`
- Modify: `bot-gateway/src/index.ts` (start it next to `bridgeConfigWatcher.start()`, ~line 64)
- Test: `bot-gateway/src/bridge/__tests__/mirrorWorker.test.ts`

**Interfaces:**
- Consumes: Task 1 RPCs (`bridge_mirror_claim`, `bridge_mirror_finish`, `bridge_mirror_backfill`, `bridge_mirror_observe`, `bridge_mirror_restart_walk`, `update_message_content_silent`); Task 2 `mirrorFileToStorage`, `isDiscordLinkExpired`, `TooLargeError`, `GoneError`, `getUploadCeilingBytes`; `getBridgeAttachmentMode`; `WebSocketGateway.isBotConnected(botId)` and `sendToBot(botId, event)`.
- Produces: `class MirrorWorker { constructor(deps: MirrorWorkerDeps); start(): void; stop(): Promise<void>; tick(): Promise<'idle' | 'worked'> }` where `MirrorWorkerDeps = { rpc(name: string, args: object): Promise<any>; readMessage(id: string): Promise<{ id: string; content: any[]; channel_id: string; bot_id: string } | null>; mode(): Promise<BridgeAttachmentMode>; ceiling(): Promise<number>; mirror: typeof mirrorFileToStorage; gateway: Pick<WebSocketGateway, 'isBotConnected' | 'sendToBot'>; now(): number; log: Pick<Console, 'info' | 'warn' | 'error'> }`. `tick()` is exported for tests; `start()` loops `tick()` with `BRIDGE_MIRROR_POLL_MS` when idle and immediately when worked, `BRIDGE_MIRROR_CONCURRENCY` loops.

Per tick: `observe(mode, ceiling)`; if mode ≠ `mirror` → idle. If `!walk_done` → `bridge_mirror_backfill(500)`. Claim one (lease 1800 s). Read the message (gone → finish is a no-op; return worked). For each Discord-CDN `file`/`url` part in order: expired → if bot connected, `sendToBot(bot_id, { op: 0, t: 'REFRESH_ATTACHMENTS', d: { messageId, channelId, content } })` and finish `queued` with retry 120 s and attempts +1 (five refreshes that never yield a usable link end in `failed`, "Discord would not refresh the link"); not connected → retry 900 s, attempts unchanged; stop processing the message. Otherwise `mirror(...)`: `TooLargeError` → remember the largest bytes and continue with the other parts; `GoneError` on a non-expired link → `failed`, "gone from Discord". Then write all mirrored parts in one `update_message_content_silent(id, readContent, newContent)`; false → re-read once and re-apply the already uploaded paths to matching parts (same base URL); still false → finish `queued` retry 60 s. Final state: `too_large` with `largest_bytes` if any part was too large, else `done`. Other errors → backoff table, 5th → `failed` with `last_error`. On `start()` while mode is `mirror`: `bridge_mirror_restart_walk()` once.

- [ ] **Step 1: Write failing tests** with fake deps (no network, no DB):
  - mode `link` → `tick()` returns `idle`, no claim.
  - fresh link → `mirror` called, `update_message_content_silent` called with the part now `type: 'file'`, `path`, `url`, `fileType`; finish `done`.
  - expired link, bot connected → `sendToBot` called with `REFRESH_ATTACHMENTS` and the message content; finish `queued` 120 s with attempts +1; no mirror call; at the 5th → `failed`. Bot not connected → finish `queued` 900 s, attempts unchanged.
  - two parts, one `TooLargeError(600_000_000)` → the other is written; finish `too_large`, `largest_bytes = 600000000`.
  - CAS false then the re-read shows the author edited the text (Review Focus 1) → second update keeps the new text and the mirrored path; finish `done`.
  - message gone after claim (Review Focus 3) → no throw, no update, returns `worked`.
  - mode flips to `link` between ticks (Review Focus 4) → second tick idle, row not finished.
  - generic error 5 times → finishes with retry 60, 300, 1800, 7200, then `failed`.
  - backfill is called while `walk_done` is false and not after.
- [ ] **Step 2: Run** `cd bot-gateway && npx vitest run src/bridge/__tests__/mirrorWorker.test.ts`. Expected: FAIL.
- [ ] **Step 3: Implement** `mirrorWorker.ts` and wire it in `index.ts` with the real deps (`supabase.rpc`, a `messages` select, `getBridgeAttachmentMode`, `getUploadCeilingBytes`, `mirrorFileToStorage`, the gateway instance). Stop it on shutdown alongside the other pollers.
- [ ] **Step 4: Run** full `npx vitest run` and `npx tsc --noEmit` in `bot-gateway`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(gateway): background worker stores Discord files in mirror mode, old messages included`.

### Task 5: Admin status, docs, changelog

**Files:**
- Modify: `src/components/admin/InstanceConfig.vue` (the `bridgeAttachmentMode` block, ~lines 78-90)
- Modify: `src/locales/{en,de,es,fr,ja,ko,zh}.json`
- Modify: `docs/self-host/discord-bridges.md` ("Attachments from Discord"), `docs/run/discord-bridge.md` (the 50 MB note), `CHANGELOG.md` (`[Unreleased]` → Added)
- Test: the component's existing test file under `src/components/admin/__tests__/` (create `InstanceConfig.mirrorStatus.test.ts` if none fits)

**Interfaces:**
- Consumes: Task 1 `bridge_mirror_status()`.
- Produces: none.

- [ ] **Step 1: Write a failing component test:** with mode `mirror` and `supabase.rpc('bridge_mirror_status')` mocked to `{queued: 12, done: 300, too_large: 2, too_large_bytes: 1200000000, failed: 1}`, the hint shows "12 waiting", "300 stored", "2 too large (1.2 GB)" with the `harmony config --upload-ceiling` advice, and "1 failed"; with mode `link` the RPC is not called.
- [ ] **Step 2: Run** `npx vitest run src/components/admin`. Expected: FAIL.
- [ ] **Step 3: Implement.** Load the status when the block shows with mode `mirror` (and on a 30 s interval while it shows). en copy: `"Storing Discord files: {queued} waiting, {done} stored"`, `"{count} too large ({size}): raise the upload ceiling with harmony config --upload-ceiling 500M"`, `"{count} failed"`, `"Every Discord file is stored"` when queued, too_large and failed are 0. Translate into the other six. Docs: mirror covers existing messages automatically; the cap is the upload ceiling (Discord allows up to 500 MB); the gateway needs free temp disk about the size of the largest file (`BRIDGE_MIRROR_TMPDIR`) and Storage needs room for all of them; links expired in the meantime are refreshed through the bridge, which must be online; one gateway process assumed. Replace the 50 MB note.
- [ ] **Step 4: Run** `npx vitest run src/components/admin`, `npm run type-check`, and the docs build if the repo has one (`npm run docs:build` or as CI's Docs job does). Expected: PASS.
- [ ] **Step 5: Commit** `feat(admin): show progress of storing Discord files; docs for mirror mode`.

### Task 6: Live test on the local stack (driver, not a subagent)

**Files:** none committed, except fixes found.

- [ ] Bring up the stack with the test kit (`~/.config/harmony-test/kit/README.md`), using a **separate compose project name and port** (not the shared `hme2e`), with `FILE_SIZE_LIMIT=1048576`, `upload_ceiling_bytes = 1048576`, `BRIDGE_MIRROR_INLINE_MAX_BYTES=204800`.
- [ ] Pair the seeded Discord test server, mode `link`, import a channel with images and a 2–8 MB file. Expected: Discord links stored.
- [ ] Switch to `mirror`. Expected: the backlog drains; files under 1 MB become storage paths; the large file is `too_large`; no notification or edited mark; admin status matches.
- [ ] Raise the ceiling to 16 MB (`FILE_SIZE_LIMIT` and `upload_ceiling_bytes`). Expected: the `too_large` row is requeued and stored.
- [ ] Force an expired link (set a queued message's part `ex` to a past value in SQL) with the bridge running. Expected: refresh sent, then stored on the next attempt. With the bridge stopped: retried every 15 min without counting attempts.
- [ ] Memory: in the gateway container, run a tsx script calling `mirrorFileToStorage` with a `fetchImpl` that streams a 500 MB synthetic MP4 (ceiling 600 MB, Storage limit raised to match); process RSS stays under +100 MB. No allow-list change in product code.
- [ ] Take down only this test stack.
