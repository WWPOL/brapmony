package lol.mony.harmony.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

/**
 * One emoji a message part names, as `src/types/chat.ts`'s `EmojiContent` carries it.
 *
 * The web's `Emoji` (`src/types/media.ts`) is an `emojis` row, and an emoji part holds the whole
 * row: `{ id, name, url, ... }`. Not every field is always there -- a bridged Discord emoji and a
 * reaction round-trip both produce `id: null`, and an unresolved emoji has no `url` -- so every
 * field is nullable here. `content` holds a native unicode codepoint when the emoji wraps a
 * system emoji rather than an uploaded image.
 *
 * Decoding accepts the object form the instance stores, the legacy bare-string form older
 * clients wrote (`:name:` or `name`), and `null`, so one odd emoji can never fail a page of
 * history.
 */
@Serializable(with = EmojiSerializer::class)
data class Emoji(
    val id: String? = null,
    val name: String? = null,
    val url: String? = null,
    /** A native unicode emoji this object wraps, when it is not a custom image. */
    val content: String? = null,
    /** Host of an emoji from another instance; absent for this instance's own. */
    val domain: String? = null,
) {
    /** What a text-only rendering shows, as the web's `discordEmojiPartText`/`:name:` does. */
    val shortcode: String
        get() = when {
            !content.isNullOrBlank() -> content
            !name.isNullOrBlank() -> ":" + name + ":"
            else -> ":emoji:"
        }

    /** True when there is an image to draw; a unicode emoji draws as its own glyph instead. */
    val hasImage: Boolean get() = !url.isNullOrBlank()
}

internal object EmojiSerializer : KSerializer<Emoji> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Emoji")

    override fun deserialize(decoder: Decoder): Emoji {
        val input = decoder as? JsonDecoder ?: return Emoji(name = decoder.decodeString())
        return when (val element = input.decodeJsonElement()) {
            is JsonObject -> Emoji(
                id = element.stringOrNull("id"),
                name = element.stringOrNull("name"),
                url = element.stringOrNull("url"),
                content = element.stringOrNull("content"),
                domain = element.stringOrNull("domain"),
            )
            // Legacy: some senders stored the emoji as its shortcode or its bare name.
            is JsonPrimitive -> legacyEmoji(element.contentOrNull.orEmpty())
            else -> Emoji()
        }
    }

    override fun serialize(encoder: Encoder, value: Emoji) {
        val output = encoder as? JsonEncoder ?: run {
            encoder.encodeString(value.name.orEmpty()); return
        }
        output.encodeJsonElement(toJson(value))
    }

    fun toJson(value: Emoji): JsonObject = buildJsonObject {
        value.id?.let { put("id", it) }
        value.name?.let { put("name", it) }
        value.url?.let { put("url", it) }
        value.content?.let { put("content", it) }
        value.domain?.let { put("domain", it) }
    }

    /** `:wave:` and `wave` both name the emoji `wave`. */
    private fun legacyEmoji(value: String): Emoji {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return Emoji()
        return if (trimmed.length > 2 && trimmed.startsWith(':') && trimmed.endsWith(':')) {
            Emoji(name = trimmed.substring(1, trimmed.length - 1))
        } else Emoji(name = trimmed)
    }
}

/**
 * A message body part. The instance stores `content` as a JSON array of these; the web client's
 * `src/types/chat.ts` names the same parts in `MessagePart`.
 *
 * Decoding is deliberately total: every known field is optional, and the part's own JSON is kept
 * in [raw]. A part type this client does not know, or a field whose shape changed, therefore
 * degrades to a placeholder rather than failing the whole page -- which is what one ordinary
 * object-valued emoji used to do to an entire channel's history.
 */
@Serializable(with = ContentPartSerializer::class)
data class ContentPart(
    val type: String = "text",
    val text: String? = null,
    val url: String? = null,
    val name: String? = null,
    /** `type = "emoji"`: the emoji object, an older bare string, or null. */
    val emoji: Emoji? = null,
    val markdown: Boolean? = null,
    /** `mention`: the mentioned profile's id, handle and display name. */
    val userId: String? = null,
    val username: String? = null,
    val displayName: String? = null,
    val domain: String? = null,
    val isLocal: Boolean? = null,
    /** `role_mention`: a role's id, name and colour (`null` for @here/@everyone). */
    val roleId: String? = null,
    val roleName: String? = null,
    val roleColor: String? = null,
    /** `channel_mention`: the channel and server a `#channel` names, and a shared message. */
    val channelId: String? = null,
    val serverId: String? = null,
    val messageId: String? = null,
    /** `hashtag`: the tag without `#`, and how often it was used. */
    val count: Int? = null,
    /** `url`: whether the sender asked for a preview. */
    val preview: Boolean? = null,
    /** `file`: the media kind, its name, size and the `message_media` object it names. */
    val fileType: String? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val path: String? = null,
    val altText: String? = null,
    val mimeType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** `sticker`: the sticker's own id, name, description and size. */
    val description: String? = null,
    /** `sticker`: the image part it stands in for, which clients that render stickers hide. */
    val stickerId: String? = null,
    /** `poll`: the poll's id, question and whether several answers may be picked. */
    val pollId: String? = null,
    val question: String? = null,
    val options: List<String>? = null,
    val allowMultiple: Boolean? = null,
    val expiresAt: String? = null,
    /** `system`: what happened (`join`, `leave`) and who it happened to. */
    val eventType: String? = null,
    val user: JsonObject? = null,
    /** `system`: who ran it, and when; null when the instance did it itself. */
    val initiatedBy: JsonObject? = null,
    val timestamp: String? = null,
    /** `url`: the link text of a masked link `[label](url)`; absent, the URL shows. */
    val label: String? = null,
    /** `embed`: the provider that renders the card, its ids, and whether it is folded. */
    val provider: String? = null,
    val previewId: String? = null,
    val embedId: String? = null,
    val collapsed: Boolean? = null,
    /** `hashtag`: when it was last used, and its normalised form for searching. */
    val lastUpdated: String? = null,
    val normalized: String? = null,
    /** The part's own JSON, so nothing the instance stores is lost to a field this build lacks. */
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    /** The text a message shows for this part: its text, or the shortcode a part stands for. */
    val asText: String
        get() = when (type) {
            "text" -> text.orEmpty()
            "emoji" -> emoji?.shortcode ?: ":emoji:"
            "mention" -> (username ?: displayName)?.let { "@" + it }.orEmpty()
            "role_mention" -> (roleName ?: roleId)?.let { "@" + it }.orEmpty()
            "channel_mention" -> name?.let { "#" + it }.orEmpty()
            "hashtag" -> name?.let { "#" + it }.orEmpty()
            // A masked link reads as its label; an unmasked one as its URL.
            "url" -> label?.takeIf { it.isNotBlank() } ?: url.orEmpty()
            "file" -> "[" + (fileType ?: "file") + ": attachment]"
            "sticker" -> name?.let { ":" + it + ":" } ?: "[sticker]"
            "poll" -> question?.let { "[Poll: " + it + "]" } ?: "[Poll]"
            "system" -> systemText()
            "embed" -> url.orEmpty()
            else -> text.orEmpty()
        }

    /**
     * A system event as a person reads it: `Carol joined` rather than `[join]`.
     *
     * The `user` object carries the display name when the instance sent one, so the event
     * names the person rather than the event code. A shape this build does not recognise
     * still reads as something rather than nothing.
     */
    private fun systemText(): String {
        val who = user?.stringOrNull("display_name") ?: user?.stringOrNull("username")
        val what = when (eventType) {
            "join" -> "joined"
            "leave" -> "left"
            null -> null
            else -> eventType
        }
        return listOfNotNull(who, what).joinToString(" ").ifBlank { "[system]" }
    }

    /** True when this part flows inside the sentence it belongs to. */
    val isInline: Boolean get() = !isBlock

    /** True when this part stands on its own line: an attachment, sticker, poll, system event. */
    val isBlock: Boolean
        get() = type in setOf("file", "sticker", "poll", "system", "embed")

    companion object {
        fun text(value: String) = ContentPart(type = "text", text = value)
    }
}

internal object ContentPartSerializer : KSerializer<ContentPart> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ContentPart")

    override fun deserialize(decoder: Decoder): ContentPart {
        val input = decoder as? JsonDecoder ?: return ContentPart(text = decoder.decodeString())
        val part = when (val element = input.decodeJsonElement()) {
            is JsonObject -> element
            is JsonPrimitive -> buildJsonObject {
                put("type", "text"); put("text", element.contentOrNull.orEmpty())
            }
            else -> JsonObject(emptyMap())
        }
        return ContentPart(
            type = part.stringOrNull("type") ?: "text",
            text = part.stringOrNull("text"),
            url = part.stringOrNull("url"),
            name = part.stringOrNull("name"),
            emoji = part.emojiOrNull(),
            markdown = part.booleanOrNull("markdown"),
            userId = part.stringOrNull("userId"),
            username = part.stringOrNull("username"),
            displayName = part.stringOrNull("displayName"),
            domain = part.stringOrNull("domain"),
            isLocal = part.booleanOrNull("isLocal"),
            roleId = part.stringOrNull("roleId"),
            roleName = part.stringOrNull("roleName"),
            roleColor = part.stringOrNull("roleColor"),
            channelId = part.stringOrNull("channelId"),
            serverId = part.stringOrNull("serverId"),
            messageId = part.stringOrNull("messageId"),
            count = part.intOrNull("count"),
            preview = part.booleanOrNull("preview"),
            fileType = part.stringOrNull("fileType"),
            fileName = part.stringOrNull("fileName"),
            fileSize = part.longOrNull("fileSize"),
            path = part.stringOrNull("path"),
            altText = part.stringOrNull("altText"),
            mimeType = part.stringOrNull("mimeType"),
            width = part.intOrNull("width"),
            height = part.intOrNull("height"),
            description = part.stringOrNull("description"),
            stickerId = part.stringOrNull("stickerId"),
            pollId = part.stringOrNull("pollId"),
            question = part.stringOrNull("question"),
            options = (part["options"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            allowMultiple = part.booleanOrNull("allowMultiple"),
            expiresAt = part.stringOrNull("expiresAt"),
            eventType = part.stringOrNull("event_type"),
            user = part["user"] as? JsonObject,
            initiatedBy = part["initiated_by"] as? JsonObject,
            timestamp = part.stringOrNull("timestamp"),
            label = part.stringOrNull("label"),
            provider = part.stringOrNull("provider"),
            previewId = part.stringOrNull("previewId"),
            embedId = part.stringOrNull("embedId"),
            collapsed = part.booleanOrNull("collapsed"),
            lastUpdated = part.stringOrNull("last_updated"),
            normalized = part.stringOrNull("normalized"),
            raw = part,
        )
    }

    override fun serialize(encoder: Encoder, value: ContentPart) {
        val output = encoder as? JsonEncoder ?: run { encoder.encodeString(value.asText); return }
        // The original part is the truth; a part this build built itself writes its own fields.
        val body = if (value.raw.isNotEmpty()) value.raw else buildJsonObject {
            value.text?.let { put("text", it) }
            value.url?.let { put("url", it) }
            value.name?.let { put("name", it) }
            value.emoji?.let { put("emoji", EmojiSerializer.toJson(it)) }
        }
        output.encodeJsonElement(buildJsonObject {
            put("type", value.type)
            body.forEach { (key, element) -> if (key != "type") put(key, element) }
        })
    }

    /** The `emoji` field as an object, an older bare string, or absent. */
    private fun JsonObject.emojiOrNull(): Emoji? = when (val element = this["emoji"]) {
        null, is JsonNull -> null
        is JsonObject -> Emoji(
            id = element.stringOrNull("id"), name = element.stringOrNull("name"),
            url = element.stringOrNull("url"), content = element.stringOrNull("content"),
            domain = element.stringOrNull("domain"),
        )
        is JsonPrimitive -> Emoji(name = element.contentOrNull?.trim()?.trim(':')?.ifBlank { null })
        else -> Emoji()
    }
}

/** A JSON value read as a string; numbers and booleans keep their own spelling. */
internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

internal fun JsonObject.booleanOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.let {
        it.booleanOrNull ?: when (it.contentOrNull?.lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> null
        }
    }

internal fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull?.toIntOrNull()

internal fun JsonObject.longOrNull(key: String): Long? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull?.toLongOrNull()

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
    /**
     * The nonce this send carried, as the reference stores it in `messages.metadata`.
     *
     * The web client puts a `client_nonce` in a message's metadata so a send that is retried or
     * raced by realtime can be recognised as the same message rather than a second one. This
     * client reads the same field, so its own optimistic row reconciles against an acknowledged
     * row exactly as the web client's outbox does.
     */
    val clientNonce: String?
        get() = (metadata as? JsonObject)?.stringOrNull("client_nonce")

    /**
     * The text a message shows: its parts rendered the way the web client's
     * `convertMessagePartsToText` does, so a mention, an emoji or a link is not silently dropped.
     */
    val plainText: String get() = content.joinToString("") { it.asText }

    /** The parts that draw something of their own inline, in order, for the message body. */
    val inlineParts: List<ContentPart> get() = content.filter { it.isInline }

    /**
     * True when this message's text can be edited here: not encrypted, and nothing but text parts.
     * Editing rewrites the whole body as one text part, which would drop a mention or an emoji.
     */
    val isEditable: Boolean
        get() = !encrypted && content.all { it.type == "text" }

    /** True when the message carries an attachment, sticker, poll or embed of its own. */
    val hasAttachmentParts: Boolean
        get() = content.any { it.type in setOf("file", "sticker", "embed", "poll") }

    /** The replies threaded off this message, when the caller loaded them. */
    fun isMine(authUserId: String?): Boolean = userId != null && userId == authUserId
}

/** A row of `reactions`, aggregated the way the web client groups them per emoji. */
@Serializable
data class Reaction(
    val id: String? = null,
    @SerialName("message_id") val messageId: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("bot_id") val botId: String? = null,
    @SerialName("custom_emoji_content") val customEmojiContent: String? = null,
    @SerialName("emoji_id") val emojiId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
) {
    val emoji: String get() = customEmojiContent ?: emojiId ?: "?"
    /** True for a custom emoji, whose `emoji_id` names an `emojis` row rather than a codepoint. */
    val isCustom: Boolean get() = emojiId != null

    /**
     * What one pill is: the custom emoji's row id when there is one, else the stored content.
     *
     * This is the identity a pill is drawn under, the one a tap toggles, and the one the
     * reactor list filters by, so all three agree about which reactions belong together. The
     * display label cannot do that job: every custom emoji's label is `:custom:`, so two
     * different custom pills would be one group and a tap would remove the wrong one.
     */
    val key: String get() = emojiId ?: customEmojiContent ?: emoji

    /**
     * What to write back to `reactions.custom_emoji_content` when this account toggles the
     * pill. Joining an existing pill sends the content that pill already carries, which is what
     * the instance's own `check_reaction_emoji` trigger expects.
     */
    val content: String get() = customEmojiContent ?: emojiId ?: emoji
}

@Serializable
data class Thread(
    val id: String,
    @SerialName("channel_id") val channelId: String,
    @SerialName("parent_message_id") val parentMessageId: String? = null,
    @SerialName("server_id") val serverId: String? = null,
    val name: String? = null,
    @SerialName("message_count") val messageCount: Int = 0,
    val archived: Boolean = false,
    val locked: Boolean = false,
    @SerialName("auto_archive_duration") val autoArchiveDuration: Int? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("last_message_at") val lastActivityAt: String? = null,
) {
    /**
     * Whether this thread still belongs in the sidebar's open list, by the reference's rule
     * (`isThreadExpired` in `src/stores/useThreads.ts`): a thread that was archived is gone,
     * and so is one whose own auto-archive clock ran out after its last message.
     *
     * A thread with neither a last message nor a duration cannot expire, which is what keeps
     * a just-created thread visible before its first reply arrives.
     */
    fun isActive(now: java.time.Instant = java.time.Instant.now()): Boolean {
        if (archived) return false
        val last = lastActivityAt?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
            ?: return true
        val minutes = autoArchiveDuration ?: return true
        if (minutes <= 0) return true
        return last.plusSeconds(minutes.toLong() * 60).isAfter(now)
    }
}

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

/**
 * One row of the `get_unread_counts` RPC.
 *
 * The column is **`unread_messages`** (`db_schema/migrations/20261011100001_unread_badge_fixes.sql`,
 * and the web client reads it as `row.unread_messages` in `src/stores/useDM.ts`). This row used
 * to name it `unread_count`, a column neither the function nor the `unread_counts` table has,
 * so every count decoded to the default 0 and no badge ever drew.
 */
@Serializable
data class UnreadCountRow(
    @SerialName("server_id") val serverId: String? = null,
    @SerialName("channel_id") val channelId: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("unread_mentions") val unreadMentions: Int = 0,
    @SerialName("unread_messages") val unreadMessages: Int = 0,
    @SerialName("last_read_message_id") val lastReadMessageId: String? = null,
    val muted: Boolean = false,
)
