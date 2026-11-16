-- A bot that still has messages can't be deleted from My bots.
--
-- messages.bot_id and reactions.bot_id are ON DELETE CASCADE, so deleting a bridge bot from My bots
-- would silently delete every message it bridged or imported. The trigger refuses that delete when
-- the caller is a signed-in client (the role PostgREST assumes) and a message of the bot exists.
-- Disconnecting the bridge still works: discord_bridges_retire_bot deactivates a bot with messages
-- and keeps them. Trusted callers pass: service role (e2e clean-up), postgres, and SECURITY DEFINER
-- functions, where current_user is the owner. This is the same test a_bots_integration_guard uses.
--
-- Cost: one EXISTS on idx_messages_bot_channel (bot_id, channel_id), only for a client delete.

BEGIN;

SET LOCAL lock_timeout = '5s';

CREATE OR REPLACE FUNCTION public.guard_bot_delete_keeps_history()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = public, pg_temp
AS $$
BEGIN
    IF current_user IN ('authenticated', 'anon')
       AND EXISTS (SELECT 1 FROM public.messages m WHERE m.bot_id = OLD.id) THEN
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
