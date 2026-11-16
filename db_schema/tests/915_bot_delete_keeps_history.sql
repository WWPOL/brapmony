-- A bot with messages can't be deleted from My bots,
-- 20261116990016_bot_delete_keeps_history.sql.
--
-- alice owns bot915a (has a message) and bot915b (none). bot915c has a message and is the bot of a
-- bridge, which is then deleted: upstream's retire trigger keeps the bot, deactivated.

BEGIN;
SET LOCAL search_path = tests, public;
SELECT plan(12);

GRANT USAGE ON SCHEMA tests TO service_role;

INSERT INTO public.bots (id, username, display_name, owner_id, bot_type) VALUES
  ('f9150000-0000-0000-0000-0000000000b1', 'bot915a', 'Bot 915a', '11111111-0000-0000-0000-000000000001', 'bridge'),
  ('f9150000-0000-0000-0000-0000000000b2', 'bot915b', 'Bot 915b', '11111111-0000-0000-0000-000000000001', 'bridge'),
  ('f9150000-0000-0000-0000-0000000000b3', 'bot915c', 'Bot 915c', '11111111-0000-0000-0000-000000000001', 'bridge'),
  ('f9150000-0000-0000-0000-0000000000b4', 'bot915d', 'Bot 915d', '11111111-0000-0000-0000-000000000001', 'bridge');
INSERT INTO public.messages (id, channel_id, bot_id, content) VALUES
  ('f9150000-0000-0000-0000-0000000000a1', '66666666-0000-0000-0000-000000000006', 'f9150000-0000-0000-0000-0000000000b1', '[{"type": "text", "text": "kept"}]'),
  ('f9150000-0000-0000-0000-0000000000a3', '66666666-0000-0000-0000-000000000006', 'f9150000-0000-0000-0000-0000000000b3', '[{"type": "text", "text": "kept c"}]'),
  ('f9150000-0000-0000-0000-0000000000a4', '66666666-0000-0000-0000-000000000006', 'f9150000-0000-0000-0000-0000000000b4', '[{"type": "text", "text": "kept d"}]');
INSERT INTO public.discord_bridges (id, server_id, bot_id, mode, discord_guild_id, created_by) VALUES
  ('f9150000-0000-0000-0000-0000000000c3', '55555555-0000-0000-0000-000000000005', 'f9150000-0000-0000-0000-0000000000b3', 'self',
   '915000000000000003', '11111111-0000-0000-0000-000000000001');

-- bot915e belongs to alice but speaks in a server she is not a member of: RLS hides its messages from her.
INSERT INTO public.servers (id, name, owner) VALUES
  ('f9150000-0000-0000-0000-0000000000d1', 'S 915', '22222222-0000-0000-0000-000000000002');
INSERT INTO public.channels (id, server_id, name, type) VALUES
  ('f9150000-0000-0000-0000-0000000000e1', 'f9150000-0000-0000-0000-0000000000d1', 'other915', 0);
INSERT INTO public.bots (id, username, display_name, owner_id, bot_type) VALUES
  ('f9150000-0000-0000-0000-0000000000b5', 'bot915e', 'Bot 915e', '11111111-0000-0000-0000-000000000001', 'bridge');
INSERT INTO public.messages (id, channel_id, bot_id, content) VALUES
  ('f9150000-0000-0000-0000-0000000000a5', 'f9150000-0000-0000-0000-0000000000e1', 'f9150000-0000-0000-0000-0000000000b5', '[{"type": "text", "text": "hidden"}]');

SELECT tests.authenticate_as('aaaaaaaa-0000-0000-0000-000000000001');

SELECT is((SELECT count(*)::int FROM public.messages WHERE bot_id = 'f9150000-0000-0000-0000-0000000000b5'), 0,
  'the owner cannot read that bot''s messages');
SELECT throws_ok($q$DELETE FROM public.bots WHERE id = 'f9150000-0000-0000-0000-0000000000b5'$q$,
  '23503', 'This bot still has messages in a server. Disconnect its bridge first; its messages are kept.',
  'the owner is still refused when RLS hides the messages');

SELECT throws_ok($q$DELETE FROM public.bots WHERE id = 'f9150000-0000-0000-0000-0000000000b1'$q$,
  '23503', 'This bot still has messages in a server. Disconnect its bridge first; its messages are kept.',
  'the owner cannot delete a bot that has messages');
SELECT lives_ok($q$DELETE FROM public.bots WHERE id = 'f9150000-0000-0000-0000-0000000000b2'$q$,
  'the same owner can delete a bot without messages');

SELECT tests.clear_authentication();
SELECT is((SELECT count(*)::int FROM public.messages WHERE bot_id = 'f9150000-0000-0000-0000-0000000000b1'), 1,
  'the refused delete left the message');
SELECT is((SELECT count(*)::int FROM public.bots WHERE id = 'f9150000-0000-0000-0000-0000000000b2'), 0,
  'the bot without messages is gone');

-- The service role passes (e2e clean-up).
SELECT set_config('request.jwt.claims', '{"role":"service_role"}', true);
SELECT set_config('role', 'service_role', true);
SELECT lives_ok($q$DELETE FROM public.bots WHERE id = 'f9150000-0000-0000-0000-0000000000b4'$q$,
  'the service role can delete a bot that has messages');
SELECT tests.clear_authentication();
SELECT is((SELECT count(*)::int FROM public.messages WHERE id = 'f9150000-0000-0000-0000-0000000000a4'), 0,
  'and its messages go with it');

-- Disconnecting a bridge keeps a bot with messages, deactivated.
DELETE FROM public.discord_bridges WHERE id = 'f9150000-0000-0000-0000-0000000000c3';
SELECT is((SELECT is_active FROM public.bots WHERE id = 'f9150000-0000-0000-0000-0000000000b3'), false,
  'deleting a bridge deactivates its bot instead of deleting it');
SELECT is((SELECT count(*)::int FROM public.messages WHERE id = 'f9150000-0000-0000-0000-0000000000a3'), 1,
  'and keeps the bot''s messages');

-- The helper answers signed-in clients only, not anon.
SELECT ok(has_function_privilege('authenticated', 'public.bot_has_messages(uuid)', 'EXECUTE'),
  'a signed-in client can reach the helper the trigger calls');
SELECT ok(NOT has_function_privilege('anon', 'public.bot_has_messages(uuid)', 'EXECUTE'),
  'anon cannot call the helper');

SELECT * FROM finish();
ROLLBACK;
