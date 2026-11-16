# Fork-only (WWPOL/brapmony); sourced by bootstrap.sh. See CLAUDE.md "Syncing with upstream".
# Instances that applied the fork's migrations under their old numbers get the ledger rows
# renamed to the new ones, which frees upstream's migrations that share the old numbers.
# Needs psql_exec. $1 is the TSV (old_version old_name new_version new_name); a missing file
# is a no-op. A row is renamed only if it matches exactly and the new version is not recorded.
rename_fork_ledger() {
	local tsv="$1" ov on nv nn changed
	[[ -f "$tsv" ]] || return 0
	while IFS=$'\t' read -r ov on nv nn || [[ -n "$ov" ]]; do
		ov="${ov%$'\r'}"; nn="${nn%$'\r'}"
		[[ -z "$ov" || "$ov" == old_version ]] && continue
		changed="$(psql_exec -qtA -v ov="$ov" -v on="$on" -v nv="$nv" -v nn="$nn" <<'SQL'
UPDATE supabase_migrations.schema_migrations
SET version = :'nv', name = :'nn'
WHERE version = :'ov' AND name = :'on'
  AND NOT EXISTS (SELECT 1 FROM supabase_migrations.schema_migrations WHERE version = :'nv')
RETURNING version;
SQL
)" || return 1
		[[ -z "$changed" ]] || printf '  renamed ledger %s -> %s\n' "$ov" "$nv"
	done <"$tsv"
}
