-- Migrate from Discord: a Discord server's members claim their Harmony accounts, and their Discord
-- roles, profiles and standing sync to Harmony while the migration is open.
--
-- This file holds the schema and the configuration RPCs. Claims, sync and readiness append to it.
--
-- discord_migrations      one row per server. state is draft, open or finished. link_token (48 hex)
--                         is the public part of the invitation link members open; regenerating it
--                         ends old links. credential_source picks where the Discord application and
--                         bot token come from: instance_bot (the instance's application), bridge
--                         (the server's hosted bridge bot, plus its own OAuth client) or own.
-- discord_migration_secrets  client id and Vault ids of the OAuth client secret and bot token for
--                         the own and bridge sources. service_role only. Deleting the row deletes
--                         the Vault secrets.
-- discord_migration_discord_roles  the gateway's snapshot of the guild's roles. harmony_permissions
--                         is the Discord role's permissions translated to Harmony bits (ADMINISTRATOR
--                         included). Managed roles and @everyone are never stored.
-- discord_migration_role_map  Discord role to Harmony role.
-- discord_migration_links member to Discord account. sync_fields names the profile fields the member
--                         lets the migration copy; discord_role_ids are the roles last seen.
-- discord_migration_claims, discord_migration_link_states  short-lived rows of the claim flow.
--                         service_role only.
--
-- Instance configuration
--   discord_migration_account_creation  boolean, default true, public: Discord members without a
--                                       Harmony account may create one while claiming.
--
-- Clients reach the tables only through the RPCs, which are gated by can_manage_discord_bridge
-- (owner or MANAGE_SERVER). Errors: 42501 caller lacks MANAGE_SERVER or authentication; P0002 server
-- or migration absent; 22023 invalid argument; 23514 check violation; 55000 guild_locked (the
-- guild of an open migration cannot change); 0A000 Vault absent; P0001 MIGRATION_CREDENTIALS_MISSING.
BEGIN;

SET LOCAL lock_timeout = '3s';

-- ---------------------------------------------------------------------------
-- Tables
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.discord_migrations (
    server_id uuid PRIMARY KEY REFERENCES public.servers(id) ON DELETE CASCADE,
    discord_guild_id text,
    credential_source text,
    state text NOT NULL DEFAULT 'draft',
    link_token text NOT NULL DEFAULT encode(extensions.gen_random_bytes(24), 'hex'),
    opened_at timestamptz,
    finished_at timestamptz,
    last_sync_at timestamptz,
    last_sync_result jsonb,
    sync_requested_at timestamptz,
    sync_cursor timestamptz,
    sync_paused_reason text,
    removal_brake_pct integer NOT NULL DEFAULT 10,
    bot_can_ban boolean,
    readiness_warnings jsonb,
    created_by uuid REFERENCES public.profiles(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT discord_migrations_guild_check
        CHECK (discord_guild_id IS NULL OR discord_guild_id ~ '^[0-9]{1,20}$'),
    CONSTRAINT discord_migrations_source_check
        CHECK (credential_source IS NULL OR credential_source IN ('instance_bot', 'bridge', 'own')),
    CONSTRAINT discord_migrations_state_check CHECK (state IN ('draft', 'open', 'finished')),
    CONSTRAINT discord_migrations_brake_check CHECK (removal_brake_pct BETWEEN 1 AND 100),
    CONSTRAINT discord_migrations_token_key UNIQUE (link_token)
);

CREATE TABLE IF NOT EXISTS public.discord_migration_secrets (
    server_id uuid PRIMARY KEY REFERENCES public.discord_migrations(server_id) ON DELETE CASCADE,
    client_id text,
    client_secret_secret uuid,
    bot_token_secret uuid,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT discord_migration_secrets_client_check
        CHECK (client_id IS NULL OR client_id ~ '^[0-9]{1,20}$')
);

CREATE TABLE IF NOT EXISTS public.discord_migration_discord_roles (
    server_id uuid NOT NULL REFERENCES public.discord_migrations(server_id) ON DELETE CASCADE,
    discord_role_id text NOT NULL,
    name text NOT NULL,
    color integer NOT NULL DEFAULT 0,
    position integer NOT NULL DEFAULT 0,
    harmony_permissions bigint NOT NULL DEFAULT 0,
    fetched_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (server_id, discord_role_id),
    CONSTRAINT discord_migration_discord_roles_id_check CHECK (discord_role_id ~ '^[0-9]{1,20}$'),
    CONSTRAINT discord_migration_discord_roles_name_check CHECK (char_length(name) <= 100)
);

CREATE TABLE IF NOT EXISTS public.discord_migration_role_map (
    server_id uuid NOT NULL REFERENCES public.discord_migrations(server_id) ON DELETE CASCADE,
    discord_role_id text NOT NULL,
    harmony_role_id uuid NOT NULL REFERENCES public.server_roles(id) ON DELETE CASCADE,
    PRIMARY KEY (server_id, discord_role_id),
    CONSTRAINT discord_migration_role_map_id_check CHECK (discord_role_id ~ '^[0-9]{1,20}$')
);

CREATE INDEX IF NOT EXISTS discord_migration_role_map_harmony_idx
    ON public.discord_migration_role_map (harmony_role_id);

CREATE TABLE IF NOT EXISTS public.discord_migration_links (
    server_id uuid NOT NULL REFERENCES public.discord_migrations(server_id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    discord_user_id text NOT NULL,
    status text NOT NULL DEFAULT 'active',
    sync_fields text[] NOT NULL DEFAULT '{}',
    discord_role_ids text[] NOT NULL DEFAULT '{}',
    discord_joined_at timestamptz,
    linked_at timestamptz NOT NULL DEFAULT now(),
    last_synced_at timestamptz,
    PRIMARY KEY (server_id, user_id),
    CONSTRAINT discord_migration_links_discord_user_key UNIQUE (server_id, discord_user_id),
    CONSTRAINT discord_migration_links_discord_user_check CHECK (discord_user_id ~ '^[0-9]{1,20}$'),
    CONSTRAINT discord_migration_links_status_check
        CHECK (status IN ('active', 'left', 'banned', 'kicked_on_harmony')),
    CONSTRAINT discord_migration_links_sync_fields_check
        CHECK (sync_fields <@ ARRAY['avatar', 'display_name', 'banner', 'accent_color',
                                    'server_nickname', 'server_avatar', 'server_banner']::text[])
);

CREATE TABLE IF NOT EXISTS public.discord_migration_claims (
    ticket text PRIMARY KEY,
    server_id uuid NOT NULL REFERENCES public.discord_migrations(server_id) ON DELETE CASCADE,
    discord_user_id text NOT NULL,
    discord_username text,
    discord_global_name text,
    discord_avatar_url text,
    discord_role_ids text[] NOT NULL DEFAULT '{}',
    discord_joined_at timestamptz,
    auth_user_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    CONSTRAINT discord_migration_claims_ticket_check CHECK (ticket ~ '^[0-9a-f]{64}$'),
    CONSTRAINT discord_migration_claims_discord_user_check CHECK (discord_user_id ~ '^[0-9]{1,20}$')
);

CREATE INDEX IF NOT EXISTS discord_migration_claims_server_idx ON public.discord_migration_claims (server_id);
CREATE INDEX IF NOT EXISTS discord_migration_claims_expires_idx ON public.discord_migration_claims (expires_at);

CREATE TABLE IF NOT EXISTS public.discord_migration_link_states (
    state text PRIMARY KEY,
    server_id uuid NOT NULL REFERENCES public.discord_migrations(server_id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    CONSTRAINT discord_migration_link_states_state_check CHECK (state ~ '^[0-9a-f]{64}$')
);

CREATE INDEX IF NOT EXISTS discord_migration_link_states_server_idx
    ON public.discord_migration_link_states (server_id);
CREATE INDEX IF NOT EXISTS discord_migration_link_states_expires_idx
    ON public.discord_migration_link_states (expires_at);

COMMENT ON TABLE public.discord_migrations IS
    'Per-server Migrate from Discord configuration and state; reached by clients through discord_migration_* RPCs only.';
COMMENT ON TABLE public.discord_migration_secrets IS
    'Client id and Vault secret ids of a migration''s own Discord application; service_role only.';

-- ---------------------------------------------------------------------------
-- Grants and RLS: service_role only. Clients use the RPCs.
-- ---------------------------------------------------------------------------
REVOKE ALL ON public.discord_migrations, public.discord_migration_secrets,
              public.discord_migration_discord_roles, public.discord_migration_role_map,
              public.discord_migration_links, public.discord_migration_claims,
              public.discord_migration_link_states
    FROM PUBLIC, anon, authenticated;
GRANT ALL ON public.discord_migrations, public.discord_migration_secrets,
             public.discord_migration_discord_roles, public.discord_migration_role_map,
             public.discord_migration_links, public.discord_migration_claims,
             public.discord_migration_link_states
    TO service_role;

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['discord_migrations', 'discord_migration_secrets',
                             'discord_migration_discord_roles', 'discord_migration_role_map',
                             'discord_migration_links', 'discord_migration_claims',
                             'discord_migration_link_states'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS %I ON public.%I', t || '_service_role', t);
        EXECUTE format('CREATE POLICY %I ON public.%I AS PERMISSIVE FOR ALL TO service_role USING (true) WITH CHECK (true)',
                       t || '_service_role', t);
    END LOOP;
END;
$$;

-- ---------------------------------------------------------------------------
-- Instance configuration
-- ---------------------------------------------------------------------------
INSERT INTO public.instance_config (config_key, config_value, description)
VALUES ('discord_migration_account_creation', 'true'::jsonb,
        'Discord members may create a Harmony account while claiming it in Migrate from Discord.')
ON CONFLICT (config_key) DO NOTHING;

DO $do$
DECLARE
    v_keys text[] := public.public_instance_config_keys();
BEGIN
    IF NOT ('discord_migration_account_creation' = ANY (v_keys)) THEN
        EXECUTE format(
            'CREATE OR REPLACE FUNCTION public.public_instance_config_keys() RETURNS text[] '
            'LANGUAGE sql IMMUTABLE SET search_path = public, pg_temp AS %L',
            format('SELECT %L::text[]', v_keys || 'discord_migration_account_creation'::text));
    END IF;
END;
$do$;

-- ---------------------------------------------------------------------------
-- Triggers
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.discord_migration_secrets_purge()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions, pg_temp
AS $$
BEGIN
    IF to_regclass('vault.secrets') IS NOT NULL THEN
        DELETE FROM vault.secrets
         WHERE id IN (OLD.bot_token_secret, OLD.client_secret_secret);
    END IF;
    RETURN NULL;
END;
$$;

DROP TRIGGER IF EXISTS discord_migration_secrets_purge ON public.discord_migration_secrets;
CREATE TRIGGER discord_migration_secrets_purge
    AFTER DELETE ON public.discord_migration_secrets
    FOR EACH ROW EXECUTE FUNCTION public.discord_migration_secrets_purge();

-- ---------------------------------------------------------------------------
-- Helpers (not callable by clients)
-- ---------------------------------------------------------------------------

-- The caller's profile id; raises 42501 unless the caller manages the Discord bridge of the local
-- server, P0002 when the server is absent or remote.
CREATE OR REPLACE FUNCTION public.discord_migration_assert_manager(p_server_id uuid)
RETURNS uuid
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public, extensions, pg_temp
AS $$
DECLARE
    v_me uuid := public.get_current_profile_id();
BEGIN
    IF v_me IS NULL THEN
        RAISE EXCEPTION 'Authentication required' USING ERRCODE = '42501';
    END IF;
    IF NOT public.can_manage_discord_bridge(p_server_id) THEN
        RAISE EXCEPTION 'Missing permission: MANAGE_SERVER' USING ERRCODE = '42501';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.servers s
                    WHERE s.id = p_server_id AND s.is_local_server IS NOT FALSE) THEN
        RAISE EXCEPTION 'Server not found' USING ERRCODE = 'P0002';
    END IF;
    RETURN v_me;
END;
$$;

-- ---------------------------------------------------------------------------
-- Client RPCs
-- ---------------------------------------------------------------------------

-- The configuration row without secrets, plus has_own_credentials, bridge_available (the server has
-- a hosted bridge), instance_bot_available, caller_is_owner, discord_roles (the snapshot), role_map
-- and link_counts by status. A server without a migration yields state null.
CREATE OR REPLACE FUNCTION public.discord_migration_get(p_server_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public, extensions, pg_temp
AS $$
DECLARE
    v_me uuid := public.discord_migration_assert_manager(p_server_id);
    v_row public.discord_migrations;
BEGIN
    SELECT * INTO v_row FROM public.discord_migrations WHERE server_id = p_server_id;

    RETURN COALESCE(to_jsonb(v_row), jsonb_build_object('server_id', p_server_id, 'state', NULL))
        || jsonb_build_object(
            'has_own_credentials', EXISTS (SELECT 1 FROM public.discord_migration_secrets s
                                            WHERE s.server_id = p_server_id
                                              AND s.client_id IS NOT NULL
                                              AND s.client_secret_secret IS NOT NULL
                                              AND s.bot_token_secret IS NOT NULL),
            'bridge_available', EXISTS (SELECT 1 FROM public.discord_bridges b
                                         WHERE b.server_id = p_server_id AND b.mode = 'hosted'),
            'instance_bot_available', public.discord_bridge_instance_bot_enabled()
                                      AND public.discord_bridge_instance_bot_configured(),
            'caller_is_owner', EXISTS (SELECT 1 FROM public.servers s
                                        WHERE s.id = p_server_id AND s.owner = v_me),
            'discord_roles', COALESCE((
                SELECT jsonb_agg(jsonb_build_object(
                           'discord_role_id', r.discord_role_id, 'name', r.name, 'color', r.color,
                           'position', r.position, 'harmony_permissions', r.harmony_permissions::text,
                           'fetched_at', r.fetched_at)
                           ORDER BY r.position DESC, r.discord_role_id)
                  FROM public.discord_migration_discord_roles r
                 WHERE r.server_id = p_server_id), '[]'::jsonb),
            'role_map', COALESCE((
                SELECT jsonb_agg(jsonb_build_object(
                           'discord_role_id', m.discord_role_id, 'harmony_role_id', m.harmony_role_id)
                           ORDER BY m.discord_role_id)
                  FROM public.discord_migration_role_map m
                 WHERE m.server_id = p_server_id), '[]'::jsonb),
            'link_counts', (
                SELECT jsonb_build_object(
                           'active', count(*) FILTER (WHERE l.status = 'active'),
                           'left', count(*) FILTER (WHERE l.status = 'left'),
                           'banned', count(*) FILTER (WHERE l.status = 'banned'),
                           'kicked_on_harmony', count(*) FILTER (WHERE l.status = 'kicked_on_harmony'))
                  FROM public.discord_migration_links l
                 WHERE l.server_id = p_server_id));
END;
$$;

-- Upsert. A null guild id keeps the stored one, else takes the server's bridge's. A null source keeps
-- the stored one. The guild of an open migration cannot change (55000 guild_locked); a draft that
-- moves to another guild drops the role snapshot, the map and the readiness warnings. Returns
-- discord_migration_get().
CREATE OR REPLACE FUNCTION public.discord_migration_save(
    p_server_id uuid,
    p_discord_guild_id text,
    p_credential_source text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions, pg_temp
AS $$
DECLARE
    v_me uuid := public.discord_migration_assert_manager(p_server_id);
    v_cur public.discord_migrations;
    v_guild text;
    v_source text;
BEGIN
    SELECT * INTO v_cur FROM public.discord_migrations WHERE server_id = p_server_id FOR UPDATE;

    v_guild := COALESCE(NULLIF(btrim(p_discord_guild_id), ''),
                        v_cur.discord_guild_id,
                        (SELECT b.discord_guild_id FROM public.discord_bridges b WHERE b.server_id = p_server_id));
    v_source := COALESCE(NULLIF(btrim(p_credential_source), ''), v_cur.credential_source);

    IF v_cur.server_id IS NOT NULL THEN
        IF v_cur.state = 'open' AND v_guild IS DISTINCT FROM v_cur.discord_guild_id THEN
            RAISE EXCEPTION 'guild_locked' USING ERRCODE = '55000',
                DETAIL = 'The Discord server of an open migration cannot change.';
        END IF;
        IF v_guild IS DISTINCT FROM v_cur.discord_guild_id THEN
            DELETE FROM public.discord_migration_discord_roles WHERE server_id = p_server_id;
            DELETE FROM public.discord_migration_role_map WHERE server_id = p_server_id;
        END IF;
        UPDATE public.discord_migrations
           SET discord_guild_id = v_guild,
               credential_source = v_source,
               readiness_warnings = CASE WHEN v_guild IS DISTINCT FROM v_cur.discord_guild_id
                                         THEN NULL ELSE readiness_warnings END,
               bot_can_ban = CASE WHEN v_guild IS DISTINCT FROM v_cur.discord_guild_id
                                  THEN NULL ELSE bot_can_ban END,
               updated_at = now()
         WHERE server_id = p_server_id;
    ELSE
        INSERT INTO public.discord_migrations (server_id, discord_guild_id, credential_source, created_by)
        VALUES (p_server_id, v_guild, v_source, v_me);
    END IF;

    RETURN public.discord_migration_get(p_server_id);
END;
$$;

-- Stores the server's own Discord application: client id, OAuth client secret and bot token. NULL or
-- '' keeps the stored value; a new secret replaces the stored Vault secret. The migration must be saved
-- first (P0002).
CREATE OR REPLACE FUNCTION public.discord_migration_set_own_credentials(
    p_server_id uuid,
    p_client_id text,
    p_client_secret text,
    p_bot_token text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions, pg_temp
AS $$
DECLARE
    v_client_id text := NULLIF(btrim(COALESCE(p_client_id, '')), '');
    v_client_secret text := NULLIF(btrim(COALESCE(p_client_secret, '')), '');
    v_token text := NULLIF(regexp_replace(btrim(COALESCE(p_bot_token, '')), '^Bot\s+', '', 'i'), '');
    v_old public.discord_migration_secrets;
    v_client_secret_id uuid;
    v_token_id uuid;
BEGIN
    PERFORM public.discord_migration_assert_manager(p_server_id);
    IF v_client_id IS NOT NULL AND v_client_id !~ '^[0-9]{1,20}$' THEN
        RAISE EXCEPTION 'Not a Discord application id' USING ERRCODE = '22023';
    END IF;
    IF v_client_secret IS NOT NULL AND v_client_secret !~ '^[A-Za-z0-9_-]{16,128}$' THEN
        RAISE EXCEPTION 'Not a Discord client secret' USING ERRCODE = '22023';
    END IF;
    IF v_token IS NOT NULL
       AND (char_length(v_token) > 256
            OR v_token !~ '^[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{10,}$') THEN
        RAISE EXCEPTION 'Not a Discord bot token' USING ERRCODE = '22023';
    END IF;
    PERFORM public.discord_bridge_assert_vault();

    PERFORM 1 FROM public.discord_migrations WHERE server_id = p_server_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'migration_not_configured' USING ERRCODE = 'P0002';
    END IF;
    SELECT * INTO v_old FROM public.discord_migration_secrets WHERE server_id = p_server_id FOR UPDATE;

    IF v_client_secret IS NOT NULL THEN
        v_client_secret_id := vault.create_secret(v_client_secret, NULL,
            'Discord migration ' || p_server_id || ': OAuth2 client secret');
    END IF;
    IF v_token IS NOT NULL THEN
        v_token_id := vault.create_secret(v_token, NULL,
            'Discord migration ' || p_server_id || ': bot token');
    END IF;

    INSERT INTO public.discord_migration_secrets
        (server_id, client_id, client_secret_secret, bot_token_secret, updated_at)
    VALUES (p_server_id, v_client_id, v_client_secret_id, v_token_id, now())
    ON CONFLICT (server_id) DO UPDATE
       SET client_id = COALESCE(EXCLUDED.client_id, discord_migration_secrets.client_id),
           client_secret_secret = COALESCE(EXCLUDED.client_secret_secret,
                                           discord_migration_secrets.client_secret_secret),
           bot_token_secret = COALESCE(EXCLUDED.bot_token_secret, discord_migration_secrets.bot_token_secret),
           updated_at = now();

    DELETE FROM vault.secrets
     WHERE id IN (CASE WHEN v_client_secret_id IS NOT NULL THEN v_old.client_secret_secret END,
                  CASE WHEN v_token_id IS NOT NULL THEN v_old.bot_token_secret END);

    UPDATE public.discord_migrations SET updated_at = now() WHERE server_id = p_server_id;
END;
$$;

-- A new 48 hex link token; links made with the old one stop working.
CREATE OR REPLACE FUNCTION public.discord_migration_regenerate_token(p_server_id uuid)
RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions, pg_temp
AS $$
DECLARE
    v_token text;
BEGIN
    PERFORM public.discord_migration_assert_manager(p_server_id);
    UPDATE public.discord_migrations
       SET link_token = encode(extensions.gen_random_bytes(24), 'hex'), updated_at = now()
     WHERE server_id = p_server_id
    RETURNING link_token INTO v_token;
    IF v_token IS NULL THEN
        RAISE EXCEPTION 'migration_not_configured' USING ERRCODE = 'P0002';
    END IF;
    RETURN v_token;
END;
$$;

-- ---------------------------------------------------------------------------
-- Gateway functions (service_role)
-- ---------------------------------------------------------------------------

-- {client_id, client_secret, bot_token} by credential source. instance_bot reads the instance bot;
-- bridge takes the bot token from the server's hosted bridge and the client id and secret from the
-- migration's own credentials; own reads the migration's own credentials. Raises
-- MIGRATION_CREDENTIALS_MISSING when any part is missing.
CREATE OR REPLACE FUNCTION public.discord_migration_credentials(p_server_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public, extensions, pg_temp
AS $$
DECLARE
    v_source text;
    v_result jsonb;
BEGIN
    SELECT m.credential_source INTO v_source FROM public.discord_migrations m WHERE m.server_id = p_server_id;
    IF v_source IS NOT NULL THEN
        PERFORM public.discord_bridge_assert_vault();
    END IF;

    IF v_source = 'instance_bot' THEN
        SELECT jsonb_build_object('client_id', ib.application_id,
                                  'client_secret', c.decrypted_secret,
                                  'bot_token', t.decrypted_secret)
          INTO v_result
          FROM public.discord_bridge_instance_bot ib
          JOIN vault.decrypted_secrets c ON c.id = ib.client_secret_secret
          JOIN vault.decrypted_secrets t ON t.id = ib.bot_token_secret
         WHERE ib.id AND ib.application_id IS NOT NULL;
    ELSIF v_source = 'bridge' THEN
        SELECT jsonb_build_object('client_id', s.client_id,
                                  'client_secret', c.decrypted_secret,
                                  'bot_token', t.decrypted_secret)
          INTO v_result
          FROM public.discord_migration_secrets s
          JOIN vault.decrypted_secrets c ON c.id = s.client_secret_secret
          JOIN public.discord_bridges b ON b.server_id = s.server_id AND b.mode = 'hosted'
          JOIN public.discord_bridge_secrets bs ON bs.bridge_id = b.id
          JOIN vault.decrypted_secrets t ON t.id = bs.discord_token_secret
         WHERE s.server_id = p_server_id AND s.client_id IS NOT NULL;
    ELSIF v_source = 'own' THEN
        SELECT jsonb_build_object('client_id', s.client_id,
                                  'client_secret', c.decrypted_secret,
                                  'bot_token', t.decrypted_secret)
          INTO v_result
          FROM public.discord_migration_secrets s
          JOIN vault.decrypted_secrets c ON c.id = s.client_secret_secret
          JOIN vault.decrypted_secrets t ON t.id = s.bot_token_secret
         WHERE s.server_id = p_server_id AND s.client_id IS NOT NULL;
    END IF;

    IF v_result IS NULL THEN
        RAISE EXCEPTION 'MIGRATION_CREDENTIALS_MISSING';
    END IF;
    RETURN v_result;
END;
$$;

-- Replaces the guild's role snapshot with p_roles, [{id, name, color, position, harmony_permissions}]
-- (harmony_permissions a decimal string), and deletes map rows whose Discord role is gone. The
-- @everyone role (id = the guild id) is skipped. P0002 when the migration is absent.
CREATE OR REPLACE FUNCTION public.discord_migration_store_discord_roles(p_server_id uuid, p_roles jsonb)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions, pg_temp
AS $$
DECLARE
    v_guild text;
    v_ids text[];
BEGIN
    IF p_roles IS NULL OR jsonb_typeof(p_roles) <> 'array' THEN
        RAISE EXCEPTION 'roles must be an array' USING ERRCODE = '22023';
    END IF;
    SELECT m.discord_guild_id INTO v_guild FROM public.discord_migrations m
     WHERE m.server_id = p_server_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'migration_not_configured' USING ERRCODE = 'P0002';
    END IF;

    -- One row per valid role id; @everyone (the guild id) is never stored.
    SELECT COALESCE(array_agg(DISTINCT r ->> 'id'), '{}') INTO v_ids
      FROM jsonb_array_elements(p_roles) r
     WHERE jsonb_typeof(r) = 'object'
       AND r ->> 'id' ~ '^[0-9]{1,20}$'
       AND r ->> 'id' IS DISTINCT FROM v_guild;

    DELETE FROM public.discord_migration_discord_roles r
     WHERE r.server_id = p_server_id AND NOT (r.discord_role_id = ANY (v_ids));
    DELETE FROM public.discord_migration_role_map m
     WHERE m.server_id = p_server_id AND NOT (m.discord_role_id = ANY (v_ids));

    INSERT INTO public.discord_migration_discord_roles
        (server_id, discord_role_id, name, color, position, harmony_permissions, fetched_at)
    SELECT DISTINCT ON (r ->> 'id')
           p_server_id,
           r ->> 'id',
           left(COALESCE(NULLIF(btrim(r ->> 'name'), ''), r ->> 'id'), 100),
           COALESCE((r ->> 'color')::integer, 0),
           COALESCE((r ->> 'position')::integer, 0),
           COALESCE((r ->> 'harmony_permissions')::bigint, 0),
           now()
      FROM jsonb_array_elements(p_roles) r
     WHERE jsonb_typeof(r) = 'object'
       AND r ->> 'id' = ANY (v_ids)
     ORDER BY r ->> 'id'
    ON CONFLICT (server_id, discord_role_id) DO UPDATE
       SET name = EXCLUDED.name, color = EXCLUDED.color, position = EXCLUDED.position,
           harmony_permissions = EXCLUDED.harmony_permissions, fetched_at = EXCLUDED.fetched_at;
END;
$$;

-- Stores the readiness warnings the gateway found (any jsonb; null clears them).
CREATE OR REPLACE FUNCTION public.discord_migration_store_warnings(p_server_id uuid, p_warnings jsonb)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions, pg_temp
AS $$
BEGIN
    UPDATE public.discord_migrations
       SET readiness_warnings = p_warnings, updated_at = now()
     WHERE server_id = p_server_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'migration_not_configured' USING ERRCODE = 'P0002';
    END IF;
END;
$$;

-- ---------------------------------------------------------------------------
-- Function grants
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    fn regprocedure;
BEGIN
    FOREACH fn IN ARRAY ARRAY[
        'public.discord_migration_get(uuid)',
        'public.discord_migration_save(uuid, text, text)',
        'public.discord_migration_set_own_credentials(uuid, text, text, text)',
        'public.discord_migration_regenerate_token(uuid)'
    ]::regprocedure[] LOOP
        EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC, anon, authenticated, service_role', fn);
        EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO authenticated', fn);
    END LOOP;

    FOREACH fn IN ARRAY ARRAY[
        'public.discord_migration_credentials(uuid)',
        'public.discord_migration_store_discord_roles(uuid, jsonb)',
        'public.discord_migration_store_warnings(uuid, jsonb)'
    ]::regprocedure[] LOOP
        EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC, anon, authenticated', fn);
        EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO service_role', fn);
    END LOOP;

    -- Reached only from the definers above and from triggers, which run as the owner.
    FOREACH fn IN ARRAY ARRAY[
        'public.discord_migration_assert_manager(uuid)',
        'public.discord_migration_secrets_purge()'
    ]::regprocedure[] LOOP
        EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC, anon, authenticated, service_role', fn);
    END LOOP;
END;
$$;

COMMIT;

NOTIFY pgrst, 'reload schema';
