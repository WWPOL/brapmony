# Thread Sidebar Visibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A server setting (instance default + per-server override) that makes the channel sidebar list either every active thread (`all`) or only joined threads (`joined`), plus @mention-joins-thread in both modes.

**Architecture:** One migration adds the `server_settings.thread_sidebar` column, the get/set and member-readable effective-mode RPCs (newcomer-alerts pattern), the mention join inside `thread_message_handler`, and an additive `thread` key on the mention notification payload. The client filters the threads it already loads with a pure helper, caches the effective mode per server, refreshes a thread when a mention notification names it, and gets two settings controls.

**Tech Stack:** Postgres 15 plpgsql + pgTAP (Supabase), Vue 3 + Pinia + vue-i18n, Vitest + @vue/test-utils.

**Spec:** `docs/superpowers/specs/2026-11-11-thread-sidebar-visibility-design.md`

## Global Constraints

- Migration file `db_schema/migrations/20261112600001_thread_sidebar_mode.sql`; pgTAP file `db_schema/tests/280_thread_sidebar_mode.sql` (`20261112500001` and pgTAP 274–276 belong to `feat/discord-migration`).
- Modes are exactly the strings `'all'` and `'joined'`. `instance_config` key `thread_sidebar_default`; absent or any other value reads as `'all'`.
- Migration style: `BEGIN; SET LOCAL lock_timeout = '5s'; … COMMIT; NOTIFY pgrst, 'reload schema';`, header comment describing every object, converges on rerun (`IF NOT EXISTS`, `CREATE OR REPLACE`).
- No mention join under `public.is_history_import()`.
- Upstream-bound later: no Claude mentions in code comments or docs.
- Strings in all 7 locales (`src/locales/{en,de,es,fr,ja,ko,zh}.json`), keys added without reformatting the files (they're CRLF; preserve line endings).
- DB scripts run in WSL from a CR-stripped copy: `wsl bash /mnt/c/Users/Rudhra/.config/harmony-test/kit/wsl-run.sh /mnt/c/Users/Rudhra/Code/brapmony/.claude/worktrees/moonlit-forging-orbit <script>`. Docker exists only in WSL.

## Review Focus

1. A mention of someone who is not a member of the server, or can't view the channel: no row in `thread_members` (Task 2 test `mention of a non-viewer joins no one`).
2. A thread reply mentioning several people where one blocked the author: the others join, the blocker doesn't (Task 2 test `a blocker is not joined, the others are`).
3. `joined` mode with the open thread being one the member never joined: it still shows, and disappears once another thread is opened (Task 3 test `keeps the open thread, drops it once closed`).
4. The effective-mode RPC failing (remote server, network error): the sidebar behaves as `all` and logs, never empty (Task 3 test `falls back to all when the lookup fails`).
5. Switching the server back to "Instance default": `server_value` becomes NULL and the mode follows the instance (Task 1 test `NULL returns the server to the instance default`).

---

### Task 1: Setting column and RPCs

**Files:**
- Create: `db_schema/migrations/20261112600001_thread_sidebar_mode.sql`
- Create: `db_schema/tests/280_thread_sidebar_mode.sql`
- Modify (regenerated, never by hand): `db_schema/SURFACE.tsv`, `db_schema/REACHABILITY.tsv`

**Interfaces:**
- Produces (SQL):
  - `server_settings.thread_sidebar text NULL CHECK (thread_sidebar IN ('all','joined'))`, comment says NULL follows `thread_sidebar_default`.
  - `server_thread_sidebar_state(p_server_id uuid) RETURNS jsonb` — `{mode, server_value, instance_default}`; invoker, service_role only. Same shape as `server_newcomer_alerts_state` in `20261006100001_newcomer_message_alerts.sql:132`.
  - `get_server_thread_sidebar(p_server_id uuid) RETURNS jsonb` and `set_server_thread_sidebar(p_server_id uuid, p_mode text) RETURNS jsonb` — guards copied from `get/set_server_newcomer_alerts` (authenticated, local server, `has_permission(..., 'MANAGE_SERVER')`, errcode 42501); set upserts `server_settings` like `set_server_newcomer_alerts` and raises 22023 for a mode other than NULL/`all`/`joined`. If `set_server_newcomer_alerts` writes an audit entry, do the same; it does not as of this writing.
  - `server_thread_sidebar_mode(p_server_id uuid) RETURNS text` — definer, STABLE; raises 42501 unless `public.current_user_is_member_of_server(p_server_id)`; returns `state->>'mode'`. Granted to authenticated, service_role.

- [ ] **Step 1: Write the failing pgTAP file** using the fixture profiles (`11111111-…01` alice owner, auth `aaaaaaaa-…01`; `22222222-…02` bob member, auth `bbbbbbbb-…02`; `33333333-…03` carol not a member, auth `cccccccc-…03`) and a fresh local server and channel with ids prefixed `28000000-`, as in `256_thread_departures.sql`. Assertions for this task:
  - `throws_ok` inserting `server_settings.thread_sidebar = 'some'` → 23514.
  - As alice: `get_server_thread_sidebar` returns `{"mode":"all","server_value":null,"instance_default":"all"}`.
  - Insert `instance_config ('thread_sidebar_default', '"joined"')`: get returns mode `joined`, server_value null.
  - Set `'garbage'` default: mode reads `all`.
  - As alice `set_server_thread_sidebar(s, 'joined')` then `(s, NULL)`: the second returns `server_value` null and mode = instance default (test name `NULL returns the server to the instance default`).
  - `set_server_thread_sidebar(s, 'nope')` → 22023.
  - As bob (no MANAGE_SERVER): get and set → 42501; `server_thread_sidebar_mode(s)` returns the effective mode.
  - As carol: `server_thread_sidebar_mode(s)` → 42501.
  - Signed out (`tests.clear_authentication()`): get → 42501.
- [ ] **Step 2: Run the suite and see 280 fail.** Run: `… wsl-run.sh … scripts/run-db-tests.sh` Expected: `280_thread_sidebar_mode.sql` errors (functions missing); others pass.
- [ ] **Step 3: Write the migration's column and four functions** (signatures above; header comment lists them).
- [ ] **Step 4: Run the suite.** Expected: all files pass, 280 included.
- [ ] **Step 5: Regenerate the manifests.** Run `… wsl-run.sh … scripts/generate-surface.sh` and `… scripts/generate-reachability.sh`. Expected: new rows for the four functions only. `server_thread_sidebar_mode` and `get/set_server_thread_sidebar` will read `unreferenced` until Task 3/5 call them; rerun after those tasks.
- [ ] **Step 6: Commit** `feat(db): server setting for which threads the sidebar lists`.

### Task 2: Mention joins a thread; mention payload names the thread

**Files:**
- Modify: `db_schema/migrations/20261112600001_thread_sidebar_mode.sql` (append)
- Modify: `db_schema/tests/280_thread_sidebar_mode.sql` (append, bump `plan(n)`)
- Modify (regenerated): `db_schema/SURFACE.tsv`, `db_schema/REACHABILITY.tsv`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces:
  - `thread_message_handler()` redefined: copy the latest body from `20261112400001_history_import_thread_last_activity.sql` verbatim, then add the mention loop after the author insert, gated on `NEW.is_system IS NOT TRUE AND NEW.is_deleted IS NOT TRUE AND NOT public.is_history_import() AND jsonb_typeof(NEW.content) = 'array'`. For each element with `->>'type' = 'mention'`: `v_uid := resolve_mention_profile_id(part, NEW.user_id, v_domain)` (domain read once from `instance_config` key `domain`, as `handle_message_federation` does); insert `(NEW.thread_id, v_uid)` into `thread_members ON CONFLICT DO NOTHING` when `v_uid IS NOT NULL`, `v_uid IS DISTINCT FROM NEW.user_id`, `public.can_view_channel(v_uid, NEW.channel_id)`, and `NOT EXISTS (SELECT 1 FROM public.user_blocks b WHERE b.blocker_id = v_uid AND b.blocked_user_id = NEW.user_id)`. Leave the member_count recount where it is (after the loop) so it counts the new rows.
  - `handle_message_federation()` redefined: copy the latest body from `20261110900004_history_import_quiet.sql` verbatim; in the mention notification payload add `'thread', CASE WHEN NEW.thread_id IS NULL THEN NULL ELSE jsonb_build_object('id', NEW.thread_id) END`. No other change.

- [ ] **Step 1: Append failing assertions** (a thread `T` in the Task 1 server, with parent message; `dave` = a profile you add who is a server member but denied VIEW_CHANNEL on the channel via a channel permission overwrite, or not a server member at all, whichever the fixtures make simpler):
  - Bob posts in T mentioning carol after carol joins the server: carol is in `thread_members` (`a mention joins a viewer`).
  - Carol `leave_thread(T)`, bob mentions her again: membership is back and `thread_departures` has no row for her (`a mention brings back someone who left`).
  - A mention of a non-member / non-viewer: no row (`mention of a non-viewer joins no one`).
  - One reply mentioning carol and a profile that has a `user_blocks` row blocking bob: carol joined, the blocker not (`a blocker is not joined, the others are`).
  - A reply whose only mention parts are `{"type":"role_mention","roleId":<a role id>}` and a `role_mention` of the server's default role (@everyone is the `is_default` role; see `handle_role_mention_notifications`), posted by the owner so the permission guard allows it: no new members.
  - With `set_config('harmony.history_import','true',true)`, a reply mentioning a new viewer: no row; reset the flag after.
  - A channel message (no `thread_id`) mentioning someone: no membership.
  - `is_system = true` reply mentioning someone: no membership.
  - Author mentioning themselves: `member_count` unchanged.
  - `member_count` equals the `thread_members` count after a mention join.
  - The `mention` notification for a thread reply has `data->'thread'->>'id' = T` (query `public.notifications` for carol, type `mention`); a channel message's has `data->'thread'` null.
- [ ] **Step 2: Run the suite; the new assertions fail.**
- [ ] **Step 3: Append the two redefinitions** and extend the header comment.
- [ ] **Step 4: Run the suite; all pass.** Also run `scripts/check-plpgsql.sh` if present under `scripts/` (CI job `plpgsql`).
- [ ] **Step 5: Regenerate SURFACE/REACHABILITY** (expect no new rows; reachability "called by" lines may change).
- [ ] **Step 6: Commit** `feat(db): an @mention in a thread reply adds the member to the thread`.

### Task 3: Client mode service and sidebar filter

**Files:**
- Create: `src/services/ThreadSidebarService.ts`
- Create: `src/utils/threadSidebar.ts`
- Create: `src/utils/__tests__/threadSidebar.test.ts`
- Create: `src/services/__tests__/ThreadSidebarService.test.ts`
- Modify: `src/components/ChannelSidebar.vue:1135-1137` (`getChannelActiveThreads`) and where the server's channels load
- Modify (regenerated): `db_schema/REACHABILITY.tsv`

**Interfaces:**
- Consumes: Task 1 RPC names.
- Produces:
  - `src/utils/threadSidebar.ts`: `export type ThreadSidebarMode = 'all' | 'joined'`; `export function parseThreadSidebarMode(value: unknown): ThreadSidebarMode` (`'joined'` or JSON `"joined"` → `'joined'`, anything else `'all'`); `export function sidebarThreads<T extends { id: string; is_member?: boolean; left?: boolean }>(threads: T[], mode: ThreadSidebarMode, openThreadId: string | null): T[]`.
  - `src/services/ThreadSidebarService.ts` (shape of `NewcomerAlertService.ts`): `THREAD_SIDEBAR_DEFAULT_KEY = 'thread_sidebar_default'`; `interface ThreadSidebarState { mode: ThreadSidebarMode; server_value: ThreadSidebarMode | null; instance_default: ThreadSidebarMode }`; `getServerThreadSidebar(serverId)`, `setServerThreadSidebar(serverId, mode: ThreadSidebarMode | null)`, `getInstanceThreadSidebarDefault(): Promise<ThreadSidebarMode>`, `setInstanceThreadSidebarDefault(mode)` (via `set_instance_config`, description `'Which threads the channel sidebar lists for servers whose server_settings.thread_sidebar is NULL'`), and `getThreadSidebarMode(serverId): Promise<ThreadSidebarMode>` calling `server_thread_sidebar_mode`, returning `'all'` on any error (logged with `debug.error`).
  - A per-server cache: `useThreadSidebarMode(serverId: Ref<string | null>, isLocal: Ref<boolean>)` composable in the service file or a small Pinia store (builder's choice) exposing `mode: Ref<ThreadSidebarMode>` (starts `'all'`, set after load, not called for `isLocal === false`) and `setCached(serverId, mode)` so Task 5's save updates it immediately.

- [ ] **Step 1: Write failing unit tests** in `threadSidebar.test.ts`:
  - `all` mode keeps every thread except `left: true`.
  - `joined` mode keeps `is_member: true` threads only (`left` irrelevant: a member who left has `is_member` false).
  - `keeps the open thread, drops it once closed`: joined mode, open id = a non-member thread → included; `openThreadId` null → excluded.
  - `parseThreadSidebarMode`: `'joined'`, `"joined"` JSON value → joined; `undefined`, `'all'`, `'x'`, `false` → all.
- [ ] **Step 2: Write failing service tests** (mock `@/supabase` like `src/components/settings/server/__tests__/ServerNewcomerAlerts.test.ts` does): each function calls the RPC name and args above; `getThreadSidebarMode` returns `'all'` when the RPC errors (`falls back to all when the lookup fails`).
- [ ] **Step 3: Run** `npx vitest run src/utils/__tests__/threadSidebar.test.ts src/services/__tests__/ThreadSidebarService.test.ts` — FAIL (modules missing).
- [ ] **Step 4: Implement both files and the cache.**
- [ ] **Step 5: Wire `ChannelSidebar.vue`**: `getChannelActiveThreads(channelId)` returns `sidebarThreads(threadsStore.activeChannelThreads(channelId), mode.value, selectedThreadId.value)`; the mode comes from the cache keyed by `currentServer.id`, with `isLocal = currentServer.is_local_server !== false`.
- [ ] **Step 6: Run** the two new test files plus `npx vitest run src/components/__tests__/ChannelSidebar` — PASS (existing sidebar tests unchanged: default mode `all`).
- [ ] **Step 7: Regenerate REACHABILITY** (`server_thread_sidebar_mode` now `client`).
- [ ] **Step 8: Commit** `feat(sidebar): list only joined threads when the server says so`.

### Task 4: A mention notification refreshes its thread

**Files:**
- Modify: `src/stores/useNotification.ts` (`_processIncomingNotification`)
- Test: `src/stores/__tests__/` — add `useNotification.threadMention.test.ts` (follow the mocking in the existing `src/stores/__tests__/useNotification*.test.ts` files)

**Interfaces:**
- Consumes: Task 2 payload `data.thread.id`; `ThreadService.getThread(threadId: string, forceRefresh = true)` (`src/services/ThreadService.ts:156`), which stores `is_member`/`left`.
- Produces: nothing new.

- [ ] **Step 1: Failing test**: processing a `mention` notification whose `data.thread.id = 't1'` calls `getThread('t1', true)` once; one without `data.thread` doesn't call it; an `activitypub_mention` doesn't.
- [ ] **Step 2: Run** it — FAIL.
- [ ] **Step 3: Implement**: in `_processIncomingNotification`, for `type === 'mention'` with a string `data?.thread?.id`, fire-and-forget the refresh (lazy `import('@/services/ThreadService')` if a static import would create a cycle; errors go to `debug.error`). Make sure the refresh also updates the threads store entry (check `getThread` writes to the store; if not, call `useThreadsStore().patch(id, { is_member: true, left: false })` after a successful read).
- [ ] **Step 4: Run** — PASS, plus `npx vitest run src/stores`.
- [ ] **Step 5: Commit** `feat(threads): a mention in a thread shows it in the sidebar right away`.

### Task 5: Settings controls and strings

**Files:**
- Create: `src/components/admin/ThreadSidebarDefault.vue` (beside `NewcomerAlertsDefault.vue`), mounted in `src/components/admin/InstanceConfig.vue` right after `<NewcomerAlertsDefault />` (line 70)
- Create: `src/components/settings/server/ServerThreadSidebar.vue`, mounted in `src/views/ServerSettings.vue` next to `ServerNewcomerAlerts` (line 158), same visibility condition
- Create: `src/components/admin/__tests__/ThreadSidebarDefault.test.ts`, `src/components/settings/server/__tests__/ServerThreadSidebar.test.ts`
- Modify: all 7 locale files
- Modify (regenerated): `db_schema/REACHABILITY.tsv`

**Interfaces:**
- Consumes: Task 3 service functions and cache `setCached`.
- Produces: nothing.

Strings (en; translate for the other six), under a new top-level `threadSidebar` object:
- `admin.title` "Threads in the sidebar", `admin.hint` "Which threads servers list under their channels, unless a server chooses for itself."
- `server.title` "Threads in the sidebar", `server.hint` "Joined only works like Discord: members see the threads they're in, and find the rest with the Threads button."
- `modes.all` "All active threads", `modes.joined` "Joined threads only", `modes.instance` "Instance default ({mode})"
- `saveFailed` "Couldn't save the thread setting"

- [ ] **Step 1: Failing component tests** (pattern of `ServerNewcomerAlerts.test.ts` and the admin component tests):
  - Admin: loads `getInstanceThreadSidebarDefault`, shows the current choice; choosing "Joined threads only" calls `setInstanceThreadSidebarDefault('joined')`; a failed save toasts `threadSidebar.saveFailed` and restores the previous choice.
  - Server: three choices with the instance option labelled with the instance's mode; choosing "Instance default" calls `setServerThreadSidebar(id, null)`; choosing "Joined threads only" calls it with `'joined'` and updates the cache via `setCached(id, 'joined')`; failed save toasts and restores.
  - Both: no missing i18n keys (the suites' missing-key check).
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement** the components (radio group or select, matching the neighbouring settings' controls), mount them, add the strings.
- [ ] **Step 4: Run** the two new suites, `npx vitest run src/components/admin src/components/settings/server`, and `npx vue-tsc --build --force` — PASS / no errors.
- [ ] **Step 5: Regenerate REACHABILITY** (get/set RPCs now `client`); confirm `git diff db_schema/SURFACE.tsv` shows only the Task 1 rows across the branch.
- [ ] **Step 6: Commit** `feat(settings): choose which threads the sidebar lists, per instance and per server`.

### Task 6: Whole-branch check

- [ ] Run the full pgTAP suite in WSL, `npx vitest run src/components src/stores src/services src/utils`, `npx vue-tsc --build --force`, and `cd bot-gateway && npx vitest run` (unchanged, sanity). All pass, apart from the known Windows-only suites (`tests/unit/selfHost*`, `realtimeChannelsPrivate`, `viewportUnits`, `shareUrls`, `usernameRules.reserved`, `permissionCoverage`), which also fail on master.
- [ ] `git log origin/master..HEAD --format=%B | grep -i claude` lists only the fork's Co-Authored-By trailers; `git diff origin/master..HEAD | grep -i claude` prints nothing (no Claude mentions in code or docs).
