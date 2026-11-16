# Fork rebuild on upstream Harmony 1.9.7

Spec: none separate; the analysis and the user's rulings are recorded here (Context, Rulings).

## Context

Upstream Harmony 1.9.7 (`upstream/master` af4c124d) took most fork PRs through its #205, with a
lot of rework. Fork master before the rebuild is the tag `fork-pre-1.9.7` (f0ea8530). It runs on
harmony.rooday.com at 3638ff87 with every fork migration applied.

The rebuild branch `rebuild/1.9.7` starts at `upstream/master` and adds only the fork-only work, as
clean feature commits. Everywhere else upstream's code wins, including files where upstream holds
a reworked version of one of our merged PRs.

Fork-only work to port, from merge commits on `fork-pre-1.9.7`. A PR's diff is
`git diff <merge>^1 <merge>`.

| Fork PR | Merge | What |
|---|---|---|
| #5 + f0ea8530 | 834c465d, f0ea8530 | CLAUDE.md (take the file as it is at `fork-pre-1.9.7`) |
| #11 | ead3b34b | pgTAP 250 busy_day near UTC midnight |
| #3 | 2e03f778 | server profiles, pronouns, Change/Manage Nicknames |
| #22 | 681592fd | Migrate from Discord |
| #26 | 61ef1831 | bridge server profile sync endpoints |
| #27 | 48d279e1 | thread sidebar all/joined, @mention joins thread |
| #28 | 8a2f7d59 | Migrate from Discord follow-ups |
| #30 | 3638ff87 | /migrate scrolls on phones |

Not ported: #12 (upstream rewrote those docs); every other fork PR is already in upstream.

Fork-only docs to carry over: `docs/superpowers/**` (from `fork-pre-1.9.7`),
`docs/specs/2026-11-13-server-profiles-v2-design.md` (from commit 792dd7ff), and this plan.

## Rulings (from the user, 2026-11-16)

- Rebuild on upstream and force-push fork master later. Nothing is pushed in this plan.
- Port server profiles v1 now; v2 comes later.
- Accept upstream's import rules (import off by default, `self` bridges only).
- Later, convert the instance's bridge from `instance` to `self` in place: same bridge row, same bot.
- Nothing touches harmony.rooday.com in this plan.

## Global Constraints

**Workspaces**
- Harmony: worktree `C:/Users/Rudhra/Code/worktrees/brapmony-rebuild`, branch `rebuild/1.9.7`.
- Bridge: worktree `C:/Users/Rudhra/Code/worktrees/bridge-rebuild`, branch `rebuild/2.7.0`
  (base `upstream/master` ab9a5a5, old fork master tag `fork-pre-1.9.7` = 7e3cfb9c).
- Never push, and never touch other worktrees or the main checkouts.

**Commits**
- Conventional Commits. These are fork-only commits, so keep the trailer
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Commits are signed through 1Password. If signing fails, retry once, then commit with
  `git -c commit.gpgsign=false commit ...` and say so in the report. Never change git config, and
  never use `--no-verify`.

**Migrations**
- Never edit an upstream migration file. Only fork files are added or renamed.
- Fork migrations are renumbered into the fork range `20261116990001`+. The mapping is exact:

| Old (applied on harmony.rooday.com) | New |
|---|---|
| 20261112100001_nickname_permissions | 20261116990001_nickname_permissions |
| 20261112100002_server_profiles | 20261116990002_server_profiles |
| 20261112100003_server_profile_broadcast | 20261116990003_server_profile_broadcast |
| 20261112100004_server_profile_review_fixes | 20261116990004_server_profile_review_fixes |
| 20261112100005_server_profile_audit_actions | 20261116990005_server_profile_audit_actions |
| 20261112100006_profile_change_pronouns | 20261116990006_profile_change_pronouns |
| 20261112500001_discord_migration | 20261116990007_discord_migration |
| 20261112600001_bridge_server_profile_sync | 20261116990008_bridge_server_profile_sync |
| 20261112700001_thread_sidebar_mode | 20261116990009_thread_sidebar_mode |
| 20261112800001_discord_migration_bridge_guild | 20261116990010_discord_migration_bridge_guild |
| 20261112800002_discord_migration_member_overrides | 20261116990011_discord_migration_member_overrides |
| 20261112800003_discord_migration_owner_roles | 20261116990012_discord_migration_owner_roles |
| 20261112800004_discord_migration_sync_choice | 20261116990013_discord_migration_sync_choice |
| 20261112800005_discord_migration_override_followups | 20261116990014_discord_migration_override_followups |

  New fork migrations take the next numbers: 20261116990015 onward, in task order.
- **Function rebase rule.** A fork migration runs after every upstream migration, so a function it
  defines replaces upstream's. For each `CREATE OR REPLACE FUNCTION` in a fork migration, find the
  newest upstream definition of that function: the highest-versioned upstream migration that
  defines it, else the baseline. Start from that body, and re-apply only the fork's own change.
  Known overlaps: `thread_message_handler` and `handle_message_federation` (latest upstream in
  20261110900004), `server_audit_attribute_bot` (20261112300001), `public_instance_config_keys`
  (20261112700001, which appends keys dynamically). Report any other function you find defined on
  both sides.
- Tables, columns and indexes keep their names. Statements stay idempotent where the old file was
  (`IF NOT EXISTS` and the like).

**pgTAP numbering** (fork range 901+, exact):
270_nickname_permissions → 901, 271_server_profiles → 902, 272_server_profile_broadcast → 903,
273_server_presence_accepted_only → 904, 274_discord_migration_config → 905,
275_discord_migration_claim → 906, 276_discord_migration_sync → 907,
280_bridge_server_profile_sync → 908, 281_thread_sidebar_mode → 909,
282_discord_migration_member_overrides → 910, 283_discord_migration_owner_roles → 911,
284_discord_migration_sync_choice → 912, 285_discord_migration_override_followups → 913.
New fork pgTAP files take 914 onward, in task order.

**Census files.** `db_schema/SURFACE.tsv`, `REACHABILITY.tsv` and `PUBLISHED-NO-CALLER.tsv` are
generated, never merged by hand. After a task changes SQL, regenerate them in WSL:
`MSYS_NO_PATHCONV=1 wsl bash ~/.config/harmony-test/kit/wsl-run.sh /mnt/c/Users/Rudhra/Code/worktrees/brapmony-rebuild scripts/generate-surface.sh`,
then the same with `scripts/generate-reachability.sh` and `scripts/check-published-no-caller.sh --write`.
In Git Bash, `~` means the Windows home, so give the kit's path in its WSL form, `/mnt/c/Users/Rudhra/.config/harmony-test/kit/wsl-run.sh`.

**Windows checkout**
- The checkout uses CRLF line endings. Edit with tools that keep them, such as the Edit tool or
  Python with `newline=''`.
- Use the exact case of every path: `sed -i` on a path with different case renames the file.
- Docker exists only inside WSL.

**Tests**
- App: `npx vitest run <paths>`, and `npx vue-tsc --build --force`.
- bot-gateway / federation-backend: `npx vitest run` and `npx tsc --noEmit`, each in its own
  directory.
- DB: `MSYS_NO_PATHCONV=1 wsl bash /mnt/c/Users/Rudhra/.config/harmony-test/kit/wsl-run.sh /mnt/c/Users/Rudhra/Code/worktrees/brapmony-rebuild scripts/run-db-tests.sh`,
  with your own `E2E_PROJECT` and `E2E_PORT` if you start an e2e stack. Never `down` a stack you
  didn't start.
- Known Windows-only failures: realtimeChannelsPrivate, shareUrls, usernameRules.reserved,
  viewportUnits, permissionCoverage, and `tests/unit/selfHost*`. Report these; don't fix them.
- Run `npm ci` once in each package directory you test, if `node_modules` is missing.

**Scale.** Follow CLAUDE.md "Scale and performance". Where upstream 1.9.7 reworked a hot path that
a fork feature touches (member lists in #210, bridge lookups), adapt the fork feature to upstream's
new shape. Don't put back the shape upstream replaced.

## Tasks

### Task 1: Fork meta

- Copy `CLAUDE.md` from `fork-pre-1.9.7`.
- Copy `docs/superpowers/` from `fork-pre-1.9.7`. Copy
  `docs/specs/2026-11-13-server-profiles-v2-design.md` from 792dd7ff.
- Apply #11's diff (ead3b34b) to `db_schema/tests/250_signup_review.sql`.
- Add `scripts/fork/check-migration-versions.sh [upstream-ref]` (default `upstream/master`). It
  lists the migration files of HEAD that the ref doesn't have. It exits 1 if any of them has a
  14-digit version the ref also uses, or a counter (the last six digits) that doesn't start with
  `99`. It prints each offender, and prints `ok: N fork migrations` on success. Add a short shell
  test `scripts/fork/check-migration-versions.test.sh`, run from a temporary git repo: one case
  passes, one collides, one uses the wrong range.
- In CLAUDE.md, add a "Syncing with upstream" section, at most 12 lines:
  - the fork migration range `…99xxxx`, and running the check script before each upstream sync;
  - the function rebase rule;
  - `db_schema/LEDGER-RENAMES.tsv` (Task 8).
- One commit: `chore(fork): fork notes, plans and migration range check`.

### Task 2: Server profiles, database (#3, DB part)

- Port #3's migrations as 20261116990001–990006 and its pgTAP tests as 901–904. Follow the
  function rebase rule.
- `service_set_member_server_profile` keeps its signature. Tasks 4 and 5 call it.
- Make sure upstream's pgTAP files still pass, especially 272 (the import-flag trigger audit) and
  284 (member directory).
- Regenerate the census files.
- Commit: `feat(fork): server profiles database (migrations 990001-990006)`.

### Task 3: Server profiles, app (#3, everything else)

- Port the rest of #3: src, bot-gateway, federation-backend, locales, e2e
  `server-profiles.spec.ts`, docs. Don't port `docs/superpowers` (Task 1 did).
- Upstream #210 (migration 20261113100001_member_directory, `src/services`, the member list) loads
  members by range, search and id. Carry server profile fields through those reads. Never read a
  whole membership.
- Upstream #199 (the pins popup) was reworked upstream. Port only #3's change to it: the
  `server-id` on DisplayName.
- Tests: the unit tests #3 added or changed, vue-tsc, and type-check in bot-gateway and
  federation-backend.
- Commit: `feat(fork): server profiles, pronouns and nickname permissions`.

### Task 4: Migrate from Discord (#22, #28, #30)

- Port #22, then #28, then #30, as at most three commits. Migrations: 20261116990007 and
  990010–990014. pgTAP: 905–907 and 910–913.
- The function rebase rule applies, notably to `public_instance_config_keys`.
- Upstream 1.9.7 changed bot-gateway bridge code (privacy, sync guards, `capabilities`, import
  rules). Keep upstream's behaviour and add the migration's code next to it.
- Includes `docs/run/discord-migration.md`, `e2e/specs/discord-migration.spec.ts`, locales and
  `scripts/check-schema-usage.sh` changes.
- Regenerate the census files.
- Commits: `feat(fork): migrate from Discord`, `feat(fork): migration follow-ups ...`,
  `fix(fork): /migrate scrolls on phones`.

### Task 5: Bridge server profile sync (#26)

- Port #26 as migration 20261116990008 and pgTAP 908, plus its bot-gateway routes, docs and the
  CHANGELOG line.
- `server_audit_attribute_bot` follows the rebase rule (latest upstream body in 20261112300001).
- The routes must answer under upstream's `capabilities` scheme. Add a capability only if the
  bridge needs one to decide whether it may offer `/bridge sync-profile`. Name it in the report.
- Commit: `feat(fork): bridge server profile sync endpoints`.

### Task 6: Thread sidebar (#27)

- Port #27 as migration 20261116990009 and pgTAP 909, plus the client, locales and e2e
  `thread-sidebar-mode.spec.ts`.
- Rebase `thread_message_handler` and `handle_message_federation` on 20261110900004's upstream
  bodies, then re-add only the mention-join loop and the `'thread'` payload key.
- Commit: `feat(fork): thread sidebar all/joined, @mention joins a thread`.

### Task 7: Reassert migration

- The instance applied the old fork files, then gets upstream's newer migrations. Wherever both
  define a function, upstream's body wins there, unlike on a fresh install.
- Add `20261116990015_fork_reassert_functions.sql`. It re-creates, from the rebased fork files,
  every function that a fork migration defines and an upstream migration newer than the
  merge-base 31449eef also defines. It runs everywhere; on a fresh install it changes nothing.
- Header comment: why the file exists, and the list of functions.
- pgTAP 914: each listed function's body contains its fork marker. Pick one distinctive string per
  function from the fork change.
- Commit: `fix(fork): reassert fork function bodies after upstream 1.9.7`.

### Task 8: Ledger renames in bootstrap.sh

- Add `db_schema/LEDGER-RENAMES.tsv` with columns `old_version old_name new_version new_name`,
  one row per mapping row above.
- In `self-host/bootstrap.sh`, before `applied=` is read, apply each row. Rename only when the
  ledger holds `old_version` with exactly `old_name`, and `new_version` is not already recorded.
  Use one `UPDATE ... SET version, name` per row. A missing file means nothing happens. Print
  `  renamed ledger <old> -> <new>` for each row renamed.
- This frees upstream's colliding versions (20261112500001, 600001, 700001, 800001, 800002) so
  they run. A fork migration that is already applied stays applied under its new number.
- On a fresh install or an upstream instance it does nothing.
- Tests: extend the selfHost shell or unit test pattern if one covers bootstrap. Otherwise add
  `scripts/fork/ledger-renames.test.sh`, which runs the SQL against a throwaway Postgres in WSL
  and covers rename, name mismatch, already-renamed, and no file.
- Commit: `feat(fork): bootstrap renames fork migration ledger rows`.

### Task 9: Guard against deleting a bridge bot with history

- `messages.bot_id` and `reactions.bot_id` are `ON DELETE CASCADE`. Deleting a bot from
  **My bots** would delete every message it bridged.
- Migration 20261116990016: a `BEFORE DELETE` trigger on `public.bots`. It raises 23503 with the
  message `This bot still has messages in a server. Disconnect its bridge first; its messages are kept.`
  when `auth.role()` is `authenticated` (or `anon`) and a message with that bot exists. The
  service role and the owner pass, so account deletion, upstream's retire trigger and e2e cleanup
  keep working.
- `UserBotsManagement.vue` shows the database message on failure. Check how it reports errors
  today first.
- pgTAP 915. Regenerate the census files.
- Commit: `fix(fork): a bot with bridged history can't be deleted from My bots`.

### Task 10: Upgrade-path check

- Add `scripts/fork/check-upgrade-path.sh [--from-ref fork-pre-1.9.7] [--from-dump <file>]`, for
  WSL Docker. It:
  1. starts a throwaway Postgres in the image bootstrap uses;
  2. brings it to the old state, either by applying `--from-ref`'s migrations in version order and
     recording the ledger as bootstrap does, or by restoring `--from-dump`;
  3. runs HEAD's `bootstrap.sh` migration step, ledger renames included;
  4. builds a second database by a fresh install of HEAD;
  5. compares the two catalogs, reusing `scripts/restore-drill.sh`'s comparison where possible:
     tables, columns, functions by signature and body hash, indexes, constraints, policies,
     triggers.
- It exits 0 when they're equal, and otherwise prints the differences by name.
- Run it with `--from-ref fork-pre-1.9.7` and report the result. Every difference becomes a fix
  migration in this task (990017+), or an explained, expected entry in the script's allowlist.
- Commit: `test(fork): upgrade-path check from the old fork schema`.

### Task 11: Bridge rebuild

- In the bridge worktree, port from `fork-pre-1.9.7`: 2550048c (permission map), af706ff2
  (CLAUDE.md), and 0a9b7c25, 609b27ca, eb2724e7, 4b72df61 (profile sync).
- Skip 37110be4, fba6d1a2 and c0db4719: they're already upstream in reworked form. Check that each
  one's effect is present upstream, and say so in the report.
- `/bridge sync-profile` registers through upstream's `capabilities` check, using the capability
  named in Task 5 if there is one.
- Tests: `npx tsc --noEmit && npx vitest run`. `credentials.test.ts`'s 0600 test fails on Windows.
- Commits: one per feature.

### Task 12: Bridge instance → self conversion (design and fork step)

- Research how upstream 1.9.7 issues setup codes and credentials for `self` bridges, what
  `discord_bridges.mode` controls, what the instance bridge host keeps, and what changes for an
  existing bridge row when its mode changes.
- Write `docs/superpowers/specs/2026-11-16-bridge-instance-to-self.md`. It holds:
  - the facts above;
  - the smallest fork-only step that converts a bridge in place: same row, same `bot_id`, pairs
    kept. For example, an instance-admin-only SQL function
    `fork_convert_bridge_to_self(p_bridge_id)` that returns a setup code;
  - the operator runbook: turn off the instance-bot offer, run the conversion, start a self-run
    bridge container with the same Discord application token and the setup code, and copy
    `permission-sync.yml`;
  - how to verify that relay, edits and deletes on old messages, and import work;
  - rollback.
- Implement the fork step as migration 990018+ (after Task 10's), with a pgTAP test. Keep
  `history_import`'s checks unchanged.
- Commit: `feat(fork): convert an instance bridge to self-run in place`.
