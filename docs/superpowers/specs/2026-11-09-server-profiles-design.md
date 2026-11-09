# Server profiles, pronouns and nickname permissions

Status: design, awaiting approval (2026-11-09). PR 2 of the stack in
`2026-11-09-discord-migration-design.md` ("Delivery stack").

## Intent

Bring Harmony's member identity to parity with Discord, so that a community moving from Discord
keeps how its members present themselves, and so the Discord migration (PR 3) has somewhere to put
synced data. Two parts:

- **Pronouns** on the account profile.
- **Server profiles**: per-server nickname, avatar, banner, bio and pronouns, shown everywhere inside
  that server and falling back to the account profile. Today `user_servers.nickname` exists but is
  neither editable nor shown anywhere.

Plus two Discord-equivalent permissions to govern it.

## Goals

- Account-level `pronouns`, editable in account settings, shown under the name on profile cards and
  federated.
- Server profile fields on the membership: `nickname` (exists), `avatar_url`, `banner_url`, `bio`,
  `pronouns`. Each is optional; unset means "use the account value".
- Wherever a member is shown *in the context of a server*, use the server profile, field by field.
  This covers the message author, the member list, voice (tiles, dock and roster), mention chips and
  autocomplete, typing indicators, reaction-user lists, the user popover/profile card, and thread
  views. DMs, friends lists, the account settings preview and the fediverse/posts surfaces keep the
  account profile.
- The profile card in a server shows the server version, plus the account display name and @username
  (as Discord shows both).
- Editing: **Edit server profile** in the server right-click menu (`ServerContextMenu.vue`) opens
  account settings on a new **Server profiles** tab. The tab has a server picker preselected to that
  server, the five fields each with **Reset to default**, and a live preview. **Change nickname** on
  your own member right-click goes to the same place.
- Moderation: right-clicking another member offers **Change nickname** and **Reset server profile**
  (clears avatar, banner, bio and pronouns) to holders of Manage Nicknames who outrank the target.
- Permissions:
  - `CHANGE_NICKNAME` (bit 32) lets you edit your own server profile (all five fields).
  - `MANAGE_NICKNAMES` (bit 33) lets you edit others' nicknames and reset others' server profiles.
    Role hierarchy is respected; the owner is exempt from being changed by others.
  - `CHANGE_NICKNAME` is granted to every server's default (@everyone) role, existing servers
    included. `MANAGE_NICKNAMES` is granted to nobody new; ADMINISTRATOR implies both, as usual.

## Non-goals

- Splitting name color from banner accent (one `profiles.color`, decided).
- Federating server profiles between instances. For servers hosted on another instance, the Server
  profiles tab shows that server as "not available for servers on other instances yet". Remote members
  of a local server see their account profile used.
- Per-server display-name color, decorations, or effects.
- Changes to bio (already exists) other than its per-server variant.
- The Discord bridge's permission mapping (separate PR in `harmony-discord-bridge`, below).

## Anti-goals

- A server profile leaking outside its server (in DMs, other servers, or federation).
- Anyone without Manage Nicknames changing another member's server profile, or a moderator changing
  someone above them.
- Names or avatars visibly flicking from the account to the server version on load. Server profile
  data must arrive with the member data that already renders.
- Breaking existing behaviour for servers that never touch the feature.

## Constraints and what exists today

- `user_servers.nickname text` (CHECK ≤ 64, `sanitize_member_nickname_trigger`) is defined in
  `db_schema/migrations/20261009200001_table_layout_convergence.sql`. The instance-level hosted schema
  lacks the column (see the comment in `20261008500001_function_trigger_convergence.sql`); any new
  columns must converge there too.
- Account profile editing is in `src/components/settings/user/UserAccountSettings.vue`, writing via
  `src/services/ProfileService.ts`. Uploads use bucket `avatars` (`src/utils/fileUpload.ts`
  `uploadImageObject`) and bucket `banners` (`src/utils/bannerUtils.ts`). The bio cap is 500 chars.
- Display resolution lives in `src/composables/useUserData.ts`: `getUserAvatarUrl`,
  `getUserDisplayName`, `getUserColor`, `getUserBio`. These sit over `src/services/UserDataService.ts`,
  an id-keyed global cache with no server context. `src/composables/useMessageAuthorPresentation.ts`
  is the message author presenter. `src/components/common/Avatar.vue` is the common avatar component;
  a few files (`ChatComponent`, `ThreadView`, `VoiceTile`, `UnifiedVoiceDock`,
  `UserPreviewComponent`, `App.vue`) read `avatar_url` directly.
- Members per server are loaded in `src/services/usersService.ts` and `membershipService.ts`.
  Realtime `user_servers` changes are handled in `UserDataService.ts`.
- Permission bits are positional, defined by `public.permission_bit_names()` (latest in
  `20261012300001_soundboard_external_sounds.sql`, bits 0–31). Mirrored in
  `src/services/RoleService.ts` `PERMISSION_BITS` and `db_schema/PERMISSIONS.json`. That migration is
  the template for adding bits.
- Outbound actor serialisation is in `federation-backend/src/activitypub/converters/toActivityPub.ts`
  (`attachment` = `profile_fields` as `PropertyValue`). Inbound is in `converters/fromActivityPub.ts`
  and `ActorService.ts`.

## Approaches considered

- **A. Server profile columns on `user_servers` + a server-aware resolver (chosen).** Builds on where
  the nickname already lives, its trigger, RLS, loading and realtime. Member lists carry a bit more
  data (bio ≤ 500 chars).
- **B. A separate `server_member_profiles` table.** Leaner member lists, but it splits or moves the
  nickname, needs its own RLS, realtime and lazy load, and risks a flash of the account profile.
- **Swap the global cache per active server.** Rejected: wrong whenever two contexts are on screen
  (DMs, threads).

## Design

### Data

- `profiles.pronouns text NULL` (CHECK ≤ 40 chars, trimmed, empty → NULL).
- `user_servers`: add `avatar_url text`, `banner_url text`, `bio text` (CHECK ≤ 500), `pronouns text`
  (CHECK ≤ 40). Extend the existing sanitise trigger to trim these and turn empty into NULL.
- Writes go through RPCs, not direct UPDATE:
  - `set_my_server_profile(p_server_id uuid, p_fields jsonb) RETURNS jsonb`. Keys present in
    `p_fields` are set, and null clears. Requires an accepted membership and `CHANGE_NICKNAME`.
  - `moderate_server_profile(p_server_id uuid, p_user_id uuid, p_nickname text, p_reset_profile bool)
    RETURNS jsonb`. Requires `MANAGE_NICKNAMES`, and the caller must outrank the target by top role
    position. Never applies to the owner unless the caller is the owner. It writes the server audit
    log (`member_nickname_changed`, `member_server_profile_reset`).
  - Both write the audit log for self-changes only if the existing audit conventions log self nickname
    changes; otherwise only moderator actions are logged.
  - `service_set_member_server_profile(p_server_id uuid, p_user_id uuid, p_fields jsonb) RETURNS jsonb`
    is service_role only and can set all five fields for an accepted member. It is the write path for
    the Discord migration (PR 3) and other trusted server-side code. It skips the permission check and
    writes no self-change audit.
  - All setters act on accepted memberships only (not pending or banned).
- Revoke direct client UPDATE of these columns if RLS currently allows members to update their own
  `user_servers` row; keep whatever other columns rely on it (folder, position, mute).
- Permissions migration: append `CHANGE_NICKNAME`, `MANAGE_NICKNAMES` to `permission_bit_names()` and
  set bit 32 on every `server_roles` row with `is_default = true` (converging: OR the bit in).
  **Every path that creates a default role** must also include bit 32: server creation, templates
  applied to new servers, federated or remote server copies, the bridge's clone, and any client-side
  "default permissions" constant (search for the hard-coded default-role `permissions` values, e.g. the
  seeding in `20261012300001_soundboard_external_sounds.sql`). A pgTAP test creates a fresh server and
  asserts its default role has bit 32.
- Bot install flags: `bot-gateway/src/auth/botPermissions.ts` `INSTALL_FLAG_BITS` today notes that
  `change_nickname` and `manage_nicknames` "have no role-permission bit". Map them to the new bits. Update
  `PERMISSIONS.json` and `RoleService.ts`, and add labels and descriptions to the role editor, grouped
  with the membership permissions.
- Uploads: reuse the `avatars` and `banners` buckets under a path that includes the server id
  (`<userId>/servers/<serverId>/...`). Use the existing policies if they key on the first path segment
  being the user; otherwise extend them.

### Display resolution

- Add server-scoped member data to the member cache. Whatever loads `user_servers` rows for a server
  also loads the five fields. The realtime handler updates them.
- New composable functions in `useUserData.ts` (or a sibling `useMemberProfile.ts`), signatures
  decided by the builder:
  - `getMemberDisplayName(userId, serverId | null)`: nickname → account display name → username.
  - `getMemberAvatarUrl(userId, serverId | null)`, `getMemberBannerUrl`, `getMemberBio`,
    `getMemberPronouns`.
  - `serverId = null` returns the account values.
- Swap call sites that render a member in a server context to these, passing the current server id:
  the message author presenter first, then the member list, voice, mentions, typing, reactions, the
  profile card and threads, plus the direct `avatar_url` readers listed above.

Rules the resolver follows:
- **Stored values always display**, whatever the member's permissions. `CHANGE_NICKNAME` only gates
  editing. Without it, a member can neither edit nor reset their own server profile (as on Discord);
  a Manage Nicknames holder can reset it.
- Rows of remote members (on servers hosted here) are resolved like any other. Only the RPCs above
  write the fields, so no extra guard is needed.
- Existing `user_servers.nickname` values are real. Today the only writer is account migration
  (`20261010900001_account_migration.sql` carries a moved account's nickname), and the bot gateway
  already exposes them to bots (the Discord bridge uses them for puppet names). No data clean-up is
  needed; showing them is the intended change.
- Resolution is live, so past messages show the current nickname. Mentions are stored by user id and
  resolve with the server context of the message.
- Server-scoped surfaces beyond rendering use the server profile: notifications and push for server
  messages, in-server search results, pins, and messages forwarded into the same server. The audit log,
  moderation tools and admin screens show the account display name and @username, with the nickname
  in parentheses when set, so identity stays unambiguous.

### Profile card

Inside a server:
- avatar, banner, bio and pronouns from the server profile;
- the name line is the nickname if set, with the account display name and @username beneath.

Outside a server, the card shows the account profile, with account pronouns under the name.

### Settings UI

- Account settings: a pronouns input under the display name.
- New **Server profiles** tab in user settings:
  - a server picker listing servers you're a member of on this instance;
  - preselected via a route query (`?server=<id>`) from the server menu and Change nickname;
  - nickname, avatar upload, banner upload, bio and pronouns, each showing the account value as a
    placeholder, with Reset;
  - a live preview of the profile card;
  - fields disabled, with a note, when you lack `CHANGE_NICKNAME` in that server.
- Server right-click menu: **Edit server profile**.
- Member right-click (others): **Change nickname** (a small dialog) and **Reset server profile**
  (confirm), shown when permitted.

### Federation

- Outbound: when `pronouns` is set, add a `PropertyValue` named "Pronouns" to `attachment`, ahead of
  `profile_fields`, unless the user already has a profile field named "Pronouns" (case-insensitive).
- Inbound: if a remote actor has a `PropertyValue` named "Pronouns" (case-insensitive), store it in
  `profiles.pronouns` and leave it in `profile_fields` too.
- Server profile fields are never federated.

## Follow-up PR: harmony-discord-bridge permission map

In `C:\Users\Rudhra\Code\harmony-discord-bridge` (work in a git worktree),
`src/utils/discordPermissions.ts` `DISCORD_TO_HARMONY_PERMISSION_BITS`:
- Add the three bits missing today: `PinMessages → 23`, `UseSoundboard → 30`,
  `UseExternalSounds → 31`.
- Add `ChangeNickname → 32` and `ManageNicknames → 33`. Either ship them after Harmony with these bits
  is released, or gate them on the Harmony instance's version or permission list so an older
  instance never receives unknown bits.
- Update the bridge's tests.

## Decided by the user

- Approach A.
- One color (no split).
- Match Discord: per-server nickname, avatar, banner, bio and pronouns; global pronouns.
- "Edit server profile" in the server right-click menu.
- Add Change Nickname / Manage Nicknames equivalents.
- Bridge permission-map fix as its own PR in the bridge repo.

## Calls made by the designer (check these)

- Bits 32/33. `CHANGE_NICKNAME` on every default role, including existing servers.
- `CHANGE_NICKNAME` governs all five server profile fields; `MANAGE_NICKNAMES` can set others'
  nicknames but only reset (not set) their other fields.
- Pronouns capped at 40 chars.
- Server profiles are local to the hosting instance; unavailable for servers on other instances.
- Pronouns federate as a "Pronouns" `PropertyValue`; inbound pronouns are extracted from it.
- DMs, friends and fediverse surfaces keep the account profile.
- Service-role setter for PR 3; accepted memberships only.
- Every default-role creation path gets bit 32; the bot install flags map to the new bits.
- Stored server profile values display regardless of permission; without `CHANGE_NICKNAME` you can't
  edit or reset your own.
- Notifications, in-server search and pins use the server profile; the audit log and moderation
  screens show the account identity with the nickname alongside.
- A moderator reset leaves uploaded files in storage (orphaned, as other avatar changes do today).

## Left to the builder

- Exact composable names and file split; whether the member cache is a new store or part of
  `UserDataService`.
- Upload path and policy details within the existing buckets.
- Dialog and tab layout within existing settings patterns; i18n keys.
- Test split: pgTAP for RPCs, the permission backfill and RLS; vitest for resolvers and components; one
  Playwright path (set a nickname and avatar in a server, see them in chat, not in DMs).
