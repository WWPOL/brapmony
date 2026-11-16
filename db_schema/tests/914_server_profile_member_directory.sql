-- 20261116990015_server_profile_member_directory.sql: the member directory carries the server
-- profile fields a list shows, and a nickname is a name the list sorts and searches by.
--
-- Fixture roles: alice owns server_1 and bob is an accepted member of it.
BEGIN;
SET LOCAL search_path = tests, public;
SELECT plan(13);

UPDATE public.profiles SET display_name = 'Alice A', pronouns = 'she/her'
 WHERE id = '11111111-0000-0000-0000-000000000001';
UPDATE public.profiles SET display_name = 'Bob B' WHERE id = '22222222-0000-0000-0000-000000000002';
UPDATE public.user_servers SET nickname = 'Zed', avatar_url = 'a/servers/s/z.png', bio = 'server bio'
 WHERE user_id = '22222222-0000-0000-0000-000000000002' AND server_id = '55555555-0000-0000-0000-000000000005';

CREATE FUNCTION tests.md914_member(p_list jsonb, p_user text) RETURNS jsonb LANGUAGE sql AS $fn$
  SELECT m FROM jsonb_array_elements(p_list -> 'members') m WHERE m ->> 'username' = p_user;
$fn$;

SELECT tests.authenticate_as('aaaaaaaa-0000-0000-0000-000000000001');

SELECT is(tests.md914_member(public.get_server_member_list('55555555-0000-0000-0000-000000000005'), 'bob') ->> 'nickname',
          'Zed', 'a listed member carries its nickname on the server');
SELECT is(tests.md914_member(public.get_server_member_list('55555555-0000-0000-0000-000000000005'), 'bob') ->> 'server_avatar_url',
          'a/servers/s/z.png', 'and its server avatar');
SELECT ok(NOT (tests.md914_member(public.get_server_member_list('55555555-0000-0000-0000-000000000005'), 'bob') ? 'bio'),
          'but not its server bio, which loads with the profile card');
SELECT is(tests.md914_member(public.get_server_member_list('55555555-0000-0000-0000-000000000005'), 'alice') ->> 'pronouns',
          'she/her', 'a member carries its account pronouns');
SELECT ok(NOT (tests.md914_member(public.get_server_member_list('55555555-0000-0000-0000-000000000005'), 'alice') ? 'nickname'),
          'a member with no nickname carries none');
SELECT is(tests.md914_member(public.get_server_member_list('55555555-0000-0000-0000-000000000005'), 'bob') ->> 'display_name',
          'Bob B', 'the account display name stays');

-- Search ranks and orders by the shown name: "AAA first" (bob) before "Alice A" (alice), both
-- starting with the query.
SELECT tests.clear_authentication();
UPDATE public.user_servers SET nickname = 'AAA first'
 WHERE user_id = '22222222-0000-0000-0000-000000000002' AND server_id = '55555555-0000-0000-0000-000000000005';
SELECT tests.authenticate_as('aaaaaaaa-0000-0000-0000-000000000001');
SELECT is((SELECT string_agg(m ->> 'username', ',' ORDER BY ord)
             FROM jsonb_array_elements(public.search_server_members('55555555-0000-0000-0000-000000000005', 'a')) WITH ORDINALITY t(m, ord)
            WHERE m ->> 'username' IN ('alice', 'bob')),
          'bob,alice', 'a nickname orders search results: bob "AAA first" before alice "Alice A"');

-- Search by nickname.
SELECT is((SELECT string_agg(m ->> 'username', ',') FROM jsonb_array_elements(
            public.search_server_members('55555555-0000-0000-0000-000000000005', 'aaa first')) m),
          'bob', 'search finds a member by nickname');
SELECT is((SELECT string_agg(m ->> 'username', ',') FROM jsonb_array_elements(
            public.search_server_members('55555555-0000-0000-0000-000000000005', 'bob b')) m),
          'bob', 'and still by display name');
SELECT is(jsonb_array_length(public.search_server_members('55555555-0000-0000-0000-000000000005', 'zed')), 0,
          'a nickname that was replaced no longer matches');

-- Another server's nickname is not this server's.
SELECT is(tests.md914_member(public.get_server_member_list('55555555-0000-0000-0000-000000000005'), 'alice') ->> 'nickname',
          NULL, 'a nickname is read from the listed server only');

-- Whitespace-only nickname reads as none.
SELECT tests.clear_authentication();
UPDATE public.user_servers SET nickname = '   '
 WHERE user_id = '22222222-0000-0000-0000-000000000002' AND server_id = '55555555-0000-0000-0000-000000000005';
SELECT tests.authenticate_as('aaaaaaaa-0000-0000-0000-000000000001');
SELECT ok(NOT (tests.md914_member(public.get_server_member_list('55555555-0000-0000-0000-000000000005'), 'bob') ? 'nickname'),
          'a blank nickname is not carried');

-- The viewer's own place in a stored order (lists over 200 members) uses the same name key as
-- the order: bob "Aab", alice "Ccc", zed "zed" -> bob, alice, zed. With display names alone bob
-- ("Xbob") would sort after alice.
SELECT tests.clear_authentication();
INSERT INTO public.profiles (id, username, display_name, is_local, domain)
VALUES ('e9140000-0000-0000-0000-000000000001', 'zed', 'Zed', true, 'localhost');
INSERT INTO public.user_servers (user_id, server_id, status)
VALUES ('e9140000-0000-0000-0000-000000000001', '55555555-0000-0000-0000-000000000005', 'accepted');
UPDATE public.profiles SET display_name = 'Xbob' WHERE id = '22222222-0000-0000-0000-000000000002';
UPDATE public.user_servers SET nickname = 'Aab'
 WHERE user_id = '22222222-0000-0000-0000-000000000002' AND server_id = '55555555-0000-0000-0000-000000000005';
UPDATE public.user_servers SET nickname = 'Ccc'
 WHERE user_id = '11111111-0000-0000-0000-000000000001' AND server_id = '55555555-0000-0000-0000-000000000005';
INSERT INTO public.user_presence (profile_id, status, online, published_status, published_at) VALUES
  ('22222222-0000-0000-0000-000000000002', 1, true, 1, now()),
  ('e9140000-0000-0000-0000-000000000001', 1, true, 1, now())
ON CONFLICT (profile_id) DO UPDATE SET status = 1, online = true, published_status = 1;
SELECT is((public.server_member_order_for(
             ROW('55555555-0000-0000-0000-000000000005', now(), 0, 3,
                 '[{"key":"online","count":2},{"key":"offline","count":1}]'::jsonb,
                 ARRAY['22222222-0000-0000-0000-000000000002', 'e9140000-0000-0000-0000-000000000001',
                       '11111111-0000-0000-0000-000000000001']::uuid[])::public.server_member_order,
             '11111111-0000-0000-0000-000000000001')).member_ids,
          ARRAY['22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000001',
                'e9140000-0000-0000-0000-000000000001']::uuid[],
          'a nicknamed viewer is placed by the name the order sorts by');

SELECT * FROM finish();
ROLLBACK;
