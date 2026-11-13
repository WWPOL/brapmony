package lol.mony.harmony.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A message body part. The schema stores `content` as JSON: a list of these. The web client's
 * `src/types/chat.ts` names the same parts; text is the only one most messages carry.
 */
@Serializable
data class ContentPart(
    val type: String = "text",
    val text: String? = null,
    val url: String? = null,
    val name: String? = null,
    val emoji: String? = null,
    val markdown: Boolean? = null,
) {
    companion object {
        fun text(value: String) = ContentPart(type = "text", text = value)
    }
}

@Serializable
data class Message(
    val id: String,
    @SerialName("channel_id") val channelId: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("thread_id") val threadId: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("bot_id") val botId: String? = null,
    @SerialName("reply_to") val replyTo: String? = null,
    val content: List<ContentPart> = emptyList(),
    @SerialName("is_deleted") val isDeleted: Boolean = false,
    @SerialName("is_system") val isSystem: Boolean = false,
    @SerialName("is_pinned") val isPinned: Boolean = false,
    @SerialName("pinned_at") val pinnedAt: String? = null,
    val encrypted: Boolean = false,
    val reactions: JsonElement? = null,
    val metadata: JsonElement? = null,
    @SerialName("media_paths") val mediaPaths: List<String>? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    /** The plain text a message shows: its text parts joined, as the web client renders them. */
    val plainText: String
        get() = content.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("")

    /** The replies threaded off this message, when the caller loaded them. */
    fun isMine(authUserId: String?): Boolean = userId != null && userId == authUserId
}

/** A row of `reactions`, aggregated the way the web client groups them per emoji. */
@Serializable
data class Reaction(
    val id: String? = null,
    @SerialName("message_id") val messageId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("custom_emoji_content") val customEmojiContent: String? = null,
    @SerialName("emoji_id") val emojiId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
) { val emoji: String get() = customEmojiContent ?: emojiId ?: "?" }

@Serializable
data class Thread(
    val id: String,
    @SerialName("channel_id") val channelId: String,
    @SerialName("parent_message_id") val parentMessageId: String? = null,
    @SerialName("server_id") val serverId: String? = null,
    val name: String? = null,
    @SerialName("message_count") val messageCount: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("last_message_at") val lastActivityAt: String? = null,
)

/** A direct-message conversation and the people in it. */
@Serializable
data class Conversation(
    val id: String,
    val name: String? = null,
    val type: String = "direct",
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("is_active") val isActive: Boolean = true,
    val metadata: JsonElement? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class ConversationParticipant(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("user_id") val userId: String,
    val role: String = "member",
    @SerialName("last_read_at") val lastReadAt: String? = null,
    @SerialName("last_read_message_id") val lastReadMessageId: String? = null,
    @SerialName("is_muted") val isMuted: Boolean = false,
    @SerialName("left_at") val leftAt: String? = null,
)

/** A conversation with the other people's profiles resolved, ready for the list. */
data class DirectConversation(
    val conversation: Conversation,
    val participants: List<Profile>,
) {
    val title: String
        get() = conversation.name
            ?: participants.joinToString(", ") { it.shownName }
            .ifBlank { "Conversation" }
}

/** One row of the `get_unread_counts` RPC, as the web client's `UnreadCount` type reads it. */
@Serializable
data class UnreadCountRow(
    @SerialName("server_id") val serverId: String? = null,
    @SerialName("channel_id") val channelId: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("unread_mentions") val unreadMentions: Int = 0,
    @SerialName("unread_count") val unreadCount: Int = 0,
    @SerialName("last_read_message_id") val lastReadMessageId: String? = null,
    val muted: Boolean = false,
)
