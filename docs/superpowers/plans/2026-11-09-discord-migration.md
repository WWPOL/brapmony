# Migrate from Discord Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Per-server "Migrate from Discord": members sign in with Discord through a per-server link, are verified as members of the linked Discord server, get or link a Harmony account, join the cloned Harmony server with their mapped roles, and stay in sync (roles, membership, opt-in profile fields) until the owner finishes the migration.

**Architecture:** Postgres owns state and every rule. A new migration adds tables plus RPCs: admin config, a user `claim`, and service-only sync RPCs that do the role diff in SQL. The bot gateway (`bot-gateway/`, Express, service role) does only what the database can't: Discord OAuth, Discord REST calls with the bot token, GoTrue admin user creation, and the scheduled sync loop. Both the new-account path and the existing-account path end in the same RPC, `discord_migration_claim(ticket)`, called by the signed-in user, so joining, roles and moderation checks live in one place. The Vue frontend adds a server settings panel and a public `/migrate/:token` page.

**Tech Stack:** PostgreSQL + Supabase (PostgREST, GoTrue, Vault), pgTAP (`db_schema/tests`, `scripts/run-db-tests.sh`), Node/Express + vitest (`bot-gateway`), Vue 3 + Pinia + vue-i18n + vitest (`src/`), Playwright (`e2e/specs`).

**Spec:** `docs/superpowers/specs/2026-11-09-discord-migration-design.md`

## Global Constraints

- Migration file: `db_schema/migrations/20261109100001_discord_migration.sql`. Follow the house style: idempotent ("converges by state"), `BEGIN; SET LOCAL lock_timeout …; … COMMIT;`, ends with `NOTIFY pgrst, 'reload schema'`, and `SET search_path = public, extensions, pg_temp` on every `SECURITY DEFINER`. Later DB tasks append to this same file.
- pgTAP tests: `db_schema/tests/246_discord_migration_config.sql`, `247_discord_migration_claim.sql`, `248_discord_migration_sync.sql`. Run with `bash scripts/run-db-tests.sh`.
- After adding RPCs, regenerate `db_schema/SURFACE.tsv` (`scripts/generate-surface.sh`) and `db_schema/REACHABILITY.tsv` (`scripts/generate-reachability.sh`), and run `scripts/check-rpc-coverage.sh` and `scripts/check-plpgsql.sh`.
- Snowflakes are validated with `^[0-9]{1,20}$` everywhere.
- Secrets live only in Vault. Tables holding Vault ids are `REVOKE ALL … FROM anon, authenticated` and `GRANT … TO service_role` only, like `discord_bridge_secrets`.
- Config permission: `public.can_manage_discord_bridge(p_server_id)` (owner or MANAGE_SERVER). Reuse it; don't add a twin.
- The user OAuth scope is exactly `identify`. No privileged intents.
- Defaults: sync interval `DISCORD_MIGRATION_SYNC_MS` = 21600000 (6 h); `removal_brake_pct` = 10; brake minimum = 3 removals; claim ticket lifetime = 7 days; OAuth link state lifetime = 10 minutes.
- Discord-managed roles (`managed: true`) and `@everyone` (role id == guild id) are never stored, shown or mapped.
- Harmony moderation takes precedence: a Harmony ban, or a link `status = 'kicked_on_harmony'`, is never undone by claim or sync. Sync never adds members, and never removes the server owner or a holder of an ADMINISTRATOR role.
- Discord owns mapped roles while `state = 'open'`. Unmapped Harmony roles are never touched.
- Audit rows for writes made by the migration use `server_audit_write(..., p_source => 'system')` with `details->>'via' = 'discord_migration'`.
- All user-visible strings go through vue-i18n (`src/locales/en.json`, key prefix `discordMigration.`).
- Local accounts only: `profiles.is_local = true`.

## Review Focus

1. **Discord outage or lost bot access mid-sync.** Expected: nothing is removed, the migration pauses with a reason, and admins see it. Pinned by Task 4 `brake_and_pause` and Task 6 `pauses_on_guild_403`.
2. **The same Discord account claimed by a second Harmony account on the same server, or an already-linked Harmony user linking a different Discord account.** Expected: refused with a clear error, existing link untouched. Pinned by Task 3 `claim_conflicts`.
3. **Someone kicked or banned on Harmony who is still in the Discord server opens the link again.** Expected: refused, and nothing changes. Pinned by Task 3 `claim_respects_harmony_moderation`.
4. **The owner edits the role map while open, e.g. remaps a Discord role to another Harmony role.** Expected: the next sync removes the old Harmony role and grants the new one for linked members, and unmapped roles stay. Pinned by Task 4 `remap_moves_roles`.
5. **A new-account user abandons the flow after GoTrue user creation, before NewProfile or before confirming email, and returns days later.** Expected: signing in still completes the claim while the ticket is under 7 days old. After that the page says to start again, and starting again with the same Discord account works. Pinned by Task 3 `ticket_expiry_and_retry` and Task 9 `pending claim on login`.

---

### Task 1: Schema and admin configuration RPCs

**Files:**
- Create: `db_schema/migrations/20261109100001_discord_migration.sql`
- Create: `db_schema/tests/246_discord_migration_config.sql`
- Modify: `db_schema/SURFACE.tsv`, `db_schema/REACHABILITY.tsv`, `db_schema/PERMISSIONS.json` (only if the coverage scripts require it)

**Interfaces:**
- Produces (tables):
  - `discord_migrations`: `server_id uuid PK → servers ON DELETE CASCADE`, `discord_guild_id text` (snowflake check), `credential_source text CHECK IN ('instance_bot','bridge','own')`, `state text NOT NULL DEFAULT 'draft' CHECK IN ('draft','open','finished')`, `link_token text UNIQUE NOT NULL DEFAULT encode(gen_random_bytes(24),'hex')`, `opened_at`, `finished_at`, `last_sync_at`, `last_sync_result jsonb`, `sync_requested_at timestamptz`, `sync_cursor timestamptz`, `sync_paused_reason text`, `removal_brake_pct int NOT NULL DEFAULT 10 CHECK 1..100`, `bot_can_ban boolean`, `readiness_warnings jsonb` (written by the gateway's refresh), `created_by`, `updated_at`.
  - `discord_migration_secrets` (service_role only): `server_id PK`, `client_id text`, `client_secret_secret uuid`, `bot_token_secret uuid`. A delete trigger removes the Vault secrets, as in `discord_bridge_instance_bot`.
  - `discord_migration_discord_roles`: `server_id`, `discord_role_id`, `name`, `color int`, `position int`, `fetched_at`. PK (server_id, discord_role_id). This is the snapshot the gateway writes.
  - `discord_migration_role_map`: `server_id`, `discord_role_id`, `harmony_role_id uuid NOT NULL → server_roles ON DELETE CASCADE`. PK (server_id, discord_role_id).
  - `discord_migration_links`: `server_id`, `user_id uuid → profiles`, `discord_user_id text`, `status text CHECK IN ('active','left','banned','kicked_on_harmony')`, `sync_avatar bool DEFAULT false`, `sync_display_name bool DEFAULT false`, `linked_at`, `last_synced_at`. UNIQUE (server_id, user_id) and UNIQUE (server_id, discord_user_id).
  - `discord_migration_claims` (service_role only): `ticket text PK` (64 hex), `server_id`, `discord_user_id`, `discord_username`, `discord_global_name`, `discord_avatar_url`, `auth_user_id uuid NULL`, `expires_at`.
  - `discord_migration_link_states` (service_role only): `state text PK` (64 hex), `server_id`, `expires_at`.
  - `instance_config` key `discord_migration_account_creation`, default `true`, public: add it to `public_instance_config_keys()`.
- Produces (RPCs, `authenticated`, gated by `can_manage_discord_bridge`):
  - `discord_migration_get(p_server_id uuid) RETURNS jsonb`: the config row without secrets, plus `has_own_credentials bool`, `bridge_available bool` (a hosted `discord_bridges` row exists), `instance_bot_available bool`, the role snapshot, the role map, `readiness` (from Task 2), and link counts by status.
  - `discord_migration_save(p_server_id uuid, p_discord_guild_id text, p_credential_source text) RETURNS jsonb`: upsert. `discord_guild_id` defaults to `discord_bridges.discord_guild_id` when null. Refuses edits to the guild id while `state = 'open'`.
  - `discord_migration_set_own_credentials(p_server_id uuid, p_client_id text, p_client_secret text, p_bot_token text) RETURNS void`: writes Vault secrets (rotation replaces the old secret). A null argument keeps the current value.
  - `discord_migration_regenerate_token(p_server_id uuid) RETURNS text`.
- Produces (service_role only): `discord_migration_credentials(p_server_id uuid) RETURNS jsonb` → `{client_id, client_secret, bot_token}`, resolved by source:
  - `instance_bot` reads `discord_bridge_instance_bot`.
  - `bridge` uses the hosted bridge's `discord_token_secret` for the bot token, plus this table's `client_id`/`client_secret`.
  - `own` reads this table.
  - Raises `MIGRATION_CREDENTIALS_MISSING` if any part is missing.
- Produces: `discord_migration_store_discord_roles(p_server_id uuid, p_roles jsonb) RETURNS void` (service_role). Replaces the snapshot and deletes map rows whose Discord role no longer exists.

- [ ] **Step 1: Write the failing pgTAP test `246_discord_migration_config.sql`**
  - A member without MANAGE_SERVER: `discord_migration_get`/`save` raise `42501`.
  - The owner saves `('123456789012345678','own')`; `get` returns `state = 'draft'` and a 48-char `link_token`.
  - `save` with a guild id of `'abc'` raises a check violation.
  - `set_own_credentials` as owner, then `authenticated` cannot `SELECT` `discord_migration_secrets` (permission denied). `discord_migration_credentials` as service_role returns the three values.
  - `regenerate_token` returns a value different from the previous token.
  - `save` with null guild id while a `discord_bridges` row exists copies its guild id.
  - `discord_migration_account_creation` is in `public_instance_config_keys()`, and its default is `true`.
  - `store_discord_roles` with a role missing from the previous snapshot deletes that role's map row.
- [ ] **Step 2: Run `bash scripts/run-db-tests.sh`.** Expected: 246 fails (functions missing).
- [ ] **Step 3: Write the tables, RLS, Vault handling and RPCs above in the migration file.** Model Vault writes and the delete trigger on `20261008800001_discord_bridge_instance_bot.sql`.
- [ ] **Step 4: Run `bash scripts/run-db-tests.sh`.** Expected: 246 passes, and no other file regresses.
- [ ] **Step 5: Regenerate the surface and reachability files, then run `scripts/check-rpc-coverage.sh` and `scripts/check-plpgsql.sh`.** Expected: clean.
- [ ] **Step 6: Commit** `feat(db): discord migration schema and config RPCs`.

### Task 2: Role map, readiness gate, open / finish / re-open

**Files:**
- Modify: `db_schema/migrations/20261109100001_discord_migration.sql`
- Modify: `db_schema/tests/246_discord_migration_config.sql`

**Interfaces:**
- Consumes: Task 1 tables.
- Produces:
  - `discord_migration_save_role_map(p_server_id uuid, p_map jsonb) RETURNS jsonb`. `p_map` is `[{discord_role_id, harmony_role_id}]`. It replaces the map and returns `readiness`.
    - Refuses with `42501` any Harmony role whose `position` is at or above the caller's highest role position, unless the caller is the server owner (reuse the comparison `guard_server_role_hierarchy` uses).
    - Refuses roles from another server, and the server's default (`is_default`) role.
  - `discord_migration_readiness(p_server_id uuid) RETURNS jsonb` → `{ready bool, unmapped_roles: [{discord_role_id,name}], has_credentials bool, has_guild bool, roles_fetched bool}`. `ready` = credentials + guild + snapshot non-empty + zero unmapped roles. Called inside `discord_migration_get`.
  - `discord_migration_set_state(p_server_id uuid, p_state text) RETURNS jsonb`:
    - `draft|finished → open` requires `ready`, sets `opened_at`, clears `finished_at` and `sync_paused_reason`, and sets `sync_requested_at = now()` (re-open syncs immediately).
    - `open → finished` sets `finished_at`.
    - Anything else raises.
  - `discord_migration_request_sync(p_server_id uuid) RETURNS void`: sets `sync_requested_at = now()`. Allowed only while `open`.
  - `discord_migration_auto_match(p_server_id uuid) RETURNS jsonb`: proposes `[{discord_role_id, harmony_role_id|null}]` by case-insensitive trimmed exact name. It doesn't write anything; the UI calls it to prefill.

- [ ] **Step 1: Add failing assertions to 246.**
  - `auto_match` pairs "Moderator" with "moderator ".
  - `readiness.ready` is false with one unmapped role and lists it. After mapping it, `ready` is true.
  - A non-owner MANAGE_SERVER admin mapping a role above their own raises `42501`. The owner mapping it succeeds.
  - `set_state(open)` while not ready raises `MIGRATION_NOT_READY`.
  - `open → finished → open` works and sets `sync_requested_at`.
  - `request_sync` while finished raises.
- [ ] **Step 2: Run `bash scripts/run-db-tests.sh`.** Expected: the new assertions fail.
- [ ] **Step 3: Implement the RPCs.**
- [ ] **Step 4: Run the tests and the coverage scripts.** Expected: pass and clean.
- [ ] **Step 5: Commit** `feat(db): discord migration role map and lifecycle`.

### Task 3: Claims, joining and Harmony moderation precedence

**Files:**
- Modify: `db_schema/migrations/20261109100001_discord_migration.sql`
- Create: `db_schema/tests/247_discord_migration_claim.sql`

**Interfaces:**
- Consumes: Task 1 tables, `server_bans`, `user_servers`, `user_roles`, `server_membership_events`.
- Produces:
  - `discord_migration_create_claim(p_server_id uuid, p_discord_user jsonb, p_discord_role_ids text[]) RETURNS jsonb` (service_role).
    - `p_discord_user` is `{id, username, global_name, avatar_url}`.
    - Requires `state = 'open'`.
    - Returns `{ticket}` for a new 64-hex ticket with a 7-day expiry, or an error code: `MIGRATION_CLOSED`, `DISCORD_BANNED_HERE` (a link exists with status `banned`) or `ALREADY_LINKED_ELSEWHERE` (an active link for that Discord id belongs to another user; the caller can't know the user yet, so this is also re-checked in claim).
    - The role ids are stored on the claim (add column `discord_role_ids text[]`).
  - `discord_migration_bind_claim(p_ticket text, p_auth_user_id uuid) RETURNS void` (service_role): binds a ticket to the GoTrue user the gateway just created.
  - `discord_migration_claim(p_ticket text) RETURNS jsonb` (`authenticated`). It works out the caller's profile from `auth.uid()`, which must be a local profile.
    - If the claim has an `auth_user_id`, it must equal `auth.uid()`.
    - Refuses with `HARMONY_BANNED` if there is a `server_bans` row, `KICKED_ON_HARMONY` if a link has that status, `DISCORD_ACCOUNT_TAKEN` if another user has this Discord id here, `ALREADY_LINKED_OTHER_DISCORD` if the caller is linked to a different Discord id here, and `TICKET_EXPIRED`.
    - Otherwise:
      - upserts the link as `active`, joins via `user_servers` (status accepted, bypassing invite-only), and writes a membership event;
      - applies the mapped roles for the claim's role ids with the Task 4 helper `discord_migration_apply_roles`;
      - deletes the claim and returns `{server_id, roles_granted: [names]}`.
    - Re-claiming as the same user with the same Discord id is idempotent.
  - `discord_migration_my_pending_claims() RETURNS jsonb` (`authenticated`): tickets bound to `auth.uid()` and not expired. Task 9 uses it to finish claims after sign-in.
  - `discord_migration_set_my_sync(p_server_id uuid, p_sync_avatar bool, p_sync_display_name bool) RETURNS void` (`authenticated`, own link only).
  - `discord_migration_allow_back(p_server_id uuid, p_user_id uuid) RETURNS void` (`can_manage_discord_bridge`): `kicked_on_harmony → left`.
  - Trigger on `server_membership_events`: when a kick event (use the existing kick `event_type` value written by `kick_server_member`) is recorded for a user with a link on that server, and it was not written by the migration (the migration sets the transaction-local `SET LOCAL harmony.via = 'discord_migration'`), set the link status to `kicked_on_harmony`.
  - Purge: expired claims and link states are deleted inside `create_claim`; no cron is needed.

- [ ] **Step 1: Write the failing test `247_discord_migration_claim.sql`.**
  - `claim_happy_path`: an open migration with a map `D1→Mod`, a claim with roles `[D1, D9]` (D9 unmapped); bob claims, joins as accepted, holds Mod only, and the claim row is gone.
  - `claim_respects_harmony_moderation`:
    - with bob in `server_bans`, claim raises `HARMONY_BANNED` and creates no membership;
    - after `kick_server_member(bob)` as an admin, the link becomes `kicked_on_harmony`, and a new claim raises `KICKED_ON_HARMONY`;
    - after `allow_back`, claim succeeds.
  - `claim_conflicts`:
    - carol claiming bob's Discord id raises `DISCORD_ACCOUNT_TAKEN`;
    - bob claiming with a different Discord id raises `ALREADY_LINKED_OTHER_DISCORD`;
    - bob's link is unchanged after both.
  - `ticket_expiry_and_retry`:
    - a claim with `expires_at` in the past raises `TICKET_EXPIRED`;
    - a fresh `create_claim` for the same Discord id then succeeds;
    - a ticket bound to another auth user raises `42501`;
    - `my_pending_claims` returns only the caller's unexpired tickets.
  - `claim_closed`: after `set_state(finished)`, `create_claim` returns `MIGRATION_CLOSED`.
  - `set_my_sync` on another user's link has no effect or raises.
- [ ] **Step 2: Run the DB tests.** Expected: 247 fails.
- [ ] **Step 3: Implement the RPCs and the trigger.**
- [ ] **Step 4: Run the DB tests and the coverage scripts.** Expected: pass and clean.
- [ ] **Step 5: Commit** `feat(db): discord migration claims and moderation precedence`.

### Task 4: Sync RPCs (role diff, removals, brake)

**Files:**
- Modify: `db_schema/migrations/20261109100001_discord_migration.sql`
- Create: `db_schema/tests/248_discord_migration_sync.sql`

**Interfaces:**
- Produces (all service_role):
  - `discord_migration_apply_roles(p_server_id uuid, p_user_id uuid, p_discord_role_ids text[]) RETURNS jsonb` → `{added: uuid[], removed: uuid[]}`. The target set is the map rows matching the ids. It adds missing target roles and removes every *mapped* Harmony role (any role present in this server's map) the user holds that is not in the target, including hand-granted ones. Roles not in the map are never touched. Audit uses source `system`, via `discord_migration`. Task 3 also calls it.
  - `discord_migration_due(p_limit int DEFAULT 20) RETURNS SETOF jsonb`: open, unpaused migrations where `sync_requested_at IS NOT NULL` or `last_sync_at < now() - p_interval` (pass the interval as `p_interval interval DEFAULT '6 hours'`), oldest first.
  - `discord_migration_links_batch(p_server_id uuid, p_limit int) RETURNS SETOF jsonb`: active links ordered by `last_synced_at NULLS FIRST`, with `{user_id, discord_user_id, sync_avatar, sync_display_name}`.
  - `discord_migration_sync_member(p_server_id uuid, p_user_id uuid, p_member jsonb) RETURNS jsonb`. `p_member` is `{present: bool, banned: bool|null, role_ids: text[], avatar_url, global_name}`.
    - Present: run `apply_roles`. If `sync_avatar`, set `profiles.avatar_url` (Task 6 passes a URL it has already mirrored). If `sync_display_name`, set `profiles.display_name`. Stamp `last_synced_at`.
    - Not present: mark it as a removal candidate. Don't remove here; return `{candidate: 'left'|'banned'}`.
  - `discord_migration_apply_removals(p_server_id uuid, p_candidates jsonb) RETURNS jsonb`. `p_candidates` is `[{user_id, kind}]`.
    - **Brake:** if `count > 0 AND count >= 3 AND count * 100 > removal_brake_pct * active_links`, set `sync_paused_reason = 'removal_brake'`, apply nothing, and return `{paused: true}`.
    - Otherwise, with `SET LOCAL harmony.via = 'discord_migration'`, for each candidate: skip and log if the user is the server owner or holds an ADMINISTRATOR role. Otherwise call the kick or ban internals (`left` → kick, `banned` → ban; reason "Left the Discord server" or "Banned on the Discord server") and set the link status to `left`/`banned`.
  - `discord_migration_pause(p_server_id uuid, p_reason text) RETURNS void`. Also writes a `server_audit_write` row (`action 'discord_migration_paused'`, source `system`), so admins see it in the audit log as well as the panel.
  - `discord_migration_record_sync(p_server_id uuid, p_result jsonb, p_cursor timestamptz) RETURNS void`: sets `last_sync_at`, `last_sync_result` and `sync_cursor`, and clears `sync_requested_at`.
- The gateway has no `auth.uid()`. If `kick_server_member`/`ban_server_member` check the caller, factor their bodies into internal `_kick_server_member_internal(p_server_id, p_user_id, p_reason)` / `_ban_server_member_internal(...)` that both the public RPC and this one call. Keep the public signatures unchanged.

- [ ] **Step 1: Write the failing test `248_discord_migration_sync.sql`.**
  - `role_diff`: bob holds Mod (mapped, hand-granted) and Artist (unmapped); `sync_member` with `role_ids = []` removes Mod and keeps Artist.
  - `remap_moves_roles`: map `D1→Mod`, bob synced with `[D1]` holds Mod; remap `D1→Helper`; sync again leaves bob with Helper and without Mod.
  - `profile_opt_in`: with `sync_display_name = false`, display_name is unchanged; after opting in, it is updated.
  - `brake_and_pause`:
    - 10 active links and 2 candidates: removals apply (below the 3 minimum);
    - 30 links and 4 candidates (13% > 10%): `paused = true`, `sync_paused_reason = 'removal_brake'`, and all 4 are still members;
    - `discord_migration_due` no longer returns that server.
  - `never_removes_owner_or_admin`: an owner candidate and an ADMINISTRATOR-role candidate are both still members afterwards.
  - `removal_kinds`: a `left` candidate is removed with the link `left`, and the trigger does **not** mark it `kicked_on_harmony`; a `banned` candidate gets a `server_bans` row with the link `banned`.
  - `due_ordering`: a migration with `sync_requested_at` set is returned, a finished one is not.
- [ ] **Step 2: Run the DB tests.** Expected: 248 fails.
- [ ] **Step 3: Implement the RPCs, including the internal kick/ban refactor if needed.**
- [ ] **Step 4: Run all DB tests, including the existing kick/ban tests.** Expected: pass. Then run the coverage scripts.
- [ ] **Step 5: Commit** `feat(db): discord migration sync RPCs`.

### Task 5: Gateway Discord REST client

**Files:**
- Create: `bot-gateway/src/migration/discordClient.ts`
- Create: `bot-gateway/src/migration/__tests__/discordClient.test.ts`

**Interfaces:**
- Consumes: `DISCORD_API_BASE` from `bot-gateway/src/bridge/instanceBot.ts`.
- Produces:
  - `type DiscordResult<T> = { ok: true; value: T } | { ok: false; status: number; reason: string }`
  - `migrateAuthorizeUrl(clientId: string, redirectUri: string, state: string): string` (scope `identify`, `response_type=code`, `prompt=none`)
  - `exchangeUserCode(input: {clientId; clientSecret; code; redirectUri}): Promise<DiscordResult<{ accessToken: string }>>`
  - `fetchCurrentUser(accessToken: string): Promise<DiscordResult<DiscordUser>>` where `DiscordUser = { id; username; global_name: string|null; avatar_url: string|null }`. The avatar URL is built from the hash (`https://cdn.discordapp.com/avatars/{id}/{hash}.png?size=256`).
  - `fetchGuildRoles(botToken, guildId): Promise<DiscordResult<DiscordRole[]>>`: excludes `managed` roles and `@everyone`. `DiscordRole = { id; name; color; position }`.
  - `fetchGuildMember(botToken, guildId, userId): Promise<DiscordResult<{ present: false } | { present: true; role_ids: string[]; user: DiscordUser }>>`: a 404 with Discord code 10007 is `present: false`. Any other 404 (unknown guild, 10004) is a failure.
  - `fetchBan(botToken, guildId, userId): Promise<DiscordResult<boolean>>`: 404 → false; 403 → `{ok:false,status:403}`.
  - `fetchBotPermissionsInGuild(botToken, guildId): Promise<DiscordResult<{ canBan: boolean }>>`: from the bot's member roles plus guild roles, using bit `1n << 2n` (BAN_MEMBERS) or `1n << 3n` (ADMINISTRATOR).
  - `fetchApplication(botToken): Promise<DiscordResult<{ id: string }>>` (`GET /oauth2/applications/@me`).
  - All calls take `{ fetchImpl?: typeof fetch, timeoutMs?: number }`. On 429 they wait `retry_after` seconds once and retry; a second 429 fails with `reason: 'rate_limited'`.

- [ ] **Step 1: Write failing vitest tests with a stubbed `fetchImpl`.**
  - The authorize URL contains `scope=identify` and the state.
  - Roles exclude managed roles and the role whose id equals the guild id.
  - A member 404/10007 maps to present false; 404/10004 maps to ok false.
  - A ban 403 is ok false with status 403.
  - A 429 then 200 succeeds after one retry, and 429 twice fails `rate_limited`.
  - The avatar URL is built correctly, and is null when the hash is null.
- [ ] **Step 2: Run `cd bot-gateway && npx vitest run src/migration`.** Expected: fail.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the same command.** Expected: pass. Then run `npm run type-check` in `bot-gateway`.
- [ ] **Step 5: Commit** `feat(gateway): discord REST client for migrations`.

### Task 6: Gateway migration API (setup checks, OAuth, account creation)

**Files:**
- Create: `bot-gateway/src/api/MigrationAPI.ts`
- Create: `bot-gateway/src/api/__tests__/MigrationAPI.test.ts`
- Modify: `bot-gateway/src/index.ts` (mount `app.use('/bridge/v2/migrate', new MigrationAPI().router)` next to the BridgeV2API mount)

**Interfaces:**
- Consumes: Task 5 client; RPCs `discord_migration_credentials`, `discord_migration_store_discord_roles`, `discord_migration_create_claim`, `discord_migration_bind_claim`; `supabase.auth.admin.createUser`; `supabase.auth.getUser(jwt)` to authenticate admins; `can_manage_discord_bridge` checked through an RPC call made with the admin's JWT.
- Produces routes (base `/bridge/v2/migrate`; rate-limit each like `linkLimiter` in `BridgeV2API.ts`):
  - `POST /:serverId/refresh` (Bearer = the admin's Supabase JWT):
    - resolves credentials, fetches roles and stores the snapshot;
    - fetches `canBan` and writes `bot_can_ban`;
    - for the `bridge` source with no `client_id`, fills it from `fetchApplication`;
    - fetches the guild channels (`GET /guilds/{g}/channels`, add `fetchGuildChannels` to Task 5's client) and writes `readiness_warnings = {unpaired_channels: [names not in discord_bridge_channels for this server], member_overrides: <count of permission_overwrites with type 1>}` through a service RPC `discord_migration_store_warnings(p_server_id, p_warnings jsonb)`, added to Task 1's migration file;
    - returns `{ok, roles: n, canBan}`, or `{ok:false, error}` where `error ∈ credentials_missing | token_invalid | bot_not_in_guild`.
  - `GET /authorize?token=<link_token>` → 302 to Discord, with a link state stored for 10 minutes. If the migration isn't open → 302 to `/migrate/<token>?error=closed`.
  - `GET /callback?code&state`:
    - exchanges the code, fetches the user, then fetches the guild member with the bot;
    - absent → `/migrate/<token>?error=not_member`;
    - otherwise `create_claim`, then 302 `/migrate/<token>?ticket=<ticket>`; claim error codes pass through as `error=<code lowercased>`.
  - `POST /account` with body `{ticket, username, email, password}`:
    - checks the instance `discord_migration_account_creation` is not false (else 403 `account_creation_disabled`);
    - checks the ticket is valid and unbound;
    - checks the username is free among local profiles (else 409 `username_taken`);
    - `auth.admin.createUser({ email, password, email_confirm: false, user_metadata: { username } })`; an existing email → 409 `email_exists`;
    - `bind_claim`, then returns `{ ok: true, needsConfirmation: <instance requires email confirmation> }`. Read the confirmation requirement from the existing instance config/GoTrue setting the frontend already uses for registration; if none is exposed, use `GOTRUE_MAILER_AUTOCONFIRM` env.
- Redirect URI: `${configuredBaseUrl}/bridge/v2/migrate/callback` (reuse the base URL helper `BridgeV2API` uses for `discordCallbackUrl`).

- [ ] **Step 1: Write failing tests with supertest, mocking `supabase` and the Task 5 client the way `BridgeV2API.instance.test.ts` mocks them.**
  - `authorize` for an open token redirects to discord.com with state; for a finished token it redirects with `error=closed`.
  - `callback` for a non-member redirects with `error=not_member` and never calls `create_claim`; for a member it redirects with a ticket.
  - A bad or expired state → 400.
  - `account` with account creation disabled → 403; username taken → 409; existing email → 409 `email_exists`; success → `createUser` called with `email_confirm: false`, then `bind_claim`.
  - `refresh` without a JWT → 401; a non-manager → 403; success stores roles.
- [ ] **Step 2: Run `cd bot-gateway && npx vitest run src/api/__tests__/MigrationAPI.test.ts`.** Expected: fail.
- [ ] **Step 3: Implement and mount.**
- [ ] **Step 4: Run the gateway tests and type-check.** Expected: pass.
- [ ] **Step 5: Commit** `feat(gateway): discord migration OAuth and account API`.

### Task 7: Gateway sync runner

**Files:**
- Create: `bot-gateway/src/migration/MigrationSyncRunner.ts`
- Create: `bot-gateway/src/migration/__tests__/MigrationSyncRunner.test.ts`
- Modify: `bot-gateway/src/index.ts` (start the runner after `bridgeConfigWatcher.start()`); `bot-gateway/src/config/supabase.ts` (`config.migration = { syncMs, pollMs: 30_000, budgetMs: 120_000, batch: 100 }` from env `DISCORD_MIGRATION_SYNC_MS`, default 21600000)

**Interfaces:**
- Consumes: Task 4 RPCs; Task 5 client; `bot-gateway/src/utils/mirrorExternalMedia.ts` for avatars (store the mirrored URL, never the raw Discord CDN URL).
- Produces: `class MigrationSyncRunner { constructor(deps?: { rpc?, discord?, now?, intervalMs? }); start(): void; stop(): void; runOnce(): Promise<void>; syncServer(m: DueMigration): Promise<SyncResult> }`. `SyncResult = { checked: number; rolesChanged: number; removed: number; paused?: string; errors: number }`.
- Algorithm for `syncServer`:
  1. Resolve credentials. On `MIGRATION_CREDENTIALS_MISSING` → `pause('credentials_missing')`.
  2. Fetch the guild roles and store the snapshot. A 401 → `pause('token_invalid')`; 403/404 → `pause('bot_not_in_guild')`. Return without touching members.
  3. Loop `links_batch` until `budgetMs` elapses or no links remain. Per link, run `fetchGuildMember`:
     - present → `sync_member`;
     - absent → ban check if `bot_can_ban`, then collect the candidate;
     - a non-404 failure → `errors++` and continue. If more than half of the members checked so far failed, stop and pause `discord_errors`.
  4. `apply_removals(candidates)`, then `record_sync(result, cursor)`.
  - `runOnce` takes `discord_migration_due` and syncs each server sequentially. The timer runs `runOnce` every `pollMs`, and never overlaps (an in-flight guard).

- [ ] **Step 1: Write failing tests with injected fakes.**
  - `pauses_on_guild_403`: the roles fetch returns 403 → `pause('bot_not_in_guild')` and neither `sync_member` nor `apply_removals` is called.
  - Absent members become candidates passed in one `apply_removals` call.
  - A ban check happens only when `bot_can_ban`.
  - The avatar goes through the mirror before `sync_member`.
  - The budget stops the loop and `record_sync` still runs.
  - More than half of member fetches erroring → paused `discord_errors`, and no removals are applied.
  - `runOnce` doesn't overlap when called twice concurrently.
- [ ] **Step 2: Run `cd bot-gateway && npx vitest run src/migration`.** Expected: fail.
- [ ] **Step 3: Implement and wire into `index.ts`.**
- [ ] **Step 4: Run the gateway tests and type-check.** Expected: pass.
- [ ] **Step 5: Commit** `feat(gateway): scheduled discord migration sync`.

### Task 8: Server settings panel

**Files:**
- Create: `src/components/settings/discord-migration/DiscordMigrationSettings.vue`
- Create: `src/components/settings/discord-migration/migrationApi.ts`
- Create: `src/components/settings/discord-migration/__tests__/DiscordMigrationSettings.test.ts`
- Modify: `src/views/ServerSettings.vue` (add the nav entry after `discord-bridge`: `{ id: 'discord-migration', label: t('discordMigration.navLabel'), group: 'integrations' }` gated on `p.canEditBasicInfo`, as the bridge is; add a `v-if` panel and an import)
- Modify: `src/locales/en.json`

**Interfaces:**
- Consumes:
  - Task 1–3 RPCs via `supabase.rpc`: `discord_migration_get`, `save`, `set_own_credentials`, `auto_match`, `save_role_map`, `set_state`, `request_sync`, `regenerate_token`, `allow_back`;
  - the gateway `POST /bridge/v2/migrate/:serverId/refresh` (the base URL comes from wherever `src/components/settings/discord-bridge/bridgeApi.ts` gets the gateway URL).
- Produces: `migrationApi.ts` exports typed wrappers for each call above: `getMigration(serverId)`, `saveMigration(...)`, `setOwnCredentials(...)`, `refreshFromDiscord(serverId)`, `autoMatch(serverId)`, `saveRoleMap(serverId, map)`, `setState(serverId, state)`, `requestSync(serverId)`, `regenerateToken(serverId)`, `allowBack(serverId, userId)`.
- UI sections, in order:
  1. Prerequisites text: clone the server first; the bot must be in the Discord server.
  2. Credential source radio, with options hidden when unavailable: `instance_bot`, `bridge`, `own`. `own` shows client ID, client secret and bot token fields, with secrets write-only. It also shows the redirect URI to copy.
  3. Discord server ID.
  4. "Fetch from Discord" (`refresh`), with errors shown inline. A notice appears when `bot_can_ban` is false.
  5. Role map table: one row per Discord role (color dot + name) → a Harmony role `<select>`, prefilled from `autoMatch`, with a "matched by name" hint. No ignore option. A counter "N roles still need a match".
  6. Readiness warnings, then **Open migration**, disabled until `readiness.ready`.
  7. While open: the link with copy and regenerate; last sync time and result; the pause reason with a "Resume" button that calls `set_state('open')`; **Sync now**; a member list (user, Discord id, status, last synced) with **Allow back** on `kicked_on_harmony` rows; **Finish migration** behind a confirm dialog.
  8. While finished: a **Re-open** confirm that warns mapped roles and membership will follow Discord again.

- [ ] **Step 1: Write failing component tests (vitest + @vue/test-utils, mocking `migrationApi`).**
  - The Open button is disabled while `readiness.unmapped_roles` is non-empty, and the counter shows 2.
  - Choosing a Harmony role calls `saveRoleMap` with `{discord_role_id, harmony_role_id}`.
  - The role select has no "ignore" option.
  - The open state renders the link containing `link_token`, and the Finish confirm calls `setState('finished')`.
  - A finished state shows Re-open and its warning text.
  - A `kicked_on_harmony` row shows Allow back.
- [ ] **Step 2: Run `npx vitest run src/components/settings/discord-migration`.** Expected: fail.
- [ ] **Step 3: Implement, register in `ServerSettings.vue`, and add the i18n keys.**
- [ ] **Step 4: Run the tests, `npm run type-check` and `npm run lint`.** Expected: pass.
- [ ] **Step 5: Commit** `feat(ui): Migrate from Discord server settings`.

### Task 9: Public migrate page, pending claims, profile sync toggles

**Files:**
- Create: `src/views/MigrateFromDiscordView.vue`
- Create: `src/services/discordMigrationService.ts`
- Create: `src/views/__tests__/MigrateFromDiscordView.test.ts`
- Modify: `src/router/index.ts` (public route `/migrate/:token`, no auth guard; same meta as `/invite/:code`)
- Modify: `src/views/NewProfile.vue` (after a successful `createProfile`, call `completePendingClaims()`; prefill the username from `?username=` when present)
- Modify: the app's post-sign-in hook (where the session is established after login; find it from `LoginView.vue`/the auth store) to call `completePendingClaims()` when a profile exists
- Modify: the user's server membership/settings area (where per-server user settings live; if none exists, add to the server's member context menu "Discord sync settings") for `set_my_sync` toggles
- Modify: `src/locales/en.json`
- Add RPC: `discord_migration_preview(p_token text) RETURNS jsonb` (anon + authenticated) in the migration file. It returns `{server_name, icon_url}` only when `state = 'open'`, else null. Add an assertion to `247`.

**Interfaces:**
- Consumes: gateway `/bridge/v2/migrate/authorize`, `/account`; RPCs `discord_migration_preview`, `discord_migration_claim`, `discord_migration_my_pending_claims`, `discord_migration_set_my_sync`.
- Produces: `discordMigrationService.ts` exports `preview(token)`, `startDiscordSignIn(token)` (navigates to authorize), `createAccount({ticket, username, email, password})`, `claim(ticket)`, `completePendingClaims(): Promise<ClaimResult[]>`, `setMySync(serverId, {avatar, displayName})`.
- Page states, driven by the query string:
  1. No `ticket` and no `error` → landing: server icon and name, then:
     - signed in: "You'll link this to @username" (not you? sign out) plus "Sign in with Discord";
     - signed out: "I have a Harmony account" (to `/login?redirect=/migrate/<token>`) and "I'm new: continue with Discord".
  2. `error=` → the matching message for: `closed`, `not_member`, `harmony_banned`, `kicked_on_harmony`, `discord_banned_here`, `discord_account_taken`, `already_linked_other_discord`, `ticket_expired` (offer "start again"), `account_creation_disabled`.
  3. `ticket=` and signed in → call `claim`, then show the result (roles received) with profile-sync checkboxes (avatar, display name) that call `setMySync`.
  4. `ticket=` and signed out → the new-account form (username prefilled from Discord, email, password, profile-sync checkboxes). On submit, call `createAccount`:
     - `needsConfirmation` → "Confirm your email, then sign in. You'll join automatically."
     - otherwise `supabase.auth.signInWithPassword`, then NewProfile with `?username=`; NewProfile completes the claim.
     - Store the chosen sync toggles in `localStorage` keyed by ticket, and apply them after the claim.
  5. A federated (non-local) signed-in session → "Create an account on this instance to migrate."

- [ ] **Step 1: Write failing tests (mock the service).**
  - Signed out with no ticket shows both buttons.
  - `error=harmony_banned` shows the banned message and no sign-in button.
  - Signed in with a ticket calls `claim` once and shows the granted role names.
  - Signed out with a ticket shows the form; a `createAccount` 409 `username_taken` shows an inline error.
  - `needsConfirmation` shows the confirm-email message.
  - `completePendingClaims` calls `claim` for each pending ticket and swallows `TICKET_EXPIRED`.
  - In 247: `preview` returns null for a finished migration.
- [ ] **Step 2: Run `npx vitest run src/views/__tests__/MigrateFromDiscordView.test.ts` and the DB tests.** Expected: fail.
- [ ] **Step 3: Implement the view, the service, the route, the NewProfile and post-login hooks, and the sync toggles.**
- [ ] **Step 4: Run the tests, type-check and lint.** Expected: pass.
- [ ] **Step 5: Commit** `feat(ui): public migrate-from-Discord page and claim completion`.

### Task 10: Operator switch, docs, end-to-end

**Files:**
- Modify: `self-host/admin.sh` and `self-host/harmony`: `harmony migration account-creation [on|off]` writes `instance_config.discord_migration_account_creation`
- Create: `docs/run/discord-migration.md` (for owners: prerequisites (clone via `/bridge clone-server` + `/bridge sync-perms`), credential options, redirect URI, role mapping rule ("every non-managed Discord role must map; delete roles you don't want on Discord before, or on Harmony after"), what syncs, Harmony moderation precedence, the brake, finishing and re-opening)
- Modify: `docs/self-host/discord-bridges.md` (the instance bot's Discord app must also list `<base>/bridge/v2/migrate/callback` as a redirect; `DISCORD_MIGRATION_SYNC_MS`; the CLI switch); `docs/self-host/cli.md`
- Modify: `CHANGELOG.md`
- Create: `e2e/specs/discord-migration.spec.ts`

**Interfaces:**
- Consumes: everything above. Stub Discord in e2e by pointing the gateway's `DISCORD_API_BASE` at a local stub. If it's a hard-coded constant, make it read `process.env.DISCORD_API_BASE ?? 'https://discord.com/api/v10'` in `instanceBot.ts`, and do the same for the authorize URL host.

- [ ] **Step 1: Write the failing e2e happy path.** The owner configures `own` credentials, fetches roles, maps the one unmatched role and opens. A new user follows the link: stub Discord consent → form → account → NewProfile → lands in the server holding the mapped role. The owner presses Sync now after the stub drops that role, and the role is gone.
- [ ] **Step 2: Run `npx playwright test e2e/specs/discord-migration.spec.ts`.** Expected: fail before the stub and env wiring, pass after.
- [ ] **Step 3: Add the CLI switch, docs and changelog entry.**
- [ ] **Step 4: Run `npm run docs:lint`, `bash scripts/run-db-tests.sh`, `npm test`, `cd bot-gateway && npm test`, and the e2e spec.** Expected: all pass.
- [ ] **Step 5: Commit** `feat: discord migration operator switch, docs, e2e`.
