-- fork_convert_bridge_to_self, 20261116990018_convert_bridge_to_self.sql: an instance bridge becomes
-- a self-run bridge in place, with the same bot, guild and pairs, so its history stays reachable and
-- history import stops being blocked by the bridge's mode.
--
-- Fixture server_1 (alice owns it) with #general. Added: bridge917 (mode instance) run by bot917,
-- paired with Discord channel ...01; a second server with a self bridge; bob is not an admin.
-- The instance bot offer is on and its application is the bridge's, so the first call is refused;
-- then the instance application changes and the conversion goes through with the offer still on.

BEGIN;
SET LOCAL search_path = tests, public;
SELECT plan(21);

UPDATE public.profiles SET is_admin = true WHERE id = '11111111-0000-0000-0000-000000000001';

INSERT INTO public.bots (id, username, display_name, owner_id, bot_type) VALUES
  ('f9170000-0000-0000-0000-0000000000b1', 'bot917', 'Bot 917', '11111111-0000-0000-0000-000000000001', 'bridge'),
  ('f9170000-0000-0000-0000-0000000000b2', 'bot917b', 'Bot 917b', '11111111-0000-0000-0000-000000000001', 'bridge');
INSERT INTO public.discord_bridges (id, server_id, mode, bot_id, discord_guild_id, discord_application_id, created_by)
VALUES ('f9170000-0000-0000-0000-0000000000d1', '55555555-0000-0000-0000-000000000005', 'instance',
        'f9170000-0000-0000-0000-0000000000b1', '900000000000000917', '917000000000000001',
        '11111111-0000-0000-0000-000000000001');
INSERT INTO public.discord_bridge_channels (bridge_id, harmony_channel_id, discord_channel_id, direction) VALUES
  ('f9170000-0000-0000-0000-0000000000d1', '66666666-0000-0000-0000-000000000006', '9170000000000000001', 'both');
-- The Vault copy of the instance host's token; the row needs no vault to exist.
INSERT INTO public.discord_bridge_secrets (bridge_id, discord_token_secret, harmony_token_secret)
VALUES ('f9170000-0000-0000-0000-0000000000d1', NULL, gen_random_uuid());
INSERT INTO public.discord_bridge_link_states (state_hash, bridge_id, expires_at)
VALUES (repeat('a', 64), 'f9170000-0000-0000-0000-0000000000d1', now() + interval '15 minutes');
INSERT INTO public.messages (id, channel_id, bot_id, content) VALUES
  ('f9170000-0000-0000-0000-0000000000a1', '66666666-0000-0000-0000-000000000006',
   'f9170000-0000-0000-0000-0000000000b1', '[{"type":"text","text":"bridged before"}]');

INSERT INTO public.servers (id, name, owner)
VALUES ('f9170000-0000-0000-0000-0000000000e2', 'Server 917b', '11111111-0000-0000-0000-000000000001');
INSERT INTO public.discord_bridges (id, server_id, mode, bot_id, created_by)
VALUES ('f9170000-0000-0000-0000-0000000000d2', 'f9170000-0000-0000-0000-0000000000e2', 'self',
        'f9170000-0000-0000-0000-0000000000b2', '11111111-0000-0000-0000-000000000001');

UPDATE public.instance_config SET config_value = '"self_run"' WHERE config_key = 'bridge_history_import';

-- Who may call it ------------------------------------------------------------------------------------------
SELECT is(public.discord_bridge_import_block('f9170000-0000-0000-0000-0000000000d1'), 'bridge_mode',
          'an instance bridge is blocked from importing by its mode');
SELECT is(has_function_privilege('anon', 'public.fork_convert_bridge_to_self(uuid)', 'EXECUTE'), false,
          'anon cannot call it');
SELECT tests.authenticate_as('bbbbbbbb-0000-0000-0000-000000000002');
SELECT throws_ok($q$SELECT public.fork_convert_bridge_to_self('f9170000-0000-0000-0000-0000000000d1')$q$,
  '42501', 'Unauthorized: Admin role required', 'a member who is not an instance admin is refused');
SELECT tests.clear_authentication();
SELECT throws_ok($q$SELECT public.fork_convert_bridge_to_self('f9170000-0000-0000-0000-0000000000d1')$q$,
  '42501', 'Unauthorized: Admin role required', 'a call without a signed-in account is refused');

-- Refusals ---------------------------------------------------------------------------------------------------
SELECT tests.authenticate_as('aaaaaaaa-0000-0000-0000-000000000001');
SELECT throws_ok($q$SELECT public.fork_convert_bridge_to_self('f9170000-0000-0000-0000-0000000000d2')$q$,
  '22023', 'Only an instance bridge can be converted (this one is self)', 'a self bridge is refused');
SELECT throws_ok($q$SELECT public.fork_convert_bridge_to_self('f9170000-0000-0000-0000-0000000000ff')$q$,
  'P0002', 'Discord bridge not found', 'an unknown bridge is refused');
SELECT tests.clear_authentication();

-- The instance bot offer is on and the bridge uses its application.
INSERT INTO public.discord_bridge_instance_bot (id, application_id, bot_token_secret, client_secret_secret)
VALUES (true, '917000000000000001', gen_random_uuid(), gen_random_uuid());
UPDATE public.instance_config SET config_value = 'true'::jsonb WHERE config_key = 'discord_bridge_instance_bot_enabled';
SELECT tests.authenticate_as('aaaaaaaa-0000-0000-0000-000000000001');
SELECT throws_ok($q$SELECT public.fork_convert_bridge_to_self('f9170000-0000-0000-0000-0000000000d1')$q$,
  '55000', 'Turn the instance bot off first: its host would leave this Discord server',
  'refused while the instance bot is on and the bridge uses its application');
SELECT tests.clear_authentication();
SELECT is((SELECT mode FROM public.discord_bridges WHERE id = 'f9170000-0000-0000-0000-0000000000d1'), 'instance',
          'the refusal changed nothing');

-- Conversion -------------------------------------------------------------------------------------------------
-- The offer stays on, for another application than the bridge's.
UPDATE public.discord_bridge_instance_bot SET application_id = '917000000000000002';
SELECT tests.authenticate_as('aaaaaaaa-0000-0000-0000-000000000001');
CREATE TEMP TABLE code917 AS
SELECT public.fork_convert_bridge_to_self('f9170000-0000-0000-0000-0000000000d1') AS code;
SELECT tests.clear_authentication();

SELECT matches((SELECT code FROM code917), '^HB-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}$', 'it returns a setup code');
SELECT is((SELECT mode FROM public.discord_bridges WHERE id = 'f9170000-0000-0000-0000-0000000000d1'), 'self',
          'the mode is self');
SELECT is((SELECT bot_id FROM public.discord_bridges WHERE id = 'f9170000-0000-0000-0000-0000000000d1'),
          'f9170000-0000-0000-0000-0000000000b1'::uuid, 'the bot is the same');
SELECT is((SELECT discord_guild_id || '/' || discord_application_id FROM public.discord_bridges
            WHERE id = 'f9170000-0000-0000-0000-0000000000d1'), '900000000000000917/917000000000000001',
          'the guild and application are kept');
SELECT is((SELECT count(*) FROM public.discord_bridge_channels WHERE bridge_id = 'f9170000-0000-0000-0000-0000000000d1'),
          1::bigint, 'the channel pair is kept');
SELECT is((SELECT count(*) FROM public.discord_bridge_secrets WHERE bridge_id = 'f9170000-0000-0000-0000-0000000000d1')
        + (SELECT count(*) FROM public.discord_bridge_link_states WHERE bridge_id = 'f9170000-0000-0000-0000-0000000000d1'),
          0::bigint, 'the instance-only secrets row and link states are gone');
SELECT is(public.discord_bridge_import_block('f9170000-0000-0000-0000-0000000000d1'), NULL,
          'import is no longer blocked by the bridge mode');
SELECT is((SELECT count(*) FROM public.messages WHERE bot_id = 'f9170000-0000-0000-0000-0000000000b1'), 1::bigint,
          'the bot''s messages are untouched');
SELECT is((SELECT count(*) FROM public.admin_audit_log WHERE action_type = 'config_change'
              AND target_id = 'discord_bridge_mode'
              AND action_details ->> 'bridge_id' = 'f9170000-0000-0000-0000-0000000000d1'
              AND action_details ->> 'old_value' = 'instance' AND action_details ->> 'new_value' = 'self'),
          1::bigint, 'the change is in the admin audit log');

-- The code redeems like one from Get a setup code, for the same bot.
CREATE TEMP TABLE redeem917 AS
SELECT public.discord_bridge_redeem_code((SELECT code FROM code917)) AS r;
SELECT is((SELECT r ->> 'bridge_id' FROM redeem917), 'f9170000-0000-0000-0000-0000000000d1', 'the code redeems');
SELECT is((SELECT count(*) FROM public.bot_tokens WHERE bot_id = 'f9170000-0000-0000-0000-0000000000b1' AND is_active),
          1::bigint, 'redeeming issued a token for the existing bot');
SELECT is((SELECT count(*) FROM public.bots WHERE username LIKE 'discord-bridge-%'), 0::bigint,
          'no new bridge bot was provisioned');

-- A converted bridge is no longer an instance bridge.
SELECT tests.authenticate_as('aaaaaaaa-0000-0000-0000-000000000001');
SELECT throws_ok($q$SELECT public.fork_convert_bridge_to_self('f9170000-0000-0000-0000-0000000000d1')$q$,
  '22023', 'Only an instance bridge can be converted (this one is self)', 'converting it again is refused');
SELECT tests.clear_authentication();

SELECT * FROM finish();
ROLLBACK;
