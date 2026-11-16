# Converting an instance bridge to a self-run bridge in place

Status: design and fork step, 2026-11-16. Fork-only. Nothing here goes upstream.

## Why

harmony.rooday.com has one Discord bridge: `discord_bridges` row `9c792d43-3dfa-403e-a763-5a178c4f976b`,
mode `instance`, bot `2fbbc834-8316-4ffe-ac71-24826e5c29da`. That bot owns its bridged and
imported messages and 402 reactions. The "instance bot" Discord application is the server owner's own
app.

Upstream 1.9.7 runs history import only when `instance_config.bridge_history_import = 'self_run'`
and the bridge's mode is `self` (`discord_bridge_import_block`,
`db_schema/migrations/20261112700001_bridge_import_limits.sql:85-97`). Upstream's only switch from
instance to self is to disconnect and set up again, which provisions a new bridge bot
(`discord_bridge_setup_code` calls `discord_bridge_provision_bot` when `bot_id IS NULL`,
`20261008300001_discord_bridges.sql:647-650`). Discord-id lookups answer only the bot that owns a
message, so the old history would detach. The conversion must keep the same row and the same
`bot_id`.

Verdict from the research below: an in-place conversion works. Redeem reuses `discord_bridges.bot_id`
and never mints a bot; only the mode check blocks it today. The fork step is one SQL function.

## Facts

### How a self bridge gets credentials

- `discord_bridge_setup_code(p_bridge_id)` (`20261008300001_discord_bridges.sql:633-665`) needs
  MANAGE_SERVER on the bridge's server (`discord_bridge_for_manager`, `:418-440`), refuses any mode
  but `self` (`:647`), provisions a bot only when `bot_id IS NULL` (`:649`), and stores the SHA-256
  of an `HB-XXXX-XXXX-XXXX` code in `discord_bridge_setup_codes` (table `:131-141`; one live code
  per bridge, 30 minutes).
- `POST /bridge/v2/redeem` (`bot-gateway/src/api/BridgeV2API.ts:175-195`, per-IP rate limited)
  calls `discord_bridge_redeem_code` (`20261008300001_discord_bridges.sql:861-895`). It marks the
  code used, refuses a bridge whose mode is not `self` or whose `bot_id` is NULL (`:887`), and
  returns `{bridge_id, server_id, harmony_token}`.
- The token comes from `discord_bridge_issue_token(bot_id)` (`:478-496`): it revokes every active
  token of the existing bot and issues one new one. No bot is created. The token is for
  `discord_bridges.bot_id`, so the self bridge acts as the same bot, and an instance host that still
  held the old token loses it.
- The bridge program (`bridge-rebuild/src/v2/credentials.ts`, `launch.ts:73-160`) redeems
  `HARMONY_SETUP_CODE` once, saves `credentials.json` in `DATA_DIR` (and redeems again only when
  the code changes) and reads the Discord token from `DISCORD_TOKEN`. The Discord token never
  reaches Harmony in self mode.

### What `discord_bridges.mode` controls

Allowed values: `self`, `hosted`, `instance` (CHECK, `20261008800001_discord_bridge_instance_bot.sql:43-45`).

| Reader | Effect |
|---|---|
| `discord_bridge_setup_code`, `discord_bridge_redeem_code` | `self` only |
| `discord_bridge_instance_hosted()` (`20261008800001...sql:570-600`), gateway `GET /bridge/v2/hosted/instance` (`BridgeV2API.ts:237-260`) | Lists `mode = 'instance'` bridges that have a guild, an active bot and a `discord_bridge_secrets` row with a Harmony token. Returns NULL (gateway 404) while the offer is off or unconfigured |
| `discord_bridge_hosted_list()` | `hosted` only |
| `discord_bridge_report_status` (`20261008800001...sql:637-730`) | An `instance` bridge's snapshot keeps only its own guild and never selects a guild; the instance bot's recorded bot user is updated from its heartbeats. A `self` bridge stores the full guild list and picks the guild only when none is set |
| `discord_bridge_set_guild` | Refuses `instance` bridges (their guild comes from OAuth) |
| `discord_bridge_instance_linked_count` and the bot limit | Count `instance` bridges with a guild, so a converted bridge frees a slot |
| Unique index `discord_bridges_instance_guild_key` | One `instance` bridge per Discord guild; not applied to `self` |
| `discord_bridge_import_block` | `bridge_mode` unless `self`; `instance_off` unless `bridge_history_import = self_run` |
| Gateway `bridgeConfig.ts:199`, `GET /config` and `BRIDGE_CONFIG_UPDATE` | Passes `mode` and `history_import.{enabled,reason}` to the bridge |
| Settings UI (`src/components/settings/discord-bridge/*`) | `self` shows "Get a setup code" (`BridgeConnectSelf`); `instance` shows the Add to Discord flow and the instance-intent notes |
| Fork Migrate from Discord (`20261116990007`, `...10`, `...11`) | Reads `mode = 'hosted'` only; not affected |
| Vault secrets | `discord_bridge_secrets` holds a Harmony token for `instance` (and a Discord token too for `hosted`). The `discord_bridge_secrets_purge` trigger (`20261008300001...sql:332-350`) deletes the Vault secrets when the row is deleted. A `self` bridge has no row |
| App emoji map | Not in the database. Local file, see below |

If the offer (`discord_bridge_instance_bot_enabled`) is switched off, `instance` rows stay as they
are. The gateway answers 404, and the host stops every instance bridge and its Discord client
(`bridge-rebuild/src/v2/InstanceHost.ts:146-152`). The row is offline until the offer is back on.
`discord_bridge_instance_bot_clear()` also deletes the stored application and its Vault secrets
(`20261008800001...sql:338-370`); instance rows stay.

**Hazard.** While the offer is on, the host leaves every Discord server its shared client is in that
no instance bridge links, 10 minutes after the last poll that linked it (`InstanceHost.ts:44`,
`:221-243`). If the bridge is converted to `self` while the offer is on and the self bridge uses
the same Discord application, the host removes the bot from the server. The offer must be off
first. The fork function enforces this.

### What the self-run bridge needs

- Image `ghcr.io/y4my4my4m/harmony-discord-bridge` (upstream's; this instance runs the fork's `ghcr.io/wwpol/harmony-discord-bridge`). Its `Dockerfile` runs as
  `node` (uid 1000) with `DATA_DIR=/data`, `HEALTH_PORT=8080`, `VOLUME /data`.
- Env (`src/env.ts:46-125`, `bridge-rebuild/docker-compose.yml`): `HARMONY_URL` (the instance's
  public address, e.g. `https://harmony.rooday.com`), `HARMONY_SETUP_CODE` (first start only),
  `DISCORD_TOKEN` (the application's bot token). Optional: `LOG_LEVEL`, `MAX_UPLOAD_MB`,
  `BRIDGE_IMPORT_POLL_MS`, `BRIDGE_IMPORT_PAGE_DELAY_MS`. No `BRIDGE_MODE`.
- Volume `/data`: `credentials.json` (written at redeem), `permission-sync.yml`
  (`V2Bridge.ts:347`: `<DATA_DIR>/permission-sync.yml`), `app-emojis/<application id>.json`
  (`V2Bridge.ts:348`).
- The host keeps the same files elsewhere: permission-sync at
  `<host DATA_DIR>/instance/<bridge id>/permission-sync.yml` (`launch.ts:219`) and the emoji map at
  `<host DATA_DIR>/app-emojis/<application id>.json`, shared by every bridge of that application
  (`launch.ts:220`). Copy both into the new volume (`/data/permission-sync.yml`,
  `/data/app-emojis/`). If the permission file is lost, the permission sync starts from a blank
  state; if the emoji map is lost, the bridge rebuilds it and a duplicate-name rejection from
  Discord identifies an earlier upload.
- The Discord application needs the same privileged intents the instance host used (Server
  Members, Message Content; Presence only if "sync presence" is on).

### What lives in the database (kept) and what is local

Kept by the conversion because they are columns or rows of the bridge: `server_id`, `bot_id` and the
bot's installation and permissions, `discord_guild_id` and name, `discord_application_id`, `settings`,
`snapshot` and `status` (replaced on the first heartbeat), `discord_bridge_channels` (pairs and
directions), and everything keyed by `bot_id` (messages, reactions, `bridge_message_by_discord_id`
lookups) or `bridge_id` (imports, privacy opt-outs). Webhooks are created and held by the bridge
program on Discord's side and found again through the pairs; nothing about them is stored per mode.
Local to the bridge program: `credentials.json`, `permission-sync.yml`, the app emoji map.

### What changes or breaks when the mode changes

- No trigger or CHECK blocks `instance` to `self`. `discord_bridges_touch`
  (`20261008300001...sql:266-283`) moves `updated_at` when `mode` changes, so the gateway watcher
  sends `BRIDGE_CONFIG_UPDATE`.
- Left behind, the `discord_bridge_secrets` row would keep a Vault token that the redeem revokes.
  `discord_bridge_link_states` rows are instance-only OAuth states. Both are cleared.
- `discord_bridge_setup_code` cannot serve an instance admin who is not a server manager, so the
  function builds the code itself, in the same table and format.
- Upstream writes no audit row for a bridge mode change, only for instance config changes
  (`log_admin_action` with `'config_change'` in `discord_bridge_instance_bot_set` and `_clear`).
  The function writes one in the same shape.
- The gateway holds no state per mode; there is nothing to clear in `bot-gateway`.

## Design: `public.fork_convert_bridge_to_self(p_bridge_id uuid) returns text`

Migration `db_schema/migrations/20261116990018_convert_bridge_to_self.sql`, pgTAP
`db_schema/tests/917_convert_bridge_to_self.sql`. SECURITY DEFINER, executable by `authenticated`
(not `anon`, not `service_role`); the body requires `is_current_user_admin()` (the check
`discord_bridge_instance_bot_clear` uses).

1. Refuse a caller that is not an instance admin: `42501`.
2. Lock the bridge. Unknown: `P0002`. Not `instance`: `22023`. No `bot_id`: `22023` (nothing to
   keep, and a setup code would provision a new bot).
3. Refuse when the offer is on and configured and the bridge's application is the instance
   application, or is not yet known: `55000`, "Turn the instance bot off first". A different
   application may keep the offer on.
4. Delete the bridge's `discord_bridge_secrets` row (the trigger deletes its Vault secrets) and its
   `discord_bridge_link_states`.
5. Set `mode = 'self'`. `bot_id`, guild, settings, snapshot and pairs are untouched.
6. Insert a setup code (replacing any live one) and return it.
7. Write an `admin_audit_log` row: `config_change`, target `discord_bridge_mode`, with bridge,
   server and bot ids and `old_value` `instance`, `new_value` `self`.

Not done: no UI; `history_import`'s own checks are unchanged. Turning on `bridge_history_import` is
the existing admin setting.

Call path: SQL. The REST RPC needs the admin's JWT. In psql the transaction sets the same claims
the pgTAP bootstrap uses (`tests.authenticate_as`), and the function reads the admin from them.

## Runbook (harmony.rooday.com)

Do this when nobody relies on the bridge. Relay pauses between steps 3 and 7 (a few minutes). The
commands run on the server, in the Harmony folder.

1. **Update the instance** to the rebuild so migration `20261116990018` exists:
   `HARMONY_REMOTE=fork harmony update --version master` (the update takes its own backup too).
2. **Take a backup**: `harmony backup --no-storage` (this change does not touch uploads), and copy
   the folder off the server. The bridge host's own files are not in it, so copy out its permission
   file too (step 6 uses it):
   `sudo docker cp harmony-discord-bridge-host:/data/instance/9c792d43-3dfa-403e-a763-5a178c4f976b/permission-sync.yml ~/permission-sync.yml`.
   Also write down how many messages the bridge bot has now, for step 9:
   `sudo docker exec -i supabase-db psql -U postgres -d postgres -tAc "SELECT count(*) FROM messages WHERE bot_id = '2fbbc834-8316-4ffe-ac71-24826e5c29da'"`.
3. **Turn the instance bot offer off**: Admin panel, Discord bridge, instance bot, switch it off.
   Or in SQL:
   `sudo docker exec -i supabase-db psql -U postgres -d postgres -c "UPDATE instance_config SET config_value = 'false'::jsonb WHERE config_key = 'discord_bridge_instance_bot_enabled'"`.
   The bridge host should stop the instance bot within about a minute, since it polls every 60 s
   (`sudo docker logs harmony-discord-bridge-host`; the line should read "Instance bot disabled on this
   Harmony instance; stopping its bridges", from the bridge source, not yet seen on this instance). Don't leave it on: the host would remove the bot from your Discord server
   10 minutes after the bridge stops being an instance bridge, and the function refuses while the
   offer is on for the bridge's own application. If you offer another application to other
   communities, it may stay on.
4. **Run the conversion.** Find your admin account's login id, then call the function. Keep the
   code it prints; it works for 30 minutes.

   ```
   sudo docker exec -i supabase-db psql -U postgres -d postgres -c \
     "SELECT auth_user_id, username FROM profiles WHERE is_admin"
   sudo docker exec -i supabase-db psql -U postgres -d postgres <<'SQL'
   BEGIN;
   SELECT set_config('request.jwt.claim.sub', '<ADMIN auth_user_id>', true);
   SELECT set_config('request.jwt.claims', json_build_object('sub', '<ADMIN auth_user_id>')::text, true);
   SELECT public.fork_convert_bridge_to_self('9c792d43-3dfa-403e-a763-5a178c4f976b');
   COMMIT;
   SQL
   ```

   The result is a code like `HB-ABCD-EF23-GH45`. Check that
   `SELECT mode, bot_id FROM discord_bridges WHERE id = '9c792d43-3dfa-403e-a763-5a178c4f976b'`
   shows `self` and `2fbbc834-8316-4ffe-ac71-24826e5c29da`.
5. **Stop anything else using the same Discord application token** (an old bridge container, a
   dev bridge). Two programs on one token relay everything twice.
6. **Put the permission file into a new volume** before the first start. This instance's bridge
   host keeps only `permission-sync.yml` (no `app-emojis` folder), so that is all to copy; the
   volume must end up owned by uid 1000, which the last command does whatever happens before it.

   ```
   sudo docker volume create harmony-bridge
   sudo docker run --rm -v harmony-bridge:/data -v "$HOME":/src:ro alpine sh -c \
     'cp /src/permission-sync.yml /data/permission-sync.yml; chown -R 1000:1000 /data'
   ```

   If the file is missing, the bridge starts with a blank permission-sync state; the second command
   still sets the owner. If you ever have an `app-emojis` folder to bring too, copy it with
   `cp -r /src/app-emojis /data/ 2>/dev/null || true` on its own line before the `chown`, never
   chained to it with `&&`.
7. **Start the self-run bridge** with the same Discord application's bot token and the code. The
   image is the fork's: use tag `master`, or a pinned `sha-...` tag once the rebuilt bridge is
   published (prefer the pinned tag, since `master` moves).

   ```
   sudo docker run -d --name harmony-bridge --restart unless-stopped \
     -v harmony-bridge:/data \
     -e HARMONY_URL=https://harmony.rooday.com \
     -e HARMONY_SETUP_CODE=HB-ABCD-EF23-GH45 \
     -e DISCORD_TOKEN=<the application's bot token> \
     ghcr.io/wwpol/harmony-discord-bridge:master
   sudo docker logs -f harmony-bridge
   ```

   Wait for "Setup code redeemed" and a connected line (wording from the bridge source, not yet
   seen running). The code is used once and `credentials.json` in the volume keeps the login, so
   after the first start recreate the container without the code and the data stays:

   ```
   sudo docker rm -f harmony-bridge
   sudo docker run -d --name harmony-bridge --restart unless-stopped \
     -v harmony-bridge:/data \
     -e HARMONY_URL=https://harmony.rooday.com \
     -e DISCORD_TOKEN=<the application's bot token> \
     ghcr.io/wwpol/harmony-discord-bridge:master
   ```
8. **Turn on history import**: Admin panel, Discord history import, History import = "Self-run
   bridges" (read its warning first). Or set `instance_config` `bridge_history_import` to
   `"self_run"`.
9. **Verify** (first on the ROODAY Dev Server pair, then spot-check the rest):
   - Server settings, Discord bridge: a self-run bridge, connected, with the same channel pairs.
   - Relay both ways: a new Harmony message appears in Discord; a Discord message appears in
     Harmony.
   - An old imported message: edit it in Discord and in Harmony, then delete one; each shows on the
     other side. That proves old messages still belong to the same bot.
   - An old reaction: add and remove one on an old message.
   - Import: import one small channel; it finishes and its messages appear, older than the live
     ones.
   - `SELECT count(*) FROM messages WHERE bot_id = '2fbbc834-8316-4ffe-ac71-24826e5c29da'` is at
     least the number you wrote down in step 2: all of its existing messages are still there.
10. **Clean up**: once everything checks out, leave the offer off. The old host
    container (`harmony-discord-bridge-host`) can stay; it has nothing to run. Keep `~/permission-sync.yml`
    for a while in case of rollback.

### Rollback

Stop the self bridge first (`sudo docker stop harmony-bridge`) if it has started. Then:

1. Put the row back as an instance bridge, with a fresh token for the host:

   ```
   sudo docker exec -i supabase-db psql -U postgres -d postgres <<'SQL'
   BEGIN;
   UPDATE discord_bridges SET mode = 'instance' WHERE id = '9c792d43-3dfa-403e-a763-5a178c4f976b' AND mode = 'self';
   INSERT INTO discord_bridge_secrets (bridge_id, discord_token_secret, harmony_token_secret)
   VALUES ('9c792d43-3dfa-403e-a763-5a178c4f976b', NULL,
           vault.create_secret(public.discord_bridge_issue_token('2fbbc834-8316-4ffe-ac71-24826e5c29da'), NULL,
                               'Discord bridge 9c792d43-3dfa-403e-a763-5a178c4f976b: Harmony bot token'))
   ON CONFLICT (bridge_id) DO UPDATE SET harmony_token_secret = EXCLUDED.harmony_token_secret, updated_at = now();
   COMMIT;
   SQL
   ```

2. Turn the instance bot offer back on (step 3 reversed), set History import back to Off if you
   want, and `sudo docker rm -f harmony-bridge`. The messages and reactions never moved, so no data needs
   restoring.

Only if the database is damaged: `harmony restore` the step 2 backup. That drops everything
written since the backup.

The rollback SQL was not run against a database with Vault in this task; try it on a copy first if
you can.

## Not built

If a bridge has no `bot_id` the function refuses. Moving ownership of messages and reactions from
one bot to another was the fallback if in-place conversion could not work; it can, so it is not
built.
