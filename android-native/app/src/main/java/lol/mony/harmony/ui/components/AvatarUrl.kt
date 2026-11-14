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
     * A server's icon or banner, resolved the same way an avatar is.
     *
     * `servers.icon` and `servers.banner` are written by the same uploader that writes
     * `profiles.avatar_url`, so they hold the same three shapes: an absolute URL, a site-relative
     * path, or a bare storage path. A bare path names the `server-icons` bucket, which is where
     * the web client's server uploader puts them.
     */
    fun resolveServer(baseUrl: String?, path: String?): String? {
        val raw = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (SCHEME.matches(raw) || raw.startsWith("/")) return resolve(baseUrl, raw)
        val base = baseUrl?.toHttpUrlOrNull() ?: return null
        val prefix = base.encodedPath.trimEnd('/')
        return base.newBuilder()
            .encodedPath(prefix + "/storage/v1/object/public/server-icons/" + raw.trimStart('/'))
            .query(null).fragment(null).build().toString()
    }

    /** True when [url] is something an image loader can actually fetch. */
    fun isFetchable(url: String?): Boolean =
        url != null && (url.startsWith("http://") || url.startsWith("https://") ||
            url.startsWith("data:") || url.startsWith("content:"))
}
