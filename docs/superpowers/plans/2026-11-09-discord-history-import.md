# Discord History Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let any bridged Harmony server import a paired Discord channel's past messages and threads into its Harmony channel, with original dates, quietly, resumably, and without duplicates.

**Architecture:** Harmony stores one import job per channel pair (`discord_bridge_imports`). The app and the bridge's `/bridge import-history` command start and cancel jobs. The bridge polls its jobs, reads Discord history newest-first, translates messages with the same code as live relay, and posts batches to new Bridge v2 endpoints. Those call `SECURITY DEFINER` functions that insert backdated rows under a transaction-local flag (`harmony.history_import`) that every side-effect trigger respects, and stamp `metadata.history_import = true` so the gateway never dispatches them. A unique index on `(channel_id, metadata->>'discord_message_id')` makes every insert idempotent.

**Tech Stack:** Postgres 15 / Supabase (plpgsql, pgTAP), bot-gateway (Express + supabase-js, vitest), Vue 3 + vue-i18n app (vitest), harmony-discord-bridge (discord.js 14, vitest).

**Spec:** `docs/superpowers/specs/2026-11-09-discord-history-import-design.md`. Read it before any task.

**Repos:**
- **H** = Harmony, this worktree: `C:\Users\Rudhra\Code\brapmony\.claude\worktrees\moonlit-forging-orbit`.
- **B** = bridge: `C:\Users\Rudhra\Code\harmony-discord-bridge`. Work on a new branch `feat/history-import` there.

Tasks H1–H7 come before B1–B5. B tasks rely on the H endpoint contracts below, not on H code.

## Global Constraints

- Commit messages: Conventional Commits, `type(scope): description` (both repos).
- Migrations follow `docs/contribute/migrations.md`:
  - a leading comment;
  - `BEGIN; SET LOCAL lock_timeout = '3s'; … COMMIT; NOTIFY pgrst, 'reload schema';`;
  - every new function revoked from `PUBLIC, anon` (and `authenticated` unless clients call it), with explicit grants;
  - `SECURITY DEFINER` functions set `search_path = public, pg_temp`.
- After each migration task, regenerate the inventories (`./scripts/generate-surface.sh`, `./scripts/generate-reachability.sh`, `./scripts/check-published-no-caller.sh --write`) and commit them with the migration.
- Run pgTAP with `./scripts/run-db-tests.sh` (needs Docker).
- **Import flag:** `harmony.history_import`, set with `set_config('harmony.history_import', 'true', true)`, read only through `public.is_history_import()`.
- **Row marker:** `metadata.history_import = true` on every imported message and import-created notice. `metadata.created_via = 'history_import'`.
- **Importable pair:** `direction IN ('both', 'to_harmony')`, and the Harmony channel is not encrypted.
- **Job states:** `queued | running | paused | done | failed | cancelled`. **Phases:** `messages | threads | replies`.
- **Harmony pin cap:** 50 per channel (existing `v_max_pins`); over the cap → skip and increment `pins_skipped`.
- **Batch limit:** at most 100 messages per `POST /imports/{id}/messages`.
- **The bridge never imports** `author.bot` messages, or message types other than `Default` and `Reply`.
- **Bridge version with import:** `2.6.0`. The app enables import UI only when `discord_bridges.bridge_version` ≥ `2.6.0`.
- **Bridge env:**
  - `BRIDGE_IMPORT_POLL_MS`, default `10000`;
  - `BRIDGE_IMPORT_PAGE_DELAY_MS`, default `1000`;
  - `BRIDGE_IMPORT_CONCURRENCY`: imports running at once per process, default `1`.

## Endpoint contracts (H5 produces, B1 consumes)

Base `{api_url}/bridge/v2`, header `Authorization: Bot <bridge bot token>`. Every route answers `404 {code:'import_not_found'}` for a job not of this bridge.

```ts
type ImportState = 'queued'|'running'|'paused'|'done'|'failed'|'cancelled'
type ImportPhase = 'messages'|'threads'|'replies'
interface ImportJob {
  id: string; harmony_channel_id: string; discord_channel_id: string
  state: ImportState; phase: ImportPhase; cancel_requested: boolean
  start_discord_message_id: string|null; start_at: string|null
  reached_discord_message_id: string|null; reached_at: string|null
  channel_created_at: string|null
  threads_total: number|null; threads_done: number; thread_cursor: Record<string, unknown>|null
  imported_count: number; skipped_count: number; pins_skipped: number
  error: string|null; requested_via: 'harmony'|'discord'
  created_at: string; updated_at: string; finished_at: string|null
}
interface ImportMessage {
  discord_message_id: string; created_at: string; edited_at?: string|null
  content: unknown[]                    // Harmony parts from the translator, pre-pipeline
  discord_user: Record<string, unknown> // buildDiscordUserMetadata(...).discord_user
  reply_to_discord_message_id?: string|null
  thread_id?: string|null               // Harmony thread uuid
  pinned?: boolean
}
```

| Route | Body | Answer |
|---|---|---|
| `GET /imports` | — | `{ imports: ImportJob[] }`: this bridge's jobs, all states |
| `POST /imports` | `{ discord_channel_ids?: string[] }` (omitted = all importable pairs) | `201 { imports: ImportJob[] }`; `400 {code:'pair_not_importable', discord_channel_id}` |
| `PATCH /imports/:id` | any of `state` (`running`, `paused`, `done`, `failed`, and `cancelled` only when `cancel_requested`), `phase`, `start_discord_message_id`, `start_at`, `reached_discord_message_id`, `reached_at`, `channel_created_at`, `threads_total`, `threads_done`, `thread_cursor`, `error` | `200 { import: ImportJob }`; `409 {code:'invalid_transition'}` |
| `POST /imports/:id/cancel` | — | `200 { imports: ImportJob[] }` (same rules as the app's cancel) |
| `POST /imports/:id/messages` | `{ messages: ImportMessage[] }` (1..100) | `200 { inserted: {discord_message_id, id}[], skipped: string[] }`; `409 {code:'import_not_running'}` |
| `POST /imports/:id/threads` | `{ discord_thread_id, name, created_at, starter_discord_message_id?: string\|null, discord_user?: object\|null, archived: boolean, locked: boolean }` | `200 { thread_id }` (idempotent per `discord_thread_id`) |
| `POST /imports/:id/replies/link` | — | `200 { linked: number }` |

## Review Focus

1. **A live Discord message is relayed for a channel while its import batch inserts the same Discord ID.** Exactly one row, and neither request fails. Tests: H1 (pgTAP + gateway conflict test) and H4 (`skipped` contains the ID).
2. **The bridge crashes mid-job and restarts.** The job resumes from `reached_discord_message_id`; a re-sent batch comes back all `skipped`; `imported_count` counts only real inserts. Tests: H4 (counts), B2 (resume test).
3. **A pair is removed, or the Harmony channel becomes encrypted, while its job runs.** The next batch answers `409 import_not_running` (the job flipped to `failed` with error `pair_removed` / `channel_encrypted`), and the bridge stops that job without retry loops. Tests: H4, B4.
4. **Discord history becomes unreadable mid-job** (permission revoked, channel deleted: DiscordAPIError 50001/50013/10003). The job is `failed` with a readable `error`, and the bridge moves on to the next queued job. Test: B2.
5. **An author has left the Discord server** (no guild member) or the message is attachment- or sticker-only. It is still imported with the `User`-only `discord_user`, matching live relay. Test: B2.

---

## Part H — Harmony

### Task H1: One row per Discord message per channel

**Files:**
- Create: `db_schema/migrations/20261109100001_discord_message_dedupe_cleanup.sql`: in a transaction, for each `(channel_id, metadata->>'discord_message_id')` with more than one row, soft-delete all but the oldest (`created_at`, then `id`). Soft-delete means `is_deleted = true` under `harmony.silent_content_update` so no edit badge appears.
- Create: `db_schema/migrations/20261109100002_discord_message_unique_index.sql`: no transaction, following `20261031800002_search_quality_backfill.sql`. It runs `CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS messages_channel_discord_message_id_unique ON public.messages (channel_id, (metadata->>'discord_message_id')) WHERE channel_id IS NOT NULL AND metadata ? 'discord_message_id' AND is_deleted IS NOT TRUE;`
- Modify: `bot-gateway/src/api/BotRestAPI.ts` `sendMessage` (~L567–650).
- Test: `db_schema/tests/246_discord_message_dedupe.sql`, `bot-gateway/src/api/__tests__/BotRestAPI.bridge.test.ts`.

**Interfaces:**
- Produces: index `messages_channel_discord_message_id_unique`. Later tasks insert with `ON CONFLICT (channel_id, (metadata->>'discord_message_id')) WHERE … DO NOTHING`, using the same predicate.

- [ ] **Step 1: Write the pgTAP test** `246_discord_message_dedupe.sql`, `plan(3)`:
  - a second insert of the same `discord_message_id` in the same channel raises `unique_violation` (`throws_ok … '23505'`);
  - the same ID in another channel succeeds;
  - a soft-deleted duplicate doesn't block a new row.
- [ ] **Step 2: Run** `./scripts/run-db-tests.sh`. Expected: 246 fails (no index).
- [ ] **Step 3: Write both migrations.**
- [ ] **Step 4: Run** `./scripts/run-db-tests.sh`. Expected: all pass.
- [ ] **Step 5: Write the gateway test** `sendMessage returns the existing message when the Discord message id is already bridged`. Mock the insert to return error `{ code: '23505', message: '…messages_channel_discord_message_id_unique…' }`. Expect `200` and the body of the existing row, looked up by channel and `discord_message_id`.
- [ ] **Step 6: Implement** in `sendMessage`: on that error code with that index name in the message, select the existing row with the same select list as the insert, `logBotAction` nothing, and answer `200 this.formatMessage(existing)`.
- [ ] **Step 7: Run** `cd bot-gateway && npx vitest run src/api/__tests__/BotRestAPI.bridge.test.ts`. Expected: PASS.
- [ ] **Step 8: Regenerate inventories, then commit** `feat(bridge): one message per Discord message id per channel`.

### Task H2: Import jobs table and RPCs

**Files:**
- Create: `db_schema/migrations/20261109100003_discord_bridge_imports.sql`.
- Test: `db_schema/tests/247_discord_bridge_imports.sql`.

**Interfaces:**
- **Table** `public.discord_bridge_imports`, with the columns of `ImportJob` plus:
  - `bridge_id uuid NOT NULL REFERENCES discord_bridges ON DELETE CASCADE`;
  - `harmony_channel_id uuid NOT NULL REFERENCES channels ON DELETE CASCADE`;
  - `requested_by uuid REFERENCES profiles ON DELETE SET NULL`;
  - `UNIQUE (bridge_id, harmony_channel_id)`: one row per pair, reused across runs;
  - CHECKs on `state`, `phase` and `requested_via`.
  - RLS: `SELECT` to `authenticated` where `can_manage_discord_bridge(<bridge's server_id>)`; no client writes; `ALL` to `service_role`.
- **App RPCs** (grant `authenticated`; each checks `can_manage_discord_bridge`):
  - `discord_bridge_import_start(p_bridge_id uuid, p_harmony_channel_ids uuid[] DEFAULT NULL) RETURNS SETOF discord_bridge_imports`
  - `discord_bridge_import_cancel(p_bridge_id uuid, p_import_id uuid DEFAULT NULL) RETURNS SETOF discord_bridge_imports`
- **Service-role functions** (H5 calls them):
  - `discord_bridge_import_start_internal(p_bridge_id uuid, p_discord_channel_ids text[], p_via text, p_requested_by uuid) RETURNS SETOF discord_bridge_imports`. The app RPC delegates to it.
  - `discord_bridge_import_update(p_bridge_id uuid, p_import_id uuid, p_patch jsonb) RETURNS discord_bridge_imports`
  - `discord_bridge_import_cancel_internal(p_bridge_id uuid, p_import_id uuid) RETURNS SETOF discord_bridge_imports`. The app's cancel RPC delegates to it after its manager check.
- **Start rules:**
  - `NULL` ids means every importable pair; a non-importable pair named explicitly raises `pair_not_importable`.
  - **Upsert per pair:**
    - no row → insert `queued`;
    - `failed`, `cancelled` or `paused` → `queued`, keeping the cursors, with `error` and `cancel_requested` cleared;
    - `done` → `queued`, with every cursor and counter reset;
    - `queued` or `running` → unchanged;
    - pair's `discord_channel_id` changed → reset as for `done`.
- **Cancel rules:** `queued`/`paused` → `cancelled` with `finished_at = now()`; `running` → `cancel_requested = true`; `NULL` id = all of the bridge's jobs.
- **Update rules:**
  - Accept only the PATCH keys in the contract.
  - **Transitions:**
    - `queued` → `running`;
    - `running` → `paused`, `done` or `failed`, or `cancelled` only if `cancel_requested`;
    - `paused` → `running`;
    - anything else raises `invalid_transition`.
  - `done`, `failed` and `cancelled` set `finished_at`; every update sets `updated_at`.

- [ ] **Step 1: Write the pgTAP test** `247_discord_bridge_imports.sql` (fixture pattern as in `137_bridge_thread_author.sql`). Assertions:
  - alice (manager) start with `NULL` creates rows only for `both`/`to_harmony` pairs, skipping a `to_discord` pair and an encrypted channel;
  - bob (non-manager) start raises;
  - an explicit `to_discord` pair raises `pair_not_importable`;
  - starting a `failed` row keeps `reached_discord_message_id`;
  - starting a `done` row clears it;
  - cancelling a `queued` job → `cancelled`; cancelling a `running` job → `cancel_requested` true, state unchanged;
  - update `queued`→`done` raises `invalid_transition`;
  - update with an unknown key raises;
  - bob cannot `SELECT` the rows; alice can.
- [ ] **Step 2: Run** `./scripts/run-db-tests.sh`. Expected: 247 fails.
- [ ] **Step 3: Write the migration.**
- [ ] **Step 4: Run** `./scripts/run-db-tests.sh`. Expected: all pass.
- [ ] **Step 5: Regenerate inventories, then commit** `feat(bridge): history import jobs`.

### Task H3: The quiet-import flag

**Files:**
- Create: `db_schema/migrations/20261109100004_history_import_quiet.sql`.
- Modify: `bot-gateway/src/api/BotRestAPI.ts` (`botSuppliedMetadata`, near L96); `bot-gateway/src/gateway/EventDispatcher.ts` (`handleMessageCreate`, ~L820).
- Test: `db_schema/tests/248_history_import_quiet.sql`, `bot-gateway/src/gateway/__tests__/` (existing EventDispatcher test file, or new `EventDispatcher.historyImport.test.ts`), `bot-gateway/src/api/__tests__/BotRestAPI.bridge.test.ts`.

**Interfaces:**
- Produces `public.is_history_import() RETURNS boolean`: STABLE, `current_setting('harmony.history_import', true) = 'true'`. Revoke from `PUBLIC, anon, authenticated`; grant `service_role`. Triggers call it as definer.
- `handle_messages_updated_at` on INSERT under the flag keeps a supplied `NEW.updated_at` when it is `>= NEW.created_at`, else `created_at`. Outside the flag, behaviour is unchanged.

**Trigger audit.** For every trigger on `public.messages` and `public.threads`, and the thread-notice path, read the function and decide skip or keep by the spec's rules ("Quiet import", including "last activity"). Skip by adding `IF public.is_history_import() THEN RETURN COALESCE(NEW, OLD); END IF;` at the top (for AFTER triggers, `RETURN NULL` where that's the existing convention). At minimum, classify:

- `trigger_send_push_notification`, `trigger_queue_push_notification` (skip)
- `trigger_new_message_unread`, `trigger_new_dm_unread` (skip)
- `trigger_role_mention_notifications`, `trigger_thread_reply_notification`, `z_messages_channel_message_notifications` (skip)
- `trg_newcomer_first_message`, `trg_redact_encrypted_notification_preview` (skip)
- `trg_handle_message_federation`, `trigger_federate_channel_message`, `trigger_federate_dm`, `z_messages_pin_federation` (skip)
- `trigger_broadcast_message_insert` (skip)
- `trg_moderate_message`, `automod_message_guard` (skip)
- `trigger_enforce_channel_slowmode` (skip)
- `trg_process_local_link_previews` (skip)
- `smart_route_channel_message`, `trigger_thread_message_insert` (decide: skip anything that bumps last-activity or thread reply counts' "latest" fields; keep pure counts)
- `trigger_index_message`, `trg_profile_message_counter_ins` (keep)
- every `a_messages_*` guard, `trg_enforce_*`, `check_message_sticker_parts`, `trg_role_mention_permission` (keep: validation)

Write the classification as a comment table at the top of the migration.

- [ ] **Step 1: Write the pgTAP test** `248_history_import_quiet.sql`. A control insert of a bot message mentioning bob in `#general` produces bob's notification, unread and push queue rows, a federation queue row and a channel last-activity change. The builder names the exact tables found in the audit. Then the same insert inside `set_config('harmony.history_import','true',true)` with `created_at = now() - interval '2 years'` and a supplied `updated_at` 1 day later produces none of those, keeps the supplied `updated_at`, and is findable through `search_messages`. One `is()` per side effect.
- [ ] **Step 2: Run** `./scripts/run-db-tests.sh`. Expected: 248 fails.
- [ ] **Step 3: Write the migration** with the audit table and the guards.
- [ ] **Step 4: Run** `./scripts/run-db-tests.sh`. Expected: all pass, including every existing file.
- [ ] **Step 5: Write gateway tests:**
  - `botSuppliedMetadata drops history_import`: a bridge bot's `sendMessage` with `metadata.history_import: true` stores no such key;
  - `handleMessageCreate skips history_import rows`: no `dispatch` call, and no `webhooks.wants` call.
- [ ] **Step 6: Implement:**
  - add `history_import` to the keys `botSuppliedMetadata` strips;
  - in `handleMessageCreate`, return early when `message.metadata?.history_import === true`, before `resolveServerId`.
- [ ] **Step 7: Run** `cd bot-gateway && npx vitest run`. Expected: PASS.
- [ ] **Step 8: Regenerate inventories, then commit** `feat(bridge): quiet flag for imported history`.

### Task H4: Import insert functions

**Files:**
- Create: `db_schema/migrations/20261109100005_history_import_functions.sql`.
- Test: `db_schema/tests/249_history_import_functions.sql`.

**Interfaces** (all `SECURITY DEFINER`, granted to `service_role` only):

- `bridge_import_guard(p_bot_id uuid, p_import_id uuid) RETURNS discord_bridge_imports`
  - Raises `import_not_found` unless the bot is the job's bridge bot.
  - When the pair is gone or no longer importable, or the channel is encrypted: sets the job `failed`, with error `pair_removed`, `pair_not_importable` or `channel_encrypted`, then raises `import_not_running`.
  - Raises `import_not_running` when `state <> 'running'`.
- `bridge_import_messages(p_bot_id uuid, p_import_id uuid, p_messages jsonb) RETURNS jsonb`
  - `p_messages` holds pipeline-processed `ImportMessage`s (`content` final).
  - Calls the guard, then sets the flag.
  - Inserts with:
    - `created_at`, and `updated_at = COALESCE(edited_at, created_at)`;
    - `bot_id = p_bot_id`, `channel_id = job.harmony_channel_id`;
    - `thread_id`, which must be a thread of that channel;
    - `reply_to` = the Harmony id of `reply_to_discord_message_id` in this channel when it exists;
    - `metadata = {discord_user, discord_message_id, discord_reply_to: reply_to_discord_message_id, history_import: true, bot: true, created_via: 'history_import'}`.
  - Uses `ON CONFLICT … DO NOTHING` on the H1 index.
  - For `pinned` rows that were inserted: sets `is_pinned`/`pinned_at = created_at` while the channel has fewer than 50 pins; otherwise increments `pins_skipped`.
  - Adds the counts of inserted and skipped rows to `imported_count` and `skipped_count`.
  - Returns `{inserted:[{discord_message_id,id}], skipped:[…]}`.
- `bridge_import_thread(p_bot_id uuid, p_import_id uuid, p_thread jsonb) RETURNS uuid`
  - Guard, then the flag.
  - Returns the existing thread for `discord_thread_id` if paired.
  - Otherwise creates it the way `bot_create_thread` does: on the starter's Harmony message when `starter_discord_message_id` resolves in this channel, else standalone. It is backdated to `created_at` and keeps `archived`/`locked`.
  - The "started a thread" notice it creates carries `created_at` and the row marker.
- `bridge_import_link_replies(p_bot_id uuid, p_import_id uuid) RETURNS integer`
  - Guard (the job may be `running` in phase `replies`), then the flag.
  - Sets `reply_to` for this channel's rows that have `metadata.history_import`, a `discord_reply_to`, and a null `reply_to` whose target now exists.
  - It must not move `updated_at`: use `harmony.silent_content_update` as well.
  - Returns the count.

- [ ] **Step 1: Write the pgTAP test** `249_history_import_functions.sql`. Assertions:
  - a wrong bot → `import_not_found`;
  - a non-running job → `import_not_running`;
  - a batch of 3 → 3 inserted, with original `created_at` and the marker; re-sending it → 0 inserted, 3 skipped, and `imported_count` still 3;
  - `edited_at` → `updated_at`;
  - a reply whose target is in the same batch links;
  - a reply whose target comes in a later batch links after `bridge_import_link_replies`, which returns 1;
  - with 50 existing pins, a `pinned` message is inserted unpinned and `pins_skipped` = 1;
  - `bridge_import_thread` twice with the same `discord_thread_id` → the same uuid, with `created_at` backdated;
  - after unpairing the channel, the next batch → the job is `failed` with `pair_removed`;
  - an encrypted channel → `channel_encrypted`;
  - none of these calls creates the H3 side-effect rows (reuse one H3 check).
- [ ] **Step 2: Run** `./scripts/run-db-tests.sh`. Expected: 249 fails.
- [ ] **Step 3: Write the migration.**
- [ ] **Step 4: Run** `./scripts/run-db-tests.sh` and `./scripts/check-plpgsql.sh`. Expected: all pass.
- [ ] **Step 5: Regenerate inventories, then commit** `feat(bridge): history import insert functions`.

### Task H5: Bridge v2 import endpoints

**Files:**
- Create: `bot-gateway/src/api/messageContent.ts`.
- Create: `bot-gateway/src/bridge/historyImport.ts`, with an Express `Router` factory.
- Modify: `bot-gateway/src/api/BotRestAPI.ts` (use `messageContent.ts`); `bot-gateway/src/api/BridgeV2API.ts` (mount the routes after the `bridgeBot` routes, ~L165, with the same `...bridgeBot` middleware).
- Test: `bot-gateway/src/api/__tests__/BridgeV2API.imports.test.ts`.

**Interfaces:**
- Produces `prepareBotMessageContent(input: { content: unknown; embeds?: unknown; metadata?: unknown; channelId: string; botId: string }): Promise<unknown[]>` in `messageContent.ts`.
  - It runs exactly what `sendMessage` runs today: `formatContent`, `typedMassMentions` when the message isn't a bridge author, `resolveMentionParts`, then `applyBridgeAttachmentPolicy`.
  - Move the private helpers it needs. `BotRestAPI.sendMessage` then calls it, with behaviour unchanged.
- Produces `createHistoryImportRouter(): Router` in `historyImport.ts`, serving the contract routes above.
  - It maps RPC errors to the contract codes: `import_not_found`→404, `import_not_running`/`invalid_transition`→409, `pair_not_importable`→400.
  - `POST /imports` calls `discord_bridge_import_start_internal` with `p_via 'discord'` and `p_requested_by NULL`.
  - `POST /imports/:id/messages` validates 1..100 items and the field types (400 `invalid_batch`), runs `prepareBotMessageContent` per message, and drops messages whose content comes out empty (counted as skipped). It then calls `bridge_import_messages` once.
  - The bridge's existing REST rate limit applies.

- [ ] **Step 1: Write the tests** in `BridgeV2API.imports.test.ts` (mock supabase as `BridgeV2API.test.ts` does):
  - a non-bridge bot gets `403`;
  - `GET /imports` returns `{imports}`;
  - `POST /imports` without ids calls the start RPC with `null`, and answers `201`;
  - `PATCH` with `state: 'cancelled'` gets the RPC's `invalid_transition` mapped to `409`;
  - `POST messages` with 101 items → `400 invalid_batch`;
  - `POST messages` runs each item through `prepareBotMessageContent`: a Discord CDN URL part is mirrored when the policy is `mirror`, and a `@everyone` text part is not turned into a mass mention;
  - `POST messages` passes the RPC's `{inserted, skipped}` through;
  - `POST threads` returns `{thread_id}`;
  - `POST cancel` calls `discord_bridge_import_cancel_internal`;
  - `POST replies/link` returns `{linked}`.
- [ ] **Step 2: Run** `cd bot-gateway && npx vitest run src/api/__tests__/BridgeV2API.imports.test.ts`. Expected: FAIL.
- [ ] **Step 3: Extract `prepareBotMessageContent`.** Run `npx vitest run src/api/__tests__/BotRestAPI*.test.ts`. Expected: PASS, no changes to those tests.
- [ ] **Step 4: Implement `historyImport.ts`** and mount it.
- [ ] **Step 5: Run** `cd bot-gateway && npx vitest run && npx tsc --noEmit`, then `./scripts/check-rpc-coverage.sh`. Expected: PASS.
- [ ] **Step 6: Commit** `feat(bridge): Bridge v2 history import endpoints`.

### Task H6: Import UI in bridge settings

**Files:**
- Create: `src/components/settings/discord-bridge/importProgress.ts`.
- Create: `src/components/settings/discord-bridge/BridgeImportControls.vue`: the per-pair control (button, progress bar, state line, cancel/retry).
- Modify:
  - `src/components/settings/discord-bridge/bridgeApi.ts`;
  - `BridgeChannelPairs.vue`: renders `BridgeImportControls` per pair, plus **Import all** and **Cancel all** above the list;
  - `bridgeErrors.ts`: maps `pair_not_importable`;
  - every `src/locales/*.json`, under `discordBridge.import.*`. Write the English, then copy it into the other locales if that's the repo's convention; check how recent keys were added to `de.json`.
- Test: `src/components/settings/discord-bridge/__tests__/BridgeImport.test.ts` (use `bridgeTestKit.ts`).

**Interfaces:**
- `bridgeApi.ts`:
  - `fetchImports(bridgeId: string): Promise<ImportJob[]>`: `.from('discord_bridge_imports')`;
  - `startImports(bridgeId: string, harmonyChannelIds: string[] | null): Promise<ImportJob[]>`;
  - `cancelImports(bridgeId: string, importId: string | null): Promise<ImportJob[]>`.
  - Export `ImportJob` with the contract's shape.
- `importProgress.ts`:
  - `importPercent(job: ImportJob): number | null` returns `round(100 * (start_at − reached_at) / (start_at − channel_created_at))`, clamped 0..100. It's `null` when any of the three is missing, and `100` when `state === 'done'`.
  - `bridgeSupportsImport(version: string | null): boolean` is semver ≥ `2.6.0`.
  - `isImportablePair(pair, channel): boolean` encodes the "importable pair" rule.
- Refresh `fetchImports` on the page's existing poll.

**Copy (English)** for the UI states:
- Button: **Import history**. Running: **Importing… *n*%** with "Back to *Month YYYY*" and "*count* messages". Thread phase: "Threads *n* of *m*".
- Queued: **Waiting**. Done: **Imported *count* messages**. Failed: **Import stopped: *reason*** with **Try again**.
- Cancelled: **Cancelled** with **Resume**. Paused: **Paused: waiting for the bridge**.
- Ineligible: "Only pairs that bring Discord messages to Harmony can import history" or "Encrypted channels can't import history".
- Old bridge: "Update the bridge to 2.6.0 or later to import history".
- Mirror note, shown once above the list when the instance's attachment handling is `mirror`: "Imported files are copied into this instance's storage."

- [ ] **Step 1: Write the tests** in `BridgeImport.test.ts`:
  - `importPercent` → 50 at the midpoint, null without `channel_created_at`, 100 when done;
  - `bridgeSupportsImport('2.5.9')` false, `('2.6.0')` true, `(null)` false;
  - the button calls `startImports(bridgeId, [channelId])`;
  - **Import all** calls `startImports(bridgeId, null)`;
  - a running job shows the percent and **Cancel**, which calls `cancelImports(bridgeId, job.id)`;
  - a `to_discord` pair shows the ineligible text and no button;
  - with `bridge_version` `2.5.0`, the update text shows and there are no buttons.
- [ ] **Step 2: Run** `npx vitest run src/components/settings/discord-bridge/__tests__/BridgeImport.test.ts`. Expected: FAIL.
- [ ] **Step 3: Implement** the files above.
- [ ] **Step 4: Run** `npx vitest run src/components/settings/discord-bridge && npm run lint && npx vue-tsc --noEmit`. If `package.json` names the type check differently, use that. Expected: PASS.
- [ ] **Step 5: Commit** `feat(bridge): import Discord history from bridge settings`.

### Task H7: End-to-end, docs, changelog

**Files:**
- Create: `e2e/specs/bridge-history-import.spec.ts`. Follow `e2e/specs/bridge-threads.spec.ts` for driving the bridge's API with a bridge bot token.
- Modify:
  - `docs/run/discord-bridge.md`: a new "Import history" section, a `/bridge import-history` row in the commands table, and the thread note ~L191;
  - `docs/build/bridges.md`: a new "History import" section with the endpoint contract;
  - `docs/self-host/discord-bridges.md`: the `BRIDGE_IMPORT_*` env vars, a mirror-mode disk note and a `2.6.0` row in the versions table;
  - `CHANGELOG.md` under `[Unreleased]`.

- [ ] **Step 1: Write the e2e spec:**
  - a manager opens Discord Bridge settings and clicks **Import history** on a pair;
  - the test, acting as the bridge, calls `PATCH` (running), `POST messages` with 2 messages dated 2023 (one mentioning a second user), `PATCH` (done);
  - the channel shows both messages in date order above existing ones, under the Discord author names;
  - the second user has no notification and no unread badge for the channel;
  - the pair shows **Imported 2 messages**.
- [ ] **Step 2: Run** `npx playwright test e2e/specs/bridge-history-import.spec.ts`. Expected: PASS. If the e2e stack can't start here, say so in the task report; don't skip silently.
- [ ] **Step 3: Write the docs and changelog** in the existing style of each page.
- [ ] **Step 4: Commit** `docs(bridge): history import`.

---

## Part B — Bridge (`harmony-discord-bridge`, branch `feat/history-import`)

### Task B1: API client for imports

**Files:**
- Modify: `src/v2/BridgeApi.ts`.
- Test: `test/v2Bridge.test.ts`, or a new `test/bridgeApiImports.test.ts`.

**Interfaces:**
- Produces, on `BridgeApi`, with the contract types exported from `src/v2/BridgeApi.ts`:
  - `listImports(): Promise<ImportJob[]>`
  - `startImports(discordChannelIds?: string[]): Promise<ImportJob[]>`
  - `updateImport(id: string, patch: Partial<ImportJob>): Promise<ImportJob>`
  - `postImportMessages(id: string, messages: ImportMessage[]): Promise<{ inserted: { discord_message_id: string; id: string }[]; skipped: string[] }>`
  - `importThread(id: string, body: ImportThreadBody): Promise<string>`
  - `linkImportReplies(id: string): Promise<number>`
  - `cancelImport(id: string): Promise<ImportJob[]>`
- A `409` throws `BridgeApiError` with `status 409`, and the body's `code` is exposed as `error.code`. Add `readonly code?: string` to `BridgeApiError` and fill it in `failure()`.

- [ ] **Step 1: Write the tests** with a fake `fetchImpl`: each method hits the contract path and method; `postImportMessages` on 409 `{code:'import_not_running'}` rejects with `code === 'import_not_running'`.
- [ ] **Step 2: Run** `npx vitest run test/bridgeApiImports.test.ts`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `npm run typecheck && npx vitest run`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(import): Bridge v2 import client`.

### Task B2: Message importer (messages phase)

**Files:**
- Create: `src/runtime/historyImport.ts`.
- Modify: `src/runtime/BridgeRuntime.ts`: extract the translation used by `onDiscordMessage` (~L1892–1960).
- Test: `test/historyImport.test.ts` (use `test/fakeDiscord.ts`).

**Interfaces:**
- Produces, in `BridgeRuntime`: `translateForImport(msg: DiscordMessage): Promise<Omit<ImportMessage, 'thread_id' | 'pinned'> | null>`.
  - It uses the same `translator.discordToHarmonyParts`, `discordToHarmonyContext(await this.emojiServerFor(...))`, guild-member lookup and `buildDiscordUserMetadata` as live relay.
  - `reply_to_discord_message_id = msg.reference?.messageId ?? null`.
  - `edited_at = msg.editedAt?.toISOString() ?? null`.
  - It returns `null` for `author.bot`, for message types other than `Default`/`Reply`, and for empty parts.
  - `onDiscordMessage` keeps its own reply resolution and is otherwise unchanged.
- Produces `class HistoryImporter`. Constructor deps: `{ api: Pick<BridgeApi, 'updateImport'|'postImportMessages'|'importThread'|'linkImportReplies'|'listImports'>; fetchPage(channelId: string, before: string | undefined): Promise<DiscordMessage[]>; fetchPins(channelId: string): Promise<Set<string>>; translate(msg): Promise<…|null>; pageDelayMs: number; sleep?: (ms: number) => Promise<void>; log }`.
- Produces `run(job: ImportJob, signal: AbortSignal): Promise<'done'|'cancelled'|'failed'|'paused'>`.
- **Messages phase algorithm:**
  1. `PATCH running`.
  2. On first run, freeze `start_discord_message_id`/`start_at` to the newest message and set `channel_created_at` from the channel ID's snowflake.
  3. Fetch pins once.
  4. Page with `before = reached_discord_message_id ?? undefined`, 100 per page, newest-first.
  5. Translate each message and drop the `null`s. Post the batch with `pinned` set from the pin set, then `PATCH reached_*` to the oldest message on the page.
  6. Sleep `pageDelayMs`, and re-read `cancel_requested` every page via `listImports`.
  7. An empty page ends the phase: `PATCH phase 'threads'`.
  8. **Errors:**
     - a `409` from the API → return `'failed'`, with no PATCH (the server already failed it);
     - DiscordAPIError 50001, 50013 or 10003 → `PATCH failed, error: 'discord_access_lost'`;
     - `signal` aborted → `PATCH paused`;
     - `cancel_requested` → `PATCH cancelled`.

- [ ] **Step 1: Write the tests:**
  - **Paging:** 250 fake messages → 3 posts newest-first, `reached_*` patched after each, `start_*` frozen once.
  - **Resume:** a job with `reached_discord_message_id` starts paging `before` it.
  - **Skips:** bot and system messages aren't posted.
  - **Departed author:** a message whose author has no guild member is posted with the `User`-only `discord_user`.
  - **Pins:** a pinned ID is posted with `pinned: true`.
  - **Cancel:** with `cancel_requested` after page 1, it returns `'cancelled'` and patches `cancelled`.
  - **Abort** → `'paused'`.
  - **Server stop:** a 409 on post → `'failed'`, no further fetches.
  - **Discord access lost:** a 50001 on fetch → `PATCH failed discord_access_lost`.
  - `translateForImport` returns the same `content` and `discord_user` as live relay produces for the same fake message: assert against `onDiscordMessage`'s `sendMessage` call.
- [ ] **Step 2: Run** `npx vitest run test/historyImport.test.ts`. Expected: FAIL.
- [ ] **Step 3: Extract `translateForImport`.** Run `npx vitest run test/runtime.test.ts test/fidelity.test.ts`. Expected: PASS, unchanged.
- [ ] **Step 4: Implement the messages phase.**
- [ ] **Step 5: Run** `npm run typecheck && npx vitest run`. Expected: PASS.
- [ ] **Step 6: Commit** `feat(import): import channel history from Discord`.

### Task B3: Threads and replies phases

**Files:**
- Modify: `src/runtime/historyImport.ts`.
- Test: `test/historyImport.test.ts`.

**Interfaces:**
- **New deps:**
  - `listThreads(channelId: string, cursor: Record<string, unknown> | null): Promise<{ threads: DiscordThreadLike[]; next: Record<string, unknown> | null }>`. It covers active threads (guild `fetchActive`, filtered to the channel), then archived public, then archived private, with private skipped when the bot lacks Manage Threads. The cursor records which list and the `before` timestamp.
  - `canReadPrivateThreads(channelId: string): boolean`.
- **Threads phase:**
  - Set `threads_total` once the first full listing is known. Otherwise leave it null and show "Threads *n*".
  - For each thread: `importThread` (starter = `thread.id` when the thread started from a message, i.e. Discord's thread id equals its starter id), then the same paging loop as the messages phase, with `thread_id` set and no `reached_*` (each thread restarts from its newest message; dedupe makes that safe).
  - After each thread, `PATCH threads_done, thread_cursor`.
  - When private threads were skipped, report the existing problem mechanism's warning `private_threads_skipped`. Add the problem kind to `src/problems.ts`.
  - Then `PATCH phase 'replies'`.
- **Replies phase:** `linkImportReplies(job.id)`, then `PATCH done`.

- [ ] **Step 1: Write the tests:**
  - 2 threads (one with a starter, one standalone) → `importThread` called with the right `starter_discord_message_id`, and their messages posted with `thread_id`;
  - resume with `thread_cursor` skips completed threads;
  - without Manage Threads, private archived threads aren't listed and the problem is raised;
  - the replies phase calls `linkImportReplies` once, then patches `done`.
- [ ] **Step 2: Run** `npx vitest run test/historyImport.test.ts`. Expected: FAIL on the new tests.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `npm run typecheck && npx vitest run`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(import): threads and reply links`.

### Task B4: Scheduling in every mode

**Files:**
- Create: `src/runtime/importScheduler.ts`.
- Modify:
  - `src/v2/V2Bridge.ts`: start and stop the scheduler with the bridge; feed `HistoryImporter` real deps from the Discord client;
  - `src/v2/InstanceHost.ts` and `src/v2/HostRunner.ts`: share one `ImportSlots` per process;
  - `src/env.ts`: the three `BRIDGE_IMPORT_*` vars;
  - `src/v2/selfCheck.ts` and `src/problems.ts`: a `message_content_needed_for_import` problem when a job is queued and the intent is off.
- Test: `test/importScheduler.test.ts`.

**Interfaces:**
- `class ImportSlots { constructor(max: number); acquire(): Promise<() => void> }`, shared per process.
- `class ImportScheduler { constructor(deps: { api; importer: HistoryImporter; slots: ImportSlots; pollMs: number; intentsOk(): boolean }); start(): void; stop(): Promise<void>; kick(): void }`.
- **Each poll** (`listImports`):
  - when nothing runs for this bridge, take the job to run in order `running` (left by a crash), `paused`, then `queued` by `created_at`;
  - acquire a slot, then `importer.run`, and release the slot when it returns;
  - one job at a time per bridge.
- `stop()` aborts the running job, which patches `paused`, and waits for it.
- `kick()` polls now (used by B5).
- When `intentsOk()` is false, don't start jobs; raise the problem.

- [ ] **Step 1: Write the tests:**
  - priority order `running` > `paused` > `queued`;
  - never two jobs at once for one bridge;
  - two bridges with `ImportSlots(1)` → the second waits until the first finishes;
  - `stop()` → the importer's signal is aborted;
  - intent off → no `run`, problem raised;
  - a `'failed'` result moves on to the next queued job at the next poll.
- [ ] **Step 2: Run** `npx vitest run test/importScheduler.test.ts`. Expected: FAIL.
- [ ] **Step 3: Implement, and wire it into the three modes.**
- [ ] **Step 4: Run** `npm run typecheck && npx vitest run`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(import): schedule imports in every bridge mode`.

### Task B5: `/bridge import-history` command, version, docs

**Files:**
- Modify:
  - `src/runtime/commands.ts`: a new subcommand under `bridge` (~L52–96) and its handler;
  - `package.json`: version `2.6.0`;
  - `README.md`: the import section and env vars.
- Test: `test/commands.test.ts`.

**Interfaces:**
- Subcommand `import-history`, with options:
  - `action` (choices `start` default, `status`, `cancel`);
  - `scope` (choices `channel` default, `all`).
- **Gate:** the existing `/bridge` Administrator check.
- **Behaviour by action:**
  - `start`: `startImports(scope === 'all' ? undefined : [pairId(interaction.channelId)])`, then `scheduler.kick()`. The reply is ephemeral: "Importing history for *n* channel(s). Progress shows in Harmony under Server settings → Discord Bridge, or run `/bridge import-history action:status`."
  - `status`: an ephemeral list of each job in scope, as `#channel: state, percent, messages`, with the same percent formula as H6.
  - `cancel`: `cancelImport(id)` for each active job in scope. Reply "Cancelling…".
- **Errors:**
  - in an unpaired channel with `scope:channel`, reply "This channel isn't bridged";
  - on `pair_not_importable`, reply "This channel's pair doesn't bring Discord messages to Harmony".
- [ ] **Step 1: Write the tests** in `commands.test.ts`:
  - a non-admin is refused;
  - `start` in a paired channel calls `startImports([discordChannelId])` and `kick`;
  - `scope:all` calls `startImports(undefined)`;
  - `status` formats a running job with its percent;
  - `cancel` calls the cancel route;
  - an unpaired channel gets the not-bridged reply.
- [ ] **Step 2: Run** `npx vitest run test/commands.test.ts`. Expected: FAIL.
- [ ] **Step 3: Implement**, bump the version and update the README.
- [ ] **Step 4: Run** `npm run typecheck && npx vitest run && npm run build`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(import): /bridge import-history command (2.6.0)`.
