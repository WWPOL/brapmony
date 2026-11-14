package lol.mony.harmony.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A `profiles` row - one person, local or federated.
 *
 * `id` is the profile's own id; `authUserId` links it to the `auth.users` row and is what
 * membership tables key on. They are equal for accounts this instance created, but the web
 * client's fixtures keep them apart on purpose, so the app never assumes they match.
 */
@Serializable
data class Profile(
    val id: String,
    @SerialName("auth_user_id") val authUserId: String? = null,
    val username: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("banner_url") val bannerUrl: String? = null,
    val bio: String? = null,
    val pronouns: String? = null,
    val domain: String? = null,
    @SerialName("is_local") val isLocal: Boolean = true,
    @SerialName("appearance_settings") val appearanceSettings: JsonElement? = null,
    @SerialName("notification_settings") val notificationSettings: JsonElement? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    /**
     * `profiles.status` is a `smallint` (0 offline, 1 online, 2 idle, 3 do-not-disturb), not a
     * string. Declaring it as one made every profile fail to decode, which is why no author
     * name or avatar ever reached the message list.
     */
    val status: JsonElement? = null,
    /** `profiles.custom_status` is `jsonb`, not a string: `{"text": "...", "emoji": ...}`. */
    @SerialName("custom_status") val customStatus: JsonElement? = null,
) {
    /** The handle people see: `@name`, and `@name@domain` away from this instance. */
    fun handle(localDomain: String? = null): String {
        val bare = if (isLocal || domain.isNullOrBlank() || domain == localDomain) "@$username" else "@$username@$domain"
        return bare
    }

    val shownName: String get() = displayName?.takeIf { it.isNotBlank() } ?: username
}
