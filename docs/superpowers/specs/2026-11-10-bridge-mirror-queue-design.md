# Bridge attachment mirror queue: large files, and files already bridged

**Date:** 2026-11-10. **Area:** bot-gateway attachment mirroring (`bot-gateway/src/utils/mirrorExternalMedia.ts`), used by live bridging and the Discord history import when `bridge_attachment_mode = 'mirror'`.

## Intent

"Mirror to storage" should mean what an admin reads into it: Discord files on this instance live in this instance's storage. Today it falls short three ways:

1. **Hard-coded 50 MB cap** (`MAX_BYTES`). Larger files keep their Discord link, which expires. It ignores the instance's upload ceiling (`instance_config.upload_ceiling_bytes`, which mirrors storage-api's `FILE_SIZE_LIMIT`, set by `harmony config --upload-ceiling`).
2. **Whole-file buffering.** `arrayBuffer()` plus `stripMediaBytes` on the whole buffer. Discord allows 500 MB uploads; a few in flight can OOM a small gateway.
3. **Only new messages get mirrored.** Messages bridged or imported while the mode was `link` or `refresh`, or whose file was over the cap, keep Discord links forever. The only workaround is soft-deleting and re-importing.

Also found while designing: mirroring runs inline inside each history-import batch request, and the bridge gives that request 120 s. A batch with a few large videos would time out.

Measured on the user's production server: 54 Discord attachments over 50 MB (5.8 GB); 20,468 smaller files (27.5 GB) mirror fine.

## Goals

- In `mirror` mode, every Discord file on a bridged or imported message ends up in storage, up to the upload ceiling, including messages that existed before the switch. No separate action: switching to mirror is the action (user's call: "isn't it implied if they're doing mirror mode?").
- Memory use stays flat regardless of file size.
- History import and live relay stay fast: no request waits on a large download.
- Quiet: rewriting a message's files notifies nobody and doesn't mark it edited.
- Admins can see progress and what's stuck (too large, failed).

## Non-goals

- Mirroring non-Discord URLs.
- Changing `link` or `refresh` mode behaviour.
- Any bridge change. The bridge is untouched; this works with bridge 2.x as deployed.

## Anti-goals

- A large file must never take the gateway down (OOM) or block live relay.
- Never lose a message's file: if mirroring fails, the Discord link stays.
- Never overwrite a message a user or bot edited meanwhile (compare-and-swap).

## Approaches considered

1. **Bigger cap, stream inline** (in the request). Simple, but import batches would exceed the bridge's 120 s timeout and live relay would stall on big files. Rejected.
2. **Separate "Store Discord files" action** for backfill, on top of the mode. Proposed first; the user pointed out mirror mode already implies it. Rejected in favour of 3.
3. **Background mirror queue in the gateway (chosen).** Small files mirror inline as today. Large files, failed mirrors and every pre-existing message with Discord links go into a durable queue that one worker drains, streaming through disk. Expired links are refreshed through the existing `REFRESH_ATTACHMENTS` path.

## Design

### Existing pieces reused

- `REFRESH_ATTACHMENTS` gateway op (`bot-gateway/src/index.ts`, `/attachments/refresh`): the bridge re-signs a message's Discord links via Discord's `attachments/refresh-urls` and writes them back with `PATCH /messages/:id/content-silent`. The worker sends this op directly (`gateway.sendToBot(botId, ...)`) when a link has expired.
- `update_message_content_silent(p_message_id, p_old_content, p_content)`: quiet compare-and-swap content update.
- `stripMetadata.ts`: ISOBMFF (MP4/MOV) stripping edits bytes in place ("No byte moves"), behind a `ByteSource` abstraction (`blobSource`).
- `instance_config.upload_ceiling_bytes`.

### Queue (one migration)

Numbering: fork master's latest is `20261111900001` (pgTAP 268), and the open WWPOL/brapmony#3 holds `20261111950001`–`950006` and pgTAP 269–272. Use `20261112200001` and pgTAP 274 onward.

`public.bridge_mirror_queue`, service_role only (RLS on, no client policies):
- `message_id uuid PRIMARY KEY REFERENCES messages(id) ON DELETE CASCADE`
- `bot_id uuid NOT NULL`, `channel_id uuid NOT NULL`
- `state text NOT NULL CHECK (state IN ('queued','done','too_large','failed'))`
- `attempts int NOT NULL DEFAULT 0`, `next_attempt_at timestamptz NOT NULL DEFAULT now()`
- `largest_bytes bigint` (size of the largest file left, when known; used to requeue `too_large` when the ceiling rises), `last_error text`
- `created_at`, `updated_at`
- Index on `(next_attempt_at) WHERE state = 'queued'`.

RPCs (SECURITY DEFINER, service_role only, registered like other RPCs):
- `bridge_mirror_claim(p_lease interval)`: next due `queued` row, `FOR UPDATE SKIP LOCKED`, pushes `next_attempt_at` out by the lease so a crashed worker's row comes back. Safe with several gateway processes.
- `bridge_mirror_enqueue(p_message_id)`: upsert to `queued` (no-op if already queued).
- `bridge_mirror_backfill(p_after_created_at, p_after_id, p_limit)`: walks bot-authored messages (`idx_messages_bot_id` exists) in `(created_at, id)` order, enqueues those whose `content` has a Discord-CDN `file` or `url` part, returns the last key. The worker stores the cursor in `instance_config` (`bridge_mirror_backfill_cursor`), so the walk resumes and runs once per switch to mirror.
- `bridge_mirror_status()`: counts by state plus total bytes of `too_large`; callable by instance admins (follow how other instance-admin RPCs check).

Mode switch: when `bridge_attachment_mode` becomes `mirror` (the worker sees the change on its poll), reset the backfill cursor and start walking. When the upload ceiling rises, requeue `too_large` rows whose `largest_bytes` now fits.

### Worker (gateway process)

- One loop, `BRIDGE_MIRROR_CONCURRENCY` (default 1). Idle when the mode isn't `mirror`; rows wait.
- Per claimed message: read the current row. For each Discord-CDN part:
  - **Expired link** (the `ex` query param, hex Unix seconds, is past or under 10 minutes away): send `REFRESH_ATTACHMENTS` to the bridge bot if it's connected, set `next_attempt_at` +2 min, stop. Bot offline: +15 min, no attempt counted.
  - **Size:** `part.fileSize` if present, else the response's `Content-Length`. Over the ceiling → `too_large` with `largest_bytes`, no download.
  - **Download** to a temp file (`os.tmpdir()`), counting bytes and aborting past the ceiling.
  - **Strip:** images up to `IMAGE_MAX_BYTES` read into memory and stripped as today; ISOBMFF stripped in place through a file-backed `ByteSource`; anything else uploaded as is.
  - **Upload** from the file as a stream (supabase-js `upload` with a Node stream and `duplex: 'half'`, or TUS if streaming through storage-api's standard endpoint fails above some size: the builder verifies which works against storage-api v1.90 and Kong). Same path scheme and content-type logic as `mirrorExternalMediaToStorage`.
  - Delete the temp file in `finally`.
- Rewrite: build the new content (same part shape `applyBridgeAttachmentPolicy` produces: `type: 'file'`, `fileType`, `url`, `path`), call `update_message_content_silent` with the content read at the start. If it returns false, re-read and retry once, then requeue.
- Done when no Discord-CDN parts remain → `done`. Errors: backoff 1 min, 5 min, 30 min, 2 h, 12 h; after 5 attempts `failed` with `last_error`. Download 404/403 after a fresh refresh means Discord deleted the file → `failed` ("gone from Discord").

### Inline path changes (`applyBridgeAttachmentPolicy`)

- Size known up front and over `BRIDGE_MIRROR_INLINE_MAX_BYTES` (default 25 MB), or `Content-Length` over it: don't download; keep the Discord link and enqueue the message after insert.
- Inline mirror failure (today: logged, link kept): also enqueue.
- `MAX_BYTES` goes away; the ceiling is the only cap.
- The message id isn't known inside the part pipeline, so `prepareBotMessageContent` returns which parts were deferred, and the three callers (createBotMessage, editBotMessage, history-import batch) enqueue after their insert or update.

### Admin UI

Under the attachment mode setting in Instance admin → Config (`src/components/admin/InstanceConfig.vue`), when the mode is `mirror`: "Storing Discord files: N waiting, M stored, K too large (raise the upload ceiling: `harmony config --upload-ceiling 500M`), F failed". Strings in all 7 locales.

### Docs

`docs/self-host/discord-bridges.md` ("Attachments from Discord"): mirror covers existing messages, the ceiling is the cap, temp disk needed (about the largest file), storage needed. `docs/run/discord-bridge.md`: the 50 MB note.

## Testing

- Unit (vitest): expiry parsing; size gating; deferral above the inline threshold; worker state transitions (expired → refresh, offline, too large, gone, CAS conflict, success); temp file always removed; ISOBMFF strip via the file source matches the in-memory result.
- pgTAP: queue RLS and grants; claim skips locked and leased rows; backfill finds only bot messages with Discord-CDN parts and resumes from its cursor; status counts.
- Live: the Discord test server can't hold files over 10 MB (no boosts), so the local stack runs with a ~1 MB ceiling and a ~200 KB inline threshold: import in `link` mode, switch to `mirror`, watch the backlog drain (including expired links via the bridge), raise the ceiling and watch `too_large` drain. Memory of the gateway stays flat while mirroring a ~500 MB synthetic file served locally (allow-list override for the test only).

## Left to the builder

- Exact backoff numbers, lease length, poll interval.
- stream vs TUS upload, after testing against storage-api.
- Whether `bridge_mirror_status` lives in a new RPC or extends an existing admin config RPC.

## Calls made after review (builder check)

These were settled by the designer, not the user; flagged so they can be checked.

1. **Refresh has no acknowledgement** (the bridge's `onRefreshAttachments` is fire-and-forget, deduped 15 s, swallows errors). After sending it the worker waits 2 min, re-reads the message, and compares: links changed means refreshed; still expired counts one attempt on the normal backoff, and 5 attempts end in `failed` ("Discord would not refresh the link"). A 404/403 on a link that is not expired means `failed` ("gone from Discord").
2. **The bridge's refresh write could undo a mirror.** `PATCH /messages/:id/content-silent` re-reads the row and swaps without comparing to the bridge's snapshot. Guard in the gateway's `silentUpdateMessageContent`: a silent update may not replace a part that carries a storage `path` with one that doesn't (409 instead). Refresh writes only ever swap Discord links, so nothing legitimate is refused.
3. **Enqueue after insert, plus a startup backfill.** Callers enqueue deferred messages right after their insert (history import maps `inserted[].discord_message_id` back to the deferred parts; dropped, rejected and already-present messages are not enqueued). The gap of a crash between insert and enqueue is closed by running the backfill walk again on every gateway start while the mode is `mirror` (it only enqueues messages not already queued or done), as well as on each switch to mirror. Note imported messages are backdated, so the walk keys on `(created_at, id)` over the whole range, not "since last time".
4. **Only bridge bots.** Backfill and worker take messages whose `bot_id` is a bridge bot (`discord_bridges.bot_id`), since only those can answer `REFRESH_ATTACHMENTS`. The inline path is unchanged for other bots.
5. **One gateway process.** Self-hosted Harmony runs one bot-gateway container, so `sendToBot` reaches every bridge. The claim RPC stays lease-based so a second process can't double-mirror; multi-process routing is out of scope and noted in the docs.
6. **Mode tracking.** The worker stores `bridge_mirror_last_mode` next to the backfill cursor in `instance_config` and resets the cursor only on a transition into `mirror`. A restart while in mirror resumes the cursor (and per 3, re-walks once finished). Builder confirms service_role may write these keys past any instance_config guard trigger.
7. **Part shapes.** Backfilled `url` parts with a Discord-CDN link become `file` parts, as on the live path. APNG detection reads the first bytes of the temp file (only the chunk headers before the first IDAT). Stickers go through the same path as any image.
8. **Temp location.** `BRIDGE_MIRROR_TMPDIR` (default `os.tmpdir()`, which in the gateway container is the container's disk, not tmpfs). Docs say it needs free space about the size of the largest file.
