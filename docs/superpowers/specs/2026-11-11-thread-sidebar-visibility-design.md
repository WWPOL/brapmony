# Thread sidebar visibility: all active or joined only

## Why

After the Discord history import, every thread that was active on Discord showed up under its
channel in every member's sidebar. On Discord the sidebar lists only the threads you are in; the
rest are in the channel's Threads list. Harmony deliberately lists every active thread (and since
`20261110300001_thread_departures.sql`, leaving a thread hides it). Both behaviours are reasonable,
so this is a setting, not a replacement.

## Goals

- Two sidebar modes:
  - `all`: today's behaviour. Every active thread of the channel except ones the account left.
  - `joined`: only threads the account is a member of, plus the thread currently open, as on
    Discord (decided by the user): a thread opened without joining shows while it's open and drops
    off when you go elsewhere; joining, posting or being mentioned keeps it. The rest stay
    reachable from the channel's Threads button (`AllThreadsModal.vue`), which is unchanged.
- The instance admin sets the default. Each server can follow the instance or choose a mode.
- An @mention of a specific member in a thread reply adds that member to the thread, in **both**
  modes (decided by the user), like Discord. It also brings back someone who had left the thread
  (decided by the user).
- Role, @everyone and @here mentions add no one (decided by the user).

## Non-goals

- A per-user preference. The choice is the server's (instance default, server override).
- Changing which threads a member can see. Visibility stays where it is today: the
  `threads_select_member` RLS policy limits thread reads to channels in
  `current_user_viewable_channel_ids()` (`20261001200002_enforce_channel_view_permission.sql`).
  The sidebar filter only narrows what the client already received. Future private threads will
  restrict reads in that policy, and this feature follows it unchanged.
- Adding Discord members to imported threads. That belongs to the Discord account-claim work.
- Mentions added by editing a message (only new thread replies join anyone).

## Anti-goals

- A history import or bridged backfill adding anyone to threads.
- A mention adding someone to a thread in a channel they can't view (that would leak the thread
  and its channel).
- Anything changing for an instance that never touches the setting.

## Approaches considered

**Which threads the sidebar shows**

- **A. Filter in the client (chosen by the user).** The client already loads every active thread
  the account can see, with `is_member` and `left` (`ThreadService`, from the `thread_members` and
  `thread_departures` embeds). `joined` mode filters on `is_member`. Smallest change, a mode switch
  applies immediately, and the Threads list needs every thread anyway. Cost: the client still
  downloads all active threads, as it does today.
- **B. Filter in the database.** A query returns only the sidebar's threads and the Threads list
  loads the rest. Less data on very large servers, but it changes thread loading in two places for
  little gain at current sizes.

**Where the mention join happens**

- **In the database, in `thread_message_handler`** (chosen). That trigger already adds the author
  to `thread_members` on every thread reply, so every message source (web, bots, bridged Discord
  relay, federation) is covered, and it already knows when a history import is running
  (`is_history_import()`).
- In the client after sending: misses bots, the bridge and federation. Ruled out.

**Where the setting is stored**

- **The newcomer-alerts pattern** (chosen, my call): a nullable `server_settings` column (NULL =
  follow the instance) plus an `instance_config` default key, with MANAGE_SERVER-guarded get/set
  RPCs. See `20261006100001_newcomer_message_alerts.sql` and `src/services/NewcomerAlertService.ts`.
  Proven in this codebase, and the admin and server settings UIs already have a matching slot.
- A column on `servers`: would need its own write rules and is visible more widely than needed.

## Design

### Database (migration `20261112600001_thread_sidebar_mode.sql`, pgTAP `280_thread_sidebar_mode.sql`)

Numbers: `20261112500001` and pgTAP 274–276 are taken by `feat/discord-migration`; fork master is
at `20261112400001` / 279. Re-check before writing.

- `server_settings.thread_sidebar text NULL CHECK (thread_sidebar IN ('all', 'joined'))`.
  NULL follows the instance.
- `instance_config` key `thread_sidebar_default`, value `'all'` or `'joined'`. Absent or any
  other value reads as `all` (my call: nothing changes until someone flips it).
- `get_server_thread_sidebar(p_server_id)` and `set_server_thread_sidebar(p_server_id, p_mode)`
  (p_mode NULL = follow the instance) for MANAGE_SERVER, returning
  `{ mode, server_value, instance_default }`, mirroring `get/set_server_newcomer_alerts`,
  including its audit log entry if newcomer alerts write one.
- `server_thread_sidebar_mode(p_server_id) RETURNS text`: the effective mode, callable by any
  member of the server (members need it to draw their sidebar; the get RPC above is staff-only).
  Non-members get an error or NULL, matching how similar member-readable RPCs behave. The setting
  applies to local servers only. For a server hosted on another instance the client uses `all`
  without calling the RPC, or treats an error as `all` (my call).
- `thread_message_handler`: a separate loop after the existing author insert. It runs only when
  `NEW.thread_id IS NOT NULL`, `NEW.is_system IS NOT TRUE`, `NEW.is_deleted IS NOT TRUE` and
  `NOT public.is_history_import()`. For each `mention` part of `NEW.content`, resolve the profile
  with `resolve_mention_profile_id(part, NEW.user_id, domain)`, the same resolver the mention
  notification uses. Insert that profile into `thread_members` with `ON CONFLICT DO NOTHING`
  when:
  - `can_view_channel(profile, thread's channel_id)` holds (it also requires server membership);
  - the profile is not the author (already covered by the author insert);
  - and, my call, the mentioned profile has not blocked the author (`has_blocked` /
    `is_blocked_by`, whichever matches the direction), so someone you blocked can't pull a thread
    into your sidebar.

  Notification suppression (mutes, do-not-disturb, notification preferences) does **not** apply:
  membership is not a notification. So the people joined are the people the mention resolves
  to, not necessarily the people notified. Remote (federated) profiles that pass the view check
  join like local ones, as they do today when they post. The existing `z_thread_members_joined`
  trigger then clears their departure and recounts `member_count`, so "a mention adds you back"
  needs no extra code. Role, @everyone and @here are a different content part type and are never
  resolved here. Bridged and imported messages are bot-authored; the bridge's live relay is not
  an import, so a mention there of a Harmony member joins them, the same as a notification would.
  The thread's starter message is a channel message (no `thread_id`) and joins no one.
- pgTAP covers: column check constraint; effective mode resolution (NULL → instance default,
  absent default → `all`); RPC permissions (MANAGE_SERVER for get/set, members for the effective
  mode, non-members refused); mention joins a viewer; mention doesn't join someone who can't view
  the channel; mention re-joins a departed member and clears the departure; role/@everyone parts
  join no one; nothing joins under the history-import flag; channel (non-thread) messages join
  no one; system and deleted thread replies join no one; a member who blocked the author is not
  joined; the author mentioning themselves changes nothing.
- Audit: follow `set_server_newcomer_alerts` for the server setting. The instance default goes
  through `set_instance_config`, which keeps its existing behaviour.

### Client

- `src/services/ThreadSidebarService.ts` (new), shaped like `NewcomerAlertService.ts`: get/set for
  the server setting, the instance default via `instance_config` + `set_instance_config`, and the
  member-readable effective mode.
- The effective mode per server is loaded when the server's channel list loads and cached per
  server id (store or composable; builder's choice), seeded from the device's remembered mode
  (see "Decided after review"), else `all`, so the sidebar never flashes empty. A change by an admin reaches other members' sidebars the next time their
  client loads that server's channels; no live subscription (my call). The admin who changed it
  sees it immediately.
- Unread and mention indicators are unchanged: thread entries in the sidebar have none of their
  own today (unread dots and mention badges are on the channel row), so hiding a thread drops no
  indicator.
- `ChannelSidebar.vue` `getChannelActiveThreads(channelId)`:
  - `all`: unchanged, `activeChannelThreads(channelId).filter(t => !t.left)`.
  - `joined`: `activeChannelThreads(channelId).filter(t => t.is_member || t.id === selectedThreadId)`.
- A member added by mention should see the thread appear without reloading. My call: the mention
  notification payload gets an additive `thread: { id }` for thread replies (newcomer alerts
  already use this shape), and when the client receives a mention notification carrying it, it
  refreshes that thread through `ThreadService` so `is_member` becomes true. If the builder finds
  an existing realtime path for membership changes, use that instead. If no notification arrives
  (the member muted the channel or turned notifications off), the thread appears on their next
  thread load; that's accepted, since those members chose not to be pinged.
- Settings UI:
  - Admin, Instance config: a "Threads in the sidebar" picker (All active / Joined only) next to
    `NewcomerAlertsDefault.vue`.
  - Server settings: "Instance default (…current…) / All active / Joined only", next to
    `ServerNewcomerAlerts.vue` in `ServerSettings.vue`.
  - Strings in all 7 locales.
- Tests: sidebar filter in both modes (member, non-member, left, open thread); service RPC calls;
  both settings components (load, save, follow-instance).

### Decided after review (the user)

- A mention of someone who can't view the channel does nothing: no join, and no notification
  (`send_notification` already skips recipients without VIEW_CHANNEL and recipients who blocked
  the sender). If they gain access later, the thread is not added to their sidebar
  retroactively; they find it in the Threads list.
- Someone who blocked the author is never pulled into a thread by that author's mention.
- The device remembers each server's last known mode (localStorage, keyed by server id). The
  client still looks the mode up on every server load and updates what it remembers; while the
  lookup is pending, or if it fails, the remembered mode is used. Only a device that has never
  loaded the server falls back to `all` (decided by the user).
- Switching a server back to "Instance default" clears its choice; its sidebar follows the
  instance from the next load (immediately for the admin who changed it). Memberships are
  untouched.

## Constraints

- Upstream-bound after the fork PR: branch for upstream from `upstream/master`, no Claude
  attribution. Migration and pgTAP numbers stay identical in fork and upstream.
- The mention join must stay cheap: it runs only for thread replies with mention parts, and the
  per-mention view check is a single permission lookup.

## Left to the builder

- Exact RPC names and error behaviour for non-members, following nearby conventions.
- Where the effective-mode cache lives (store vs composable).
- Whether `server_settings` rows always exist for a server or need an upsert in the set RPC
  (follow `set_server_newcomer_alerts`).
- Placement and wording of the settings controls, within the slots above.
