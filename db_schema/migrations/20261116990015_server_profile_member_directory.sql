-- The member directory carries the server profile fields a list shows.
--
-- server_member_presence: a member's list name is the server nickname when set (else display name,
-- else username), and a search for a member also matches the nickname. Order, group counts and
-- @mention search all read this function, so they follow the name the list shows.
-- server_member_json: a member also carries pronouns (account), nickname and server_avatar_url
-- (this server's). Banner, bio and pronouns of a server load when a profile card opens.
--
-- Each body is the latest upstream one (server_member_presence 20261115500001, server_member_json
-- 20261113100001) with these additions only. Reads one more column of a row the function already
-- reads (user_servers) and, for the JSON, the member's own row by primary key: no new scan.

BEGIN;

SET LOCAL lock_timeout = '5s';

CREATE OR REPLACE FUNCTION public.server_member_json(
    p_profile public.profiles,
    p_server_id uuid,
    p_group text,
    p_online boolean,
    p_status smallint,
    p_mobile boolean)
RETURNS jsonb
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT jsonb_strip_nulls(jsonb_build_object(
        'id', p_profile.id,
        'group', p_group,
        'username', p_profile.username,
        'web_handle', p_profile.web_handle,
        'display_name', p_profile.display_name,
        'avatar_url', p_profile.avatar_url,
        'banner_url', p_profile.banner_url,
        'bio', p_profile.bio,
        'pronouns', p_profile.pronouns,
        'nickname', NULLIF(btrim(sp.nickname), ''),
        'server_avatar_url', NULLIF(btrim(sp.avatar_url), ''),
        'color', p_profile.color,
        'status', p_profile.status,
        'domain', p_profile.domain,
        'updated_at', p_profile.updated_at,
        'created_at', p_profile.created_at,
        'is_local', p_profile.is_local,
        'custom_status', p_profile.custom_status,
        'is_admin', p_profile.is_admin,
        'is_moderator', p_profile.is_moderator,
        'instance_role', p_profile.instance_role,
        'online', p_online,
        'presence_status', p_status,
        'is_mobile', p_mobile,
        'role_ids', COALESCE((SELECT jsonb_agg(ur.role_id ORDER BY r.position DESC, r.id)
                                FROM public.user_roles ur
                                JOIN public.server_roles r ON r.id = ur.role_id
                               WHERE ur.user_id = p_profile.id
                                 AND ur.server_id = p_server_id
                                 AND r.is_default IS NOT TRUE), '[]'::jsonb)))
      FROM (SELECT 1) one
      LEFT JOIN public.user_servers sp ON sp.server_id = p_server_id AND sp.user_id = p_profile.id;
$$;

CREATE OR REPLACE FUNCTION public.server_member_presence(
    p_server_id uuid,
    p_viewer uuid,
    p_like text,
    p_only uuid DEFAULT NULL)
RETURNS TABLE (id uuid, username text, sort_name text, is_local boolean, present boolean, pstatus smallint, mobile boolean)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT m.id, m.username, m.sort_name, m.is_local, m.present,
           CASE WHEN m.present THEN m.shown_status ELSE 0::smallint END,
           m.present AND m.shown_mobile
      FROM (SELECT p.id,
                   COALESCE(p.username, '') AS username,
                   lower(COALESCE(NULLIF(btrim(us.nickname), ''), NULLIF(btrim(p.display_name), ''), p.username, '')) AS sort_name,
                   COALESCE(p.is_local, true) AS is_local,
                   CASE WHEN p.id = p_viewer THEN COALESCE(up.status, 1) <> 4
                        ELSE COALESCE(up.published_status, 0) <> 0
                   END AS present,
                   CASE WHEN p.id = p_viewer THEN COALESCE(up.status, 1::smallint)
                        ELSE COALESCE(up.published_status, 0::smallint) END AS shown_status,
                   CASE WHEN p.id = p_viewer THEN COALESCE(up.is_mobile, false)
                        ELSE COALESCE(up.published_mobile, false) END AS shown_mobile
              FROM public.user_servers us
              JOIN public.profiles p ON p.id = us.user_id
              LEFT JOIN public.user_presence up ON up.profile_id = us.user_id
             WHERE us.server_id = p_server_id
               AND (p_only IS NULL OR us.user_id = p_only)
               AND COALESCE(us.status, 'accepted') = 'accepted'
               AND (p_like IS NULL OR p.username ILIKE p_like OR p.display_name ILIKE p_like
                    OR us.nickname ILIKE p_like)) m;
$$;

COMMIT;
