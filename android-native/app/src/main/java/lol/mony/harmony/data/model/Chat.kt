package lol.mony.harmony.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A channel's `type`. The schema stores an int: 0 text, 1 voice, 2 category, 3 announcement,
 * 4 forum - the same values `src/types/chat.ts` gives them.
 */
@JvmInline
value class ChannelType(val raw: Int) {
    val isText: Boolean get() = raw == 0 || raw == 3 || raw == 4
    val isVoice: Boolean get() = raw == 1
    val label: String
        get() = when (raw) {
            0 -> "text"
            1 -> "voice"
            2 -> "category"
            3 -> "announcement"
            4 -> "forum"
            else -> "text"
        }

    companion object {
        val TEXT get() = ChannelType(0)
        val VOICE get() = ChannelType(1)
    }
}

@Serializable
data class Server(
    val id: String,
    val name: String,
    val description: String? = null,
    val icon: String? = null,
    val banner: String? = null,
    val owner: String? = null,
    val public: Boolean = false,
    val slug: String? = null,
    @SerialName("member_count") val memberCount: Int = 0,
    @SerialName("is_local_server") val isLocalServer: Boolean = true,
    @SerialName("invite_code") val inviteCode: String? = null,
    @SerialName("host_domain") val hostDomain: String? = null,
    @SerialName("federation_enabled") val federationEnabled: Boolean = false,
    @SerialName("federation_domain") val federationDomain: String? = null,
    val rules: List<String> = emptyList(),
    @SerialName("suspended_at") val suspendedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
) {
    /** Two-letter stand-in for the icon, as the web client's server rail shows. */
    val initials: String
        get() = name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
            .ifBlank { name.take(1).uppercase() }
}

@Serializable
data class ChannelCategory(
    val id: String,
    @SerialName("server_id") val serverId: String,
    val name: String,
    val order: Int = 0,
)

@Serializable
data class Channel(
    val id: String,
    @SerialName("server_id") val serverId: String,
    val name: String,
    val description: String? = null,
    val type: Int = 0,
    val category: String? = null,
    val order: Int = 0,
    @SerialName("slowmode_seconds") val slowmodeSeconds: Int = 0,
    @SerialName("host_flags") val hostFlags: JsonElement? = null,
) {
    val channelType: ChannelType get() = ChannelType(type)
    /** What the composer placeholder calls this channel, as the web client labels it. */
    val label: String get() = if (channelType.isVoice) "$name" else name
}

/** One row of `user_servers`: this account's membership in a server. */
@Serializable
data class UserServer(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("server_id") val serverId: String,
    val status: String = "accepted",
    val nickname: String? = null,
    @SerialName("folder_id") val folderId: String? = null,
    val position: Int = 0,
    val muted: Boolean = false,
    @SerialName("muted_until") val mutedUntil: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val bio: String? = null,
    val pronouns: String? = null,
)

/** A server the rail shows: the server plus this account's membership row. */
data class ServerWithMembership(val server: Server, val membership: UserServer?)

/** The server's channels and categories, as the channel drawer groups them. */
data class ServerTree(
    val server: Server,
    val categories: List<ChannelCategory>,
    val channels: List<Channel>,
) {
    /** Channels of a category in display order; uncategorised channels go under `null`. */
    fun channelsIn(categoryId: String?): List<Channel> =
        channels.filter { it.category == categoryId }.sortedWith(compareBy({ it.order }, { it.name }))
}
