# Server Profiles, Pronouns and Nickname Permissions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Account pronouns; per-server nickname, avatar, banner, bio and pronouns shown everywhere inside a server; Change Nickname / Manage Nicknames permissions; and the Discord bridge's permission map brought up to date.

**Architecture:** Server profile fields live on `user_servers` next to the existing `nickname`. Writes go only through RPCs (self, moderator, service). The client loads the fields with the member data it already fetches. A server-aware resolver (`getMember*` functions taking `serverId | null`) replaces account-profile lookups at every in-server call site. Two permission bits are appended (32, 33) following the soundboard migration's pattern. Pronouns federate as a `PropertyValue`. The bridge maps Discord permissions by Harmony permission *name*, using the bit list the gateway now publishes in `/bridge/v2/config`.

**Tech Stack:** PostgreSQL/Supabase + pgTAP (`bash scripts/run-db-tests.sh`), Vue 3 + Pinia + vue-i18n + vitest (`npm test`), Express gateway + vitest (`cd bot-gateway && npm test`), federation-backend vitest (`npm run test:federation`), Playwright (`npx playwright test`), the bridge repo (discord.js, vitest).

**Spec:** `docs/superpowers/specs/2026-11-09-server-profiles-design.md`

## Global Constraints

- Migrations:
  - `db_schema/migrations/20261111950001_nickname_permissions.sql`;
  - `db_schema/migrations/20261111950002_server_profiles.sql`.
  - House style: idempotent ("converges by state"), `BEGIN; SET LOCAL lock_timeout …; COMMIT;`, `NOTIFY pgrst, 'reload schema'`, and `SET search_path = public, extensions, pg_temp` on every SECURITY DEFINER.
- pgTAP files:
  - `db_schema/tests/269_nickname_permissions.sql`;
  - `db_schema/tests/270_server_profiles.sql`.
  - The parked Discord-migration plan must renumber its tests when it is revised.
- After adding RPCs, regenerate `db_schema/SURFACE.tsv` (`scripts/generate-surface.sh`) and `REACHABILITY.tsv` (`scripts/generate-reachability.sh`). Then run `scripts/check-rpc-coverage.sh` and `scripts/check-plpgsql.sh`.
- Permission bits: `CHANGE_NICKNAME = 32`, `MANAGE_NICKNAMES = 33`, appended after `USE_EXTERNAL_SOUNDS` (31).
- Limits:
  - pronouns (account and server) ≤ 40 chars;
  - server bio ≤ 500 chars;
  - nickname ≤ 64 (existing).
  - Values are trimmed, and empty becomes NULL.
- Server profile fields: `nickname`, `avatar_url`, `banner_url`, `bio`, `pronouns`. NULL means "use the account value".
- Server profiles never leave their server. They don't appear in DMs, friends or fediverse/post surfaces, and are never federated.
- Audit log and moderation screens show the account display name and @username, with the nickname in parentheses.
- Writes are for accepted memberships only.
- All strings go through vue-i18n (`src/locales/en.json`). Keys: `serverProfile.*`, `permissions.CHANGE_NICKNAME`, `permissions.MANAGE_NICKNAMES`, `profile.pronouns`.
- Bridge-repo work happens only in a git worktree of `C:\Users\Rudhra\Code\harmony-discord-bridge` (never its main checkout).

## Review Focus

1. **A DM opened beside a server channel.** The DM shows the account name and avatar while the channel shows the nickname and server avatar, both at once. Pinned by Task 4 `resolver_is_per_call` and the Task 8 e2e DM assertion.
2. **Revoking Change Nickname from @everyone after people set nicknames.** Nicknames still show, the Server profiles tab is locked (Reset disabled), and moderators can still reset. Pinned by Task 2 `self_edit_requires_permission` and Task 6 `locked_tab`.
3. **A moderator tries to rename the owner or someone with a higher role.** Refused, and nothing changes. Pinned by Task 2 `moderate_respects_hierarchy`.
4. **A brand-new server, or one created from a template or the bridge clone, right after this ships.** Its @everyone has Change Nickname. Pinned by Task 1 `fresh_server_default_role` and `template_default_role`.
5. **A remote user's "Pronouns" profile field is 200 characters or contains HTML.** Stored pronouns are plain text, truncated to 40, and the profile field itself is left as is. Pinned by Task 3 `inbound_pronouns_sanitised`.

---

### Task 1: Nickname permission bits

**Files:**
- Create: `db_schema/migrations/20261111950001_nickname_permissions.sql`
- Create: `db_schema/tests/269_nickname_permissions.sql`
- Modify:
  - `src/services/RoleService.ts` (the `Permission` enum, the group list near line 98, the bit map near line 149 and the descriptions near line 215);
  - `db_schema/PERMISSIONS.json`;
  - `src/locales/en.json` (both permission blocks, near lines 681 and 913);
  - `src/components/settings/RoleManagement.vue:566` (add the keys to the membership/moderation group);
  - `bot-gateway/src/auth/botPermissions.ts` (`INSTALL_FLAG_BITS`: `change_nickname → CHANGE_NICKNAME`, `manage_nicknames → MANAGE_NICKNAMES`, and drop the "have no role-permission bit" note);
  - `bot-gateway/src/bridge/bridgeConfig.ts` (`buildBridgeConfig` adds `permission_bits: string[]` from `permission_bit_names()`).
- Test: `src/services/__tests__/RoleService.test.ts`, `bot-gateway/src/api/__tests__/BridgeV2API.test.ts`

**Interfaces:**
- Produces:
  - `permission_bit_names()` returns 34 names, ending `…, 'USE_EXTERNAL_SOUNDS', 'CHANGE_NICKNAME', 'MANAGE_NICKNAMES'`;
  - `Permission.CHANGE_NICKNAME` and `Permission.MANAGE_NICKNAMES` in the frontend;
  - `GET /bridge/v2/config` includes `permission_bits` (index = bit position).
- Model it on `20261012300001_soundboard_external_sounds.sql`:
  - **Default-role mask:** update the `create_default_server_role` mask by OR-ing in `1<<32` (3343872258 + 4294967296 = 7638839554). First confirm that `20261012300001` is still the latest `create_default_server_role` definition (`grep -l 'FUNCTION public.create_default_server_role' db_schema/migrations`). If a later migration changed the mask, OR bit 32 into that value instead.
  - **One-time backfill:** OR bit 32 into every existing `is_default` role, guarded so it runs only when the bit map is first extended. A server that removes the bit later keeps it removed on a rerun.
  - **Other default-role sources:** search the migrations for other functions that write a default role's `permissions` (server templates apply, server copies, bridge clone RPCs) and add bit 32 there too.

- [ ] **Step 1: Write failing tests.**
  - pgTAP `246`:
    - `permission_bit_names()` has 34 entries, with [33] = 'CHANGE_NICKNAME' and [34] = 'MANAGE_NICKNAMES' (1-based arrays);
    - `fresh_server_default_role`: insert a server, and its default role has bit 32 and not bit 33;
    - `template_default_role`: apply a server template to a new server, and its default role has bit 32;
    - an existing server's default role gains bit 32;
    - after clearing it and re-running the backfill block, it stays cleared.
  - vitest RoleService: `PERMISSION_BITS[Permission.CHANGE_NICKNAME] === 32` and `MANAGE_NICKNAMES === 33`; `hasPermission` on a mask with `1n<<32n` is true for CHANGE_NICKNAME.
  - Gateway: `/bridge/v2/config` response contains `permission_bits` with `[32] === 'CHANGE_NICKNAME'`.
- [ ] **Step 2: Run** `bash scripts/run-db-tests.sh`, `npx vitest run src/services/__tests__/RoleService.test.ts` and `cd bot-gateway && npx vitest run src/api/__tests__/BridgeV2API.test.ts`. Expected: fail.
- [ ] **Step 3: Implement the migration and the frontend, gateway and JSON updates.**
- [ ] **Step 4: Re-run the step 2 commands plus the coverage scripts.** Expected: pass and clean. Also run `npx vitest run tests` (the root `permissionCoverage` test). Expected: pass.
- [ ] **Step 5: Commit** `feat: CHANGE_NICKNAME and MANAGE_NICKNAMES permissions`.

### Task 2: Pronouns and server profile columns and RPCs

**Files:**
- Create: `db_schema/migrations/20261111950002_server_profiles.sql`
- Create: `db_schema/tests/270_server_profiles.sql`

**Interfaces:**
- Produces (columns):
  - `profiles.pronouns text`;
  - `user_servers.avatar_url text`, `banner_url text`, `bio text`, `pronouns text`, with CHECKs per Global Constraints.
  - The existing `sanitize_member_nickname` trigger function is extended (keep its name) to trim and NULL-empty the new columns.
  - Converge the columns on the instance-level hosted schema the way `20261009200001_table_layout_convergence.sql` does for `nickname`.
- Produces (RPCs):
  - `set_my_server_profile(p_server_id uuid, p_fields jsonb) RETURNS jsonb` (authenticated). The allowed keys are the five fields; present keys are set and JSON null clears. It requires an accepted membership and `has_permission(me, server, 'CHANGE_NICKNAME')`, else `42501`. Unknown keys → `22023`. It returns the row's five fields.
  - `moderate_server_profile(p_server_id uuid, p_user_id uuid, p_nickname text DEFAULT NULL, p_set_nickname bool DEFAULT false, p_reset_profile bool DEFAULT false) RETURNS jsonb` (authenticated).
    - It requires `MANAGE_NICKNAMES` and that the caller's top role position is strictly above the target's (reuse the comparison `guard_server_role_hierarchy` uses).
    - The owner can only be changed by the owner.
    - `p_set_nickname` sets the nickname (null clears); `p_reset_profile` clears `avatar_url`, `banner_url`, `bio` and `pronouns`.
    - Audit via `server_audit_write`: `member.nickname_update` (changes old→new) and `member.profile_reset`.
  - `service_set_member_server_profile(p_server_id uuid, p_user_id uuid, p_fields jsonb) RETURNS jsonb` (service_role only): the same key rules and no permission check.
  - Profile `pronouns` is written through the existing profile update path. If profiles are updated by direct client UPDATE under RLS, the column just needs to be covered by that policy.
- RLS: if members can UPDATE their own `user_servers` row directly, they must no longer be able to change the five profile columns. Use a column-level `REVOKE UPDATE (nickname, avatar_url, banner_url, bio, pronouns) … FROM authenticated`, or a trigger that rejects changes to them unless `current_setting('harmony.server_profile_write', true) = 'on'`, which the RPCs set with `SET LOCAL`. Pick whichever matches how the table is updated today, and keep folder, position and mute writable.
- Storage: check the `avatars`/`banners` bucket policies. If they key on the first path segment being the user id, the path `<userId>/servers/<serverId>/<file>` works unchanged; otherwise extend the insert policy for that prefix.

- [ ] **Step 1: Write failing pgTAP `247`.**
  - `self_edit`: alice (accepted, @everyone has bit 32) sets `{nickname:'Ally', pronouns:'she/her'}`, and the row has them; `{nickname:null}` clears the nickname.
  - `self_edit_requires_permission`: remove bit 32 from @everyone; alice's `set_my_server_profile` raises 42501; the existing nickname is still in the row.
  - Limits: `pronouns` of 41 chars violates its check; `bio` of 501 violates its check; `'  '` is stored as NULL.
  - Direct UPDATE of `user_servers.nickname` as alice fails or has no effect; UPDATE of `muted` still works.
  - `moderate_respects_hierarchy`:
    - a mod (MANAGE_NICKNAMES, position 5) renames alice (position 1): ok, with an audit row;
    - the mod renames an admin (position 9): 42501;
    - the mod renames the owner: 42501;
    - the mod without MANAGE_NICKNAMES: 42501.
  - Reset clears the four fields and keeps the nickname.
  - `service_set_member_server_profile` is denied to `authenticated` and works as service_role.
  - A pending (not accepted) member can't `set_my_server_profile`.
  - `profiles.pronouns` of 41 chars violates its check.
- [ ] **Step 2: Run** `bash scripts/run-db-tests.sh`. Expected: 247 fails.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the DB tests and the coverage scripts.** Expected: pass and clean.
- [ ] **Step 5: Commit** `feat(db): server profiles and pronouns`.

### Task 3: Pronouns in federation

**Files:**
- Modify: `federation-backend/src/activitypub/converters/toActivityPub.ts` (actor serialisation, ~315–375)
- Modify: `federation-backend/src/activitypub/converters/fromActivityPub.ts` (~629–700) and `federation-backend/src/services/ActorService.ts` (or wherever the profile upsert lives, ~259–300)
- Test: add cases to the existing converter tests in `federation-backend/src/__tests__/` (find the actor converter test file by grepping for `PropertyValue`)

**Interfaces:**
- Produces:
  - outbound `attachment` gets `{ type: 'PropertyValue', name: 'Pronouns', value: <pronouns> }` first when `pronouns` is set and no `profile_fields` entry is named "pronouns" (case-insensitive, trimmed);
  - inbound `extractPronouns(attachment: unknown): string | null` exported from `fromActivityPub.ts` returns the first `PropertyValue` named "pronouns" (case-insensitive), with HTML stripped to text, trimmed and truncated to 40, or null. The upsert writes it to `profiles.pronouns`.

- [ ] **Step 1: Write failing tests.**
  - Outbound: an actor with pronouns 'they/them' has the Pronouns PropertyValue first; with a user field "Pronouns" already present there is no duplicate; without pronouns there is no entry.
  - `inbound_pronouns_sanitised`: `<p>she/<b>her</b></p>` → `'she/her'`; 200 chars → 40; "PRONOUNS" matches; no field → null.
- [ ] **Step 2: Run** `npm run test:federation`. Expected: fail.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `npm run test:federation`. Expected: pass.
- [ ] **Step 5: Commit** `feat(federation): pronouns as a profile field`.

### Task 4: Member profile data and server-aware resolver

**Files:**
- Create: `src/composables/useMemberProfile.ts`
- Create: `src/composables/__tests__/useMemberProfile.test.ts`
- Modify:
  - `src/services/UserDataService.ts`: store server-scoped member fields in a map keyed `${serverId}:${userId}`, filled wherever `user_servers` rows are loaded, and updated by the existing `user_servers` realtime handler (~line 1002) on insert, update and delete. Add `pronouns` to `PROFILE_BULK_SELECT` (line 77), the select at line 369 and the user mapping.
  - `src/services/usersService.ts` and `src/services/membershipService.ts`: select the five columns where member rows are loaded.
  - `src/composables/useUserData.ts`: add `getUserPronouns(userId)`.

**Interfaces:**
- Produces, from `useMemberProfile.ts`. Every function is reactive, takes `(userId: string, serverId: string | null)`, and with `serverId = null` (or no server row) returns the account value:
  - `getMemberDisplayName`: nickname → account display name → username.
  - `getMemberAvatarUrl`: server avatar → `getUserAvatarUrl`.
  - `getMemberBannerUrl`, `getMemberBio`, `getMemberPronouns`.
  - `getMemberServerProfile(userId, serverId): { nickname, avatar_url, banner_url, bio, pronouns } | null`: raw values, for the settings tab and the profile card.
- Produces, from `UserDataService`: `setMemberServerProfile(serverId, userId, fields)` and `getMemberServerFields(serverId, userId)`.

- [ ] **Step 1: Write failing vitest tests, seeding `UserDataService` directly.**
  - `resolver_is_per_call`: the same user with a nickname in server S → `getMemberDisplayName(u,'S') === 'Ally'` and `getMemberDisplayName(u,null) === account display name`, in the same tick.
  - A null server avatar falls back to the account avatar.
  - A realtime update event for `user_servers` changes the resolved nickname.
  - A delete event (leaving the server) makes the resolver fall back.
  - `getUserPronouns` returns `profiles.pronouns`.
- [ ] **Step 2: Run** `npx vitest run src/composables/__tests__/useMemberProfile.test.ts`. Expected: fail.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the test, `npm run type-check` and the existing `UserDataService` tests.** Expected: pass.
- [ ] **Step 5: Commit** `feat: server-aware member profile resolver`.

### Task 5: Use the resolver at every in-server call site

**Files (modify):**
- `src/composables/useMessageAuthorPresentation.ts` (~line 112): name and avatar via `getMember*` with the message's server id.
- `src/components/UserSidebar.vue` (member list, ~line 426).
- Voice: `VoiceTile`, `UnifiedVoiceDock` and the voice roster (find them under `src/components/voice/`).
- Mention chips/rendering and the mention autocomplete (grep `mention` in `src/components` and `src/utils`).
- Typing indicator, the reaction-users list, `ThreadView`, and `ChatComponent` (direct `avatar_url` reads).
- Notifications and push text for server messages (grep where the notification title/author is built in `src/services` and any service worker/push formatter).
- In-server search results and the pins panel.
- `UserPreviewComponent` and `App.vue` direct `avatar_url` reads, which switch only when they render in a server context.
- Audit log and moderation screens: show `account display name (@username)` plus `(nickname)` when set.

**Interfaces:**
- Consumes: Task 4 `getMember*`. The server id comes from the existing current-server source at each site (route param or server store). For messages it is the channel's `server_id`.

- [ ] **Step 1: Write failing component tests**, one per surface family, in the existing `__tests__` folder next to each component:
  - the message author shows the nickname and server avatar in a server channel, and the account name in a DM channel;
  - the member list row shows the nickname;
  - a mention chip in a server message shows the nickname;
  - the voice tile shows the server avatar;
  - an audit log row shows `Display (@user) (Nick)`.
- [ ] **Step 2: Run** `npm test`. Expected: the new tests fail.
- [ ] **Step 3: Swap the call sites.**
- [ ] **Step 4: Run** `npm test`, `npm run type-check` and `npm run lint`. Expected: pass.
- [ ] **Step 5: Commit** `feat: show server profiles inside servers`.

### Task 6: Profile card, pronouns field, Server profiles tab, server menu entry

**Files:**
- Modify:
  - `src/components/UserProfileModal.vue`, `src/components/UnifiedProfileCard.vue` and `src/components/ProfileCard.vue`: take an optional `serverId`. Inside a server, show the nickname as the name line, with the account display name and @username beneath, and the server avatar, banner, bio and pronouns. Outside, show account pronouns under the name.
  - `src/components/settings/user/UserAccountSettings.vue`: a pronouns input under the display name, saved via `ProfileService` (add `pronouns` to `ProfileData`, line 10).
  - `src/views/UserSettings.vue` (line 402): add `{ id: 'server-profiles', label: 'serverProfile.tabLabel', icon: … }` after `account`.
  - `src/components/serverRail/ServerContextMenu.vue`: a new action `'edit-server-profile'` in `ServerMenuAction`, after Notification settings, shown for servers hosted on this instance.
  - `src/components/serverRail/ServerRail.vue`: `case 'edit-server-profile'` → `router.push({ path: '/settings/server-profiles', query: { server: id } })`.
  - `src/components/UserSidebar.vue:620`: `change-nickname` → the same route with the current server.
  - `src/services/ProfileService.ts`: `uploadServerAvatar(serverId, file)` and `uploadServerBanner(serverId, file)` using the existing upload helpers with path prefix `servers/<serverId>/`.
- Create:
  - `src/components/settings/user/ServerProfilesSettings.vue`;
  - `src/components/settings/user/__tests__/ServerProfilesSettings.test.ts`;
  - `src/services/serverProfileService.ts` exporting `setMyServerProfile(serverId, fields)` and `moderateServerProfile(serverId, userId, { nickname?, resetProfile? })`.

**Interfaces:**
- Consumes: Task 2 RPCs, Task 4 `getMemberServerProfile`, and `hasPermission(serverId, Permission.CHANGE_NICKNAME)` from the existing permission composable.
- Tab behaviour:
  - a server picker of accepted memberships on servers hosted here; servers hosted elsewhere are listed but disabled, with `serverProfile.remoteUnavailable`;
  - preselected from `?server=`;
  - five fields, with the account value as each placeholder and Reset per field;
  - a live `UnifiedProfileCard` preview with `serverId`;
  - when locked (no CHANGE_NICKNAME), the fields and Reset are disabled and `serverProfile.locked` is shown.

- [ ] **Step 1: Write failing tests.**
  - The tab preselects from the query.
  - Saving calls `setMyServerProfile` with only the changed keys.
  - Reset sends `{field: null}`.
  - `locked_tab`: without the permission, the inputs and Reset are disabled.
  - A remote server is disabled in the picker.
  - The profile card with `serverId` shows the nickname plus `@username` and server pronouns; without it, it shows account pronouns.
  - The server menu emits `edit-server-profile`.
- [ ] **Step 2: Run** `npx vitest run src/components/settings/user/__tests__/ServerProfilesSettings.test.ts` plus the card and menu tests. Expected: fail.
- [ ] **Step 3: Implement and add the i18n keys.**
- [ ] **Step 4: Run** `npm test`, type-check and lint. Expected: pass.
- [ ] **Step 5: Commit** `feat(ui): server profiles settings and profile card`.

### Task 7: Moderator actions

**Files:**
- Modify: `src/components/UserContextMenu.vue` (for other members: `'moderate-nickname'` and `'reset-server-profile'`, shown when the viewer has MANAGE_NICKNAMES and outranks the target; reuse the role-position helper the kick/ban items use) and `src/components/UserSidebar.vue` (handle both actions)
- Create: `src/components/moderation/ChangeNicknameDialog.vue` and `src/components/moderation/__tests__/ChangeNicknameDialog.test.ts`

**Interfaces:**
- Consumes: `moderateServerProfile` from Task 6.

- [ ] **Step 1: Write failing tests.**
  - The menu hides both items without MANAGE_NICKNAMES, and for a higher-ranked target.
  - The dialog submits `{nickname:'X'}`; an empty value submits `{nickname:null}`.
  - Reset asks for confirmation, then calls `{resetProfile:true}`.
- [ ] **Step 2: Run the tests.** Expected: fail.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `npm test`, type-check and lint. Expected: pass.
- [ ] **Step 5: Commit** `feat(ui): moderator nickname and server profile reset`.

### Task 8: End-to-end, docs, changelog

**Files:**
- Create: `e2e/specs/server-profiles.spec.ts`
- Create or modify: user docs under `docs/use/` (find the profile page; add "Server profiles" and "Pronouns"); the permission reference in `docs/` (grep `USE_EXTERNAL_SOUNDS` under `docs/`)
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Write the e2e test.** A user right-clicks a server, opens Edit server profile, sets nickname "Ally" and a server avatar, and saves. The channel shows "Ally" with the new avatar. A DM with another user shows the account name and avatar. A moderator resets the profile; the avatar reverts and the nickname stays.
- [ ] **Step 2: Run** `npx playwright test e2e/specs/server-profiles.spec.ts`. Expected: pass.
- [ ] **Step 3: Write the docs and changelog entry**, then run `npm run docs:lint`. Expected: clean.
- [ ] **Step 4: Run the full suites:** `bash scripts/run-db-tests.sh`, `npm test`, `npm run test:federation` and `cd bot-gateway && npm test`. Expected: all pass.
- [ ] **Step 5: Commit** `docs: server profiles, pronouns, nickname permissions`.

### Task 9: Bridge permission map by name (bridge repo)

**Repo:** `C:\Users\Rudhra\Code\harmony-discord-bridge`. Create a worktree first: `git -C C:\Users\Rudhra\Code\harmony-discord-bridge worktree add ..\harmony-discord-bridge-perms -b permission-map-by-name`. Work only there.

**Files:**
- Modify: `src/utils/discordPermissions.ts`, `src/PermissionSync.ts` (callers at lines 17–23, 58 and 413), and `src/v2/BridgeApi.ts` (the `/config` response type gets `permission_bits?: string[]`)
- Test: `test/discordPermissions.test.ts` (create) plus the existing `test/fidelity.test.ts`

**Interfaces:**
- Produces:
  - `DISCORD_TO_HARMONY_PERMISSION: [bigint, string][]`: the current pairs expressed as Harmony names, plus `PinMessages → 'PIN_MESSAGES'`, `UseSoundboard → 'USE_SOUNDBOARD'`, `UseExternalSounds → 'USE_EXTERNAL_SOUNDS'`, `ChangeNickname → 'CHANGE_NICKNAME'` and `ManageNicknames → 'MANAGE_NICKNAMES'`.
  - `setHarmonyPermissionBits(names: string[] | null): void`, called when `/config` loads. Positions are resolved by name. With `null` (an older Harmony without `permission_bits`), fall back to the built-in legacy list of positions 0–31 and skip any name not in it, so an older instance never receives bits 32 and 33.
  - `discordBitfieldToHarmonyMask`, `discordRoleToHarmonyPermissions` and `discordOverwriteToHarmonyMasks` keep their signatures.

- [ ] **Step 1: Write failing tests.**
  - With the legacy fallback, the PinMessages flag → bit 23, UseSoundboard → 30 and UseExternalSounds → 31, and ChangeNickname → no bit.
  - With `setHarmonyPermissionBits([...34 names])`, ChangeNickname → bit 32 and ManageNicknames → 33.
  - A name missing from the provided list is skipped.
  - The existing fidelity tests still pass.
- [ ] **Step 2: Run** `npm test` in the worktree. Expected: the new tests fail.
- [ ] **Step 3: Implement, and wire `setHarmonyPermissionBits` where the bridge handles the `/config` response.**
- [ ] **Step 4: Run** `npm test` and `npm run typecheck`. Expected: pass.
- [ ] **Step 5: Commit** in the worktree: `feat: map Discord permissions to Harmony by name; add pin, soundboard and nickname permissions`.
