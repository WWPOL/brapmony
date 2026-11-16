# Discord history import — design

Date: 2026-11-09
Repos: Harmony (`WWPOL/brapmony`) and the bridge (`WWPOL/harmony-discord-bridge`, fork of `y4my4my4m/harmony-discord-bridge`)

## Intent

A Discord bridge only copies messages posted after a channel pair exists and while the bridge is running. Communities moving to Harmony, or running both, lose everything before that. This feature lets any bridged server pull a paired Discord channel's past messages into its Harmony channel, so the channel reads as one continuous history.

It is a built-in Harmony feature for every bridged server and every bridge mode (`self`, `hosted`, `instance`). It is not a one-off script.

## Goals

- Import a paired Discord channel's history, threads included, into its Harmony channel with original timestamps, the Discord author's name and avatar (as live bridged messages show them), attachments, embeds, stickers, mentions, emoji, and reply links.
- Start it three ways:
  - an **Import history** button on each channel pair in **Server settings → Discord Bridge**, with progress;
  - an **Import all** button in the same settings;
  - `/bridge import-history` in Discord, for the current channel or, with an option, every paired channel.
- Make it quiet. Imported messages notify no one, change no unread counts, don't federate, reach no bot or outgoing webhook, and are subject to neither AutoMod nor slowmode.
- Make it safe to repeat. A re-run, an overlap with live-bridged messages, or a resume after a crash never duplicates a message.
- Make it resumable and cancellable. Large servers take hours, because Discord limits history reads. A restarted bridge continues where it stopped.

## Non-goals (this version)

- **Reactions.** Discord returns counts only; naming reactors costs one request per emoji per message, and Harmony stores reactions per person. A read-only "reactions on Discord" display was considered and rejected for UI clutter. This can be added later.
- Importing from a DiscordChatExporter file, or into a server with no bridge.
- Importing DMs, voice-channel text chat, or forum channels. Pairs are text or announcement channels only, so forum channels can't be paired today.
- Messages by bots and webhooks. The live bridge skips `author.bot` messages, and the import does the same. This also skips the bridge's own webhook copies of Harmony messages, which already exist in Harmony.
- Discord system messages (joins, boosts, pins notices, thread-created notices). The live bridge skips these too.

## Anti-goals (failure even if it "works")

- Anyone gets a notification, push, mention or unread badge for an old message.
- Remote instances or federation followers receive years of history.
- Anything is reposted to Discord.
- Duplicate messages appear in a channel.
- The import path becomes a way to backdate or silence live messages. Only a Bridge-type bot, for a channel its bridge pairs and for a job that exists, can use it.
- An import monopolises Discord rate limits so the live bridge stalls. On the instance bot, one server's import must not stall other servers' bridges.

## Constraints and facts from the code

- **Harmony is the monorepo here.** The bridge lives in its own repo and talks to the bot gateway over the Bridge v2 API (`bot-gateway/src/api/BridgeV2API.ts`), the REST API (`BotRestAPI.ts`) and the gateway WebSocket.
- **Live relay path** is `BridgeRuntime.onDiscordMessage` (bridge `src/runtime/BridgeRuntime.ts` ~L1892). It uses `translator.discordToHarmonyParts`, `buildDiscordUserMetadata`, `resolveDiscordReplyToHarmony`, `threads.harmonyThreadFor`, then `harmony.sendMessage`. The importer must reuse this translation and metadata so imported and live messages are identical in shape: `metadata.discord_user`, `metadata.discord_message_id`.
- **Message insert** (`BotRestAPI.sendMessage`) inserts into `public.messages` via PostgREST with the service role. It applies `applyBridgeAttachmentPolicy` (link/refresh/mirror of Discord CDN URLs), mention resolution, and `botSuppliedMetadata` with the bridge-author check.
- **`messages` has ~70 triggers**, including push (`trigger_send_push_notification`), unread (`trigger_new_message_unread`), mention/role/thread/channel notifications, federation (`trigger_federate_channel_message`, `z_messages_pin_federation`), realtime broadcast (`trigger_broadcast_message_insert`), AutoMod (`trg_moderate_message`, `automod_message_guard`), slowmode, newcomer alerts, link previews, search indexing (`trigger_index_message`), counters, and integrity/permission guards.
- **Precedent for a session flag:** `harmony.silent_content_update` (set by `update_message_content_silent`) makes `handle_messages_updated_at` keep `updated_at`. The import uses the same mechanism with a new flag.
- **`updated_at = created_at` on insert** (`handle_messages_updated_at`). The gateway's MESSAGE_UPDATE/DELETE feed (`channel_message_changes`) reads only rows with `updated_at > created_at`, from a cursor that starts at "now". Backdated rows are therefore not replayed as updates. The builder must confirm how MESSAGE_CREATE reaches bots (EventDispatcher) and make sure imported rows don't.
- **The bridge already skips echoes** of rows carrying `metadata.discord_user`. It is still a backstop, not the mechanism.
- **Discord API:** history is 100 messages per request (`before` cursor). There is no message count per channel, and bots can't use guild search, so there is no exact total. Thread listing: active threads per guild in one call; archived public threads 100 per page per channel; archived private threads need Manage Threads. Pins: one call per channel. History reads need View Channel and Read Message History. Content needs the Message Content intent.
- **Bridge config polling:** `BridgeConfigWatcher` polls `discord_bridges.updated_at` every 5 s and pushes `BRIDGE_CONFIG_UPDATE`. The settings page polls every few seconds.
- **Encrypted channels:** `harmony_channels[].encrypted` exists in the bridge config. Bridged messages can't go into an E2EE channel, so the import must refuse them.

## Approaches considered

**A. The bridge pulls history; Harmony gets a dedicated import API.** *Chosen.* Harmony stores import jobs per pair. The settings buttons and the Discord command create jobs; the bridge reads Discord history and posts batches to a new bridge-only endpoint. The endpoint inserts with original timestamps under an import flag that the side-effect triggers respect. Progress and resume point live in Harmony. Most work and spans both repos, but it is the only option that serves both the buttons and the command, keeps message translation in one place (the bridge), and uses the token the bridge already has.

**B. The owner uploads a DiscordChatExporter export in Harmony.** Rejected. It doesn't fit the Discord command, needs a separate export token (a user token is against Discord's terms), duplicates the bridge's translation logic inside Harmony, and means handling multi-GB uploads.

**C. The bridge uses the existing send-message call with a `created_at` field and an "imported" metadata flag.** Rejected. It costs one HTTP request per message (too slow for large servers), and it hangs backdating and trigger-skipping on a flag any bridge could set on any message. Harder to fence than a job-scoped endpoint.

## Decided behaviour

### Who can start it

- In Harmony: members who can manage the bridge today, i.e. the server owner or Manage Server.
- In Discord: `/bridge import-history` follows the existing `/bridge` rule, i.e. Discord Administrator.

### What a job covers

- One job per channel pair. **Import all** creates one job per eligible pair.
- **Eligible pairs:** direction `both` or `to_harmony`, and the Harmony channel is not encrypted. Ineligible pairs show why, and no button.
- A job walks the Discord channel from its newest message back to the channel's first. Every message whose Discord ID already exists in the Harmony channel is skipped. This also fills gaps from times the bridge was offline.
- Threads under the channel (active, archived public, and archived private when the bot has Manage Threads) are imported after the channel's own messages. Each Discord thread maps to a Harmony thread via the existing pairing (`discord_thread_id`, idempotent create), backdated to the Discord thread's creation time.
- **Pins** *(my call, cheap: one request per channel):* Discord-pinned messages are marked pinned in Harmony, without federating and without notices. Harmony allows 50 pins per channel (baseline ~L15731). When the limit is reached, the remaining pins are skipped and counted on the job, not failed.
- **Thread starters** *(my call):* a Discord thread started from a message links to that message's Harmony copy (`threadSync.harmonyThreadFor` already does this). A thread with no starter in Harmony (starter deleted, or not a bridged message) is created without one, which the create-thread path already allows. Creating backdated threads needs an import variant of thread creation under the same flag and marker.
- **Edits** *(builder's call):* a Discord message with `edited_timestamp` gets `updated_at = edited_timestamp`, so Harmony shows "(edited)" as it does for live edits.

### Replies

- A reply links to its target when the target is already in Harmony.
- Because the walk goes newest-first, targets are often imported after their replies. The importer stores the target's Discord ID on the reply, and a final pass per job links remaining replies whose target now exists.
- A target that never arrives (deleted on Discord, in another channel) stays as the live bridge already renders an unresolved reply.

### Quiet import

Imported rows are inserted inside a function that sets a transaction-local flag (e.g. `harmony.history_import = 'true'`). Every trigger on `messages`, and on any table the import writes (threads, pins), must be classified and its behaviour under the flag decided:

- **Skip under the flag:** anything that notifies a person (push, mention, role mention, thread reply, channel notifications, newcomer alerts), changes unread state, federates (messages, pins, threads), broadcasts to connected clients or bots, enqueues outgoing webhooks, enforces slowmode, runs AutoMod, or triggers link-preview fetching. Discord's own embeds already come through the translator.
- **Also skip: channel and thread "last activity".** Backdated rows must not move a channel's last-message time, sort order, new-activity indicator or latest-message preview, nor a thread's.
- **Keep:** validation, integrity, permission and media guards; search indexing; counters and derived data that would be wrong without them (builder judges each).
- **Persisted marker (my call):** the transaction-local flag isn't visible to the bot gateway's dispatcher, which builds MESSAGE_CREATE from the row (`EventDispatcher.ts` ~L830). So the insert function also stamps `metadata.history_import = true` on every imported row, and the dispatcher, outgoing webhooks and realtime broadcast skip rows carrying it. The key is visible in API metadata; that's acceptable and lets bots and clients tell imported messages apart. Only the import function may set it: `botSuppliedMetadata` must drop it from bot-supplied metadata.
- The flag must be impossible to set from a client session. The insert function is `SECURITY DEFINER`, granted to the service role only, and the gateway calls it only after verifying the bot, bridge, pair and job.

### Attachments

The import goes through the same `applyBridgeAttachmentPolicy` as live messages. History fetched from Discord comes with freshly signed CDN URLs, so `mirror` mode copies them while they're valid. Mirroring years of media can use a lot of disk; the import UI should mention this when the instance is in mirror mode *(builder's call on wording)*.

### Running, queueing, cancelling, resuming

- **Job states:** `queued` → `running` → `done`, plus `failed`, `cancelled` and `paused`. `paused` means the bridge went away mid-job; it auto-resumes when the bridge reconnects.
- **One running job per bridge.** Others stay queued. On the instance bot, where one process serves many bridges, the host also caps total concurrent imports so live relaying keeps its rate-limit headroom. Builder picks the cap; it should be configurable.
- **The resume point is stored in Harmony**: phase, the oldest Discord message ID done, the thread being processed, and the list position. The bridge updates it after each committed batch, so a crash loses at most one batch, which re-sends harmlessly because of dedupe.
- **Cancel:** from the settings (per job, and cancel all) and from Discord (`/bridge import-history` with a cancel option, or a separate `/bridge import-cancel`, builder's call). Already-imported messages stay. A cancelled or failed job can be restarted, and it resumes from its stored point.
- The import must yield to live traffic. Live Discord→Harmony relaying shares the Discord client and must not queue behind history reads.

### Progress

Discord gives no message count, so progress is by date. A Discord channel's creation time comes from its snowflake ID. Percent ≈ (start time − reached time) / (start time − channel creation time). The start time is frozen when the job starts: the newest message at that moment. Live messages arriving afterwards are the live bridge's job. The settings show, per pair: state, percent bar, "Back to *month year*", messages imported, and during the thread phase "Threads *n* of *m*". **Import all** also shows a combined line. The Discord command replies with a short status and can be re-run to see progress.

### Failure reporting

Use the bridge's existing problem reporting (`/status` problems) for persistent conditions: Message Content intent off, missing Read Message History on a channel, and the bot unable to see private archived threads (a warning: threads skipped, not a failure). A job that fails stores a short reason shown on its pair.

### Compatibility

- The settings show import buttons only when the connected bridge reports it supports import (a capability or version in its `/status` payload). Otherwise they show "Update the bridge to import history".
- Old bridges ignore the new job events.

## Parts left to the builder

Within the decisions above:

- **Schema:** a jobs table, e.g. `discord_bridge_imports` (bridge, pair or Harmony and Discord channel IDs, state, phase, cursor fields, counts, error, requested by and where, timestamps), RLS for managers, and DB tests in `db_schema/tests`.
- **Dedupe mechanism (decided):** a unique partial index on `(channel_id, (metadata->>'discord_message_id'))` where that key is set. A migration first resolves any existing duplicates (keep the oldest row). This makes the import race-safe against live relay. The live `sendMessage` path must then treat a conflict on that index as success and return the existing message, not a 500. Only the singular `discord_message_id` is the key. The plural `discord_message_ids` is written on Harmony-origin messages relayed to Discord, which the import never creates.
- **The batch insert function and endpoint:** e.g. `POST /bridge/v2/imports/{id}/messages`, accepting up to N messages per call with `created_at`, optional `edited_at`, thread, reply target Discord ID, and pinned flag. It returns which Discord IDs were inserted and which skipped. It must run the same content pipeline as `sendMessage` (mention parts, attachment policy, bridge-author metadata) without the per-message side effects.
- **Endpoints for creating, listing, updating and cancelling jobs**, for both the bridge (bot token) and the app (user session or RPC). Plus how the bridge learns of new or cancelled jobs: piggyback on `BRIDGE_CONFIG_UPDATE`, a new gateway event, or polling. Must work in all three modes.
- **The full trigger audit** and the flag checks.
- **How MESSAGE_CREATE dispatch and realtime broadcast are suppressed** for imported rows.
- **Bridge internals:** an importer module beside `threadSync.ts` etc., rate-limit sharing with live relay, the reply fix-up pass, thread enumeration, command wiring in `commands.ts`, and a capability flag in `/status`.
- **UI:** in `src/components/settings/discord-bridge/BridgeChannelPairs.vue` and `BridgeStatusView.vue` or nearby, following existing component and i18n patterns.
- **Docs:** `docs/run/discord-bridge.md` (a new import section and the commands table; update the thread note "Threads that existed before the bridge are picked up when someone next writes in them" ~L191, and anywhere else that says history before pairing isn't copied), `docs/build/bridges.md` (new endpoints), `docs/self-host/discord-bridges.md` (concurrency cap, mirror disk note, version table), and the bridge README.
- **Tests across all three:** DB tests for the flag and dedupe, gateway unit tests for endpoint auth and fencing, bridge vitest for the importer (pagination, resume, reply fix-up, thread phase, skip rules), and an e2e spec if the existing bridge e2e setup allows.

## Release notes

- The Harmony change ships in a batch. The bridge change ships as a new bridge minor version, recorded in the version table in `docs/self-host/discord-bridges.md`.
- The fork's publish workflow pushes to a registry. Which image name self-hosters pull (`ghcr.io/y4my4my4m/...` vs the fork's) is a release decision outside this design.
