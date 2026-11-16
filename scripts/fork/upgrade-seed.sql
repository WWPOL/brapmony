-- Seed for scripts/fork/check-upgrade-path.sh: a little data in the fork-only tables, written
-- on the old fork schema (fork-pre-1.9.7) so the upgrade can be checked for data survival, not
-- just for schema equality. Fixed UUIDs let the check read the rows back by name afterwards.
--
-- Runs as supabase_admin with ON_ERROR_STOP. Needs only objects the old schema has.

INSERT INTO public.profiles (id, username, display_name, pronouns)
VALUES ('00000000-0000-4000-8000-0000000000a1', 'upgradeseed_owner', 'Upgrade Seed Owner', 'they/them'),
       ('00000000-0000-4000-8000-0000000000a2', 'upgradeseed_member', 'Upgrade Seed Member', 'she/her');

INSERT INTO public.servers (id, name)
VALUES ('00000000-0000-4000-8000-0000000000b1', 'Upgrade Seed Server');

-- A server profile: nickname plus the fork's per-server avatar, bio and pronouns.
INSERT INTO public.user_servers (user_id, server_id, status, nickname, avatar_url, bio, pronouns)
VALUES ('00000000-0000-4000-8000-0000000000a2', '00000000-0000-4000-8000-0000000000b1', 'accepted',
        'Seed Nick', 'https://example.invalid/seed-avatar.png', 'Seeded server bio', 'she/her')
ON CONFLICT (user_id, server_id) DO UPDATE
   SET nickname = EXCLUDED.nickname, avatar_url = EXCLUDED.avatar_url,
       bio = EXCLUDED.bio, pronouns = EXCLUDED.pronouns;

INSERT INTO public.server_settings (server_id, thread_sidebar)
VALUES ('00000000-0000-4000-8000-0000000000b1', 'joined')
ON CONFLICT (server_id) DO UPDATE SET thread_sidebar = EXCLUDED.thread_sidebar;

-- Migrate from Discord: a migration, one linked member, and the profile-sync columns on the
-- bridge row (an instance bridge, as the production instance has).
INSERT INTO public.discord_migrations (server_id, discord_guild_id, credential_source, state)
VALUES ('00000000-0000-4000-8000-0000000000b1', '111111111111111111', 'bridge', 'open');

INSERT INTO public.discord_migration_links (server_id, user_id, discord_user_id, sync_fields)
VALUES ('00000000-0000-4000-8000-0000000000b1', '00000000-0000-4000-8000-0000000000a2',
        '222222222222222222', ARRAY['server_nickname', 'server_avatar']);

INSERT INTO public.discord_bridges (id, server_id, mode, discord_guild_id, profile_icon_discord_hash, profile_icon_path)
VALUES ('00000000-0000-4000-8000-0000000000c1', '00000000-0000-4000-8000-0000000000b1', 'instance',
        '111111111111111111', 'abcdef0123456789abcdef0123456789', 'seed/icon.png');
