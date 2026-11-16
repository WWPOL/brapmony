-- The member directory carries the server profile fields a list shows.
--
-- server_member_presence: a member's list name is the server nickname when set (else display name,
-- else username), and a search for a member also matches the nickname. Order, group counts and
-- @mention search all read this function, so they follow the name the list shows.
-- server_member_json: a member also carries pronouns (account), nickname and server_avatar_url
-- (this server's). Banner, bio and pronouns of a server load when a profile card opens.
--
-- server_member_order_for: the viewer is placed by the same nickname-first name the stored order
-- is built from (it compared the viewer's key with display names only).
--
-- Each body is the latest upstream one (server_member_presence 20261115500001, server_member_json
-- and server_member_order_for 20261113100001) with these additions only. Reads one more column of a row the function already
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

-- A stored order with p_viewer where the viewer sees itself. The order places every member by
-- published presence and may be seconds old; the viewer's own place follows the status it
-- chose, now. The viewer is taken out of its stored place and put into its group at its place
-- by name, found by halving: a profile read per step. Names are the ones the list shows, the
-- server nickname first (server_member_presence), read by the user_servers key.
CREATE OR REPLACE FUNCTION public.server_member_order_for(p_order public.server_member_order, p_viewer uuid)
RETURNS public.server_member_order
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_at      integer := array_position(p_order.member_ids, p_viewer);
    v_self    record;
    v_stored  text;
    v_ids     uuid[];
    v_groups  jsonb := '[]'::jsonb;
    v_group   jsonb;
    v_rank    record;
    v_before  integer := 0;
    v_lo      integer;
    v_hi      integer;
    v_mid     integer;
    v_other   record;
    v_placed  boolean := false;
    v_out     public.server_member_order := p_order;
BEGIN
    IF v_at IS NULL THEN
        RETURN p_order;
    END IF;
    SELECT k.* INTO v_self
      FROM public.server_member_keyed(p_order.server_id, p_viewer, NULL, p_viewer) k;
    IF NOT FOUND THEN
        RETURN p_order;
    END IF;
    SELECT g.key INTO v_stored
      FROM (SELECT e ->> 'key' AS key, n, sum((e ->> 'count')::integer) OVER (ORDER BY n) AS upto
              FROM jsonb_array_elements(p_order.groups) WITH ORDINALITY AS t(e, n)) g
     WHERE g.upto >= v_at
     ORDER BY g.n
     LIMIT 1;
    IF v_stored = v_self.grp THEN
        RETURN p_order;
    END IF;

    v_ids := p_order.member_ids[1 : v_at - 1] || p_order.member_ids[v_at + 1 :];

    -- The groups again: the stored group one short, the viewer's group one longer, added in
    -- its place when no other member is in it.
    FOR v_group IN SELECT e FROM jsonb_array_elements(p_order.groups) WITH ORDINALITY AS t(e, n) ORDER BY n LOOP
        IF NOT v_placed AND v_group ->> 'key' <> v_self.grp THEN
            SELECT * INTO v_rank FROM public.member_group_rank(v_group ->> 'key');
            IF (v_rank.grp_rank, -v_rank.role_pos, v_group ->> 'key') > (v_self.grp_rank, -v_self.role_pos, v_self.grp) THEN
                v_groups := v_groups || jsonb_build_object('key', v_self.grp, 'count', 1);
                v_lo := v_before + 1;
                v_placed := true;
            END IF;
        END IF;
        IF v_group ->> 'key' = v_stored THEN
            IF (v_group ->> 'count')::integer > 1 THEN
                v_groups := v_groups || jsonb_build_object('key', v_stored, 'count', (v_group ->> 'count')::integer - 1);
                v_before := v_before + (v_group ->> 'count')::integer - 1;
            END IF;
        ELSIF v_group ->> 'key' = v_self.grp THEN
            -- Its members are v_ids[v_before + 1 .. v_before + count]: the viewer's place among them.
            v_lo := v_before + 1;
            v_hi := v_before + (v_group ->> 'count')::integer + 1;
            WHILE v_lo < v_hi LOOP
                v_mid := (v_lo + v_hi) / 2;
                SELECT lower(COALESCE(NULLIF(btrim(us.nickname), ''), NULLIF(btrim(p.display_name), ''), p.username, '')) AS sort_name,
                       COALESCE(p.username, '') AS username
                  INTO v_other
                  FROM public.profiles p
                  LEFT JOIN public.user_servers us ON us.server_id = p_order.server_id AND us.user_id = p.id
                 WHERE p.id = v_ids[v_mid];
                IF FOUND AND (v_other.sort_name COLLATE public.member_name < v_self.sort_name COLLATE public.member_name
                              OR (v_other.sort_name = v_self.sort_name
                                  AND (v_other.username < v_self.username
                                       OR (v_other.username = v_self.username AND v_ids[v_mid] < p_viewer)))) THEN
                    v_lo := v_mid + 1;
                ELSE
                    v_hi := v_mid;
                END IF;
            END LOOP;
            v_groups := v_groups || jsonb_build_object('key', v_self.grp, 'count', (v_group ->> 'count')::integer + 1);
            v_before := v_before + (v_group ->> 'count')::integer;
            v_placed := true;
        ELSE
            v_groups := v_groups || v_group;
            v_before := v_before + (v_group ->> 'count')::integer;
        END IF;
    END LOOP;
    IF NOT v_placed THEN
        v_groups := v_groups || jsonb_build_object('key', v_self.grp, 'count', 1);
        v_lo := v_before + 1;
    END IF;

    v_out.member_ids := v_ids[1 : v_lo - 1] || p_viewer || v_ids[v_lo :];
    v_out.groups := v_groups;
    RETURN v_out;
END;
$$;

COMMIT;
