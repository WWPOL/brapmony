# Migrate from Discord: server-level member migration

Status: design, awaiting approval (2026-11-09)

## Intent

A community has cloned its Discord server into a Harmony server (prerequisite, done with the Discord
bridge's `/bridge clone-server` + `/bridge sync-perms` or by hand). The owner now wants members to move
over with as little friction as possible. Each Harmony server can turn on **Migrate from Discord**:
members open a per-server link, sign in with Discord, prove they are in the linked Discord server, get
(or link) a Harmony account, join the Harmony server, and receive the Harmony roles that match their
Discord roles. While the migration is open, Discord stays authoritative for mapped roles and for
membership (leave/kick/ban) and a sync keeps Harmony in step. When the community is done with Discord,
the owner **finishes** the migration and Harmony becomes the source of truth.

## Goals

- Per-server configuration; several servers on one instance can each run their own migration.
- Credentials work like the bridge: reuse what the instance or the server's bridge already holds, or the
  owner brings their own Discord application.
- One flow for new and existing Harmony users. Existing users are never gatekept: they sign in and link.
- Every migrated account has a normal Harmony login (email + password), so finishing never locks
  anyone out.
- Durable link per (server, Harmony user, Discord user). Sync on a schedule and on an admin "Sync now".
- Roles always sync for linked members (not user-configurable). One way: Discord → Harmony, mapped
  roles only.
- Membership follows Discord while open: leaving or being kicked on Discord removes the member from the
  Harmony server; a Discord ban bans them on Harmony.
- Per-user opt-in profile sync: avatar, display name (extensible list of fields later).
- End state: **Finish migration** closes the link page, stops all syncing, keeps accounts and the
  roles as they stand. **Re-open** is possible, with a warning that Discord becomes authoritative again.

## Non-goals

- Copying role definitions, role permissions or channel permissions. That is the bridge's job
  (`/bridge sync-perms`). The migration only decides **who holds which role**.
- Cloning channels/roles. Cloning is a stated prerequisite.
- Real-time sync via the Discord gateway (would need the privileged Server Members intent).
- Message history import.
- Changes to the separate `harmony-discord-bridge` repository.
- Instance-wide "Sign in with Discord" on the main login page.

## Anti-goals (failure even if it "works")

- A sync that empties the Harmony server because the bot lost access or Discord had an outage.
- Accounts whose only way in is Discord (locked out on finish).
- The migration touching roles that are not mapped (Harmony-only roles).
- Bot tokens or client secrets readable by anyone but `service_role`.
- An existing user unable to use the flow because their username/email already exists.

## Constraints and what exists today

- Frontend is Vue 3 (`src/`). Backend logic is Postgres RPCs (`db_schema/migrations/`,
  `YYYYMMDDNNNNNN_name.sql`, idempotent, `BEGIN/COMMIT`, `SET LOCAL lock_timeout`,
  `NOTIFY pgrst, 'reload schema'`). There are no edge functions; anything that calls Discord or the
  GoTrue admin API runs in `bot-gateway/` (Express, service role) or `federation-backend/`.
- The bot gateway already does a Discord OAuth code exchange for the instance bot
  (`bot-gateway/src/bridge/instanceBot.ts`, routes `/bridge/v2/discord/authorize|callback` in
  `bot-gateway/src/api/BridgeV2API.ts`). Instance bot credentials live in
  `public.discord_bridge_instance_bot` (`bot_token_secret`, `client_secret_secret` are Vault ids).
- Per-server bridges: `public.discord_bridges` (one per server, `mode in ('self','hosted')`,
  `discord_guild_id`). Hosted-mode Discord tokens are in Vault via `discord_bridge_secrets`. In `self`
  mode Harmony does not hold the Discord token.
- `public.can_manage_discord_bridge(server_id)` (owner or MANAGE_SERVER) is the permission model to
  mirror.
- Roles: `public.server_roles`, assignments `public.user_roles`, hierarchy trigger
  `guard_server_role_hierarchy`. No Discord role IDs are stored on roles.
- Membership: `public.user_servers`; moderation RPCs `kick_server_member`, `ban_server_member`.
- Welcome/rules gate: `server_welcome_screens` (`require_acceptance`) applies to migrated members as
  to anyone else.
- Registration policy (`harmony registration open|invite|closed`) maps to GoTrue `DISABLE_SIGNUP` and
  `instance_config.open_registration`. The GoTrue admin API creates users regardless, so the migration
  needs its own instance-level switch (below).
- `federation-backend/src/routes/adminInvites.ts` already uses `supabase.auth.admin` (precedent for
  admin-API account creation).
- Server settings: `src/views/ServerSettings.vue` sidebar list + `v-if` panels; panels in
  `src/components/settings/` (bridge UI in `src/components/settings/discord-bridge/`).

## Approaches considered

- **A. Bot gateway runs the Discord sign-in; users get a normal Harmony login (chosen).** The gateway
  does a per-server Discord OAuth (`identify` scope), verifies membership with the bot, then either
  links the signed-in Harmony user or creates a new account (username/email/password) via the GoTrue
  admin API; the user then signs in normally. Reuses the instance-bot OAuth code and works with any
  credential source. Cost: new users fill in one form.
- **B. Same, but passwordless Discord-only accounts.** One click, but anyone who never adds a password
  is locked out on finish. Rejected: conflicts with the end state.
- **C. GoTrue built-in Discord provider.** Least code, but one Discord app per instance configured by
  the operator, which breaks per-server / bring-your-own-app, and puts Discord on the main login page
  permanently. Rejected.
- **Inside the bridge program.** Separate repo, optional, and `self` mode holds no credentials in
  Harmony. Rejected.

## Design

### Credentials

Setting `credential_source` on the migration config:

| Source | Bot token | OAuth client id/secret | Available when |
|---|---|---|---|
| `instance_bot` | `discord_bridge_instance_bot` | same row | Instance bot configured and in the guild |
| `bridge` | this server's hosted bridge token | owner enters client secret; app id read from `GET /oauth2/applications/@me` | Server has a `hosted` bridge |
| `own` | owner pastes | owner pastes | Always |

`instance_bot` and `bridge` **reference** the existing Vault ids live (not copies), so a token rotation
on the bridge side carries over. If the referenced row disappears (bridge removed, instance bot turned
off), the migration pauses with "credentials gone; choose another source".

Secrets the migration owns are stored in Vault, ids in a `service_role`-only table, same pattern as
`discord_bridge_secrets`. The OAuth redirect URI shown to the owner is
`<base>/bridge/v2/migrate/callback` (one URI for all servers; the server is carried in `state`).
The owner must add it to their Discord application. The instance bot's redirect list must include it
too (operator doc).

Bot permissions: no new scopes. Fetching one member (`GET /guilds/{g}/members/{u}`) and listing roles
(`GET /guilds/{g}/roles`) work for any bot in the guild without privileged intents. **Ban Members** is
needed only to distinguish a ban from a removal (`GET /guilds/{g}/bans/{u}`); without it, banned users
are treated as removed and the settings page says so.

### Data model (new migration)

- `discord_migrations` (one per server): `server_id` PK, `discord_guild_id` (snowflake check; prefilled
  from `discord_bridges.discord_guild_id` if present), `credential_source`, `state`
  (`draft | open | finished`), `opened_at`, `finished_at`, `last_sync_at`, `last_sync_result` jsonb,
  `sync_paused_reason` text null, `removal_brake_pct` int default 10, created/updated by/at.
- `discord_migration_secrets` (service_role only): `server_id`, `bot_token_secret`,
  `client_id`, `client_secret_secret`.
- `discord_migration_role_map`: `server_id`, `discord_role_id`, `discord_role_name` (snapshot),
  `harmony_role_id` null, `ignored` bool. Exactly one of mapped / ignored. `@everyone` excluded.
- `discord_migration_links`: `server_id`, `user_id`, `discord_user_id`, `linked_at`, `last_synced_at`,
  `sync_avatar` bool default false, `sync_display_name` bool default false, `status`
  (`active | left | banned`). Unique (server_id, user_id) and (server_id, discord_user_id). Kept after
  finish as history.
- `discord_migration_link_states`: short-lived OAuth state (nonce, server_id, optional user_id, expiry),
  like `discord_bridge_link_states`.
- Instance config key `discord_migration_account_creation` (bool, default true): operator switch for
  whether migrations may create accounts when registration is `invite` or `closed`. Linking existing
  accounts is always allowed. Exposed through `harmony` CLI and documented.

RLS: config and role map readable/writable by `can_manage_discord_bridge(server_id)` (reuse or a twin
`can_manage_discord_migration`). Users read/update only their own link row's sync toggles. Secrets
and link states `service_role` only. Mutations through RPCs; register them in
`db_schema/SURFACE.tsv`/`REACHABILITY.tsv` and `PERMISSIONS.json` as the repo requires.

### Admin setup (Server Settings → Integrations → "Migrate from Discord")

1. Prerequisites checklist (static text + live checks): server cloned; bot in the Discord server.
2. Credentials: choose source; enter what that source needs; "Test" calls the gateway, which checks the
   token, fetches the guild, and reports bot permissions (Ban Members yes/no).
3. Discord server ID (prefilled from bridge).
4. Role mapping: gateway fetches Discord roles; auto-match by exact name (case-insensitive) to Harmony
   roles; owner confirms or edits each row or marks it ignored. Mapping to a role above the configuring
   admin's top role is refused (respect hierarchy).
5. Readiness report: **gate** = every Discord role mapped or ignored. **Warnings only**: Discord
   channels without a paired Harmony channel (from bridge pairs), per-member channel overrides on Discord
   that will not carry over, missing Ban Members.
6. Open migration → shows the shareable link `/migrate/<token>` and a copy button. The token is a
   random, unguessable value stored on `discord_migrations` (regenerable), so the public page reveals
   nothing about private servers. The public preview RPC answers only for an `open` migration's token.
7. While open: members list (linked, last sync, status), Sync now, last sync result, pause reason.
8. Finish migration (confirm dialog) / Re-open (warning that mapped roles and membership follow Discord
   again and a sync runs immediately).

Who can configure: owner or MANAGE_SERVER (mirrors bridge).

### Member flow (`/migrate/:server`, public page)

1. Landing: server name/icon, "Sign in with Discord". If the migration is not `open`, show "This
   migration is closed; ask the server's admins for an invite."
2. If the visitor is signed in to Harmony, the flow will link that account; the page says so, with a
   "not you? sign out" link. If not signed in: two buttons, "I have a Harmony account" (normal login,
   then return here) and "I'm new" (continue).
3. Gateway `/bridge/v2/migrate/authorize?server=…` → Discord consent (`identify`) → callback exchanges
   the code, reads the Discord user, fetches their guild member with the bot.
   - Not a member / banned → error page, nothing created.
   - Discord user already linked to a different Harmony account on this server → error explaining that.
4. Existing account path: create link row, join server, apply roles, done.

   Joining rules (apply to both paths): the migration link acts as an invite, so it works even when the
   server is otherwise invite-only. A **Harmony ban is sticky**: a user banned on Harmony is refused
   even if they're still on Discord. A user kicked on Harmony may re-run the link page while they are
   still in the Discord server. A link with `status = banned` (banned on Discord) is refused. One with
   `status = left` becomes `active` again if they are back in the Discord server. The welcome/rules
   gate applies after joining, as for any new member. Roles are granted right away, and the rules gate
   still blocks sending messages until accepted.

   Local accounts only. Federated users from other instances are shown "create an account on this
   instance to migrate".
5. New account path: gateway returns a short-lived signed ticket to the page; the page shows a form
   (username prefilled from Discord username, email, password, profile sync checkboxes). Submit →
   gateway validates ticket, checks the instance switch, creates the GoTrue user, creates the profile,
   link, membership and roles. Email confirmation follows the instance's GoTrue setting. If none is
   required, the page signs in with the password. If it is required, the account is created unconfirmed:
   membership and roles are already in place, and the result screen says "confirm your email, then sign
   in". The email is never auto-confirmed, because Discord membership proves nothing about the email. Username collision → inline error. Email belonging to an existing
   account → "sign in instead" (never merge silently).
6. Result screen: joined, roles received, profile-sync toggles (also later in user settings under the
   server's membership or Connections).

### Sync

Runs in the bot gateway: a timer (default every 6 h, configurable via env) over all `open` migrations,
plus "Sync now" (admin RPC that enqueues; gateway picks it up) and an immediate sync on Re-open.

Per migration:
1. Bot can't see the guild (401/403/404 on guild fetch) → pause, set `sync_paused_reason`, notify admins,
   change nothing.
2. For each active link, fetch the Discord member (respect rate limits; sequential with backoff).
   - 404 member → removal candidate (`left`). Ban check if permitted → `banned`.
   - Present → compute target Harmony roles from the role map and make the member's **mapped** roles
     match exactly: add missing ones, remove mapped roles they no longer hold on Discord, including
     ones granted by hand on Harmony. **Discord owns mapped roles while the migration is open.**
     Unmapped (Harmony-only) roles are never touched. The role-map screen states this rule.
   - Profile fields synced only where the user opted in.
3. Brake: if removal candidates exceed `removal_brake_pct` of active links (and at least N, e.g. 3),
   pause and notify instead of applying removals. Role changes still apply.
4. Apply removals with `kick_server_member` / `ban_server_member` semantics (audit log entries attributed
   to the migration). Link status updated; a member who returns to Discord can re-run the link page.
5. Store `last_sync_result` (counts, errors).

Scale: the member walk is resumable. Each run processes links oldest-`last_synced_at` first within a
time budget, honouring Discord's rate-limit headers, and the next run continues where it stopped. Large
servers therefore converge over several runs rather than requiring one run to finish in the interval.
The settings page shows progress (synced in the last cycle / total).

**Writes from the gateway.** Existing moderation and role RPCs check `auth.uid()`, which the gateway
does not have. Add service-role-only RPCs (e.g. `migration_apply_member_roles`,
`migration_remove_member`) that act as the migration itself: audit-log entries are attributed to
"Discord migration" with the server id. The hierarchy question is settled **at mapping time**: saving
the role map refuses roles at or above the saving admin's top role (the owner can map anything), and
the sync then trusts the saved map. Nothing is tied to the admin who opened the migration, so an admin
leaving changes nothing. These RPCs never touch the owner, and never remove a member who holds a role
with ADMINISTRATOR (they log and skip instead).

### Finish / re-open

Finish: `state = finished`, link page closed, sync timer skips it, Sync now hidden. Links and
secrets kept (secrets may belong to the bridge). Owner can delete the migration's own secrets
separately. Re-open: `state = open`, immediate sync.

## Decided by the user

- Approach A.
- Per-server config, credentials like the bridge (instance-provided or own).
- Existing users link, never gatekept.
- Durable link; cron + admin Sync button; roles always sync; per-user opt-in profile fields.
- One-way Discord → Harmony.
- Discord leave/kick → removed from Harmony server; Discord ban → Harmony ban.
- Discord owns mapped roles while open: a linked member's mapped roles always match Discord, hand
  grants included; unmapped roles untouched.
- Finish closes the link (stragglers need re-open or a manual invite) and stops syncing.

## Calls made by the designer (check these)

- Roles-only gate; channels/overrides are warnings.
- Removal brake (10%, min 3) and pause on lost guild access.
- New accounts require email + password; email collision → "sign in instead".
- Instance switch for account creation under invite/closed registration (default on).
- Default sync interval 6 h; resumable member walk for large servers.
- Gateway writes via new service-only RPCs attributed to "Discord migration"; hierarchy checked when
  the role map is saved; owner and ADMINISTRATOR holders are never auto-removed.
- `instance_bot`/`bridge` sources reference the bridge's Vault ids live; pause if they vanish.
- Harmony bans are sticky; Harmony kicks can re-link; the link works as an invite.
- Email confirmation follows the instance; never auto-confirmed.
- Link URL uses an unguessable token, not the server id.
- Local accounts only (no federated users).
- Links and secrets kept after finish.
- `identify` is the only user OAuth scope; membership verified with the bot.

## Left to the builder

- Exact RPC names/signatures, table column details, ticket format (signed JWT or DB row).
- Whether sync triggers ride an existing gateway poll loop or a new timer; queue mechanism for Sync now.
- i18n strings, exact UI layout within existing settings patterns.
- Test split: pgTAP for RLS/RPCs/grants, vitest for gateway OAuth/sync logic (mock Discord), one
  Playwright happy path with a stubbed Discord.
- Docs: `docs/run/` page for owners, `docs/self-host/` notes for operators (redirect URI, switch).
