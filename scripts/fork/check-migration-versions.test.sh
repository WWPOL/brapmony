#!/usr/bin/env bash
# Tests check-migration-versions.sh in a temporary git repo: pass, collision, wrong range.
set -euo pipefail

script="$(cd "$(dirname "$0")" && pwd)/check-migration-versions.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
cd "$tmp"

git init -q -b main .
git config user.email t@example.test
git config user.name test
git config commit.gpgsign false
mkdir -p db_schema/migrations
touch db_schema/migrations/20261110900004_upstream.sql db_schema/migrations/20261116990099_upstream_odd.sql
git add . && git commit -qm upstream
git branch up

fail=0
expect() { # name, expected exit, expected output substring
  local out code=0
  out="$(bash "$script" up 2>&1)" || code=$?
  if [ "$code" -ne "$2" ] || ! grep -qF -- "$3" <<<"$out"; then
    echo "FAIL $1: exit $code, output: $out"; fail=1
  else
    echo "ok   $1"
  fi
}

# Nothing added yet.
expect "no fork migrations" 0 "ok: 0 fork migrations"

# Passes.
touch db_schema/migrations/20261116990001_fork_a.sql db_schema/migrations/20261116990002_fork_b.sql
git add . && git commit -qm pass
expect "passes" 0 "ok: 2 fork migrations"

# Collides with a version upstream uses. The version is inside the fork range, so only the
# collision check can fail it.
touch db_schema/migrations/20261116990099_other_name.sql
git add . && git commit -qm collide
expect "collides" 1 "version collides with up: db_schema/migrations/20261116990099_other_name.sql"
git reset -q --hard HEAD~1

# Wrong range.
touch db_schema/migrations/20261116100001_fork_c.sql
git add . && git commit -qm range
expect "wrong range" 1 "outside the fork range (99xxxx): db_schema/migrations/20261116100001_fork_c.sql"

exit "$fail"
