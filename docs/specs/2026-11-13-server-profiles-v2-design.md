# Server profiles, v2

Status: draft for discussion (2026-11-13). Replaces the loading, storage and federation parts of
`docs/superpowers/specs/2026-11-09-server-profiles-design.md` (v1), which upstream PR
y4my4my4m/harmony#200 implements. Not
built yet. Align on this document (open questions at the end) before implementation starts.

## Why a v2

The upstream maintainer raised three concerns about #200, on the PR and in chat:

1. **The startup member list got heavy.** On start, the app fetches the member list of every server
   the user is in (`getUserIdsForServers` in `src/services/usersService.ts`, called from
   `BaseLayout.vue`). On master that query reads only `user_id, server_id`; full profiles are fetched
   later, only for the people on screen. #200 widened it to `nickname, avatar_url, banner_url, bio,
   pronouns` for every member of every server. Someone in 10 servers of 2,000 members goes from about
   20,000 IDs (around 1 MB) to as much as about 10 MB of bios and URLs before they look at anyone.
2. **@everyone repeats the same lookup per recipient.** `send_notification` inserts one
   `notifications` row per recipient, and the push worker (`PushNotificationService.sendForNotification`)
   handles each row on its own. #200 added a `user_servers` nickname lookup to that per-row work, so
   one @everyone in a 2,000-member server makes 2,000 identical queries for the same sender and server.
3. **Fit with federated identity.** A Harmony account is a fediverse identity with public posts. Does a
   per-server profile on top of it make sense? Related: #200 never federates server profiles, and only
   allows them on servers hosted by the user's own instance, so members joining from other instances
   can't set one and never see anyone's.

He was also unsure that new columns on `user_servers` are the right approach.

v2 keeps the whole feature (nickname, avatar, banner, bio, pronouns) and changes how it is stored,
loaded and federated.

## Goals

- Per-server nickname, avatar, banner, bio and pronouns, falling back to the account profile field by
  field. The user-facing behaviour of #200 stays: the settings tab, moderation actions, permissions,
  and where server profiles show and where the account profile shows.
- **No cost for people who never set one.** Startup, the member list and message loading cost the same
  as master. The extra work grows with the number of people *on screen* who have a server profile,
  not with server size.
- **@everyone costs one profile lookup per message**, not one per recipient.
- **It works across instances.** Every instance with members in a server shows the same server
  profiles, and members from any instance can set theirs.
- **Server profiles stay inside the server.** They never reach ActivityPub actor documents, public
  posts, DMs or other servers.

## Non-goals

- General performance work (batching the push worker's per-recipient queries, caching beyond what this
  feature needs). That's a separate discussion.
- Per-server name colour, decorations or effects.
- Historical names: messages show the member's current server profile, as on Discord.

## Unchanged from v1

These parts of v1 and #200 carry over as they are:

- **Permissions.** `CHANGE_NICKNAME` (bit 32) lets a member edit their own server profile.
  It's on @everyone for new servers and backfilled once for existing ones.
  `MANAGE_NICKNAMES` (bit 33) lets a moderator act on others' profiles. No role gets it automatically.
  Bot install flags map to both bits, and `/bridge/v2/config` publishes `permission_bits`.
- **Moderation.** With `MANAGE_NICKNAMES` and a role above the member's, a moderator can change
  another member's nickname to anything or clear it, and can reset their avatar, banner, bio and
  pronouns together (not edit them). Nobody but the owner changes the owner's profile. Both actions
  are audited.
- **Write RPCs.** `set_my_server_profile`, `moderate_server_profile` and
  `service_set_member_server_profile`, and the trigger that refuses direct client writes. They now
  write the new table (below).
- **Settings UI.** **Edit server profile** on the server menu, the **Server profiles** tab (server
  picker, five fields each with Reset, live preview, locked without the permission), and **Change
  nickname** / **Reset server profile** on a member's menu. One change: servers on other instances
  are no longer disabled (see Federation).
- **Where each profile shows.** Server context (messages, the member list, voice, mentions, typing,
  reactions, threads, pins, in-server search, server-channel notifications) uses the server profile.
  DMs, friends, account settings and fediverse surfaces use the account profile. The audit log and
  moderation screens show `Name (@user) (Nick)`.
- **Account pronouns.** `profiles.pronouns`, at most 40 characters, federated as a "Pronouns"
  `PropertyValue` and read back from one. Independent of server profiles; see open question 7.

## Design

### Storage: a separate, sparse table

```
server_member_profiles (
  server_id   uuid  references servers   on delete cascade,
  user_id     uuid  references profiles  on delete cascade,
  nickname    text  null,   -- ≤ 64, same sanitising as today
  avatar_path text  null,   -- storage path, never a free URL (see Images)
  banner_path text  null,
  bio         text  null,   -- ≤ 500
  pronouns    text  null,   -- ≤ 40
  updated_at  timestamptz not null default now(),
  primary key (server_id, user_id)
)
```

- A row exists only while at least one field is set. Clearing the last field deletes it. Most
  members have no row.
- `user_servers` gets no new columns and stays as narrow as on master. Its Realtime events, RLS
  (row-level security policies) and startup query don't change.
- RLS: an accepted member of the server can read its rows. Writes go only through the RPCs.
- `user_servers.nickname` already exists upstream. Whether it moves into this table is open
  question 2. The rest of this document assumes it moves, with a one-time data copy.

### Loading in two tiers

The five fields aren't needed in the same places, so they load separately.

**Tier 1, nickname and avatar.** Needed wherever a member's name renders.
- Loaded lazily, in batches, for the user IDs on screen. That means message authors, people
  mentioned in those messages (mention chips show the nickname), visible member-list rows, voice
  occupants and the typing indicator.
- One indexed query per batch: `select user_id, nickname, avatar_path from server_member_profiles
  where server_id = $1 and user_id = any($2)`. The message list makes one batch per page.
- Hooked into the same step that already fetches account profiles for those IDs
  (`ensureProfilesAvailable`), and run in parallel with it, so it adds no extra round-trip wait.
- The startup member list stays IDs only, exactly as on master.

**Tier 2, banner, bio and pronouns.** Needed only on the profile card.
- Fetched as a single row when a card opens. Never loaded in bulk.

**No flash of the account name.** In a server context, a member's name and avatar don't render as
the account values until their tier-1 lookup has resolved. "No server profile" counts as resolved.
Until then, the name area shows the same placeholder as while the account profile loads today. This
is the anti-goal v1 used to rule out a separate table; resolving both lookups in the same step
answers it.

### Client cache (server profile only)

- Keyed `serverId:userId`, in `userDataService` as #200 has it.
- **Remembers "no server profile"**, so empty lookups aren't repeated. Most lookups come back empty.
- Kept up to date by `member:update` on the members-only `server-presence:` topic. The event
  carries only tier-1 fields plus `updated_at`. A card that's open refetches tier 2 when it sees a
  newer `updated_at`.
- Entries for a server are dropped when the user leaves it. Entries for a member are dropped when that
  member leaves.
- Optional, not required for v2: keep tier-1 entries in IndexedDB (the browser's local database)
  with `updated_at`, so a reload paints names from cache and refreshes in the background. See open
  question 8.

### Notifications: look up the sender once per message

- `send_notification` already resolves `v_server_id` before its recipient loop. When there is a
  server, a channel and `p_from_user_id`, it reads that sender's tier-1 fields **once**, before the
  loop, and adds `sender_server_profile: { nickname, avatar_url }` to `notification_data`. Every
  recipient's row carries it.
- The push worker reads it from the data and does no lookup. `serverSenderNickname()` from #200 goes
  away.
- The in-app notification list and toasts read it from the data too, so they don't depend on the
  member being in the client cache.
- A notification keeps the nickname from when it was sent. That's intended.
- Servers hosted elsewhere work the same way: the recipient's instance creates the notification and
  reads the sender's profile from its copy of the server (see Federation).

### Which name shows where

A fixed rule, with no per-user setting:
- **Inside a server** (message author, member list, server-channel pushes and toasts, mentions): the
  nickname. The server and channel name are already next to it.
- **Wherever identity matters** (profile card, moderation dialogs, audit log, DMs, fediverse): the
  account display name and full `@user@domain` handle.
- **Seeing who someone really is: click, not hover.** Clicking a name opens the card. Inside a server,
  the card shows the nickname together with the account display name and full handle, on desktop and
  phone alike.

### Mention autocomplete and search

- Typing `@alex` lists every member matching by nickname, display name or username. Each row shows
  the avatar, the name and the full handle, so people with the same nickname can be told apart.
- With lazy loading the client no longer holds every member's nickname, so matching moves to the
  server. A new RPC, `search_server_members(p_server_id, p_query, p_limit default 10)`, matches all
  three names for accepted members and returns IDs plus tier-1 fields. It needs an index that serves
  prefix and substring matching on nickname (for example trigram), and the same for profile names if
  they lack one.
- The search box's `from:` filter uses the same RPC.

### Impersonation

A nickname plus a server avatar can make someone look like another member. They don't get that
member's roles, role colour, badges or permissions. The real identity is one click away on the card,
autocomplete always shows the handle, and moderators can change the nickname or reset the profile.

### Federation

Setup: server S is hosted on instance A, and its members come from A, B and C. B and C each keep a
copy of S, as they already do for channels, messages and roles.

**The host is the source of truth.** A stores every member's server profile in S, local and remote.
B and C store display-only copies. This follows the pattern roles already use (`harmony:memberRoles`
and `Update(harmony:MemberRoles)`, documented in `federation-backend/src/activitypub/copyMirror.ts`).

Why the host, not the member's home instance:
- **Moderation:** moderators on A must be able to change a B member's nickname. A can't enforce
  that on data B owns.
- **Permissions and audit:** `CHANGE_NICKNAME`, the role hierarchy and the audit log already live on
  A. Copies don't even store permission bits.
- **One answer:** every instance shows the same profile.
- **Downtime:** if A is down, edits wait, but S itself is unavailable then too.

**Reads**
- The Group's members pages, which copies already read for roles, gain
  `"harmony:memberProfiles": [{ "member": "<actor>", "nickname", "avatar", "updated" }]`. They list
  only members with a server profile, and carry tier-1 fields only. `avatar` is a URL on A's storage.
- Live changes go out as a Group-signed `Update { "type": "harmony:MemberProfile", "member",
  "nickname", "avatar", "updated" }`. All fields null means the profile was cleared.
- A copy fetches tier-2 fields from A when one of its users opens a card, then caches them until a
  newer `updated` arrives.
- Bio, banner and pronouns are members-only. A serves them, and the members pages that carry
  nicknames, only to signed requests from instances with accepted members in S, the same access the
  role data has today.

**Writes**
- A B member editing their profile in S calls B's backend. B sends a signed request from the
  member's actor to S's Group inbox, for example `harmony:SetMemberProfile` with the changed fields.
- A checks membership and `CHANGE_NICKNAME`, stores the change, writes the audit entry, and answers
  with the `Update` to every member instance, B included. On refusal it sends a `Reject`, which B
  shows as an error.
- B shows the edit as pending until A's `Update` arrives. B never stores its own version as the
  truth.
- Moderator actions (`MANAGE_NICKNAMES`) run only on A, whatever instance the moderator is on, using
  the existing path for remote moderators acting on a hosted server.

**Cost.** The profile belongs to the server's membership, not to the user's identity. Being in 50
servers puts nothing into the user's actor document. Each change costs one signed `Update` per
*instance* with members in S (usually a handful), not one per member. Changes are rare and
rate-limited (below).

**What federation never carries.** Messages federate with the account as author; each instance
resolves the nickname from its copy. Server profile fields never appear in actor documents, outboxes
or public posts, so fediverse followers only ever see the account profile. Matrix has worked the
same way for years: per-room display names and avatars live in room membership state, and the real ID
is always visible.

**Compatibility.** Instances older than this ignore the unknown property and the unknown `Update`
type, as they do for roles. They keep showing account names for S.

### Images

- Server avatars and banners are always Harmony-hosted. The database stores a **storage path**, and
  the RPCs refuse anything else. A free URL would let someone point their avatar at a site they
  control, which then learns the IP address and online times of every member who sees it, like a
  tracking pixel. (Account avatars have the same hole today; fixing that is out of scope here.)
- Local members upload to the existing `avatars` and `banners` buckets under
  `<userId>/servers/<serverId>/…`, as in #200.
- **Remote members (recommended option (a), open question 5):** the member uploads to their own
  instance B as above. B sends the URL in its `harmony:SetMemberProfile`. A fetches the file, checks
  type and size against its own limits, stores it in its own storage and publishes A's URL. The
  profile keeps working if B goes away, and A controls what it serves.
- Old files are removed when replaced or reset, unless existing avatar handling keeps them, in which
  case follow it and note that.

### Leaving, bans, rate limits, deletion

- **Leaving** deletes the member's server profile in that server, on the host and in copies; Discord
  drops nicknames too. Rejoining starts fresh.
- **Bans:** open question 9 (keep the profile for moderation context, or delete like leaving).
- **Rate limit:** at most a modest number of profile changes per member per server per window
  (builder's choice, for example 10 per 10 minutes). Each change means a Realtime broadcast and, for
  servers with members elsewhere, a federated `Update`. Moderator actions aren't limited.
- **Account deletion** cascades through the foreign keys, and the deletion fan-out tells hosts of
  remote servers to drop the member's profiles. **Data export** includes the user's server profiles.

### Discord bridge

- Messages relayed from Harmony to Discord use the sender's server nickname and avatar as the webhook
  name and avatar.
- Profile sync (fork #201 / upstream harmony#201) and the migration (harmony#202) write through
  `service_set_member_server_profile`, which now targets the new table. Both PRs are rebased on v2;
  their behaviour doesn't change.

### Migration for instances already running #200

harmony.rooday.com (this fork) already has #200's migrations (`20261112100001`–`20261112100006`),
with server profile columns on `user_servers` holding real data. Upstream instances never had them.
- Upstream's v2 migration creates the table, copies any existing `user_servers.nickname` values into
  it, and drops that column if question 2 says it moves.
- The fork needs one extra migration, kept fork-only or written to no-op where the columns don't
  exist. It copies `avatar_url`, `banner_url`, `bio` and `pronouns` from `user_servers` into the new
  table (converting avatar and banner URLs to storage paths) and drops those columns.
- Migration versions must not clash with upstream's. Pick v2's numbers after checking upstream
  master at implementation time, and mention this in the PR as an operator note.

## Benchmarks

Nothing in the repo measures logged-in startup or notification fan-out, and there's no seed for large
servers (`scripts/benchmark-api.sh` covers anonymous reads only). Before the PR goes upstream:

- **Seed script**, for example `scripts/seed-scale.sql` or `.mjs`, run against the local stack.
  Parameters: servers, members per server, servers per user, share of members with a server profile,
  messages per channel. Default: 20 servers × 2,000 members, 5% with server profiles, a few thousand
  messages. Worth offering upstream on its own.
- **Measurements, master vs. #200 vs. v2:**
  - startup member-list query: bytes and time for a user in all 20 servers;
  - tier-1 batch for 50 on-screen IDs: time;
  - one @everyone to 2,000 recipients: total queries and time from `send_notification` through the
    push worker (FCM/web push stubbed);
  - `search_server_members` for a two-letter and a five-letter query in a 2,000-member server.
- The numbers go in the PR description, per the fork's scale guidelines.

## Open questions (for the maintainer)

1. **Overall design.** Whole profile, nickname and avatar loaded lazily in batches, card fields loaded
   on click, startup back to IDs only, sender profile looked up once per message for notifications.
   Does this address both the `user_servers` load and the @everyone concern?
2. **The table.** Is a separate sparse `server_member_profiles` table better than columns on
   `user_servers`? Should the existing `user_servers.nickname` move into it or stay where it is?
3. **Which name shows where.** Is the fixed rule (nickname inside a server; account name and handle on
   the card, in moderation and outside servers; no user setting) acceptable?
4. **Federation.** Is "the host owns the server profile, copies display it, using the
   `harmony:memberRoles` pattern" the right direction? Does keeping server profiles out of actor
   documents and posts answer the concern about public federated identities?
5. **Remote members' images.** Should the host re-host images uploaded on the member's instance (a,
   recommended), load them from the member's instance (b, how remote account avatars work today), or
   take uploads directly (c, needs a new cross-instance upload path)? Does the existing media cache
   already do part of (a)?
6. **Benchmarks.** Which numbers would convince him, and what are realistic sizes on har.mony.lol
   (largest server, typical servers per user), so the seed data matches reality?
7. **Delivery order.** Should account pronouns ship first as a small separate PR? Should federation
   be part of the same PR, or a second PR right after the single-instance version?
8. **Persisted cache.** Is keeping tier-1 server profiles in IndexedDB worth it now, or only as part of
   the broader caching discussion?
9. **Bans.** On a ban, keep the member's server profile (moderation context, audit) or delete it as on
   leave?

## Left to the implementer

- Exact RPC, activity and property names, within the shapes above.
- Index choice for `search_server_members`, justified by the benchmark.
- How tier-1 batching hooks into `ensureProfilesAvailable` and the member list, and the exact
  placeholder while a lookup is pending.
- Rate-limit numbers.
- Test split:
  - pgTAP: the table, RLS, RPCs, `send_notification` adding the sender profile, `search_server_members`
    and the migrations;
  - vitest: resolvers, cache (including remembered "no profile"), notification formatting and
    federation converters;
  - one Playwright path: set a nickname and avatar, see them in chat and in a push payload, not in DMs;
  - a federation test: a remote member's change reaches the host and another copy.
