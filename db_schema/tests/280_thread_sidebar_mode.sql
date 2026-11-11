-- 20261112600001_thread_sidebar_mode.sql: which threads the channel sidebar lists, per server.
--
--   alice  owns S (MANAGE_SERVER)    bob  a member    carol  not a member
BEGIN;
SET LOCAL search_path = tests, public;
SELECT plan(14);

INSERT INTO public.servers (id, name, owner, is_local_server) VALUES
  ('28000000-0000-0000-0000-000000000001', 'Sidebar', '11111111-0000-0000-0000-000000000001', true);
INSERT INTO public.channels (id, server_id, name, type) VALUES
  ('28000000-0000-0000-0000-000000000002', '28000000-0000-0000-0000-000000000001', 'general', 0);
INSERT INTO public.user_servers (user_id, server_id, status) VALUES
  ('11111111-0000-0000-0000-000000000001', '28000000-0000-0000-0000-000000000001', 'accepted'),
  ('22222222-0000-0000-0000-000000000002', '28000000-0000-0000-0000-000000000001', 'accepted')
ON CONFLICT DO NOTHING;

SELECT throws_ok($q$INSERT INTO public.server_settings (server_id, thread_sidebar) VALUES ('28000000-0000-0000-0000-000000000001', 'some')$q$,
    '23514', NULL, 'a mode other than all or joined is refused');

SELECT tests.authenticate_as('aaaaaaaa-0000-0000-0000-000000000001');
SELECT is(public.get_server_thread_sidebar('28000000-0000-0000-0000-000000000001'),
    '{"mode":"all","server_value":null,"instance_default":"all"}'::jsonb, 'the default is all');

SELECT tests.clear_authentication();
INSERT INTO public.instance_config (config_key, config_value) VALUES ('thread_sidebar_default', '"joined"')
ON CONFLICT (config_key) DO UPDATE SET config_value = EXCLUDED.config_value;
SELECT tests.authenticate_as('aaaaaaaa-0000-0000-0000-000000000001');
SELECT is(public.get_server_thread_sidebar('28000000-0000-0000-0000-000000000001'),
    '{"mode":"joined","server_value":null,"instance_default":"joined"}'::jsonb, 'the instance default applies');

SELECT tests.clear_authentication();
UPDATE public.instance_config SET config_value = '"garbage"' WHERE config_key = 'thread_sidebar_default';
SELECT tests.authenticate_as('aaaaaaaa-0000-0000-0000-000000000001');
SELECT is(public.get_server_thread_sidebar('28000000-0000-0000-0000-000000000001')->>'mode', 'all', 'an unknown instance default reads as all');

SELECT tests.clear_authentication();
UPDATE public.instance_config SET config_value = '"joined"' WHERE config_key = 'thread_sidebar_default';
SELECT tests.authenticate_as('aaaaaaaa-0000-0000-0000-000000000001');
SELECT is(public.set_server_thread_sidebar('28000000-0000-0000-0000-000000000001', 'all'),
    '{"mode":"all","server_value":"all","instance_default":"joined"}'::jsonb, 'the server value wins over the instance default');
SELECT is(public.set_server_thread_sidebar('28000000-0000-0000-0000-000000000001', 'joined')->>'mode', 'joined', 'a manager sets joined');
SELECT is(public.set_server_thread_sidebar('28000000-0000-0000-0000-000000000001', NULL),
    '{"mode":"joined","server_value":null,"instance_default":"joined"}'::jsonb, 'NULL returns the server to the instance default');
SELECT throws_ok($q$SELECT public.set_server_thread_sidebar('28000000-0000-0000-0000-000000000001', 'nope')$q$,
    '22023', NULL, 'an unknown mode is refused');

SELECT tests.authenticate_as('bbbbbbbb-0000-0000-0000-000000000002');
SELECT throws_ok($q$SELECT public.get_server_thread_sidebar('28000000-0000-0000-0000-000000000001')$q$,
    '42501', NULL, 'a member without MANAGE_SERVER cannot read the setting');
SELECT throws_ok($q$SELECT public.set_server_thread_sidebar('28000000-0000-0000-0000-000000000001', 'all')$q$,
    '42501', NULL, 'nor change it');
SELECT is(public.server_thread_sidebar_mode('28000000-0000-0000-0000-000000000001'), 'joined', 'a member reads the effective mode');

SELECT tests.authenticate_as('cccccccc-0000-0000-0000-000000000003');
SELECT throws_ok($q$SELECT public.server_thread_sidebar_mode('28000000-0000-0000-0000-000000000001')$q$,
    '42501', NULL, 'a non-member cannot read the mode');

SELECT tests.clear_authentication();
SELECT throws_ok($q$SELECT public.get_server_thread_sidebar('28000000-0000-0000-0000-000000000001')$q$,
    '42501', NULL, 'signed out, nothing is readable');

SELECT is(public.server_thread_sidebar_state('28000000-0000-0000-0000-000000000001')->>'mode', 'joined', 'service_role reads the state');

SELECT * FROM finish();
ROLLBACK;
