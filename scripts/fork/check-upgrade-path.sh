#!/usr/bin/env bash
# Fork-only (WWPOL/brapmony). Proves that an instance running the old fork schema upgrades to
# this branch's schema, and that the result matches a fresh install of this branch.
#
#   check-upgrade-path.sh [--from-ref fork-pre-1.9.7] [--git-dir DIR] [--keep]
#   check-upgrade-path.sh --from-dump /path/to/database.dump [--keep]
#
# 1. Container A is brought to the old state: either by running the old ref's own
#    self-host/bootstrap.sh (then scripts/fork/upgrade-seed.sql adds rows to the fork-only
#    tables), or by restoring a `harmony backup` database.dump (and roles.sql beside it) the
#    way self-host/backup.sh restore does.
# 2. This checkout's bootstrap.sh --migrations-only runs on A: it renames the fork's ledger
#    rows and applies what the ledger doesn't have. The check requires the 14 "renamed ledger"
#    lines and upstream's five previously-colliding versions among the applied ones.
# 3. Container B gets a fresh install of this checkout.
# 4. The two catalogs and ledgers are compared (tables, columns, views, sequences, indexes,
#    functions with a body hash and ACL hash, constraints, policies, triggers, enum labels,
#    instance_config keys, storage buckets). Differences are listed by name. Expected ones go
#    in scripts/fork/upgrade-path-allowlist.txt, each with a one-line reason.
# 5. Data: no public table may lose rows across the upgrade, and with --from-ref the seeded
#    rows must read back unchanged.
#
# Exit 0 only when everything agrees. Needs Docker (in WSL on Windows) and the image bootstrap
# uses (supabase/postgres), like scripts/check-fresh-install.sh.
#
# From a Windows checkout, run it in WSL through the kit's wrapper, which copies scripts/,
# db_schema/ and self-host/ with CRs stripped. The copy has no .git, and a worktree's .git
# file points at a Windows path WSL git can't read, so give the main repository's git dir:
#
#   MSYS_NO_PATHCONV=1 wsl bash /mnt/c/Users/Rudhra/.config/harmony-test/kit/wsl-run.sh \
#     /mnt/c/Users/Rudhra/Code/worktrees/brapmony-rebuild scripts/fork/check-upgrade-path.sh \
#     --git-dir /mnt/c/Users/Rudhra/Code/brapmony/.git --from-ref fork-pre-1.9.7
#
# Containers are named hupg-a-<pid> and hupg-b-<pid>; only those are ever removed.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FORK_DIR="$ROOT/scripts/fork"
IMAGE="${SUPABASE_PG_IMAGE:-supabase/postgres:15.8.1.060}"
FROM_REF="fork-pre-1.9.7"
FROM_DUMP=""
GIT_DIR_ARG="${HARMONY_GIT_DIR:-}"
KEEP=0
ALLOWLIST="$FORK_DIR/upgrade-path-allowlist.txt"
SEED="$FORK_DIR/upgrade-seed.sql"

# Versions upstream 1.9.7 ships under numbers the old fork's migrations used. The ledger
# renames free them, so the upgrade must apply them.
EXPECT_APPLIED=(
  20261112500001_bridge_sync_guards
  20261112600001_bridge_privacy
  20261112700001_bridge_import_limits
  20261112800001_bridge_mirror_quota
  20261112800002_messages_bot_index
)

log() { printf '\033[36m==>\033[0m %s\n' "$*"; }
err() { printf '\033[31mFAIL\033[0m %s\n' "$*" >&2; }
ok()  { printf '\033[32m ok \033[0m %s\n' "$*"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --from-ref) FROM_REF="$2"; shift 2 ;;
    --from-dump) FROM_DUMP="$2"; shift 2 ;;
    --git-dir) GIT_DIR_ARG="$2"; shift 2 ;;
    --keep) KEEP=1; shift ;;
    -h|--help) sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//;$d'; exit 0 ;;
    *) err "unknown option: $1"; exit 2 ;;
  esac
done

command -v docker >/dev/null || { err "docker not on PATH (in WSL on Windows)"; exit 2; }
if [ -n "$FROM_DUMP" ]; then
  [ -r "$FROM_DUMP" ] || { err "cannot read $FROM_DUMP"; exit 2; }
  FROM_DUMP="$(cd "$(dirname "$FROM_DUMP")" && pwd)/$(basename "$FROM_DUMP")"
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/hupg.XXXXXX")"
A="hupg-a-$$"
B="hupg-b-$$"
OWNED=()

cleanup() {
  local rc=$?
  if [ "$KEEP" = 1 ]; then
    log "kept containers: ${OWNED[*]:-none}; artifacts in $WORK"
  else
    for c in ${OWNED[@]+"${OWNED[@]}"}; do docker rm -f "$c" >/dev/null 2>&1 || true; done
    rm -rf "$WORK"
  fi
  return $rc
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# psql as supabase_admin over TCP, the role check-fresh-install.sh uses.
apsql() { local c="$1"; shift; docker exec -i "$c" psql -X -h 127.0.0.1 -U supabase_admin -d postgres -v ON_ERROR_STOP=1 "$@"; }

start_pg() {
  local name="$1"
  docker rm -f "$name" >/dev/null 2>&1 || true
  OWNED+=("$name")
  docker run -d --name "$name" -e POSTGRES_PASSWORD=postgres "$IMAGE" >/dev/null
}

# The image restarts once while it initialises; three answers in a row is the final server.
wait_pg() {
  local name="$1" hits=0 _
  for _ in $(seq 1 120); do
    if docker exec "$name" psql -h 127.0.0.1 -U supabase_admin -d postgres -tAc 'select 1' >/dev/null 2>&1; then
      hits=$((hits + 1)); [ "$hits" -ge 3 ] && return 0
    else hits=0; fi
    sleep 2
  done
  err "$name never stabilised"; return 1
}

# compat.sql supplies realtime.messages, as in check-fresh-install.sh.
load_compat() { apsql "$1" -q -f - < "$ROOT/scripts/test-db/supabase-compat.sql" >/dev/null; }

# Run this checkout's bootstrap from a copy with CRs stripped. A CRLF checkout puts CRs inside
# function bodies; they would differ from the LF text git holds for the old ref and show up as a
# difference in every function.
HEAD_TREE="$WORK/head"; mkdir -p "$HEAD_TREE"
cp -r "$ROOT/db_schema" "$ROOT/self-host" "$HEAD_TREE/"
find "$HEAD_TREE" -type f \( -name '*.sh' -o -name '*.sql' -o -name '*.tsv' \) -exec sed -i 's/\r$//' {} +

# ---------------------------------------------------------------- SQL

cat > "$WORK/census.sql" <<'SQL'
-- kind <TAB> name <TAB> detail. Compared line by line; a changed detail is a difference.
WITH rel AS (
  SELECT c.oid, n.nspname, c.relname, c.relkind, c.relrowsecurity, c.relforcerowsecurity, c.relacl
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
   WHERE n.nspname = 'public' AND NOT c.relispartition
), tab AS (
  SELECT c.oid, n.nspname, c.relname, c.relkind
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
   WHERE n.nspname IN ('public', 'auth', 'storage', 'realtime') AND NOT c.relispartition
)
SELECT 'table', nspname||'.'||relname,
       'rls='||relrowsecurity||' force='||relforcerowsecurity||' acl='||md5(COALESCE(relacl::text, ''))
  FROM rel WHERE relkind IN ('r','p')
UNION ALL
SELECT 'view', nspname||'.'||relname, md5(pg_get_viewdef(oid)) FROM rel WHERE relkind IN ('v','m')
UNION ALL
SELECT 'sequence', nspname||'.'||relname, '' FROM rel WHERE relkind = 'S'
UNION ALL
-- Column order is not compared: ALTER TABLE ... ADD COLUMN appends, a fresh CREATE TABLE may not.
SELECT 'column', rel.nspname||'.'||rel.relname||'.'||a.attname,
       format_type(a.atttypid, a.atttypmod)||' notnull='||a.attnotnull||' gen='||a.attgenerated::text
       ||' ident='||a.attidentity::text||' default='||COALESCE(pg_get_expr(d.adbin, d.adrelid), '')
  FROM rel
  JOIN pg_attribute a ON a.attrelid = rel.oid AND a.attnum > 0 AND NOT a.attisdropped
  LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
 WHERE rel.relkind IN ('r','p','v','m')
UNION ALL
SELECT 'index', n.nspname||'.'||c.relname, pg_get_indexdef(i.indexrelid)
  FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid JOIN pg_namespace n ON n.oid = c.relnamespace
  JOIN pg_class t ON t.oid = i.indrelid
 WHERE n.nspname = 'public' AND NOT c.relispartition
UNION ALL
SELECT 'function', n.nspname||'.'||p.proname||'('||pg_get_function_identity_arguments(p.oid)||')',
       'body='||md5(pg_get_functiondef(p.oid))||' acl='||md5(COALESCE(p.proacl::text, ''))
       ||' owner='||pg_get_userbyid(p.proowner)
  FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
 WHERE n.nspname = 'public' AND p.prokind IN ('f','p','w')
UNION ALL
SELECT 'constraint', n.nspname||'.'||COALESCE(cl.relname, '-')||'.'||k.conname, pg_get_constraintdef(k.oid)
  FROM pg_constraint k JOIN pg_namespace n ON n.oid = k.connamespace
  LEFT JOIN pg_class cl ON cl.oid = k.conrelid
 WHERE n.nspname = 'public'
UNION ALL
SELECT 'policy', tab.nspname||'.'||tab.relname||'.'||pol.polname,
       'cmd='||pol.polcmd::text||' permissive='||pol.polpermissive
       ||' roles='||COALESCE((SELECT string_agg(r.rolname, ',' ORDER BY r.rolname)
                                FROM pg_roles r WHERE r.oid = ANY (pol.polroles)), 'public')
       ||' using='||COALESCE(pg_get_expr(pol.polqual, pol.polrelid), '')
       ||' check='||COALESCE(pg_get_expr(pol.polwithcheck, pol.polrelid), '')
  FROM pg_policy pol JOIN tab ON tab.oid = pol.polrelid
UNION ALL
SELECT 'trigger', tab.nspname||'.'||tab.relname||'.'||t.tgname, pg_get_triggerdef(t.oid)
  FROM pg_trigger t JOIN tab ON tab.oid = t.tgrelid WHERE NOT t.tgisinternal
UNION ALL
SELECT 'enum', n.nspname||'.'||ty.typname||'.'||e.enumlabel, e.enumsortorder::text
  FROM pg_enum e JOIN pg_type ty ON ty.oid = e.enumtypid JOIN pg_namespace n ON n.oid = ty.typnamespace
 WHERE n.nspname = 'public'
UNION ALL
SELECT 'instance_config', config_key, '' FROM public.instance_config
UNION ALL
SELECT 'bucket', id, 'public='||COALESCE(public::text, '')||' limit='||COALESCE(file_size_limit::text, '')
       ||' mime='||COALESCE(allowed_mime_types::text, '')
  FROM storage.buckets
ORDER BY 1, 2;
SQL

cat > "$WORK/ledger.sql" <<'SQL'
SELECT version||' '||COALESCE(name, '') FROM supabase_migrations.schema_migrations ORDER BY version;
SQL

# One round trip: exact count(*) per public table (partitions count through their parent).
cat > "$WORK/rowcount.sql" <<'SQL'
SELECT n.nspname||'.'||c.relname,
       (xpath('/row/cnt/text()',
              query_to_xml(format('SELECT count(*) AS cnt FROM %I.%I', n.nspname, c.relname), false, true, '')))[1]::text
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE c.relkind IN ('r','p') AND NOT c.relispartition AND n.nspname = 'public'
 ORDER BY 1;
SQL

# Rows the seed wrote, read back by their fixed ids. Values only, so a before/after diff is exact.
cat > "$WORK/probe.sql" <<'SQL'
SELECT 'profiles', concat_ws('|', username, display_name, pronouns)
  FROM public.profiles WHERE id IN ('00000000-0000-4000-8000-0000000000a1', '00000000-0000-4000-8000-0000000000a2')
UNION ALL
SELECT 'user_servers', concat_ws('|', nickname, avatar_url, bio, pronouns)
  FROM public.user_servers WHERE server_id = '00000000-0000-4000-8000-0000000000b1' AND nickname IS NOT NULL
UNION ALL
SELECT 'server_settings', thread_sidebar FROM public.server_settings WHERE server_id = '00000000-0000-4000-8000-0000000000b1'
UNION ALL
SELECT 'discord_migrations', concat_ws('|', discord_guild_id, credential_source, state)
  FROM public.discord_migrations WHERE server_id = '00000000-0000-4000-8000-0000000000b1'
UNION ALL
SELECT 'discord_migration_links', concat_ws('|', discord_user_id, array_to_string(sync_fields, ','), status)
  FROM public.discord_migration_links WHERE server_id = '00000000-0000-4000-8000-0000000000b1'
UNION ALL
SELECT 'discord_bridges', concat_ws('|', mode, discord_guild_id, profile_icon_discord_hash, profile_icon_path)
  FROM public.discord_bridges WHERE id = '00000000-0000-4000-8000-0000000000c1'
ORDER BY 1, 2;
SQL

take() { apsql "$1" -tA -F$'\t' -f - < "$WORK/$2.sql" > "$3"; } # take <container> <sql> <outfile>

# ---------------------------------------------------------------- old state (container A)

log "starting $A and $B ($IMAGE)"
start_pg "$A"; start_pg "$B"
wait_pg "$A"; wait_pg "$B"
load_compat "$A"; load_compat "$B"

if [ -n "$FROM_DUMP" ]; then
  # What backup.sh restore does: roles.sql first (errors tolerated, existing roles take the
  # ALTER ROLE), then pg_restore --clean --if-exists as supabase_admin. Expected pg_restore
  # errors on Supabase-owned objects are tolerated; the ledger must come back.
  log "restoring $FROM_DUMP into $A"
  roles="$(dirname "$FROM_DUMP")/roles.sql"
  if [ -f "$roles" ]; then
    docker exec -i "$A" psql -U supabase_admin -h 127.0.0.1 -d postgres -q < "$roles" > "$WORK/roles.log" 2>&1 || true
    n="$(grep 'ERROR:' "$WORK/roles.log" | grep -vc 'already exists' || true)"
    [ "$n" = 0 ] || echo "    $n role statement(s) failed; see roles.log (kept with --keep)"
  else
    echo "    no roles.sql beside the dump; roles the dump references may be missing"
  fi
  # The image gives new objects in public full privileges for anon/authenticated/service_role
  # through default privileges; production has none (migration 20261007900001). pg_dump only
  # emits the GRANTs that differ from a plain object, so restoring into the image's defaults
  # leaves extra grants on every table and function. Clear them before the restore.
  apsql "$A" -q <<'SQL' >/dev/null
DO $$
DECLARE r record;
BEGIN
  FOR r IN SELECT pg_get_userbyid(defaclrole) AS owner FROM pg_default_acl
            WHERE defaclnamespace = 'public'::regnamespace LOOP
    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public REVOKE ALL ON TABLES FROM anon, authenticated, service_role', r.owner);
    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public REVOKE ALL ON SEQUENCES FROM anon, authenticated, service_role', r.owner);
    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public REVOKE ALL ON FUNCTIONS FROM anon, authenticated, service_role', r.owner);
  END LOOP;
END $$;
SQL
  rc=0
  docker exec -i "$A" pg_restore -U supabase_admin -h 127.0.0.1 -d postgres --clean --if-exists \
    < "$FROM_DUMP" > "$WORK/restore.log" 2>&1 || rc=$?
  [ "$rc" = 0 ] || {
    echo "    pg_restore exited $rc; the errors beyond Supabase-owned objects:"
    grep -A1 '^pg_restore: error' "$WORK/restore.log" |
      grep 'ERROR:' | grep -vE 'cannot drop (constraint users_pkey|schema storage)|schema "storage" already exists|graphql_public\.graphql|event trigger "issue_pg_net_access"' |
      head -5 | sed 's/^/      /' || true
  }
  ledger="$(apsql "$A" -tAc 'SELECT count(*) FROM supabase_migrations.schema_migrations' 2>/dev/null || true)"
  [[ "$ledger" =~ ^[1-9][0-9]*$ ]] || { err "the restored database has no migration ledger"; exit 1; }
  ok "dump restored ($ledger migrations recorded)"
else
  [ -f "$SEED" ] || { err "missing $SEED"; exit 2; }
  if [ -z "$GIT_DIR_ARG" ]; then
    GIT_DIR_ARG="$(git -C "$ROOT" rev-parse --git-dir 2>/dev/null || true)"
  fi
  [ -n "$GIT_DIR_ARG" ] || { err "no git repository here to read $FROM_REF from; pass --git-dir <main repo>/.git"; exit 2; }
  OLD="$WORK/old"; mkdir -p "$OLD"
  git --git-dir="$GIT_DIR_ARG" archive "$FROM_REF" db_schema self-host | tar -x -C "$OLD" ||
    { err "git archive $FROM_REF failed (git dir: $GIT_DIR_ARG)"; exit 2; }
  # An archive from a CRLF checkout (or an autocrlf git) can carry CRs that break bash.
  find "$OLD" -type f \( -name '*.sh' -o -name '*.sql' -o -name '*.tsv' \) -exec sed -i 's/\r$//' {} +
  [ -f "$OLD/self-host/bootstrap.sh" ] || { err "$FROM_REF has no self-host/bootstrap.sh"; exit 2; }
  nold="$(ls "$OLD"/db_schema/migrations/*.sql | wc -l | tr -d '[:space:]')"
  log "old state: $FROM_REF's own bootstrap.sh ($nold migrations) on $A"
  SUPABASE_DB_CONTAINER="$A" bash "$OLD/self-host/bootstrap.sh" > "$WORK/old-bootstrap.log" 2>&1 ||
    { err "the old ref's bootstrap.sh failed"; tail -20 "$WORK/old-bootstrap.log" >&2; exit 1; }
  grep -qE '^Error:|failed\.' "$WORK/old-bootstrap.log" &&
    { err "the old ref's bootstrap.sh reported a failure"; grep -E '^Error:|failed\.' "$WORK/old-bootstrap.log" >&2; exit 1; }
  log "seeding fork-only tables ($(basename "$SEED"))"
  apsql "$A" -q -f - < "$SEED" >/dev/null
fi

take "$A" rowcount "$WORK/rows.before"
if [ -z "$FROM_DUMP" ]; then take "$A" probe "$WORK/probe.before"; fi
take "$A" ledger "$WORK/ledger.before"
# Upstream's 20261112800001 deletes finished mirror jobs (state 'done'); only the others must survive.
mirror_keep="$(apsql "$A" -tAc "SELECT count(*) FROM public.bridge_mirror_queue WHERE state IS DISTINCT FROM 'done'" 2>/dev/null || true)"
note_before="$(wc -l < "$WORK/ledger.before" | tr -d '[:space:]')"

# ---------------------------------------------------------------- upgrade A, fresh B

log "upgrading $A with this checkout's bootstrap.sh --migrations-only"
t_up=$SECONDS
SUPABASE_DB_CONTAINER="$A" bash "$HEAD_TREE/self-host/bootstrap.sh" --migrations-only > "$WORK/upgrade.log" 2>&1 || {
  err "the upgrade's bootstrap.sh failed"; tail -25 "$WORK/upgrade.log" >&2; exit 1; }
echo "    migration step took $((SECONDS - t_up)) s"
if grep -qE '^Error:|failed\.' "$WORK/upgrade.log"; then
  err "the upgrade reported a failure"; grep -E '^Error:|failed\.' "$WORK/upgrade.log" >&2; exit 1
fi
echo "    ledger rows before: $note_before"
grep -E '^  (renamed ledger|applied) ' "$WORK/upgrade.log" | sed 's/^/    /'

fail=0
renamed="$(grep -c '^  renamed ledger ' "$WORK/upgrade.log" || true)"
expected_renames="$(grep -vc '^old_version' "$ROOT/db_schema/LEDGER-RENAMES.tsv" | tr -d '[:space:]')"
if [ "$renamed" -ne "$expected_renames" ]; then
  err "expected $expected_renames 'renamed ledger' lines, got $renamed"; fail=1
else
  ok "$renamed ledger rows renamed"
fi
for m in "${EXPECT_APPLIED[@]}"; do
  if grep -qE "^  applied ${m}\.sql" "$WORK/upgrade.log"; then ok "applied $m"
  else err "$m was not applied by the upgrade"; fail=1; fi
done

log "fresh install of this checkout on $B"
SUPABASE_DB_CONTAINER="$B" bash "$HEAD_TREE/self-host/bootstrap.sh" > "$WORK/fresh.log" 2>&1 || {
  err "the fresh install's bootstrap.sh failed"; tail -25 "$WORK/fresh.log" >&2; exit 1; }

# ---------------------------------------------------------------- compare

take "$A" census "$WORK/a.census"
take "$B" census "$WORK/b.census"
for side in a b; do
  [ -s "$WORK/$side.census" ] || { err "the $side census is empty; the comparison would prove nothing"; exit 1; }
done
take "$A" ledger "$WORK/a.ledger"
take "$B" ledger "$WORK/b.ledger"
take "$A" rowcount "$WORK/rows.after"

log "comparing the upgraded database ($A) with the fresh install ($B)"
printf '    %-16s %9s %9s\n' kind upgraded fresh
for kind in table column view sequence index function constraint policy trigger enum instance_config bucket; do
  printf '    %-16s %9s %9s\n' "$kind" "$(grep -c "^$kind"$'\t' "$WORK/a.census" || true)" \
    "$(grep -c "^$kind"$'\t' "$WORK/b.census" || true)"
done

# key = kind<TAB>name. Classes: only-upgraded, only-fresh, differs. The allowlist (kind<TAB>name
# <TAB>reason; a name ending in * matches a prefix) removes expected ones; unused entries warn.
allow="$WORK/allow.tsv"
if [ -f "$ALLOWLIST" ]; then grep -vE '^[[:space:]]*(#|$)' "$ALLOWLIST" > "$allow" || true; else : > "$allow"; fi
awk -F'\t' -v OFS='\t' -v allowf="$allow" '
  BEGIN {
    while ((getline line < allowf) > 0) {
      split(line, f, "\t"); na++; ak[na] = f[1]; an[na] = f[2]; ar[na] = f[3]
    }
  }
  function allowed(kind, name,    i, pre) {
    for (i = 1; i <= na; i++) {
      if (ak[i] != kind) continue
      if (an[i] == name) { used[i] = 1; return ar[i] }
      if (substr(an[i], length(an[i])) == "*") {
        pre = substr(an[i], 1, length(an[i]) - 1)
        if (substr(name, 1, length(pre)) == pre) { used[i] = 1; return ar[i] }
      }
    }
    return ""
  }
  FNR == NR { k = $1 "\t" $2; a[k] = $3; seen[k] = 1; next }
  { k = $1 "\t" $2; b[k] = $3; seen[k] = 1 }
  END {
    n = 0
    for (k in seen) {
      split(k, p, "\t")
      if (!(k in b))      cls = "only in the upgraded database"
      else if (!(k in a)) cls = "only in the fresh install"
      else if (a[k] != b[k]) cls = "differs"
      else continue
      why = allowed(p[1], p[2])
      if (why != "") { print "ALLOWED", cls, p[1], p[2], why; continue }
      print "DIFF", cls, p[1], p[2], (k in a ? a[k] : "-"), (k in b ? b[k] : "-")
    }
    for (i = 1; i <= na; i++) if (!used[i]) print "UNUSED", ak[i], an[i]
  }' "$WORK/a.census" "$WORK/b.census" | sort > "$WORK/diff.out"

ndiff="$(grep -c '^DIFF' "$WORK/diff.out" || true)"
nallowed="$(grep -c '^ALLOWED' "$WORK/diff.out" || true)"
if [ "$nallowed" -gt 0 ]; then
  echo "    allowlisted ($nallowed):"
  grep '^ALLOWED' "$WORK/diff.out" | awk -F'\t' '{printf "      %s %s (%s): %s\n", $3, $4, $2, $5}'
fi
if grep -q '^UNUSED' "$WORK/diff.out"; then
  echo "    allowlist entries that matched nothing (stale?):"
  grep '^UNUSED' "$WORK/diff.out" | awk -F'\t' '{printf "      %s %s\n", $2, $3}'
fi
if [ "$ndiff" -gt 0 ]; then
  err "$ndiff catalog difference(s); the upgraded instance would not match a fresh install:"
  grep '^DIFF' "$WORK/diff.out" | awk -F'\t' '{
    printf "      [%s] %s %s\n", $2, $3, $4
    if ($2 == "differs") { printf "          upgraded: %s\n          fresh:    %s\n", $5, $6 }
  }' >&2
  fail=1
else
  ok "catalogs agree (tables, columns, views, sequences, indexes, functions by body, constraints, policies, triggers, enums)"
fi

if ! diff "$WORK/a.ledger" "$WORK/b.ledger" > "$WORK/ledger.diff"; then
  err "ledgers differ (< upgraded, > fresh):"
  grep '^[<>]' "$WORK/ledger.diff" | sed 's/^/      /' >&2
  fail=1
else
  ok "ledgers agree ($(wc -l < "$WORK/a.ledger" | tr -d '[:space:]') versions, same names)"
fi

# ---------------------------------------------------------------- data survival

lost=0
while IFS=$'\t' read -r t n; do
  after="$(awk -F'\t' -v t="$t" '$1 == t {print $2}' "$WORK/rows.after")"
  if [ "$t" = public.bridge_mirror_queue ] && [[ "$mirror_keep" =~ ^[0-9]+$ ]]; then n="$mirror_keep"; fi
  if [ "${after:-0}" -lt "$n" ]; then
    err "table $t lost rows in the upgrade: $n before, ${after:-0} after"; lost=1
  fi
done < "$WORK/rows.before"
if [ "$lost" = 0 ]; then
  ok "no public table lost rows ($(awk -F'\t' '{s += $2} END {print s + 0}' "$WORK/rows.before") rows before)"
else fail=1; fi

if [ -z "$FROM_DUMP" ]; then
  take "$A" probe "$WORK/probe.after"
  if [ "$(wc -l < "$WORK/probe.before" | tr -d '[:space:]')" -lt 7 ]; then
    err "the seed did not produce the expected rows:"; cat "$WORK/probe.before" >&2; fail=1
  elif diff "$WORK/probe.before" "$WORK/probe.after" > "$WORK/probe.diff"; then
    ok "seeded rows read back unchanged ($(wc -l < "$WORK/probe.after" | tr -d '[:space:]') values in 6 tables)"
  else
    err "seeded rows changed in the upgrade (< before, > after):"
    sed 's/^/      /' "$WORK/probe.diff" >&2; fail=1
  fi
fi

if [ "$fail" -ne 0 ]; then err "upgrade path check failed"; exit 1; fi
ok "an instance on ${FROM_DUMP:-$FROM_REF} upgrades to this checkout's schema, with its data"
