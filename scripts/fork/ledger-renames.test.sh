#!/usr/bin/env bash
# Tests self-host/fork-ledger-renames.sh against a throwaway Postgres (needs Docker; run in WSL):
# rename, name mismatch, already renamed, missing file, and the real TSV's shape.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
c="ledger-renames-test-$$"
tmp="$(mktemp -d)"
cleanup() { docker rm -f "$c" >/dev/null 2>&1 || true; rm -rf "$tmp"; }
trap cleanup EXIT

docker pull -q postgres:15 >/dev/null
docker run -d --name "$c" -e POSTGRES_PASSWORD=x postgres:15 >/dev/null
for _ in $(seq 60); do
  docker exec "$c" pg_isready -U postgres >/dev/null 2>&1 && docker exec "$c" psql -U postgres -tAc 'SELECT 1' >/dev/null 2>&1 && break
  sleep 1
done

psql_exec() { docker exec -i -e PGOPTIONS="-c client_min_messages=warning" "$c" psql -U postgres -v ON_ERROR_STOP=1 "$@"; }
# shellcheck source=../../self-host/fork-ledger-renames.sh
source "$root/self-host/fork-ledger-renames.sh"

reset() { # rows as "version:name" ...
  psql_exec -q >/dev/null <<'SQL'
CREATE SCHEMA IF NOT EXISTS supabase_migrations;
DROP TABLE IF EXISTS supabase_migrations.schema_migrations;
CREATE TABLE supabase_migrations.schema_migrations (version text PRIMARY KEY, name text, statements text[]);
SQL
  local r
  for r in "$@"; do
    psql_exec -q -v v="${r%%:*}" -v n="${r#*:}" >/dev/null <<'SQL'
INSERT INTO supabase_migrations.schema_migrations (version, name) VALUES (:'v', :'n');
SQL
  done
}
ledger() { psql_exec -tAc "SELECT version || ':' || name FROM supabase_migrations.schema_migrations ORDER BY version" | paste -sd, -; }

printf 'old_version\told_name\tnew_version\tnew_name\n\n20261112100001\tnickname_permissions\t20261116990001\tnickname_permissions\r\n20261112500001\tdiscord_migration\t20261116990007\tdiscord_migration\n' >"$tmp/r.tsv"

fail=0
expect() { # name, expected ledger, expected output
  local out got
  out="$(rename_fork_ledger "$tmp/r.tsv" 2>&1)"; got="$(ledger)"
  if [ "$got" = "$2" ] && [ "$out" = "$3" ]; then echo "ok   $1"; else
    echo "FAIL $1"; echo "  ledger: $got"; echo "  wanted: $2"; echo "  output: $out"; fail=1; fi
}

reset 20261112100001:nickname_permissions 20261112500001:discord_migration 20261110900004:upstream
expect "matching rows are renamed" \
  "20261110900004:upstream,20261116990001:nickname_permissions,20261116990007:discord_migration" \
  $'  renamed ledger 20261112100001 -> 20261116990001\n  renamed ledger 20261112500001 -> 20261116990007'

expect "second run changes nothing" \
  "20261110900004:upstream,20261116990001:nickname_permissions,20261116990007:discord_migration" ""

reset 20261112500001:upstream_other_name
expect "name mismatch is left alone (an upstream migration on the same version)" \
  "20261112500001:upstream_other_name" ""

reset 20261112500001:discord_migration 20261116990007:discord_migration
expect "new version already recorded: no change, no error" \
  "20261112500001:discord_migration,20261116990007:discord_migration" ""

reset 20261112500001:discord_migration
out="$(rename_fork_ledger "$tmp/missing.tsv" 2>&1)"
if [ -z "$out" ] && [ "$(ledger)" = "20261112500001:discord_migration" ]; then echo "ok   missing TSV is a no-op"; else echo "FAIL missing TSV"; fail=1; fi

# The real file: 14 rows, every old name equals its new name, new versions in the fork range.
real="$root/db_schema/LEDGER-RENAMES.tsv"
n="$(tail -n +2 "$real" | tr -d '\r' | awk -F'\t' 'NF==4 && $2==$4 && $3 ~ /^2026111699[0-9]{4}$/' | wc -l)"
if [ "$n" -eq 14 ]; then echo "ok   LEDGER-RENAMES.tsv has 14 well-formed rows"; else echo "FAIL LEDGER-RENAMES.tsv: $n well-formed rows"; fail=1; fi

exit $fail
