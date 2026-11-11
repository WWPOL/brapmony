-- Which threads the channel sidebar lists: every active thread ('all') or only the threads the
-- viewer has joined ('joined'). A server picks a mode; a server with none follows the instance
-- default.
--
--   server_settings.thread_sidebar       'all' | 'joined' | NULL. NULL follows instance_config
--                                        'thread_sidebar_default'; an absent or unrecognised
--                                        default reads as 'all'.
--   server_thread_sidebar_state(uuid)    {mode, server_value, instance_default}. mode is the
--                                        effective mode. Invoker, service_role only.
--   get_server_thread_sidebar(uuid)      the state, for a local server's MANAGE_SERVER holders.
--   set_server_thread_sidebar(uuid,text) stores 'all', 'joined' or NULL (back to the instance
--                                        default); any other value raises 22023. Returns the
--                                        state. Same guard as get.
--   server_thread_sidebar_mode(uuid)     the effective mode, for any member of the server
--                                        (42501 otherwise). Definer.
--
-- Converges on rerun: every statement is idempotent.

BEGIN;

SET LOCAL lock_timeout = '5s';

ALTER TABLE public.server_settings
    ADD COLUMN IF NOT EXISTS thread_sidebar text;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conrelid = 'public.server_settings'::regclass
                      AND conname = 'server_settings_thread_sidebar_check') THEN
        ALTER TABLE public.server_settings
            ADD CONSTRAINT server_settings_thread_sidebar_check
            CHECK (thread_sidebar IN ('all', 'joined'));
    END IF;
END;
$$;

COMMENT ON COLUMN public.server_settings.thread_sidebar IS
    'Threads the channel sidebar lists: all or joined. NULL follows instance_config thread_sidebar_default.';

-- mode is the effective mode; server_value is NULL when the server follows the instance.
CREATE OR REPLACE FUNCTION public.server_thread_sidebar_state(p_server_id uuid)
RETURNS jsonb
LANGUAGE sql
STABLE
SET search_path = public, pg_temp
AS $$
    SELECT jsonb_build_object(
        'mode', COALESCE(ss.thread_sidebar, d.instance_default),
        'server_value', ss.thread_sidebar,
        'instance_default', d.instance_default)
      FROM (SELECT COALESCE((SELECT CASE WHEN ic.config_value #>> '{}' = 'joined' THEN 'joined' ELSE 'all' END
                               FROM public.instance_config ic
                              WHERE ic.config_key = 'thread_sidebar_default'), 'all')
                   AS instance_default) d
      LEFT JOIN public.server_settings ss ON ss.server_id = p_server_id;
$$;

REVOKE ALL ON FUNCTION public.server_thread_sidebar_state(uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.server_thread_sidebar_state(uuid) TO service_role;

CREATE OR REPLACE FUNCTION public.get_server_thread_sidebar(p_server_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_caller uuid := public.get_current_profile_id();
BEGIN
    IF v_caller IS NULL THEN
        RAISE EXCEPTION 'Not authenticated' USING ERRCODE = '42501';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.servers s
                    WHERE s.id = p_server_id AND s.is_local_server IS NOT FALSE)
       OR NOT public.has_permission(v_caller, p_server_id, 'MANAGE_SERVER') THEN
        RAISE EXCEPTION 'Missing permission: MANAGE_SERVER' USING ERRCODE = '42501';
    END IF;
    RETURN public.server_thread_sidebar_state(p_server_id);
END;
$$;

REVOKE ALL ON FUNCTION public.get_server_thread_sidebar(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_server_thread_sidebar(uuid) TO authenticated, service_role;

-- p_mode NULL returns the server to the instance default.
CREATE OR REPLACE FUNCTION public.set_server_thread_sidebar(p_server_id uuid, p_mode text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_caller uuid := public.get_current_profile_id();
BEGIN
    IF v_caller IS NULL THEN
        RAISE EXCEPTION 'Not authenticated' USING ERRCODE = '42501';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.servers s
                    WHERE s.id = p_server_id AND s.is_local_server IS NOT FALSE)
       OR NOT public.has_permission(v_caller, p_server_id, 'MANAGE_SERVER') THEN
        RAISE EXCEPTION 'Missing permission: MANAGE_SERVER' USING ERRCODE = '42501';
    END IF;
    IF p_mode IS NOT NULL AND p_mode NOT IN ('all', 'joined') THEN
        RAISE EXCEPTION 'Thread sidebar mode must be all or joined' USING ERRCODE = '22023';
    END IF;

    INSERT INTO public.server_settings (server_id, thread_sidebar)
    VALUES (p_server_id, p_mode)
    ON CONFLICT (server_id) DO UPDATE
    SET thread_sidebar = EXCLUDED.thread_sidebar,
        updated_at = now();

    RETURN public.server_thread_sidebar_state(p_server_id);
END;
$$;

REVOKE ALL ON FUNCTION public.set_server_thread_sidebar(uuid, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.set_server_thread_sidebar(uuid, text) TO authenticated, service_role;

-- The effective mode for a member's own client; the setting itself is for managers.
CREATE OR REPLACE FUNCTION public.server_thread_sidebar_mode(p_server_id uuid)
RETURNS text
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    IF NOT public.current_user_is_member_of_server(p_server_id) THEN
        RAISE EXCEPTION 'Not a member of this server' USING ERRCODE = '42501';
    END IF;
    RETURN public.server_thread_sidebar_state(p_server_id)->>'mode';
END;
$$;

REVOKE ALL ON FUNCTION public.server_thread_sidebar_mode(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.server_thread_sidebar_mode(uuid) TO authenticated, service_role;

COMMIT;

NOTIFY pgrst, 'reload schema';
