-- Brings an instance that ran the old fork's schema to the same function bodies as a fresh install.
--
-- Found by scripts/fork/check-upgrade-path.sh. A fresh install and an upgraded instance differ in
-- four functions, for two reasons:
--
-- 1. Upstream 1.9.7 edited 20261111900001 and 20261112300001 in place: they now define
--    bridge_require_server_bot and call it from bridge_import_server_sound and
--    bridge_import_server_sticker. An instance that recorded those versions ran the old files and
--    still has the inline check and no helper. The three functions are replaced here with the text of
--    the current files (no behaviour change: the same check, the same error).
-- 2. public_instance_config_keys is built up by appending keys, in migration order. The old fork
--    appended discord_migration_account_creation before upstream's bridge_history_import; a fresh
--    install runs them the other way round. The list is a set, so the order never mattered, but the
--    function body differs. It is rebuilt here with the keys sorted, which every database reaches.
--
-- Idempotent: CREATE OR REPLACE, and the sorted list is the same on a second run.

BEGIN;

SET LOCAL lock_timeout = '5s';

-- Raises 42501 unless the bot is the Discord bridge bot of the server. No API role executes it.
CREATE OR REPLACE FUNCTION public.bridge_require_server_bot(p_bot_id uuid, p_server_id uuid)
RETURNS void
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM public.discord_bridges b WHERE b.server_id = p_server_id AND b.bot_id = p_bot_id
  ) THEN
    RAISE EXCEPTION 'bot is not the Discord bridge of this server' USING ERRCODE = '42501';
  END IF;
END;
$$;

REVOKE ALL ON FUNCTION public.bridge_require_server_bot(uuid, uuid) FROM PUBLIC, anon, authenticated, service_role;

CREATE OR REPLACE FUNCTION public.bridge_import_server_sound(
  p_bot_id uuid,
  p_server_id uuid,
  p_discord_sound_id text,
  p_name text,
  p_emoji text,
  p_emoji_id uuid,
  p_volume numeric,
  p_update boolean,
  p_storage_path text,
  p_duration_ms integer
)
RETURNS TABLE(status text, id uuid, name text, emoji text, emoji_id uuid, volume numeric, discord_sound_id text)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
-- Output columns share names with server_sounds columns; references mean the table.
#variable_conflict use_column
DECLARE
  v_row public.server_sounds%ROWTYPE;
BEGIN
  PERFORM public.bridge_require_server_bot(p_bot_id, p_server_id);

  IF p_discord_sound_id IS NULL OR p_discord_sound_id !~ '^[0-9]{1,20}$' THEN
    RAISE EXCEPTION 'invalid Discord sound id' USING ERRCODE = '22023';
  END IF;
  IF p_name IS NULL OR p_name <> btrim(p_name) OR char_length(p_name) NOT BETWEEN 1 AND 32 THEN
    RAISE EXCEPTION 'invalid sound name' USING ERRCODE = '22023';
  END IF;
  IF p_emoji IS NOT NULL AND p_emoji_id IS NOT NULL THEN
    RAISE EXCEPTION 'invalid sound emoji' USING ERRCODE = '22023';
  END IF;
  IF p_emoji IS NOT NULL AND (p_emoji <> btrim(p_emoji) OR char_length(p_emoji) NOT BETWEEN 1 AND 16) THEN
    RAISE EXCEPTION 'invalid sound emoji' USING ERRCODE = '22023';
  END IF;
  IF p_emoji_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM public.emojis e
     WHERE e.id = p_emoji_id AND (e.server_id = p_server_id OR e.scope = 'instance')
  ) THEN
    RAISE EXCEPTION 'invalid sound emoji' USING ERRCODE = '22023';
  END IF;
  IF p_volume IS NULL OR p_volume < 0 OR p_volume > 1 THEN
    RAISE EXCEPTION 'invalid sound volume' USING ERRCODE = '22023';
  END IF;

  SELECT * INTO v_row FROM public.server_sounds s
   WHERE s.server_id = p_server_id AND s.discord_sound_id = p_discord_sound_id;
  IF FOUND THEN
    IF p_update AND (v_row.name IS DISTINCT FROM p_name
                     OR v_row.emoji IS DISTINCT FROM p_emoji
                     OR v_row.emoji_id IS DISTINCT FROM p_emoji_id
                     OR v_row.volume IS DISTINCT FROM round(p_volume, 2)) THEN
      UPDATE public.server_sounds s
         SET name = p_name, emoji = p_emoji, emoji_id = p_emoji_id, volume = p_volume
       WHERE s.id = v_row.id
      RETURNING * INTO v_row;
      RETURN QUERY SELECT 'updated'::text, v_row.id, v_row.name, v_row.emoji, v_row.emoji_id,
                          v_row.volume::numeric, v_row.discord_sound_id;
      RETURN;
    END IF;
    RETURN QUERY SELECT 'existing'::text, v_row.id, v_row.name, v_row.emoji, v_row.emoji_id,
                        v_row.volume::numeric, v_row.discord_sound_id;
    RETURN;
  END IF;

  IF p_storage_path IS NULL
     OR p_storage_path !~ ('^' || p_server_id::text || '/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(mp3|ogg)$')
     OR p_duration_ms IS NULL OR p_duration_ms NOT BETWEEN 1 AND 5200 THEN
    RAISE EXCEPTION 'invalid sound file' USING ERRCODE = '22023';
  END IF;

  INSERT INTO public.server_sounds (server_id, name, emoji, emoji_id, volume, duration_ms, storage_path, discord_sound_id)
  VALUES (p_server_id, p_name, p_emoji, p_emoji_id, p_volume, p_duration_ms, p_storage_path, p_discord_sound_id)
  ON CONFLICT (server_id, discord_sound_id) WHERE discord_sound_id IS NOT NULL DO NOTHING
  RETURNING * INTO v_row;
  IF NOT FOUND THEN
    SELECT * INTO v_row FROM public.server_sounds s
     WHERE s.server_id = p_server_id AND s.discord_sound_id = p_discord_sound_id;
    RETURN QUERY SELECT 'existing'::text, v_row.id, v_row.name, v_row.emoji, v_row.emoji_id,
                        v_row.volume::numeric, v_row.discord_sound_id;
    RETURN;
  END IF;
  RETURN QUERY SELECT 'created'::text, v_row.id, v_row.name, v_row.emoji, v_row.emoji_id,
                      v_row.volume::numeric, v_row.discord_sound_id;
END;
$$;

CREATE OR REPLACE FUNCTION public.bridge_import_server_sticker(
    p_bot_id uuid,
    p_server_id uuid,
    p_discord_sticker_id text,
    p_name text,
    p_description text,
    p_update boolean DEFAULT false,
    p_path text DEFAULT NULL,
    p_url text DEFAULT NULL,
    p_width integer DEFAULT NULL,
    p_height integer DEFAULT NULL,
    p_duration_ms integer DEFAULT NULL
)
RETURNS TABLE(status text, id uuid, name text, description text, url text, discord_sticker_id text, removed boolean)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
-- Output columns share names with stickers columns; references mean the table.
#variable_conflict use_column
DECLARE
    v_name text;
    v_description text;
    v_row public.stickers%ROWTYPE;
    v_prefix text := 'server/' || p_server_id::text || '/';
    v_limit integer;
    v_meta jsonb;
    v_size text;
    v_mime text;
    v_ext text;
    v_constraint text;
BEGIN
    PERFORM public.bridge_require_server_bot(p_bot_id, p_server_id);
    IF p_discord_sticker_id IS NULL OR p_discord_sticker_id !~ '^[0-9]{1,20}$' THEN
        RAISE EXCEPTION 'invalid Discord sticker id' USING ERRCODE = '22023';
    END IF;
    v_name := btrim(p_name);
    v_description := btrim(p_description);
    PERFORM public.sticker_check_text(v_name, v_description);

    SELECT * INTO v_row FROM public.stickers s
     WHERE s.server_id = p_server_id AND s.discord_sticker_id = p_discord_sticker_id AND s.scope = 'server'
       FOR UPDATE;
    IF FOUND THEN
        IF NOT p_update
           OR (v_row.removed_at IS NULL AND v_row.name = v_name AND v_row.description = v_description) THEN
            RETURN QUERY SELECT 'existing'::text, v_row.id, v_row.name, v_row.description, v_row.url,
                                v_row.discord_sticker_id, (v_row.removed_at IS NOT NULL);
            RETURN;
        END IF;

        PERFORM pg_advisory_xact_lock(hashtextextended('stickers:server:' || p_server_id::text, 0));
        IF EXISTS (
            SELECT 1 FROM public.stickers s2
             WHERE s2.scope = 'server' AND s2.server_id = p_server_id AND s2.id <> v_row.id
               AND s2.removed_at IS NULL AND lower(s2.name) = lower(v_name)
        ) THEN
            RAISE EXCEPTION 'STICKER_NAME_TAKEN: % is taken', v_name USING ERRCODE = '23505';
        END IF;
        IF v_row.removed_at IS NOT NULL THEN
            v_limit := public.sticker_limit('server');
            IF (SELECT count(*) FROM public.stickers s3
                 WHERE s3.scope = 'server' AND s3.server_id = p_server_id AND s3.removed_at IS NULL) >= v_limit THEN
                RAISE EXCEPTION 'STICKER_LIMIT: at most % server stickers', v_limit USING ERRCODE = '23514';
            END IF;
        END IF;

        UPDATE public.stickers s
           SET name = v_name, description = v_description, removed_at = NULL, updated_at = now()
         WHERE s.id = v_row.id
        RETURNING * INTO v_row;
        RETURN QUERY SELECT 'updated'::text, v_row.id, v_row.name, v_row.description, v_row.url,
                            v_row.discord_sticker_id, false;
        RETURN;
    END IF;

    IF p_path IS NULL THEN
        RAISE EXCEPTION 'invalid sticker file' USING ERRCODE = '22023';
    END IF;

    -- The file checks of create_sticker for scope server.
    IF p_path !~ ('^' || v_prefix
        || '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(png|apng|webp|gif)$') THEN
        RAISE EXCEPTION 'STICKER_PATH: a sticker file is %<uuid>.<png|apng|webp|gif>', v_prefix
            USING ERRCODE = '22023';
    END IF;
    IF NOT public.is_storage_public_url(p_url, 'stickers', p_path) THEN
        RAISE EXCEPTION 'STICKER_URL: the url is not the public URL of %', p_path USING ERRCODE = '22023';
    END IF;
    IF p_width IS NULL OR p_height IS NULL OR p_width NOT BETWEEN 1 AND 320 OR p_height NOT BETWEEN 1 AND 320 THEN
        RAISE EXCEPTION 'STICKER_DIMENSIONS: a sticker is at most 320x320 pixels' USING ERRCODE = '22023';
    END IF;
    IF p_duration_ms IS NOT NULL AND p_duration_ms NOT BETWEEN 1 AND 5000 THEN
        RAISE EXCEPTION 'STICKER_DURATION: an animated sticker runs at most 5 seconds' USING ERRCODE = '22023';
    END IF;

    SELECT o.metadata INTO v_meta
      FROM storage.objects o
     WHERE o.bucket_id = 'stickers'
       AND o.name = p_path;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'STICKER_FILE_MISSING: % is not in the stickers bucket', p_path USING ERRCODE = '22023';
    END IF;
    v_size := v_meta ->> 'size';
    IF v_size IS NULL OR v_size !~ '^[0-9]{1,12}$' OR v_size::bigint NOT BETWEEN 1 AND 524288 THEN
        RAISE EXCEPTION 'STICKER_FILE_SIZE: a sticker file is at most 512 KB' USING ERRCODE = '22023';
    END IF;
    v_mime := lower(v_meta ->> 'mimetype');
    v_ext := substring(p_path from '\.([a-z]+)$');
    IF NOT ((v_ext IN ('png', 'apng') AND v_mime IN ('image/png', 'image/apng'))
            OR (v_ext = 'webp' AND v_mime = 'image/webp')
            OR (v_ext = 'gif' AND v_mime = 'image/gif')) THEN
        RAISE EXCEPTION 'STICKER_FILE_TYPE: a sticker is PNG, APNG, WebP or GIF' USING ERRCODE = '22023';
    END IF;

    INSERT INTO public.stickers (scope, server_id, uploader, name, description, path, url, mime_type,
                                 file_size, width, height, duration_ms, discord_sticker_id)
    VALUES ('server', p_server_id, NULL, v_name, v_description, p_path, p_url, v_mime, v_size::integer,
            p_width, p_height, p_duration_ms, p_discord_sticker_id)
    ON CONFLICT (server_id, discord_sticker_id) WHERE discord_sticker_id IS NOT NULL DO NOTHING
    RETURNING * INTO v_row;
    IF NOT FOUND THEN
        SELECT * INTO v_row FROM public.stickers s
         WHERE s.server_id = p_server_id AND s.discord_sticker_id = p_discord_sticker_id;
        RETURN QUERY SELECT 'existing'::text, v_row.id, v_row.name, v_row.description, v_row.url,
                            v_row.discord_sticker_id, (v_row.removed_at IS NOT NULL);
        RETURN;
    END IF;
    RETURN QUERY SELECT 'created'::text, v_row.id, v_row.name, v_row.description, v_row.url,
                        v_row.discord_sticker_id, false;
EXCEPTION
    WHEN unique_violation THEN
        GET STACKED DIAGNOSTICS v_constraint = CONSTRAINT_NAME;
        IF v_constraint = 'stickers_path_key' THEN
            RAISE EXCEPTION 'STICKER_FILE_TAKEN: % is another sticker''s file', p_path USING ERRCODE = '23505';
        END IF;
        RAISE EXCEPTION 'STICKER_NAME_TAKEN: % is taken', v_name USING ERRCODE = '23505';
END;
$$;

-- Same keys, sorted: the order an append sequence leaves depends on which migration ran first.
DO $do$
BEGIN
    EXECUTE format(
        'CREATE OR REPLACE FUNCTION public.public_instance_config_keys() RETURNS text[] '
        'LANGUAGE sql IMMUTABLE SET search_path = public, pg_temp AS %L',
        format('SELECT %L::text[]', (SELECT array_agg(k ORDER BY k COLLATE "C")
                                       FROM unnest(public.public_instance_config_keys()) AS k)));
END;
$do$;

COMMIT;
