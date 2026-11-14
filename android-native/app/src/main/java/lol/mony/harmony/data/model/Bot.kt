package lol.mony.harmony.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A `bots` row - an account that posts as itself, not as a person.
 *
 * `id` is the bot's own id and is what `messages.bot_id` points at, so it is a separate lookup
 * from `profiles`: a bot message has no `user_id` at all.
 */
@Serializable
data class Bot(
    val id: String,
    val username: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("banner_url") val bannerUrl: String? = null,
    val bio: String? = null,
    @SerialName("owner_id") val ownerId: String? = null,
    @SerialName("is_verified") val isVerified: Boolean = false,
    @SerialName("bot_type") val botType: String? = null,
) {
    /** The name a message row shows: the display name when the bot set one, else its handle. */
    val shownName: String get() = displayName?.takeIf { it.isNotBlank() } ?: username
}
