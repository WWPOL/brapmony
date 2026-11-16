-- Functions an upgraded instance converges on, 20261116990017_upgrade_path_convergence.sql.
-- Behaviour of the bridge import functions is covered by their own tests; this pins the shape the
-- upgrade check compares: the shared guard helper, and a sorted public config key list.

BEGIN;
SET LOCAL search_path = tests, public;
SELECT plan(7);

SELECT has_function('public', 'bridge_require_server_bot', ARRAY['uuid', 'uuid'], 'the guard helper exists');
SELECT ok(NOT has_function_privilege('authenticated', 'public.bridge_require_server_bot(uuid, uuid)', 'EXECUTE')
      AND NOT has_function_privilege('service_role', 'public.bridge_require_server_bot(uuid, uuid)', 'EXECUTE'),
  'no API role executes the helper');
SELECT ok((SELECT prosrc FROM pg_proc WHERE oid = 'public.bridge_import_server_sound(uuid, uuid, text, text, text, uuid, numeric, boolean, text, integer)'::regprocedure)
          LIKE '%bridge_require_server_bot%', 'bridge_import_server_sound uses the helper');
SELECT ok((SELECT prosrc FROM pg_proc WHERE oid = 'public.bridge_import_server_sticker(uuid, uuid, text, text, text, boolean, text, text, integer, integer, integer)'::regprocedure)
          LIKE '%bridge_require_server_bot%', 'bridge_import_server_sticker uses the helper');

SELECT ok(public.public_instance_config_keys() @> ARRAY['bridge_history_import', 'discord_migration_account_creation'],
  'the public key list holds the upstream and the fork key');
SELECT is(public.public_instance_config_keys(),
  (SELECT array_agg(k ORDER BY k COLLATE "C") FROM unnest(public.public_instance_config_keys()) AS k),
  'the public key list is sorted');
SELECT is(public.public_instance_config_keys(), (SELECT array_agg(k ORDER BY k COLLATE "C") FROM (SELECT DISTINCT k FROM unnest(public.public_instance_config_keys()) AS k) d),
  'and holds no key twice');

SELECT * FROM finish();
ROLLBACK;
