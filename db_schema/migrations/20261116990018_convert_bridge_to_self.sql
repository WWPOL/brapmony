-- Fork-only: converts an instance-mode Discord bridge to a self-run bridge in place.
--
-- Upstream's only switch from instance to self is to disconnect and set the bridge up again. That
-- provisions a new bridge bot, and bridge_message_by_discord_id answers only the bot that owns a
-- message, so every message and reaction the old bot bridged or imported would lose its Discord
-- link. This keeps the row, bot_id, guild, settings and channel pairs; only the mode changes.
--
-- fork_convert_bridge_to_self(p_bridge_id) is for instance admins (is_current_user_admin()).
--   - refuses a bridge that is not in mode instance (22023), and one without a bot (22023): a setup
--     code would provision a new bot for it, which is the thing this avoids
--   - refuses while the instance bot offer is on and configured and the bridge's Discord
--     application is that application, or not yet known (55000). The instance host leaves every
--     Discord server that no instance bridge links, 10 minutes after it stops being linked, and
--     would take the bot out of this server. Turn the offer off first.
--   - drops discord_bridge_secrets (the Vault copy of the bridge bot's Harmony token, kept for the
--     instance host) and the instance-only OAuth link states
--   - sets mode to self; discord_bridges_touch moves updated_at, so the gateway sends
--     BRIDGE_CONFIG_UPDATE
--   - issues a setup code as discord_bridge_setup_code does (same table, hash, HB-XXXX-XXXX-XXXX
--     form, 30 minutes) and returns it. That function checks MANAGE_SERVER on the server; an
--     instance admin need not hold it, so the code is built here. discord_bridge_redeem_code
--     redeems it and returns a token for the same bot_id; it revokes the old token.
--   - writes an admin audit row: config_change on config discord_bridge_mode
--
-- Not reverted by the function: the bridge's Discord application keeps its guild membership.

BEGIN;

SET LOCAL lock_timeout = '5s';

CREATE OR REPLACE FUNCTION public.fork_convert_bridge_to_self(p_bridge_id uuid)
RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions, pg_temp
AS $$
DECLARE
    c_alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    v_me uuid := public.get_current_profile_id();
    v_bridge public.discord_bridges;
    v_instance_app text;
    v_bytes bytea := extensions.gen_random_bytes(12);
    v_chars text := '';
    v_code text;
    i integer;
BEGIN
    IF v_me IS NULL OR NOT public.is_current_user_admin() THEN
        RAISE EXCEPTION 'Unauthorized: Admin role required' USING ERRCODE = '42501';
    END IF;

    SELECT * INTO v_bridge FROM public.discord_bridges WHERE id = p_bridge_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Discord bridge not found' USING ERRCODE = 'P0002';
    END IF;
    IF v_bridge.mode <> 'instance' THEN
        RAISE EXCEPTION 'Only an instance bridge can be converted (this one is %)', v_bridge.mode
            USING ERRCODE = '22023';
    END IF;
    IF v_bridge.bot_id IS NULL THEN
        RAISE EXCEPTION 'This bridge has no bot, so there is no history to keep; disconnect it and set it up again'
            USING ERRCODE = '22023';
    END IF;

    IF public.discord_bridge_instance_bot_enabled() AND public.discord_bridge_instance_bot_configured() THEN
        SELECT ib.application_id INTO v_instance_app FROM public.discord_bridge_instance_bot ib WHERE ib.id;
        IF v_bridge.discord_application_id IS NULL OR v_bridge.discord_application_id = v_instance_app THEN
            RAISE EXCEPTION 'Turn the instance bot off first: its host would leave this Discord server'
                USING ERRCODE = '55000';
        END IF;
    END IF;

    DELETE FROM public.discord_bridge_secrets WHERE bridge_id = v_bridge.id;
    DELETE FROM public.discord_bridge_link_states WHERE bridge_id = v_bridge.id;
    UPDATE public.discord_bridges SET mode = 'self' WHERE id = v_bridge.id;

    -- 256 is a multiple of 32: each byte maps uniformly onto the alphabet.
    FOR i IN 0 .. 11 LOOP
        v_chars := v_chars || substr(c_alphabet, (get_byte(v_bytes, i) % 32) + 1, 1);
    END LOOP;
    v_code := 'HB-' || substr(v_chars, 1, 4) || '-' || substr(v_chars, 5, 4) || '-' || substr(v_chars, 9, 4);
    DELETE FROM public.discord_bridge_setup_codes WHERE bridge_id = v_bridge.id;
    INSERT INTO public.discord_bridge_setup_codes (code_hash, bridge_id, expires_at)
    VALUES (encode(sha256(convert_to(v_code, 'UTF8')), 'hex'), v_bridge.id, now() + interval '30 minutes');

    PERFORM public.log_admin_action(v_me, 'config_change', 'config', 'discord_bridge_mode',
        jsonb_build_object(
            'key', 'discord_bridge_mode',
            'bridge_id', v_bridge.id,
            'server_id', v_bridge.server_id,
            'bot_id', v_bridge.bot_id,
            'old_value', 'instance',
            'new_value', 'self'));

    RETURN v_code;
END;
$$;

REVOKE ALL ON FUNCTION public.fork_convert_bridge_to_self(uuid) FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.fork_convert_bridge_to_self(uuid) TO authenticated;

COMMIT;
