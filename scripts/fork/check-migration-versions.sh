#!/usr/bin/env bash
# Checks the fork's migrations against upstream's.
# Usage: scripts/fork/check-migration-versions.sh [upstream-ref]   (default: upstream/master)
# Run from inside the repo. MIGRATIONS_DIR overrides the folder (default db_schema/migrations).
# Fork migrations are the files in HEAD that the ref doesn't have. Each one must have a
# 14-digit version the ref doesn't use, and a counter (the last six digits) starting with 99.
set -euo pipefail

ref="${1:-upstream/master}"
dir="${MIGRATIONS_DIR:-db_schema/migrations}"

root="$(git rev-parse --show-toplevel)"
cd "$root"

versions() { sed -E -n 's|^.*/([0-9]{14})_.*$|\1|p'; }

upstream_files="$(git ls-tree -r --name-only "$ref" -- "$dir/")"
head_files="$(git ls-tree -r --name-only HEAD -- "$dir/")"
upstream_versions="$(printf '%s\n' "$upstream_files" | versions)"

fork_files="$(comm -13 <(printf '%s\n' "$upstream_files" | sort) <(printf '%s\n' "$head_files" | sort))"

count=0
bad=0
while IFS= read -r f; do
  [ -n "$f" ] || continue
  count=$((count + 1))
  v="$(printf '%s\n' "$f" | versions)"
  if [ -z "$v" ]; then
    echo "bad name (no 14-digit version): $f"; bad=1; continue
  fi
  if printf '%s\n' "$upstream_versions" | grep -qx "$v"; then
    echo "version collides with $ref: $f"; bad=1
  fi
  counter="${v: -6}"
  if [ "${counter:0:2}" != "99" ]; then
    echo "counter $counter is outside the fork range (99xxxx): $f"; bad=1
  fi
done <<< "$fork_files"

[ "$bad" -eq 0 ] || exit 1
echo "ok: $count fork migrations"
