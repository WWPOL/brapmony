-- "Migrate from Discord": schema and configuration RPCs, 20261112110001_discord_migration.sql.
--
--   alice  owner of server_1 (fixture) and of s2 and s3 below
--   bob    accepted member of server_1 without MANAGE_SERVER
--
--   server_1 (fixture)  guild 123456789012345678, source 'own'
--   s2                  hosted bridge with guild 900000000000000274, source 'bridge'
--   s3                  source 'instance_bot'
BEGIN;
SET LOCAL search_path = tests, public;
SELECT plan(51);

CREATE TEMP TABLE out (k text PRIMARY KEY, v jsonb) ON COMMIT DROP;
GRANT ALL ON out TO PUBLIC;
GRANT USAGE ON SCHEMA tests TO service_role;

INSERT INTO public.servers (id, name, owner) VALUES
  ('f2740000-0000-0000-0000-0000000000a2', 'S2 274', '11111111-0000-0000-0000-000000000001'),
  ('f2740000-0000-0000-0000-0000000000a3', 'S3 274', '11111111-0000-0000-0000-000000000001');
INSERT INTO public.discord_bridges (id, server_id, mode, discord_guild_id, created_by) VALUES
  ('f2740000-0000-0000-0000-0000000000b2', 'f2740000-0000-0000-0000-0000000000a2', 'hosted',
   '900000000000000274', '11111111-0000-0000-0000-000000000001');
INSERT INTO public.server_roles (id, server_id, name, permissions) VALUES
  ('f2740000-0000-0000-0000-0000000000c1', '55555555-0000-0000-0000-000000000005', 'Role274A', 0),
  ('f2740000-0000-0000-0000-0000000000c2', '55555555-0000-0000-0000-000000000005', 'Role274B', 0);

-- Grants and RLS --------------------------------------------------------------------------------
CREATE TEMP TABLE tbls (t text) ON COMMIT DROP;
INSERT INTO tbls VALUES ('discord_migrations'), ('discord_migration_secrets'), ('discord_migration_discord_roles'),
  ('discord_migration_role_map'), ('discord_migration_links'), ('discord_migration_claims'),
  ('discord_migration_link_states');
SELECT ok((SELECT bool_and(c.relrowsecurity) FROM tbls JOIN pg_class c ON c.oid = ('public.' || tbls.t)::regclass),
  'RLS is on for every migration table');
SELECT ok(NOT EXISTS (SELECT 1 FROM tbls,
                             unnest(ARRAY['anon', 'authenticated']) r,
                             unnest(ARRAY['SELECT', 'INSERT', 'UPDATE', 'DELETE']) p
                       WHERE has_table_privilege(r, 'public.' || tbls.t, p)),
  'clients hold no privilege on the migration tables');
SELECT ok((SELECT bool_and(has_table_privilege('service_role', 'public.' || t, 'SELECT, INSERT, UPDATE, DELETE')) FROM tbls),
  'service_role holds the migration tables');

CREATE TEMP TABLE client_fns (fn regprocedure) ON COMMIT DROP;
INSERT INTO client_fns VALUES
  ('public.discord_migration_get(uuid)'), ('public.discord_migration_save(uuid, text, text)'),
  ('public.discord_migration_set_own_credentials(uuid, text, text, text)'),
  ('public.discord_migration_regenerate_token(uuid)');
CREATE TEMP TABLE service_fns (fn regprocedure) ON COMMIT DROP;
INSERT INTO service_fns VALUES
  ('public.discord_migration_credentials(uuid)'), ('public.discord_migration_store_discord_roles(uuid, jsonb)'),
  ('public.discord_migration_store_warnings(uuid, jsonb)');
SELECT is_empty($q$SELECT fn FROM client_fns WHERE has_function_privilege('anon', fn, 'EXECUTE')
                                               OR NOT has_function_privilege('authenticated', fn, 'EXECUTE')$q$,
  'authenticated, not anon, executes the configuration RPCs');
SELECT is_empty($q$SELECT fn FROM service_fns WHERE has_function_privilege('anon', fn, 'EXECUTE')
                                                OR has_function_privilege('authenticated', fn, 'EXECUTE')
                                                OR NOT has_function_privilege('service_role', fn, 'EXECUTE')$q$,
  'only service_role executes the gateway functions');

-- Permission --------------------------------------------------------------------------------------
SELECT tests.authenticate_as('bbbbbbbb-0000-0000-0000-000000000002');
SELECT throws_ok($q$SELECT public.discord_migration_get('55555555-0000-0000-0000-000000000005')$q$,
  '42501', NULL, 'a member without MANAGE_SERVER cannot read the migration');
SELECT throws_ok($q$SELECT public.discord_migration_save('55555555-0000-0000-0000-000000000005', '123456789012345678', 'own')$q$,
  '42501', NULL, 'a member without MANAGE_SERVER cannot save the migration');
SELECT throws_ok($q$SELECT public.discord_migration_set_own_credentials('55555555-0000-0000-0000-000000000005', '123456789012345678', NULL, NULL)$q$,
  '42501', NULL, 'a member without MANAGE_SERVER cannot store credentials');
SELECT throws_ok($q$SELECT public.discord_migration_regenerate_token('55555555-0000-0000-0000-000000000005')$q$,
  '42501', NULL, 'a member without MANAGE_SERVER cannot regenerate the token');
SELECT tests.clear_authentication();

-- Save and get ------------------------------------------------------------------------------------
SELECT tests.authenticate_as('aaaaaaaa-0000-0000-0000-000000000001');
INSERT INTO out VALUES ('save1', public.discord_migration_save('55555555-0000-0000-0000-000000000005', '123456789012345678', 'own'));
INSERT INTO out VALUES ('get1', public.discord_migration_get('55555555-0000-0000-0000-000000000005'));
SELECT throws_ok($q$SELECT public.discord_migration_save('55555555-0000-0000-0000-000000000005', 'abc', 'own')$q$,
  '23514', NULL, 'a guild id that is not a snowflake violates the check');
SELECT throws_ok($q$SELECT public.discord_migration_save('55555555-0000-0000-0000-000000000005', '123456789012345678', 'carrier')$q$,
  '23514', NULL, 'an unknown credential source violates the check');
SELECT tests.clear_authentication();

SELECT is((SELECT v ->> 'state' FROM out WHERE k = 'get1'), 'draft', 'a saved migration starts as a draft');
SELECT is((SELECT length(v ->> 'link_token') FROM out WHERE k = 'get1'), 48, 'the link token is 48 characters');
SELECT is((SELECT v ->> 'discord_guild_id' || '/' || (v ->> 'credential_source') FROM out WHERE k = 'get1'),
  '123456789012345678/own', 'get returns the saved guild and source');
SELECT is((SELECT v ->> 'state' FROM out WHERE k = 'save1'), 'draft', 'save returns the configuration');
SELECT ok((SELECT v ->> 'caller_is_owner' = 'true' AND v ->> 'has_own_credentials' = 'false'
                  AND v ->> 'bridge_available' = 'false' AND v ->> 'instance_bot_available' = 'false'
                  AND NOT v ? 'readiness' AND NOT v ? 'permission_gaps'
             FROM out WHERE k = 'get1'),
  'get reports owner, no own credentials, no bridge, no instance bot, and leaves readiness to a later function');
SELECT is((SELECT v -> 'link_counts' FROM out WHERE k = 'get1'),
  '{"active":0,"left":0,"banned":0,"kicked_on_harmony":0}'::jsonb, 'get counts links by status');
SELECT is((SELECT v -> 'discord_roles' FROM out WHERE k = 'get1'), '[]'::jsonb, 'get lists the (empty) role snapshot');
SELECT is((SELECT v -> 'role_map' FROM out WHERE k = 'get1'), '[]'::jsonb, 'get lists the (empty) role map');

-- Own credentials ---------------------------------------------------------------------------------
SELECT tests.authenticate_as('aaaaaaaa-0000-0000-0000-000000000001');
SELECT lives_ok($q$SELECT public.discord_migration_set_own_credentials('55555555-0000-0000-0000-000000000005',
  '300000000000000274', 'abcdefghijklmnopqrstuvwxyz274001',
  ('Bot MTAxMDEwMTAxMDEwMTAxMDEw' || '.GabcDE.' || 'abcdefghijklmnopqrstuvwxyz0123456789AB'))$q$,
  'the owner stores credentials');
SELECT throws_ok($q$SELECT * FROM public.discord_migration_secrets$q$, '42501', NULL,
  'authenticated cannot read the secrets table');
SELECT ok((public.discord_migration_get('55555555-0000-0000-0000-000000000005') ->> 'has_own_credentials') = 'true'
      AND NOT (public.discord_migration_get('55555555-0000-0000-0000-000000000005') ? 'client_secret_secret'),
  'get reports credentials without exposing the secret ids');
SELECT throws_ok($q$SELECT public.discord_migration_set_own_credentials('55555555-0000-0000-0000-000000000005', 'x', NULL, NULL)$q$,
  '22023', NULL, 'a client id that is not a snowflake is refused');
SELECT throws_ok($q$SELECT public.discord_migration_credentials('55555555-0000-0000-0000-000000000005')$q$,
  '42501', NULL, 'a client cannot read the credentials');
SELECT tests.clear_authentication();

SELECT bot_token_secret AS tok1, client_secret_secret AS cs1
  FROM public.discord_migration_secrets WHERE server_id = '55555555-0000-0000-0000-000000000005' \gset

SET LOCAL role service_role;
INSERT INTO out VALUES ('cred1', public.discord_migration_credentials('55555555-0000-0000-0000-000000000005'));
RESET role;
SELECT is((SELECT v FROM out WHERE k = 'cred1'),
  jsonb_build_object('client_id', '300000000000000274', 'client_secret', 'abcdefghijklmnopqrstuvwxyz274001',
                     'bot_token', 'MTAxMDEwMTAxMDEwMTAxMDEw' || '.GabcDE.' || 'abcdefghijklmnopqrstuvwxyz0123456789AB'),
  'service_role reads the three values; the token loses its Bot prefix');

-- Rotation replaces the old Vault secret; a null argument keeps the current value.
SELECT tests.authenticate_as('aaaaaaaa-0000-0000-0000-000000000001');
SELECT public.discord_migration_set_own_credentials('55555555-0000-0000-0000-000000000005', NULL, NULL,
  ('MTAxMDEwMTAxMDEwMTAxMDEw' || '.GxyzDE.' || 'zyxwvutsrqponmlkjihgfedcba9876543210ZY'));
SELECT tests.clear_authentication();
SELECT results_eq(
  format($q$SELECT s.client_id, s.client_secret_secret = %L, s.bot_token_secret <> %L,
                   (SELECT count(*)::int FROM vault.secrets WHERE id = %L)
              FROM public.discord_migration_secrets s WHERE s.server_id = '55555555-0000-0000-0000-000000000005'$q$,
         :'cs1', :'tok1', :'tok1'),
  $q$VALUES ('300000000000000274', true, true, 0)$q$,
  'a null argument keeps the client id and secret; the replaced token''s Vault row is gone');

-- Token regeneration ------------------------------------------------------------------------------
SELECT tests.authenticate_as('aaaaaaaa-0000-0000-0000-000000000001');
INSERT INTO out VALUES ('tok_new', to_jsonb(public.discord_migration_regenerate_token('55555555-0000-0000-0000-000000000005')));
SELECT tests.clear_authentication();
SELECT ok((SELECT v #>> '{}' <> (SELECT v ->> 'link_token' FROM out WHERE k = 'get1') AND length(v #>> '{}') = 48
             FROM out WHERE k = 'tok_new')
      AND (SELECT link_token FROM public.discord_migrations WHERE server_id = '55555555-0000-0000-0000-000000000005')
          = (SELECT v #>> '{}' FROM out WHERE k = 'tok_new'),
  'regenerate_token returns a new 48 character token and stores it');

-- The hosted bridge: guild default and credentials ------------------------------------------------
SELECT tests.authenticate_as('aaaaaaaa-0000-0000-0000-000000000001');
INSERT INTO out VALUES ('save2', public.discord_migration_save('f2740000-0000-0000-0000-0000000000a2', NULL, 'bridge'));
INSERT INTO out VALUES ('get2', public.discord_migration_get('f2740000-0000-0000-0000-0000000000a2'));
SELECT tests.clear_authentication();
SELECT is((SELECT v ->> 'discord_guild_id' FROM out WHERE k = 'save2'), '900000000000000274',
  'save with a null guild id copies the hosted bridge''s guild');
SELECT is((SELECT v ->> 'bridge_available' FROM out WHERE k = 'get2'), 'true', 'a hosted bridge makes the bridge available');

SET LOCAL role service_role;
SELECT throws_ok($q$SELECT public.discord_migration_credentials('f2740000-0000-0000-0000-0000000000a2')$q$,
  'P0001', 'MIGRATION_CREDENTIALS_MISSING', 'the bridge source needs the bridge token and own client credentials');
RESET role;

SELECT vault.create_secret('hosted-bot-token-274', NULL, 'test 274') AS hosted_tok \gset
INSERT INTO public.discord_bridge_secrets (bridge_id, discord_token_secret)
VALUES ('f2740000-0000-0000-0000-0000000000b2', :'hosted_tok');
SELECT tests.authenticate_as('aaaaaaaa-0000-0000-0000-000000000001');
SELECT public.discord_migration_set_own_credentials('f2740000-0000-0000-0000-0000000000a2',
  '300000000000000275', 'abcdefghijklmnopqrstuvwxyz274002', NULL);
SELECT tests.clear_authentication();
SET LOCAL role service_role;
INSERT INTO out VALUES ('cred2', public.discord_migration_credentials('f2740000-0000-0000-0000-0000000000a2'));
RESET role;
SELECT is((SELECT v FROM out WHERE k = 'cred2'),
  '{"client_id":"300000000000000275","client_secret":"abcdefghijklmnopqrstuvwxyz274002","bot_token":"hosted-bot-token-274"}'::jsonb,
  'the bridge source takes the bot token from the hosted bridge and the rest from the migration');

-- The instance bot ---------------------------------------------------------------------------------
SELECT tests.authenticate_as('aaaaaaaa-0000-0000-0000-000000000001');
SELECT public.discord_migration_save('f2740000-0000-0000-0000-0000000000a3', '900000000000000276', 'instance_bot');
SELECT tests.clear_authentication();
SET LOCAL role service_role;
SELECT throws_ok($q$SELECT public.discord_migration_credentials('f2740000-0000-0000-0000-0000000000a3')$q$,
  'P0001', 'MIGRATION_CREDENTIALS_MISSING', 'the instance bot source raises while no instance bot is stored');
RESET role;

SELECT vault.create_secret('instance-bot-token-274', NULL, 'test 274 bot') AS ib_tok \gset
SELECT vault.create_secret('instance-client-secret-274', NULL, 'test 274 secret') AS ib_cs \gset
INSERT INTO public.discord_bridge_instance_bot (id, application_id, bot_token_secret, client_secret_secret)
VALUES (true, '300000000000000277', :'ib_tok', :'ib_cs')
ON CONFLICT (id) DO UPDATE SET application_id = EXCLUDED.application_id,
  bot_token_secret = EXCLUDED.bot_token_secret, client_secret_secret = EXCLUDED.client_secret_secret;
UPDATE public.instance_config SET config_value = 'true'::jsonb WHERE config_key = 'discord_bridge_instance_bot_enabled';
SET LOCAL role service_role;
INSERT INTO out VALUES ('cred3', public.discord_migration_credentials('f2740000-0000-0000-0000-0000000000a3'));
RESET role;
SELECT is((SELECT v FROM out WHERE k = 'cred3'),
  '{"client_id":"300000000000000277","client_secret":"instance-client-secret-274","bot_token":"instance-bot-token-274"}'::jsonb,
  'the instance bot source reads the instance bot');
SELECT tests.authenticate_as('aaaaaaaa-0000-0000-0000-000000000001');
SELECT is(public.discord_migration_get('f2740000-0000-0000-0000-0000000000a3') ->> 'instance_bot_available', 'true',
  'a configured, enabled instance bot is available');
SELECT tests.clear_authentication();

-- Instance configuration --------------------------------------------------------------------------
SELECT ok('discord_migration_account_creation' = ANY (public.public_instance_config_keys()),
  'discord_migration_account_creation is a public instance config key');
SELECT is((SELECT config_value FROM public.instance_config WHERE config_key = 'discord_migration_account_creation'),
  'true'::jsonb, 'account creation through the migration defaults to true');

-- Role snapshot -----------------------------------------------------------------------------------
SET LOCAL role service_role;
SELECT public.discord_migration_store_discord_roles('55555555-0000-0000-0000-000000000005', jsonb_build_array(
  jsonb_build_object('id', '400000000000000001', 'name', 'Admins', 'color', 255, 'position', 5, 'harmony_permissions', '17179869183'),
  jsonb_build_object('id', '400000000000000002', 'name', 'Mods', 'color', 0, 'position', 3, 'harmony_permissions', '8'),
  jsonb_build_object('id', '123456789012345678', 'name', '@everyone', 'color', 0, 'position', 0, 'harmony_permissions', '0')));
RESET role;
SELECT is((SELECT harmony_permissions FROM public.discord_migration_discord_roles
            WHERE server_id = '55555555-0000-0000-0000-000000000005' AND discord_role_id = '400000000000000001'),
  17179869183::bigint, 'harmony_permissions round trips');
SELECT is((SELECT count(*)::int FROM public.discord_migration_discord_roles
            WHERE server_id = '55555555-0000-0000-0000-000000000005'),
  2, '@everyone (the guild id) is never stored');

INSERT INTO public.discord_migration_role_map (server_id, discord_role_id, harmony_role_id) VALUES
  ('55555555-0000-0000-0000-000000000005', '400000000000000001', 'f2740000-0000-0000-0000-0000000000c1'),
  ('55555555-0000-0000-0000-000000000005', '400000000000000002', 'f2740000-0000-0000-0000-0000000000c2');
SET LOCAL role service_role;
SELECT public.discord_migration_store_discord_roles('55555555-0000-0000-0000-000000000005', jsonb_build_array(
  jsonb_build_object('id', '400000000000000001', 'name', 'Admins renamed', 'color', 1, 'position', 6, 'harmony_permissions', '1')));
RESET role;
SELECT is((SELECT array_agg(discord_role_id) FROM public.discord_migration_role_map
            WHERE server_id = '55555555-0000-0000-0000-000000000005'),
  ARRAY['400000000000000001'], 'a role missing from the new snapshot loses its map row');
SELECT is((SELECT name || '/' || harmony_permissions FROM public.discord_migration_discord_roles
            WHERE server_id = '55555555-0000-0000-0000-000000000005'),
  'Admins renamed/1', 'the snapshot is replaced');

-- Warnings ----------------------------------------------------------------------------------------
SET LOCAL role service_role;
SELECT public.discord_migration_store_warnings('55555555-0000-0000-0000-000000000005', '["bot_cannot_ban"]'::jsonb);
RESET role;
SELECT is((SELECT readiness_warnings FROM public.discord_migrations WHERE server_id = '55555555-0000-0000-0000-000000000005'),
  '["bot_cannot_ban"]'::jsonb, 'store_warnings keeps the warnings');
SELECT tests.authenticate_as('aaaaaaaa-0000-0000-0000-000000000001');
SELECT throws_ok($q$SELECT public.discord_migration_store_warnings('55555555-0000-0000-0000-000000000005', '[]'::jsonb)$q$,
  '42501', NULL, 'a client cannot store warnings');
SELECT throws_ok($q$SELECT public.discord_migration_store_discord_roles('55555555-0000-0000-0000-000000000005', '[]'::jsonb)$q$,
  '42501', NULL, 'a client cannot store the role snapshot');
SELECT tests.clear_authentication();

-- Links -------------------------------------------------------------------------------------------
SELECT throws_ok($q$INSERT INTO public.discord_migration_links (server_id, user_id, discord_user_id, status, sync_fields)
  VALUES ('55555555-0000-0000-0000-000000000005', '22222222-0000-0000-0000-000000000002', '500000000000000001', 'active', '{nickname}')$q$,
  '23514', NULL, 'a sync field outside the list violates the check');
INSERT INTO public.discord_migration_links (server_id, user_id, discord_user_id, status, sync_fields)
VALUES ('55555555-0000-0000-0000-000000000005', '22222222-0000-0000-0000-000000000002', '500000000000000001', 'active',
        '{avatar,server_nickname}');
SELECT throws_ok($q$INSERT INTO public.discord_migration_links (server_id, user_id, discord_user_id, status)
  VALUES ('55555555-0000-0000-0000-000000000005', '11111111-0000-0000-0000-000000000001', '500000000000000001', 'active')$q$,
  '23505', NULL, 'a Discord account links to one member per server');
SELECT tests.authenticate_as('aaaaaaaa-0000-0000-0000-000000000001');
SELECT is(public.discord_migration_get('55555555-0000-0000-0000-000000000005') -> 'link_counts' ->> 'active', '1',
  'get counts the active link');
SELECT tests.clear_authentication();

-- Open migrations keep their guild -----------------------------------------------------------------
UPDATE public.discord_migrations SET state = 'open', opened_at = now()
 WHERE server_id = '55555555-0000-0000-0000-000000000005';
SELECT tests.authenticate_as('aaaaaaaa-0000-0000-0000-000000000001');
SELECT throws_ok($q$SELECT public.discord_migration_save('55555555-0000-0000-0000-000000000005', '123456789012345679', 'own')$q$,
  '55000', NULL, 'the guild id cannot change while the migration is open');
SELECT lives_ok($q$SELECT public.discord_migration_save('55555555-0000-0000-0000-000000000005', '123456789012345678', 'own')$q$,
  'saving the same guild id while open is allowed');
SELECT tests.clear_authentication();
UPDATE public.discord_migrations SET state = 'draft', opened_at = NULL
 WHERE server_id = '55555555-0000-0000-0000-000000000005';

-- A new guild drops the snapshot and the map -------------------------------------------------------
SELECT tests.authenticate_as('aaaaaaaa-0000-0000-0000-000000000001');
SELECT public.discord_migration_save('55555555-0000-0000-0000-000000000005', '123456789012345679', 'own');
SELECT tests.clear_authentication();
SELECT is((SELECT (SELECT count(*) FROM public.discord_migration_discord_roles WHERE server_id = '55555555-0000-0000-0000-000000000005')
              + (SELECT count(*) FROM public.discord_migration_role_map WHERE server_id = '55555555-0000-0000-0000-000000000005')),
  0::bigint, 'a draft that moves to another guild drops the role snapshot and map');

-- Deleting the migration deletes its Vault secrets --------------------------------------------------
SELECT bot_token_secret AS tok2, client_secret_secret AS cs2
  FROM public.discord_migration_secrets WHERE server_id = '55555555-0000-0000-0000-000000000005' \gset
DELETE FROM public.discord_migrations WHERE server_id = '55555555-0000-0000-0000-000000000005';
SELECT is((SELECT count(*)::int FROM vault.secrets WHERE id IN (:'tok2', :'cs2')), 0,
  'deleting the migration deletes its Vault secrets');
SELECT is((SELECT count(*)::int FROM public.discord_migration_secrets WHERE server_id = '55555555-0000-0000-0000-000000000005'), 0,
  'and its secrets row');

SELECT * FROM finish();
ROLLBACK;
