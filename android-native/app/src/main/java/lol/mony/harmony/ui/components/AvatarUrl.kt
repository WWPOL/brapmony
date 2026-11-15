package lol.mony.harmony.ui.components

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The image URL an avatar, banner or icon is actually fetched from.
 *
 * `profiles.avatar_url` holds whatever the uploader wrote, and the rows in one instance mix
 * every form: an absolute URL to this instance or to another one, a path relative to the site
 * root (`/default_avatar.webp`, `/uploads/avatars/<id>.webp`), or a bare storage path
 * (`<user-id>/<file>.webp`). The web client's `getAvatarUrl` resolves all of them against the
 * instance it is talking to; handing the raw string to an image loader instead makes every
 * relative form fail, which is what left message rows without pictures.
 *
 * The bundled default avatar is reported as null: it is an asset of this app, not something to
 * fetch from the server, and asking for it would either 404 or download a copy of what is
 * already drawn.
 */
object AvatarUrl {
    /** Any URI that already names its own scheme. */
    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")

    /** Paths that mean "the account has no picture"; drawn locally, never fetched. */
    private val BUNDLED_DEFAULTS = setOf(
        "/default_avatar.webp", "/default_avatar.png", "default_avatar.webp", "default_avatar.png",
    )

    /**
     * [path] resolved against [baseUrl] (the instance's site root), or null when there is
     * nothing to fetch. A storage path with no leading slash is read as an `avatars` object,
     * which is how the web client's uploader stores one.
     */
    fun resolve(baseUrl: String?, path: String?): String? {
        val raw = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (raw in BUNDLED_DEFAULTS) return null
        // Anything that already names its own scheme is fetchable as it stands and has no
        // instance to resolve against: http(s), but also data:, content: and file:.
        if (SCHEME.matches(raw)) return raw

        val base = baseUrl?.toHttpUrlOrNull() ?: return null
        val rooted = when {
            raw.startsWith("/") -> raw
            // A bare storage path: the bucket the web client uploads avatars to.
            raw.contains('/') -> "/storage/v1/object/public/avatars/" + raw.trimStart('/')
            else -> return null
        }
        // OkHttp's `resolve` treats a leading `/` as the *host* root and drops an instance
        // served under a sub-path (`https://example.test/harmony`). Join to the base instead.
        val prefix = base.encodedPath.trimEnd('/')
        return base.newBuilder().encodedPath(prefix + rooted).query(null).fragment(null).build().toString()
    }

    /**
     * The buckets the reference uploads server branding into.
     *
     * These are the names in `storage.buckets` (`src/utils/serverUtils.ts`), spelled with
     * underscores: `server_icons` holds the icon, `server_banners` the cover. This client had
     * them hyphenated, so every bare storage path became a 404 and a server with a cover drew
     * a name with no picture.
     */
    const val SERVER_ICONS_BUCKET = "server_icons"
    const val SERVER_BANNERS_BUCKET = "server_banners"

    /** A server's icon: `servers.icon`, read out of the `server_icons` bucket. */
    fun resolveServerIcon(baseUrl: String?, path: String?): String? =
        resolveBucket(baseUrl, path, SERVER_ICONS_BUCKET)

    /**
     * A server's cover: `servers.banner`, read out of the `server_banners` bucket.
     *
     * A different bucket from the icon, because the uploader stores a banner as
     * `<server-id>/banner-<ms>.<ext>` in its own bucket and normalises the column to that bare
     * object path (`normalizeServerBannerForStorage`). Resolving it against the icon bucket, or
     * against nothing at all, is what left the header without its picture.
     */
    fun resolveServerBanner(baseUrl: String?, path: String?): String? =
        resolveBucket(baseUrl, path, SERVER_BANNERS_BUCKET)

    /**
     * A server's icon, resolved the same way an avatar is.
     *
     * `servers.icon` is written by the same uploader that writes `profiles.avatar_url`, so it
     * holds the same three shapes: an absolute URL, a site-relative path, or a bare storage
     * path. A bare path names the `server_icons` bucket.
     */
    fun resolveServer(baseUrl: String?, path: String?): String? = resolveServerIcon(baseUrl, path)

    /** A bare object path joined to `bucket` under the instance's storage route. */
    private fun resolveBucket(baseUrl: String?, path: String?, bucket: String): String? {
        val raw = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // The bundled placeholder the schema defaults to is not a picture of this server.
        if (raw == "/default_server.webp" || raw == "default_server.webp") return null
        if (SCHEME.matches(raw) || raw.startsWith("/")) return resolve(baseUrl, raw)
        val base = baseUrl?.toHttpUrlOrNull() ?: return null
        val prefix = base.encodedPath.trimEnd('/')
        return base.newBuilder()
            .encodedPath(prefix + "/storage/v1/object/public/" + bucket + "/" + raw.trimStart('/'))
            .query(null).fragment(null).build().toString()
    }

    /** True when [url] is something an image loader can actually fetch. */
    fun isFetchable(url: String?): Boolean =
        url != null && (url.startsWith("http://") || url.startsWith("https://") ||
            url.startsWith("data:") || url.startsWith("content:"))
}
