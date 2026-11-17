# Live Discord test kit (fork only)

This file is fork-only, like `CLAUDE.md`: keep it out of pull requests to upstream.

The e2e suite (`e2e/`) covers Harmony on its own. Testing the Discord bridge for real
(history import, mirror queue, emoji/sticker/sound/profile sync, Migrate from Discord)
needs a real Discord bot and server. That setup is the "test kit". It lives outside the
repos, in `~/.config/harmony-test/`, because it holds a bot token.

## What the kit contains

```
~/.config/harmony-test/
  discord-bot.env        bot credentials (secret, never commit)
  kit/                   scripts; kit/README.md has the full steps
    seed/                fills the Discord test server with messages and threads
    migration/           helpers for the Migrate from Discord flow (own README)
    stack.sh             runs e2e/stack.sh inside WSL from a CR-stripped copy
    run.mjs, r.sh        run a script or service with the stack's env
    bridge.sh            runs the bridge from source against the local gateway
    harmony-setup.mts    creates the Harmony owner, member, server and bridge
```

If you have a copy of the kit, put it back at `~/.config/harmony-test/` and follow
`kit/README.md`. If it's lost or you're on a new machine, rebuild it as below.

## Rebuilding the kit from scratch

### 1. A Discord test server

Create a new, empty Discord server for testing only (never a real community): the seed
script posts hundreds of messages and the tests create and delete channels, roles and
emojis. For moderation tests (timeouts, kicks, bans) you also need a second Discord
account in the server, because a bot can't moderate the server owner.

Turn on Developer Mode in Discord (User Settings → Advanced) and copy the server ID.

### 2. A Discord bot

1. At <https://discord.com/developers/applications>, create an application.
2. **Bot** page: reset and copy the token. Under Privileged Gateway Intents, turn on
   **Server Members** and **Message Content**.
3. **OAuth2** page: copy the client secret. For Migrate from Discord tests, add the redirect
   `http://localhost:5195/bot-gateway/bridge/v2/migrate/callback`.
4. Invite the bot to the test server with the Administrator permission:
   `https://discord.com/oauth2/authorize?client_id=<APPLICATION_ID>&scope=bot%20applications.commands&permissions=8`

### 3. The credentials file

`~/.config/harmony-test/discord-bot.env`:

```
DISCORD_TOKEN=...
DISCORD_APPLICATION_ID=...
DISCORD_TEST_GUILD_ID=...
DISCORD_CLIENT_SECRET=...
```

Never print it, paste it anywhere or commit it. If it leaks, reset the token on the Bot page.

### 4. The scripts

The scripts aren't in git. Without a copy you'll have to write them again; this is what
each one does:

- **Credentials loader** (`seed/creds.mjs`): reads `discord-bot.env` and makes Discord REST
  calls with the bot token, retrying on 429.
- **Check** (`seed/check.mjs`): prints the bot's name, whether the intents are on and the
  server's channels. Run it first to confirm the bot setup.
- **Seed** (`seed/seed.mjs`): creates an "Import test" category. **#import-main** gets about
  260 messages from 4 webhook personas, with images, an embed, an edit, pins and 2 messages
  over 500 characters. **#import-threads** gets a thread on a message, a standalone thread,
  an archived thread and an archived private thread. It writes the IDs (no secrets) to
  `seed/seed-output.json` and does nothing if that file already exists.
- **WSL stack runner** (`stack.sh <repo-in-wsl> up|down|status`): Docker only runs inside WSL,
  and the Windows checkout has CRLF line endings. It copies `e2e/`, `scripts/`, `db_schema/`
  and `supabase/` to `/tmp/hstack`, strips the `\r`, runs `e2e/stack.sh` and copies the
  resulting `e2e/stack.env` back into the checkout.
- **Env runner** (`run.mjs <cwd> <env-file> [KEY=value | KEY=@OTHER_KEY]... -- <command>`):
  runs a command with the variables from an env file plus overrides.
- **Harmony setup** (`harmony-setup.mts phase1|phase2`): phase 1 creates an owner and a member,
  a server, a self-mode bridge and its setup code, saved to `state.json`. Phase 2 pairs the
  seeded channels and starts the import.
- **Bridge runner** (`bridge.sh <page-delay-ms> <log>`): runs the bridge from source with
  `HARMONY_URL=http://localhost:3002`, its own `DATA_DIR`, the setup code on the first start,
  and `BRIDGE_IMPORT_TEST_AUTHORS` set to the seed webhook IDs plus the bot's user ID. Without
  that setting, the import skips webhook and bot messages, which is all the seed has.

### 5. Running it

1. `node seed/check.mjs`, then `node seed/seed.mjs` (once per test server).
2. Keep WSL running (`wsl -d Ubuntu -- bash -c "sleep 21600"` in a background terminal);
   otherwise Ubuntu shuts down when idle and takes the stack with it.
3. Start the stack: `bash stack.sh /mnt/c/<checkout> up`.
4. Start the bot gateway from the checkout on port 3002, with `SUPABASE_URL`,
   `SUPABASE_ANON_KEY` and `SUPABASE_SERVICE_ROLE_KEY` taken from `e2e/stack.env`'s
   `E2E_*` values, `INSTANCE_DOMAIN=localhost` and `NODE_ENV=development`.
5. `harmony-setup.mts phase1`, then `bridge.sh`, then `harmony-setup.mts phase2`.

## Pitfalls

- **The stack is shared between checkouts.** It runs as compose project `hme2e` on port 54480
  unless `E2E_PROJECT` and `E2E_PORT` are set, so an `up` or `down` from one worktree replaces
  another's, volumes included. Give each session its own `E2E_PROJECT` and `E2E_PORT` and its
  own copy of the kit (different `/tmp/hstack-*` and gateway port).
- **One bridge per bot token.** Two bridges running at once relay every message twice. On
  Windows, stopping a shell doesn't stop its `node` children: kill them by command line.
- The e2e stack has no realtime service: the web app shows new messages only after a reload.
- Check every UI change at phone size too (Playwright `devices['Pixel 7']`, scroll with the
  mouse wheel, not `scrollIntoViewIfNeeded()`).
