-- A bot that still has messages can't be deleted from My bots.
--
-- messages.bot_id and reactions.bot_id are ON DELETE CASCADE, so deleting a bridge bot from My bots
-- would silently delete every message it bridged or imported. The trigger refuses that delete when
-- the caller is a signed-in client (the role PostgREST assumes) and a message of the bot exists.
-- Disconnecting the bridge still works: discord_bridges_retire_bot deactivates a bot with messages
-- and keeps them. Trusted callers pass: service role (e2e clean-up), postgres, and SECURITY DEFINER
-- functions, where current_user is the owner. This is the same test a_bots_integration_guard uses.
--
-- The existence check runs in a SECURITY DEFINER helper: as the client it would pass through
-- messages RLS, and an owner who can't read the bot's messages (left the server, private channel,
-- DM) would delete them. The trigger function itself stays SECURITY INVOKER so current_user is the
-- caller. The helper is granted to authenticated because the trigger runs as the caller; it only
-- answers yes or no for a bot id.
--
-- Cost: one EXISTS on idx_messages_bot_channel (bot_id, channel_id) and one on idx_reactions_bot_id,
-- only for a client delete.

BEGIN;

SET LOCAL lock_timeout = '5s';

CREATE OR REPLACE FUNCTION public.bot_has_messages(p_bot_id uuid)
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT EXISTS (SELECT 1 FROM public.messages m WHERE m.bot_id = p_bot_id)
        OR EXISTS (SELECT 1 FROM public.reactions r WHERE r.bot_id = p_bot_id);
$$;

REVOKE ALL ON FUNCTION public.bot_has_messages(uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.bot_has_messages(uuid) TO authenticated;

CREATE OR REPLACE FUNCTION public.guard_bot_delete_keeps_history()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = public, pg_temp
AS $$
BEGIN
    IF current_user IN ('authenticated', 'anon')
       AND public.bot_has_messages(OLD.id) THEN
        RAISE EXCEPTION 'This bot still has messages in a server. Disconnect its bridge first; its messages are kept.'
            USING ERRCODE = '23503';
    END IF;
    RETURN OLD;
END;
$$;

REVOKE ALL ON FUNCTION public.guard_bot_delete_keeps_history() FROM PUBLIC, anon, authenticated;

DROP TRIGGER IF EXISTS bots_keep_history ON public.bots;
CREATE TRIGGER bots_keep_history
    BEFORE DELETE ON public.bots
    FOR EACH ROW EXECUTE FUNCTION public.guard_bot_delete_keeps_history();

COMMIT;
