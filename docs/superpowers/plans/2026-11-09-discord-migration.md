# Migrate from Discord Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Per-server "Migrate from Discord": members sign in with Discord through a per-server link, are verified as members of the linked Discord server, get or link a Harmony account, join the cloned Harmony server with their mapped roles, and stay in sync (roles, membership, timeouts, opt-in profile fields) until the owner finishes the migration.

**Architecture:** Postgres owns state and every rule. One new migration adds tables plus RPCs: admin config and role mapping (including "Apply Discord permissions"), a user `claim`, and service-only sync RPCs that do the role diff in SQL. The bot gateway (`bot-gateway/`, Express, service role) does only what the database can't: Discord OAuth, Discord REST calls with the bot token, GoTrue admin user creation, media mirroring and the scheduled sync loop. Both the new-account path and the existing-account path end in the same RPC, `discord_migration_claim(ticket)`, called by the signed-in user, so joining, roles and moderation checks live in one place. The Vue frontend adds a server settings panel and a public `/migrate/:token` page.

**Tech Stack:** PostgreSQL + Supabase (PostgREST, GoTrue, Vault), pgTAP (`db_schema/tests`, `scripts/run-db-tests.sh`), Node/Express + vitest (`bot-gateway`), Vue 3 + Pinia + vue-i18n + vitest (`src/`), Playwright (`e2e/specs`).

**Spec:** `docs/superpowers/specs/2026-11-09-discord-migration-design.md`, including its "Revisions 2026-11-11" section, which overrides earlier text where they differ.

**Branch:** from `master` after WWPOL/brapmony#3 (server profiles) merges. It needs `service_set_member_server_profile`, `user_servers.avatar_url/banner_url`, `profiles.pronouns` and the permission bits 32-33 from that PR.

## Global Constraints

- Migration file: `db_schema/migrations/20261112110001_discord_migration.sql`. If master has a later migration when work starts, renumber past it. Follow the house style: idempotent ("converges by state"), `BEGIN; SET LOCAL lock_timeout …; … COMMIT;`, ends with `NOTIFY pgrst, 'reload schema'`, and `SET search_path = public, extensions, pg_temp` on every `SECURITY DEFINER`. Later DB tasks append to this same file.
- pgTAP tests: `db_schema/tests/274_discord_migration_config.sql`, `275_discord_migration_claim.sql`, `276_discord_migration_sync.sql` (renumber past master's highest when work starts). Run with `bash scripts/run-db-tests.sh`; on Windows use Docker in WSL on an LF copy of the tree.
- After adding RPCs, regenerate `db_schema/SURFACE.tsv` (`scripts/generate-surface.sh`) and `db_schema/REACHABILITY.tsv` (`scripts/generate-reachability.sh`), run `scripts/check-published-no-caller.sh`, `scripts/check-rpc-coverage.sh` and `scripts/check-plpgsql.sh`. CI fails on stale census files.
- Snowflakes are validated with `^[0-9]{1,20}$` everywhere.
- Secrets live only in Vault. Tables holding Vault ids are `REVOKE ALL … FROM anon, authenticated` and `GRANT … TO service_role` only, like `discord_bridge_secrets`.
- Config permission: `public.can_manage_discord_bridge(p_server_id)` (owner or MANAGE_SERVER). Reuse it; don't add a twin.
- The user OAuth scope is exactly `identify`. No privileged intents.
- Defaults: sync interval `DISCORD_MIGRATION_SYNC_MS` = 21600000 (6 h); `removal_brake_pct` = 10; brake minimum = 3 removals; claim ticket lifetime = 7 days; OAuth link state lifetime = 10 minutes.
- Discord-managed roles (`managed: true`) and `@everyone` (role id == guild id) are never stored, shown or mapped.
- Harmony moderation takes precedence: a Harmony ban, or a link `status = 'kicked_on_harmony'`, is never undone by claim or sync. Sync never adds members, never touches the server owner (no role added or removed, never removed), and never removes a holder of an ADMINISTRATOR role from the server.
- Discord owns mapped roles while `state = 'open'`. Unmapped Harmony roles are never touched.
- "Administrator-bearing" means: the Discord role's snapshot `harmony_permissions` has bit 0 (ADMINISTRATOR), or the Harmony role's `permissions` has bit 0, or the Harmony role `is_admin`. Mapping or applying an Administrator-bearing row is owner-only and needs `p_confirm_admin => true`.
- Profile sync fields, exact names: `avatar`, `display_name`, `banner`, `accent_color`, `server_nickname`, `server_avatar`, `server_banner`. Server fields are written only through `service_set_member_server_profile(p_server_id, p_user_id, p_fields jsonb)` with keys `nickname`, `avatar_url`, `banner_url`. Discord CDN URLs are never stored; avatars and banners go through `mirrorExternalMediaToStorage` (`bot-gateway/src/utils/mirrorExternalMedia.ts`).
- Timeouts written by the migration use `server_member_timeouts.source = 'discord_migration'` (extend `server_member_timeouts_source_check`). Only rows with that source are ever lifted by the migration.
- Audit rows for writes made by the migration use `server_audit_write(..., p_source => 'system')` with `details->>'via' = 'discord_migration'`. Audit actions are dotted (`member.role_add`, `member.role_remove`, `member.kick`, `member.ban`, `member.timeout`, `member.timeout_remove`, and new `discord_migration.paused`). Add any new action to `KNOWN_ACTIONS` in `src/utils/serverAuditLog.ts` with sentences in all 7 locales.
- All user-visible strings go through vue-i18n in all 7 locales (`src/locales/{en,de,es,fr,ja,ko,zh}.json`, key prefix `discordMigration.`).
- Local accounts only: `profiles.is_local = true`.

## Review Focus

1. **Discord outage or lost bot access mid-sync.** Expected: nothing is removed, the migration pauses with a reason, and admins see it. Pinned by Task 4 `brake_and_pause` and Task 7 `pauses_on_guild_403`.
2. **A Discord role carrying Administrator mapped by a non-owner, or mapped without confirmation.** Expected: refused, map unchanged; the owner with confirmation succeeds, and every sync grant or removal of that role notifies the owner. Pinned by Task 2 `admin_role_owner_only` and Task 4 `admin_grant_notifies_owner`.
3. **Apply Discord permissions by an admin who lacks a bit, or on a role above their own.** Expected: refused by the ordinary role rules, role unchanged; Harmony-only permissions are never removed. Pinned by Task 2 `apply_permissions_rules`.
4. **A Harmony moderator's running timeout while Discord has none, or a shorter one.** Expected: the Harmony timeout stays exactly as it is. Pinned by Task 4 `timeouts_respect_harmony`.
5. **Someone kicked or banned on Harmony who is still in the Discord server opens the link again.** Expected: refused, and nothing changes. Pinned by Task 3 `claim_respects_harmony_moderation`.

---

### Task 1: Schema and admin configuration RPCs

**Files:**
- Create: `db_schema/migrations/20261112110001_discord_migration.sql`
- Create: `db_schema/tests/274_discord_migration_config.sql`
- Modify: `db_schema/SURFACE.tsv`, `db_schema/REACHABILITY.tsv`, `db_schema/PUBLISHED-NO-CALLER.tsv`, `db_schema/PERMISSIONS.json` (as the census scripts require)

**Interfaces:**
- Produces (tables):
  - `discord_migrations`: `server_id uuid PK → servers ON DELETE CASCADE`, `discord_guild_id text` (snowflake check), `credential_source text CHECK IN ('instance_bot','bridge','own')`, `state text NOT NULL DEFAULT 'draft' CHECK IN ('draft','open','finished')`, `link_token text UNIQUE NOT NULL DEFAULT encode(gen_random_bytes(24),'hex')`, `opened_at`, `finished_at`, `last_sync_at`, `last_sync_result jsonb`, `sync_requested_at timestamptz`, `sync_cursor timestamptz`, `sync_paused_reason text`, `removal_brake_pct int NOT NULL DEFAULT 10 CHECK 1..100`, `bot_can_ban boolean`, `readiness_warnings jsonb`, `created_by`, `updated_at`.
  - `discord_migration_secrets` (service_role only): `server_id PK`, `client_id text`, `client_secret_secret uuid`, `bot_token_secret uuid`. A delete trigger removes the Vault secrets, as in `discord_bridge_instance_bot`.
  - `discord_migration_discord_roles`: `server_id`, `discord_role_id`, `name`, `color int`, `position int`, `harmony_permissions bigint NOT NULL DEFAULT 0` (the Discord role's permissions translated to Harmony bits by the gateway, ADMINISTRATOR included), `fetched_at`. PK (server_id, discord_role_id).
  - `discord_migration_role_map`: `server_id`, `discord_role_id`, `harmony_role_id uuid NOT NULL → server_roles ON DELETE CASCADE`. PK (server_id, discord_role_id).
  - `discord_migration_links`: `server_id`, `user_id uuid → profiles`, `discord_user_id text`, `status text CHECK IN ('active','left','banned','kicked_on_harmony')`, `sync_fields text[] NOT NULL DEFAULT '{}'` (CHECK `sync_fields <@ ARRAY['avatar','display_name','banner','accent_color','server_nickname','server_avatar','server_banner']`), `discord_role_ids text[] NOT NULL DEFAULT '{}'` (last seen on Discord), `discord_joined_at timestamptz`, `linked_at`, `last_synced_at`. UNIQUE (server_id, user_id) and UNIQUE (server_id, discord_user_id).
  - `discord_migration_claims` (service_role only): `ticket text PK` (64 hex), `server_id`, `discord_user_id`, `discord_username`, `discord_global_name`, `discord_avatar_url`, `discord_role_ids text[]`, `discord_joined_at timestamptz`, `auth_user_id uuid NULL`, `expires_at`.
  - `discord_migration_link_states` (service_role only): `state text PK` (64 hex), `server_id`, `expires_at`.
  - `instance_config` key `discord_migration_account_creation`, default `true`, public: add it to `public_instance_config_keys()`.
- Produces (RPCs, `authenticated`, gated by `can_manage_discord_bridge`):
  - `discord_migration_get(p_server_id uuid) RETURNS jsonb`: the config row without secrets, plus `has_own_credentials bool`, `bridge_available bool` (a hosted `discord_bridges` row exists), `instance_bot_available bool`, the role snapshot, the role map, `readiness` (Task 2), `permission_gaps` (Task 2), `caller_is_owner bool`, and link counts by status.
  - `discord_migration_save(p_server_id uuid, p_discord_guild_id text, p_credential_source text) RETURNS jsonb`: upsert. `discord_guild_id` defaults to `discord_bridges.discord_guild_id` when null. Refuses edits to the guild id while `state = 'open'`.
  - `discord_migration_set_own_credentials(p_server_id uuid, p_client_id text, p_client_secret text, p_bot_token text) RETURNS void`: writes Vault secrets (rotation replaces the old secret). A null argument keeps the current value.
  - `discord_migration_regenerate_token(p_server_id uuid) RETURNS text`.
- Produces (service_role only):
  - `discord_migration_credentials(p_server_id uuid) RETURNS jsonb` → `{client_id, client_secret, bot_token}`, resolved by source: `instance_bot` reads `discord_bridge_instance_bot`; `bridge` uses the hosted bridge's `discord_token_secret` for the bot token, plus this table's `client_id`/`client_secret`; `own` reads this table. Raises `MIGRATION_CREDENTIALS_MISSING` if any part is missing.
  - `discord_migration_store_discord_roles(p_server_id uuid, p_roles jsonb) RETURNS void`. `p_roles` is `[{id, name, color, position, harmony_permissions}]` (`harmony_permissions` a decimal string). Replaces the snapshot and deletes map rows whose Discord role no longer exists.
  - `discord_migration_store_warnings(p_server_id uuid, p_warnings jsonb) RETURNS void`.

- [ ] **Step 1: Write the failing pgTAP test `274_discord_migration_config.sql`**
  - A member without MANAGE_SERVER: `discord_migration_get`/`save` raise `42501`.
  - The owner saves `('123456789012345678','own')`; `get` returns `state = 'draft'` and a 48-char `link_token`.
  - `save` with a guild id of `'abc'` raises a check violation.
  - `set_own_credentials` as owner, then `authenticated` cannot `SELECT` `discord_migration_secrets` (permission denied). `discord_migration_credentials` as service_role returns the three values.
  - `regenerate_token` returns a value different from the previous token.
  - `save` with null guild id while a `discord_bridges` row exists copies its guild id.
  - `discord_migration_account_creation` is in `public_instance_config_keys()`, and its default is `true`.
  - `store_discord_roles` keeps `harmony_permissions` (`'17179869183'` round-trips) and, with a role missing from the previous snapshot, deletes that role's map row.
  - Inserting a link with `sync_fields = '{nickname}'` raises a check violation.
- [ ] **Step 2: Run `bash scripts/run-db-tests.sh`.** Expected: 274 fails (functions missing).
- [ ] **Step 3: Write the tables, RLS, Vault handling and RPCs above in the migration file.** Model Vault writes and the delete trigger on `20261008800001_discord_bridge_instance_bot.sql`.
- [ ] **Step 4: Run `bash scripts/run-db-tests.sh`.** Expected: 274 passes, and no other file regresses.
- [ ] **Step 5: Regenerate the census files and run the check scripts.** Expected: clean.
- [ ] **Step 6: Commit** `feat(db): discord migration schema and config RPCs`.

### Task 2: Role map, permission gaps, Administrator safeguards, lifecycle

**Files:**
- Modify: `db_schema/migrations/20261112110001_discord_migration.sql`
- Modify: `db_schema/tests/274_discord_migration_config.sql`

**Interfaces:**
- Consumes: Task 1 tables; `get_user_highest_role_position`, `get_user_permissions`, `assert_grantable_bits`, `permission_bit_names()`, the `server_roles` write guard (`20261010500001_server_manager_writes.sql`: hierarchy plus "grant only what you hold" on client UPDATEs).
- Produces:
  - `discord_migration_save_role_map(p_server_id uuid, p_map jsonb, p_confirm_admin boolean DEFAULT false) RETURNS jsonb`. `p_map` is `[{discord_role_id, harmony_role_id}]`. It replaces the map and returns `readiness`.
    - Refuses with `42501` any Harmony role whose `position` is at or above the caller's highest role position, unless the caller is the server owner.
    - Refuses roles from another server, and the server's default (`is_default`) role.
    - For each row that is new or changed and Administrator-bearing (see Global Constraints): a non-owner gets `42501` with message `ADMIN_ROLE_OWNER_ONLY`; the owner without `p_confirm_admin` gets `ADMIN_ROLE_NEEDS_CONFIRM` (SQLSTATE `P0001`). Nothing is written in either case.
  - `discord_migration_admin_impact(p_server_id uuid, p_discord_role_id text) RETURNS jsonb` → `{holders: [{user_id, username, display_name}]}`: active links whose `discord_role_ids` contain the role. The confirm dialog lists them.
  - `discord_migration_permission_gaps(p_server_id uuid) RETURNS jsonb` → `[{discord_role_id, harmony_role_id, missing: text[]}]`, `missing` = names (via `permission_bit_names()`) of bits in the snapshot's `harmony_permissions` and not in the Harmony role's `permissions`, in bit order. Rows with nothing missing are omitted. Called inside `discord_migration_get`.
  - `discord_migration_apply_permissions(p_server_id uuid, p_discord_role_ids text[], p_confirm_admin boolean DEFAULT false) RETURNS jsonb` → `{updated: [{harmony_role_id, added: text[]}]}`. **`SECURITY INVOKER`**: it updates `server_roles SET permissions = permissions | <gap>` as the caller, so RLS and the role write guard enforce hierarchy and "grant only what you hold". Before writing: if any gap includes ADMINISTRATOR, owner-only and `p_confirm_admin` required, with the same messages as above. Add-only: never clears a bit. Requires `can_manage_discord_bridge`.
  - `discord_migration_readiness(p_server_id uuid) RETURNS jsonb` → `{ready bool, unmapped_roles: [{discord_role_id,name}], has_credentials bool, has_guild bool, roles_fetched bool}`. `ready` = credentials + guild + snapshot non-empty + zero unmapped roles. Permission gaps are warnings, never part of `ready`.
  - `discord_migration_set_state(p_server_id uuid, p_state text) RETURNS jsonb`: `draft|finished → open` requires `ready`, sets `opened_at`, clears `finished_at` and `sync_paused_reason`, and sets `sync_requested_at = now()`; `open → finished` sets `finished_at`; anything else raises.
  - `discord_migration_request_sync(p_server_id uuid) RETURNS void`: sets `sync_requested_at = now()`. Allowed only while `open`.
  - `discord_migration_auto_match(p_server_id uuid) RETURNS jsonb`: proposes `[{discord_role_id, harmony_role_id|null}]` by case-insensitive trimmed exact name. Writes nothing.

- [ ] **Step 1: Add failing assertions to 274.**
  - `auto_match` pairs "Moderator" with "moderator ".
  - `readiness.ready` is false with one unmapped role and lists it. After mapping it, `ready` is true.
  - A non-owner MANAGE_SERVER admin mapping a role above their own raises `42501`. The owner mapping it succeeds.
  - `admin_role_owner_only`: a Discord role whose snapshot has bit 0, mapped by a MANAGE_SERVER admin → `ADMIN_ROLE_OWNER_ONLY`, map unchanged; by the owner without confirm → `ADMIN_ROLE_NEEDS_CONFIRM`; with `p_confirm_admin => true` → saved. Re-saving the same map unchanged needs no confirm. Same rules for a Harmony role with `is_admin = true`.
  - `admin_impact` lists the one active link whose `discord_role_ids` contains the role, and not a `left` link.
  - `permission_gaps`: snapshot `harmony_permissions` = KICK_MEMBERS|BAN_MEMBERS|VIEW_CHANNEL, Harmony role has VIEW_CHANNEL → `missing = {KICK_MEMBERS,BAN_MEMBERS}`.
  - `apply_permissions_rules`:
    - the owner applies → the Harmony role gains exactly those bits and keeps a Harmony-only bit (e.g. PIN_MESSAGES) it had;
    - a MANAGE_SERVER admin without BAN_MEMBERS applying the same row raises `42501` and the role is unchanged;
    - a gap containing ADMINISTRATOR applied by the owner without confirm raises `ADMIN_ROLE_NEEDS_CONFIRM`, with confirm succeeds; by a non-owner holding ADMINISTRATOR raises `ADMIN_ROLE_OWNER_ONLY`.
  - `set_state(open)` while not ready raises `MIGRATION_NOT_READY`.
  - `open → finished → open` works and sets `sync_requested_at`.
  - `request_sync` while finished raises.
- [ ] **Step 2: Run `bash scripts/run-db-tests.sh`.** Expected: the new assertions fail.
- [ ] **Step 3: Implement the RPCs.**
- [ ] **Step 4: Run the tests and the check scripts.** Expected: pass and clean.
- [ ] **Step 5: Commit** `feat(db): discord migration role map, permission gaps and lifecycle`.

### Task 3: Claims, joining and Harmony moderation precedence

**Files:**
- Modify: `db_schema/migrations/20261112110001_discord_migration.sql`
- Create: `db_schema/tests/275_discord_migration_claim.sql`

**Interfaces:**
- Consumes: Task 1 tables, `server_bans`, `user_servers`, `user_roles`, `server_membership_events` (kick events use `event_type = 'member_kick'`, as `kick_server_member` writes).
- Produces:
  - `discord_migration_create_claim(p_server_id uuid, p_discord_user jsonb, p_discord_role_ids text[], p_discord_joined_at timestamptz) RETURNS jsonb` (service_role).
    - `p_discord_user` is `{id, username, global_name, avatar_url}`. Requires `state = 'open'`.
    - Returns `{ticket}` (new 64-hex ticket, 7-day expiry), or an error code: `MIGRATION_CLOSED`, `DISCORD_BANNED_HERE` (a link exists with status `banned`) or `ALREADY_LINKED_ELSEWHERE` (an active link for that Discord id belongs to another user; re-checked in claim).
    - Purges expired claims and link states; no cron is needed.
  - `discord_migration_bind_claim(p_ticket text, p_auth_user_id uuid) RETURNS void` (service_role): binds a ticket to the GoTrue user the gateway just created.
  - `discord_migration_claim(p_ticket text) RETURNS jsonb` (`authenticated`). Works out the caller's profile from `auth.uid()`, which must be a local profile.
    - If the claim has an `auth_user_id`, it must equal `auth.uid()` (else `42501`).
    - Refuses with `HARMONY_BANNED` (a `server_bans` row), `KICKED_ON_HARMONY` (link status), `DISCORD_ACCOUNT_TAKEN` (another user has this Discord id here), `ALREADY_LINKED_OTHER_DISCORD` (the caller is linked to a different Discord id here), `TICKET_EXPIRED`.
    - Otherwise: upserts the link as `active` with `discord_role_ids` and `discord_joined_at` from the claim; joins via `user_servers` (status accepted, bypassing invite-only) and writes a membership event; applies roles with Task 4's `discord_migration_apply_roles`; deletes the claim; returns `{server_id, roles_granted: [names]}`.
    - Re-claiming as the same user with the same Discord id is idempotent.
  - `discord_migration_my_pending_claims() RETURNS jsonb` (`authenticated`): tickets bound to `auth.uid()` and not expired.
  - `discord_migration_set_my_sync(p_server_id uuid, p_fields text[]) RETURNS void` (`authenticated`, own link only). Sets `sync_fields` and clears `last_synced_at`, so the next batch takes this link first.
  - `discord_migration_my_link(p_server_id uuid) RETURNS jsonb` (`authenticated`): the caller's own link `{status, sync_fields, discord_joined_at}` or null.
  - `discord_migration_member_joined_at(p_server_id uuid, p_user_id uuid) RETURNS timestamptz` (`authenticated`): the link's `discord_joined_at` when the caller is an accepted member of the server, else null.
  - `discord_migration_allow_back(p_server_id uuid, p_user_id uuid) RETURNS void` (`can_manage_discord_bridge`): `kicked_on_harmony → left`.
  - Trigger on `server_membership_events`: a `member_kick` event for a user with a link on that server, not written by the migration (the migration sets `SET LOCAL harmony.via = 'discord_migration'`), sets the link status to `kicked_on_harmony`.

- [ ] **Step 1: Write the failing test `275_discord_migration_claim.sql`.**
  - `claim_happy_path`: an open migration with a map `D1→Mod`, a claim with roles `[D1, D9]` (D9 unmapped) and a join date; bob claims, joins as accepted, holds Mod only, the link has `discord_role_ids = {D1,D9}` and the join date, and the claim row is gone.
  - `claim_respects_harmony_moderation`: with bob in `server_bans`, claim raises `HARMONY_BANNED` and creates no membership; after `kick_server_member(bob)` as an admin, the link becomes `kicked_on_harmony`, and a new claim raises `KICKED_ON_HARMONY`; after `allow_back`, claim succeeds.
  - `claim_conflicts`: carol claiming bob's Discord id raises `DISCORD_ACCOUNT_TAKEN`; bob claiming with a different Discord id raises `ALREADY_LINKED_OTHER_DISCORD`; bob's link is unchanged after both.
  - `ticket_expiry_and_retry`: an expired claim raises `TICKET_EXPIRED`; a fresh `create_claim` for the same Discord id then succeeds; a ticket bound to another auth user raises `42501`; `my_pending_claims` returns only the caller's unexpired tickets.
  - `claim_closed`: after `set_state(finished)`, `create_claim` returns `MIGRATION_CLOSED`.
  - `set_my_sync`: bob sets `{avatar,server_nickname}` on his link and `last_synced_at` becomes null; `{nickname}` raises; carol's call on bob's server changes nothing on bob's link.
  - `member_joined_at`: a member of the server reads bob's date; a non-member gets null.
- [ ] **Step 2: Run the DB tests.** Expected: 275 fails.
- [ ] **Step 3: Implement the RPCs and the trigger.**
- [ ] **Step 4: Run the DB tests and the check scripts.** Expected: pass and clean.
- [ ] **Step 5: Commit** `feat(db): discord migration claims and moderation precedence`.

### Task 4: Sync RPCs (roles, profile fields, timeouts, removals, brake)

**Files:**
- Modify: `db_schema/migrations/20261112110001_discord_migration.sql`
- Create: `db_schema/tests/276_discord_migration_sync.sql`

**Interfaces:**
- Consumes: `service_set_member_server_profile(p_server_id, p_user_id, p_fields jsonb)` (keys `nickname`, `avatar_url`, `banner_url`; null clears), `server_member_timeouts`, `server_audit_write`, `public.notifications (user_id, type, data)`.
- Produces (all service_role):
  - Extend `server_member_timeouts_source_check` to `IN ('moderator','automod','discord_migration')`.
  - `discord_migration_apply_roles(p_server_id uuid, p_user_id uuid, p_discord_role_ids text[]) RETURNS jsonb` → `{added: uuid[], removed: uuid[]}`. Returns empty arrays for the server owner and changes nothing. Otherwise: the target set is the map rows matching the ids; adds missing target roles and removes every *mapped* Harmony role the user holds that is not in the target, including hand-granted ones. Roles not in the map are never touched. Audit `member.role_add`/`member.role_remove`, source `system`, via `discord_migration`. For an Administrator-bearing role added or removed, also `details->>'administrator' = 'true'` and a notification to the owner: type `discord_migration_admin_change`, data `{server: {id, name}, user: {id, username}, role: {id, name}, change: 'granted'|'removed'}`. Task 3 also calls it.
  - `discord_migration_due(p_limit int DEFAULT 20, p_interval interval DEFAULT '6 hours') RETURNS SETOF jsonb`: open, unpaused migrations where `sync_requested_at IS NOT NULL` or `last_sync_at < now() - p_interval`, oldest first.
  - `discord_migration_links_batch(p_server_id uuid, p_limit int) RETURNS SETOF jsonb`: active links ordered by `last_synced_at NULLS FIRST`, with `{user_id, discord_user_id, sync_fields}`.
  - `discord_migration_sync_member(p_server_id uuid, p_user_id uuid, p_member jsonb) RETURNS jsonb`. `p_member` is `{present: bool, banned: bool|null, role_ids: text[], joined_at, timed_out_until: timestamptz|null, profile: {avatar_url, display_name, banner_url, accent_color: int|null, server_nickname, server_avatar_url, server_banner_url}}`. Profile values are already mirrored by the gateway; a key absent from `profile` means "not fetched", not "clear".
    - Present: `apply_roles`; store `discord_role_ids`, `discord_joined_at`; stamp `last_synced_at`.
    - Profile, only for fields in the link's `sync_fields`: `avatar` → `profiles.avatar_url`, `display_name` → `profiles.display_name`, `banner` → `profiles.banner_url`, `accent_color` → `profiles.color` as `#rrggbb` (null leaves it). `server_nickname`/`server_avatar`/`server_banner` → one `service_set_member_server_profile` call with the opted-in keys among `nickname`, `avatar_url`, `banner_url` (a Discord null clears that key).
    - Timeouts: when `timed_out_until > now()` and there's no running timeout with another source, upsert the row with `source = 'discord_migration'`, `until`, reason `Timed out on Discord`, audit `member.timeout`. A running `moderator`/`automod` timeout is left exactly as it is. When `timed_out_until` is null or past, delete only a `discord_migration` row (audit `member.timeout_remove`).
    - Not present: return `{candidate: 'left'|'banned'}`; remove nothing here.
  - `discord_migration_apply_removals(p_server_id uuid, p_candidates jsonb) RETURNS jsonb`. `p_candidates` is `[{user_id, kind}]`.
    - **Brake:** if `count >= 3 AND count * 100 > removal_brake_pct * active_links`, set `sync_paused_reason = 'removal_brake'`, apply nothing, return `{paused: true}`.
    - Otherwise, with `SET LOCAL harmony.via = 'discord_migration'`, for each candidate: skip and log if the user is the owner or holds an ADMINISTRATOR role. Otherwise kick (`left`, reason "Left the Discord server") or ban (`banned`, reason "Banned on the Discord server") and set the link status.
    - If `kick_server_member`/`ban_server_member` check the caller, factor their bodies into internal `_kick_server_member_internal(p_server_id, p_user_id, p_reason)` / `_ban_server_member_internal(...)` that both the public RPC and this one call. Keep public signatures unchanged.
  - `discord_migration_pause(p_server_id uuid, p_reason text) RETURNS void`: sets the reason and writes an audit row `discord_migration.paused` (source `system`).
  - `discord_migration_record_sync(p_server_id uuid, p_result jsonb, p_cursor timestamptz) RETURNS void`: sets `last_sync_at`, `last_sync_result`, `sync_cursor`; clears `sync_requested_at`.

- [ ] **Step 1: Write the failing test `276_discord_migration_sync.sql`.**
  - `role_diff`: bob holds Mod (mapped, hand-granted) and Artist (unmapped); `sync_member` with `role_ids = []` removes Mod and keeps Artist.
  - `remap_moves_roles`: map `D1→Mod`, bob synced with `[D1]` holds Mod; remap `D1→Helper`; sync again leaves bob with Helper and without Mod.
  - `owner_untouched`: the owner linked with `role_ids = [D1]` gains nothing and keeps a hand-granted mapped role with `role_ids = []`.
  - `admin_grant_notifies_owner`: `D2→Admins` (Harmony role with ADMINISTRATOR) — bob synced with `[D2]` gets Admins, one `discord_migration_admin_change` notification for the owner with `change = 'granted'` and an audit row with `details->>'administrator' = 'true'`; synced with `[]` removes it with a second notification `removed`; bob stays a member.
  - `profile_opt_in`: with `sync_fields = '{}'` nothing changes; with `{display_name, accent_color, server_nickname}` display name, `profiles.color = '#ff0000'` (from `16711680`) and `user_servers.nickname` update and `profiles.avatar_url` does not; a `profile` without `server_nickname` key leaves the nickname.
  - `timeouts_respect_harmony`: a Discord timeout creates a `discord_migration` row; a later null removes it; a running `moderator` timeout is unchanged by a Discord timeout and by a Discord null.
  - `brake_and_pause`: 10 active links and 2 candidates: removals apply (below the minimum of 3); 30 links and 4 candidates (13% > 10%): `paused = true`, `sync_paused_reason = 'removal_brake'`, all 4 still members; `discord_migration_due` no longer returns that server.
  - `never_removes_owner_or_admin`: an owner candidate and an ADMINISTRATOR-role candidate are both still members afterwards.
  - `removal_kinds`: a `left` candidate is removed with the link `left` and is **not** marked `kicked_on_harmony`; a `banned` candidate gets a `server_bans` row with the link `banned`.
  - `due_ordering`: a migration with `sync_requested_at` set is returned; a finished one is not.
- [ ] **Step 2: Run the DB tests.** Expected: 276 fails.
- [ ] **Step 3: Implement the RPCs, including the internal kick/ban refactor if needed.**
- [ ] **Step 4: Run all DB tests, including the existing kick/ban/timeout tests, then the check scripts.** Expected: pass and clean.
- [ ] **Step 5: Commit** `feat(db): discord migration sync RPCs`.

### Task 5: Gateway Discord REST client and permission translation

**Files:**
- Create: `bot-gateway/src/migration/discordClient.ts`
- Create: `bot-gateway/src/migration/discordPermissions.ts`
- Create: `bot-gateway/src/migration/__tests__/discordClient.test.ts`
- Create: `bot-gateway/src/migration/__tests__/discordPermissions.test.ts`
- Modify: `bot-gateway/src/bridge/instanceBot.ts` (`DISCORD_API_BASE = process.env.DISCORD_API_BASE ?? 'https://discord.com/api/v10'`, for the e2e stub)

**Interfaces:**
- Produces (`discordClient.ts`):
  - `type DiscordResult<T> = { ok: true; value: T } | { ok: false; status: number; reason: string }`
  - `migrateAuthorizeUrl(clientId: string, redirectUri: string, state: string): string` (scope `identify`, `response_type=code`, `prompt=none`; host from `DISCORD_OAUTH_BASE ?? 'https://discord.com'`)
  - `exchangeUserCode(input: {clientId; clientSecret; code; redirectUri}): Promise<DiscordResult<{ accessToken: string }>>`
  - `fetchCurrentUser(accessToken: string): Promise<DiscordResult<DiscordUser>>`
  - `fetchUser(botToken: string, userId: string): Promise<DiscordResult<DiscordUser>>` (`GET /users/{id}`)
  - `DiscordUser = { id; username; global_name: string|null; avatar_url: string|null; banner_url: string|null; accent_color: number|null }`. URLs from hashes: `https://cdn.discordapp.com/avatars/{id}/{hash}.png?size=256`, `https://cdn.discordapp.com/banners/{id}/{hash}.png?size=600`; null hash → null.
  - `fetchGuildRoles(botToken, guildId): Promise<DiscordResult<DiscordRole[]>>`: excludes `managed` roles and `@everyone`. `DiscordRole = { id; name; color; position; permissions: string }`.
  - `fetchGuildMember(botToken, guildId, userId): Promise<DiscordResult<{ present: false } | { present: true; member: DiscordMember }>>` with `DiscordMember = { role_ids: string[]; nick: string|null; joined_at: string; timed_out_until: string|null; guild_avatar_url: string|null; guild_banner_url: string|null; user: DiscordUser }`. Guild media URLs: `https://cdn.discordapp.com/guilds/{g}/users/{u}/avatars/{hash}.png?size=256` and `…/banners/{hash}.png?size=600`. A 404 with Discord code 10007 is `present: false`; any other 404 (10004) is a failure.
  - `fetchBan(botToken, guildId, userId): Promise<DiscordResult<boolean>>`: 404 → false; 403 → `{ok:false,status:403}`.
  - `fetchBotPermissionsInGuild(botToken, guildId): Promise<DiscordResult<{ canBan: boolean }>>`: BAN_MEMBERS `1n << 2n` or ADMINISTRATOR `1n << 3n` from the bot's member roles.
  - `fetchApplication(botToken): Promise<DiscordResult<{ id: string }>>` (`GET /oauth2/applications/@me`).
  - `fetchGuildChannels(botToken, guildId): Promise<DiscordResult<Array<{ id: string; name: string; permission_overwrites: Array<{ type: number }> }>>>`.
  - All calls take `{ fetchImpl?: typeof fetch, timeoutMs?: number }`. On 429 they wait `retry_after` seconds once and retry; a second 429 fails with `reason: 'rate_limited'`.
- Produces (`discordPermissions.ts`):
  - `DISCORD_TO_HARMONY: ReadonlyArray<[bigint, string]>`: the bridge's table (`harmony-discord-bridge/src/utils/discordPermissions.ts`, `DISCORD_TO_HARMONY_PERMISSION`, 33 rows) **plus** `[PermissionFlagsBits.Administrator (1n << 3n), 'ADMINISTRATOR']`. Copy the flag values as bigint literals; don't add discord.js to the gateway.
  - `toHarmonyMask(discordPermissions: string, bitNames: string[]): string`: decimal string; a name not in `bitNames` is skipped. `bitNames` comes from the `permission_bit_names` RPC.

- [ ] **Step 1: Write failing vitest tests with a stubbed `fetchImpl`.**
  - The authorize URL contains `scope=identify` and the state.
  - Roles exclude managed roles and the role whose id equals the guild id, and keep `permissions`.
  - A member 404/10007 maps to present false; 404/10004 maps to ok false. A member payload maps `nick`, `joined_at`, `communication_disabled_until`, guild avatar and banner URLs.
  - `fetchUser` maps banner and `accent_color`; null hashes give null URLs.
  - A ban 403 is ok false with status 403. A 429 then 200 succeeds after one retry, and 429 twice fails `rate_limited`.
  - `toHarmonyMask`: Discord Administrator alone → `'1'`; KickMembers|BanMembers → bits 9 and 10 (`'1536'`); with a `bitNames` list lacking `MANAGE_NICKNAMES`, ManageNicknames is skipped; ChangeNickname → `'4294967296'`.
- [ ] **Step 2: Run `cd bot-gateway && npx vitest run src/migration`.** Expected: fail.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the same command, then `npm run typecheck` in `bot-gateway`.** Expected: pass.
- [ ] **Step 5: Commit** `feat(gateway): discord REST client and permission translation for migrations`.

### Task 6: Gateway migration API (setup checks, OAuth, account creation, sync-me)

**Files:**
- Create: `bot-gateway/src/api/MigrationAPI.ts`
- Create: `bot-gateway/src/api/__tests__/MigrationAPI.test.ts`
- Modify: `bot-gateway/src/index.ts` (mount `app.use('/bridge/v2/migrate', new MigrationAPI().router)` next to the BridgeV2API mount)

**Interfaces:**
- Consumes: Task 5; RPCs `discord_migration_credentials`, `discord_migration_store_discord_roles`, `discord_migration_store_warnings`, `discord_migration_create_claim`, `discord_migration_bind_claim`, `permission_bit_names`; `supabase.auth.admin.createUser`; `supabase.auth.getUser(jwt)`; `can_manage_discord_bridge` checked through an RPC made with the admin's JWT. A `syncOne(serverId: string, userId: string): Promise<void>` dependency injected through `new MigrationAPI({ syncOne })`; tests pass a fake, and Task 7 wires the real runner in `index.ts`.
- Produces routes (base `/bridge/v2/migrate`; rate-limit each like `linkLimiter` in `BridgeV2API.ts`):
  - `POST /:serverId/refresh` (Bearer = the admin's Supabase JWT): resolves credentials; fetches roles, translates each role's permissions with `toHarmonyMask` and stores the snapshot; writes `bot_can_ban`; for the `bridge` source with no `client_id`, fills it from `fetchApplication`; writes `readiness_warnings = {unpaired_channels: [names not in discord_bridge_channels for this server], member_overrides: <count of permission_overwrites with type 1>}`; returns `{ok, roles: n, canBan}` or `{ok:false, error}` with `error ∈ credentials_missing | token_invalid | bot_not_in_guild`.
  - `GET /authorize?token=<link_token>` → 302 to Discord, link state stored for 10 minutes. Not open → 302 `/migrate/<token>?error=closed`.
  - `GET /callback?code&state`: exchanges the code, fetches the user, fetches the guild member with the bot; absent → `/migrate/<token>?error=not_member`; otherwise `create_claim` with the member's role ids and `joined_at`, then 302 `/migrate/<token>?ticket=<ticket>`; claim error codes pass through as `error=<code lowercased>`.
  - `POST /account` with body `{ticket, username, email, password}`: 403 `account_creation_disabled` when the instance switch is false; checks the ticket is valid and unbound; 409 `username_taken` among local profiles; `auth.admin.createUser({ email, password, email_confirm: false, user_metadata: { username } })`, an existing email → 409 `email_exists`; `bind_claim`; returns `{ ok: true, needsConfirmation }` (from the instance's confirmation setting the frontend uses for registration; if none is exposed, `GOTRUE_MAILER_AUTOCONFIRM`).
  - `POST /me/:serverId/sync` (Bearer = the member's JWT): for the caller's active link on an open migration, runs `syncOne(serverId, userId)` now; 404 without a link. Lets profile-sync toggles take effect at once.
- Redirect URI: `${configuredBaseUrl}/bridge/v2/migrate/callback` (the base URL helper `BridgeV2API` uses for `discordCallbackUrl`).

- [ ] **Step 1: Write failing tests with supertest, mocking `supabase` and the Task 5 client the way `BridgeV2API.instance.test.ts` mocks them.**
  - `authorize` for an open token redirects to discord.com with state; for a finished token it redirects with `error=closed`.
  - `callback` for a non-member redirects with `error=not_member` and never calls `create_claim`; for a member it redirects with a ticket and passes `joined_at`.
  - A bad or expired state → 400.
  - `account`: switch off → 403; username taken → 409; existing email → 409 `email_exists`; success → `createUser` with `email_confirm: false`, then `bind_claim`.
  - `refresh`: without a JWT → 401; a non-manager → 403; success stores roles with `harmony_permissions` computed from `permission_bit_names`.
  - `me/:serverId/sync`: without a link → 404; with one → `syncOne` called once.
- [ ] **Step 2: Run `cd bot-gateway && npx vitest run src/api/__tests__/MigrationAPI.test.ts`.** Expected: fail.
- [ ] **Step 3: Implement and mount.**
- [ ] **Step 4: Run the gateway tests and `npm run type-check`.** Expected: pass.
- [ ] **Step 5: Commit** `feat(gateway): discord migration OAuth and account API`.

### Task 7: Gateway sync runner

**Files:**
- Create: `bot-gateway/src/migration/MigrationSyncRunner.ts`
- Create: `bot-gateway/src/migration/__tests__/MigrationSyncRunner.test.ts`
- Modify: `bot-gateway/src/index.ts` (start the runner after `bridgeConfigWatcher.start()`, and pass `runner.syncOne` to `MigrationAPI`); `bot-gateway/src/config/supabase.ts` (`config.migration = { syncMs, pollMs: 30_000, budgetMs: 120_000, batch: 100 }`, `syncMs` from env `DISCORD_MIGRATION_SYNC_MS`, default 21600000)

**Interfaces:**
- Consumes: Task 4 RPCs; Task 5 client; `mirrorExternalMediaToStorage`.
- Produces: `class MigrationSyncRunner { constructor(deps?: { rpc?, discord?, mirror?, now?, intervalMs? }); start(): void; stop(): void; runOnce(): Promise<void>; syncServer(m: DueMigration): Promise<SyncResult>; syncOne(serverId: string, userId: string): Promise<void> }`. `SyncResult = { checked: number; rolesChanged: number; removed: number; timeouts: number; paused?: string; errors: number }`.
- Algorithm for `syncServer`:
  1. Resolve credentials. On `MIGRATION_CREDENTIALS_MISSING` → `pause('credentials_missing')`.
  2. Fetch the guild roles, translate and store the snapshot. A 401 → `pause('token_invalid')`; 403/404 → `pause('bot_not_in_guild')`. Return without touching members.
  3. Loop `links_batch` until `budgetMs` elapses or no links remain. Per link, `fetchGuildMember`:
     - present → build `p_member`. Fetch `fetchUser` only when `sync_fields` has `banner` or `accent_color`. Mirror only the media of opted-in fields. Include a `profile` key only for opted-in fields. Then `sync_member`.
     - absent → ban check if `bot_can_ban`, then collect the candidate;
     - a non-404 failure → `errors++` and continue. If more than half of the members checked so far failed, stop and pause `discord_errors`.
  4. `apply_removals(candidates)`, then `record_sync(result, cursor)`.
  - `syncOne` does step 3 for one link without removals or the brake (an absent member is left for the next full run).
  - `runOnce` takes `discord_migration_due` and syncs each server sequentially. The timer runs `runOnce` every `pollMs`, never overlapping (an in-flight guard).

- [ ] **Step 1: Write failing tests with injected fakes.**
  - `pauses_on_guild_403`: the roles fetch returns 403 → `pause('bot_not_in_guild')`; neither `sync_member` nor `apply_removals` is called.
  - Absent members become candidates passed in one `apply_removals` call. A ban check happens only when `bot_can_ban`.
  - With `sync_fields = ['server_avatar']`, only the guild avatar is mirrored, `fetchUser` is not called, and `p_member.profile` has only `server_avatar_url`.
  - `timed_out_until` and `joined_at` are passed through from the member.
  - The budget stops the loop and `record_sync` still runs.
  - More than half of member fetches erroring → paused `discord_errors`, and no removals are applied.
  - `runOnce` doesn't overlap when called twice concurrently. `syncOne` never calls `apply_removals`.
- [ ] **Step 2: Run `cd bot-gateway && npx vitest run src/migration`.** Expected: fail.
- [ ] **Step 3: Implement and wire into `index.ts`.**
- [ ] **Step 4: Run the gateway tests and `npm run type-check`.** Expected: pass.
- [ ] **Step 5: Commit** `feat(gateway): scheduled discord migration sync`.

### Task 8: Server settings panel

**Files:**
- Create: `src/components/settings/discord-migration/DiscordMigrationSettings.vue`
- Create: `src/components/settings/discord-migration/AdminRoleConfirmDialog.vue`
- Create: `src/components/settings/discord-migration/migrationApi.ts`
- Create: `src/components/settings/discord-migration/__tests__/DiscordMigrationSettings.test.ts`
- Modify: `src/views/ServerSettings.vue` (nav entry after `discord-bridge`: `{ id: 'discord-migration', label: t('discordMigration.navLabel'), group: 'integrations' }` gated on `p.canEditBasicInfo`, as the bridge is; a `v-if` panel and an import)
- Modify: `src/locales/*.json` (7 files)

**Interfaces:**
- Consumes: Task 1–3 RPCs via `supabase.rpc`; the gateway `POST /bridge/v2/migrate/:serverId/refresh` (gateway base URL as `src/components/settings/discord-bridge/bridgeApi.ts` gets it).
- Produces: `migrationApi.ts` typed wrappers: `getMigration(serverId)`, `saveMigration(...)`, `setOwnCredentials(...)`, `refreshFromDiscord(serverId)`, `autoMatch(serverId)`, `saveRoleMap(serverId, map, confirmAdmin = false)`, `adminImpact(serverId, discordRoleId)`, `applyPermissions(serverId, discordRoleIds, confirmAdmin = false)`, `setState(serverId, state)`, `requestSync(serverId)`, `regenerateToken(serverId)`, `allowBack(serverId, userId)`.
- UI sections, in order:
  1. Prerequisites text: clone the server first; the bot must be in the Discord server.
  2. Credential source radio (options hidden when unavailable): `instance_bot`, `bridge`, `own`. `own` shows client ID, client secret and bot token fields (secrets write-only) and the redirect URI to copy.
  3. Discord server ID.
  4. "Fetch from Discord" (`refresh`), errors inline; a notice when `bot_can_ban` is false.
  5. Role map table: one row per Discord role (color dot + name) → Harmony role `<select>`, prefilled from `autoMatch`, "matched by name" hint, no ignore option, counter "N roles still need a match". Rows involving Administrator show a shield badge and, for non-owners, a disabled select with "Only the server owner can map roles with Administrator".
  6. Permission gaps: per mapped row with `missing`, the missing permission names and **Apply Discord permissions**; an **Apply all** button above. A server error from the role rules is shown inline on that row.
  7. Saving a map or applying a gap that needs confirmation opens `AdminRoleConfirmDialog`: lists `adminImpact` holders ("These members will become admins of this server"), says that anyone given this role on Discord while the migration is open becomes an admin too, and on confirm retries with `confirmAdmin = true`.
  8. Readiness warnings, then **Open migration**, disabled until `readiness.ready`.
  9. While open: the link with copy and regenerate; last sync time and result; the pause reason with a "Resume" button (`set_state('open')`); **Sync now**; a member list (user, Discord id, status, last synced) with **Allow back** on `kicked_on_harmony` rows; **Finish migration** behind a confirm dialog.
  10. While finished: a **Re-open** confirm that warns mapped roles and membership will follow Discord again.

- [ ] **Step 1: Write failing component tests (vitest + @vue/test-utils, mocking `migrationApi`).**
  - The Open button is disabled while `readiness.unmapped_roles` is non-empty, and the counter shows 2.
  - Choosing a Harmony role calls `saveRoleMap` with `{discord_role_id, harmony_role_id}`. The role select has no "ignore" option.
  - A gap row shows "Kick Members, Ban Members" and its Apply button calls `applyPermissions(serverId, ['D1'], false)`.
  - `saveRoleMap` rejecting `ADMIN_ROLE_NEEDS_CONFIRM` opens the dialog listing the holder from `adminImpact`; confirming calls `saveRoleMap(..., true)`.
  - For a non-owner, an Administrator row's select is disabled with the owner-only text.
  - The open state renders the link containing `link_token`, and the Finish confirm calls `setState('finished')`. A finished state shows Re-open and its warning. A `kicked_on_harmony` row shows Allow back.
- [ ] **Step 2: Run `npx vitest run src/components/settings/discord-migration`.** Expected: fail.
- [ ] **Step 3: Implement, register in `ServerSettings.vue`, and add the i18n keys in all 7 locales.**
- [ ] **Step 4: Run the tests, `npx vue-tsc --noEmit -p tsconfig.app.json` and eslint on the touched files.** Expected: pass.
- [ ] **Step 5: Commit** `feat(ui): Migrate from Discord server settings`.

### Task 9: Public migrate page, pending claims, sync toggles, join date, owner notification

**Files:**
- Create: `src/views/MigrateFromDiscordView.vue`
- Create: `src/services/discordMigrationService.ts`
- Create: `src/views/__tests__/MigrateFromDiscordView.test.ts`
- Create: `src/components/settings/user/DiscordSyncFields.vue` (the seven toggles; used on the result screen and in Settings → Server profiles)
- Modify: `src/router/index.ts` (public route `/migrate/:token`, no auth guard; same meta as `/invite/:code`)
- Modify: `src/views/NewProfile.vue` (after a successful `createProfile`, call `completePendingClaims()`; prefill the username from `?username=`)
- Modify: the post-sign-in hook (where the session is established after login; find it from `LoginView.vue`/the auth store) to call `completePendingClaims()` when a profile exists
- Modify: `src/components/settings/user/ServerProfilesSettings.vue` (below the fields: "Sync from Discord" with `DiscordSyncFields` when `discord_migration_my_link` returns a link for the selected server)
- Modify: `src/components/common/UnifiedProfileCard.vue` (in a server context, "Joined on Discord <date>" from `discord_migration_member_joined_at`, next to "Member since")
- Modify: `src/components/NotificationItem.vue`, `src/services/NotificationFormatter.ts` (type `discord_migration_admin_change`, icon `shield`, tone `system`: "<user> was given / lost the admin role <role> in <server> by the Discord migration")
- Modify: `src/locales/*.json` (7 files)
- Add RPC: `discord_migration_preview(p_token text) RETURNS jsonb` (anon + authenticated) in the migration file: `{server_name, icon_url}` only when `state = 'open'`, else null. Add an assertion to `275`.

**Interfaces:**
- Consumes: gateway `/bridge/v2/migrate/authorize`, `/account`, `/me/:serverId/sync`; RPCs `discord_migration_preview`, `discord_migration_claim`, `discord_migration_my_pending_claims`, `discord_migration_set_my_sync`, `discord_migration_my_link`, `discord_migration_member_joined_at`.
- Produces: `discordMigrationService.ts` exports `preview(token)`, `startDiscordSignIn(token)`, `createAccount({ticket, username, email, password})`, `claim(ticket)`, `completePendingClaims(): Promise<ClaimResult[]>`, `myLink(serverId)`, `setMySync(serverId, fields: SyncField[])` (calls the RPC, then the gateway `me/:serverId/sync`), `memberJoinedAt(serverId, userId)`. `type SyncField = 'avatar' | 'display_name' | 'banner' | 'accent_color' | 'server_nickname' | 'server_avatar' | 'server_banner'`.
- Page states, driven by the query string:
  1. No `ticket` and no `error` → landing: server icon and name; signed in: "You'll link this to @username" (not you? sign out) plus "Sign in with Discord"; signed out: "I have a Harmony account" (to `/login?redirect=/migrate/<token>`) and "I'm new: continue with Discord".
  2. `error=` → the matching message for: `closed`, `not_member`, `harmony_banned`, `kicked_on_harmony`, `discord_banned_here`, `discord_account_taken`, `already_linked_other_discord`, `ticket_expired` (offer "start again"), `account_creation_disabled`.
  3. `ticket=` and signed in → `claim`, then the result (roles received) with `DiscordSyncFields`.
  4. `ticket=` and signed out → the new-account form (username prefilled from Discord, email, password, `DiscordSyncFields`). On submit, `createAccount`: `needsConfirmation` → "Confirm your email, then sign in. You'll join automatically."; otherwise `signInWithPassword`, then NewProfile with `?username=`, which completes the claim. Chosen toggles are kept in `localStorage` keyed by ticket and applied after the claim.
  5. A federated (non-local) signed-in session → "Create an account on this instance to migrate."

- [ ] **Step 1: Write failing tests (mock the service).**
  - Signed out with no ticket shows both buttons. `error=harmony_banned` shows the banned message and no sign-in button.
  - Signed in with a ticket calls `claim` once and shows the granted role names.
  - Signed out with a ticket shows the form; a `createAccount` 409 `username_taken` shows an inline error; `needsConfirmation` shows the confirm-email message.
  - `completePendingClaims` calls `claim` for each pending ticket and swallows `TICKET_EXPIRED`.
  - `DiscordSyncFields` toggling `server_nickname` calls `setMySync(serverId, ['server_nickname'])`.
  - `NotificationFormatter` formats a `discord_migration_admin_change` with `change: 'granted'`.
  - In 275: `preview` returns null for a finished migration.
- [ ] **Step 2: Run `npx vitest run src/views/__tests__/MigrateFromDiscordView.test.ts src/services/__tests__/NotificationFormatter.test.ts` and the DB tests.** Expected: fail.
- [ ] **Step 3: Implement the view, service, toggles, route, NewProfile and post-login hooks, profile card date, and notification type.**
- [ ] **Step 4: Run the tests, typecheck and eslint.** Expected: pass.
- [ ] **Step 5: Commit** `feat(ui): public migrate-from-Discord page, sync toggles and claim completion`.

### Task 10: Operator switch, docs, stubbed end-to-end

**Files:**
- Modify: `self-host/admin.sh` and `self-host/harmony`: `harmony migration account-creation [on|off]` writes `instance_config.discord_migration_account_creation`
- Create: `docs/run/discord-migration.md` (owners: prerequisites (clone via `/bridge clone-server` + `/bridge sync-perms`), credential options, redirect URI, role mapping rule ("every non-managed Discord role must map; delete roles you don't want on Discord before, or on Harmony after"), **why Apply Discord permissions exists** (bridge bots can't grant moderator permissions) and the Administrator rules, what syncs (roles, membership, timeouts, opt-in fields), Harmony moderation precedence, the brake, finishing and re-opening)
- Modify: `docs/self-host/discord-bridges.md` (the instance bot's Discord app must also list `<base>/bridge/v2/migrate/callback` as a redirect; `DISCORD_MIGRATION_SYNC_MS`; the CLI switch); `docs/self-host/cli.md`
- Modify: `CHANGELOG.md`
- Create: `e2e/specs/discord-migration.spec.ts` and a Discord stub server it starts (pattern: `e2e/backend-server.ts`)

**Interfaces:**
- Consumes: everything above. The gateway reads `DISCORD_API_BASE` and `DISCORD_OAUTH_BASE` (Task 5) and the spec points them at the stub.

- [ ] **Step 1: Write the failing e2e happy path.** The owner configures `own` credentials, fetches roles, maps the one unmatched role, applies its missing Kick Members permission and opens. A new user follows the link: stub Discord consent → form → account → NewProfile → lands in the server holding the mapped role. The owner presses Sync now after the stub drops that role, and the role is gone.
- [ ] **Step 2: Run `BASE_URL=http://localhost:<free port> npx playwright test --project=journeys e2e/specs/discord-migration.spec.ts` against a stack with its own `E2E_PROJECT`/`E2E_PORT`.** Expected: fail before the stub and env wiring, pass after.
- [ ] **Step 3: Add the CLI switch, docs and changelog entry.**
- [ ] **Step 4: Run `npm run docs:lint`, the DB tests, `npx vitest run`, `cd bot-gateway && npm test`, and the e2e spec.** Expected: all pass (Windows-only vitest failures excepted; CI is the authority).
- [ ] **Step 5: Commit** `feat: discord migration operator switch, docs, e2e`.

### Task 11: Live test against Discord (manual, before merge)

**Files:** none committed. Scripts go in the session scratchpad; the kit lives in `C:\Users\Rudhra\.config\harmony-test\kit` (README there), credentials in `C:\Users\Rudhra\.config\harmony-test\discord-bot.env` (never print them).

- [ ] **Step 1: Ask the user to add the OAuth redirect.** In the Discord Developer Portal, application "Harmony Test Bridge" → OAuth2 → Redirects, add `http://localhost:<gateway port>/bridge/v2/migrate/callback` (the gateway's configured base URL plus the path). Wait for confirmation.
- [ ] **Step 2: Bring up an isolated stack.** Own `E2E_PROJECT` and `E2E_PORT` and own `/tmp` copy (the kit's `stack.sh` shares `/tmp/hstack`); check `docker ps` first and don't touch other projects. Gateway on a port other than 3002. Any bridge run uses its own `DATA_DIR`. Before starting a bridge with the test bot, make sure no other session is running one (it re-registers slash commands).
- [ ] **Step 3: Owner setup with `own` credentials** (test bot token, application id and client secret), Discord server = ROODAY Dev Server. Create temporary Discord roles: one with Kick/Ban/Manage Messages, one with Administrator. Check: auto-match, the gap list and Apply, the owner-only Administrator rule as a MANAGE_SERVER admin, the confirm dialog as owner.
- [ ] **Step 4: Member flows with real Discord accounts (the user signs in in a browser):** a new account; an existing Harmony account linking; a Discord account not in the server (refused); the same Discord account on a second Harmony account (refused).
- [ ] **Step 5: Sync:** give and remove a role on Discord, Sync now, check Harmony; time out a member on Discord and lift it; turn on `server_nickname` and change the Discord nickname; check the owner's notification when the Administrator role is granted.
- [ ] **Step 6: Membership:** kick the test member on Discord → removed on Harmony at the next sync; kick on Harmony → link refused until Allow back.
- [ ] **Step 7: Finish → the link page shows closed; Re-open → syncs at once.**
- [ ] **Step 8: Clean up:** delete the temporary Discord roles and any test webhooks, stop the bridge and gateway, tear down only your own stack. Live UI updates (Realtime) are checked on the user's full deploy, not on the e2e stack.
