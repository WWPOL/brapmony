package lol.mony.harmony.data.repo

import kotlinx.serialization.builtins.ListSerializer
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.api.RestApi
import lol.mony.harmony.data.model.Channel
import lol.mony.harmony.data.model.Conversation
import lol.mony.harmony.data.model.ConversationParticipant
import lol.mony.harmony.data.model.DirectConversation
import lol.mony.harmony.data.model.Message
import lol.mony.harmony.data.model.Profile
import lol.mony.harmony.data.model.Reaction
import lol.mony.harmony.data.model.Thread
import lol.mony.harmony.data.model.UnreadCountRow
import kotlinx.serialization.json.Json

/**
 * Messages in channels, DMs and threads.
 *
 * Every call here has a counterpart in the web client's `services/MessageService.ts`,
 * `stores/useChat.ts`, `useDM.ts` and `useThreads.ts`; the tables, columns and RPCs are the
 * same, so a message sent here is a message the web client sees, and the other way round.
 */
class ChatRepository(private val rest: RestApi) {

    // ---- channels -------------------------------------------------------------------------

    /** One page of a channel's messages, newest last, or the page before `before`. */
    suspend fun channelMessages(
        endpoint: Endpoint,
        token: String,
        channelId: String,
        before: String? = null,
        limit: Int = PAGE,
    ): Result<List<Message>> = runCatching {
        val filters = buildList {
            add("channel_id" to "eq.$channelId")
            add("is_deleted" to "eq.false")
            if (before != null) add("created_at" to "lt.$before")
        }
        rest.select(
            endpoint, token, "messages", MESSAGE_COLUMNS, filters,
            order = "created_at.desc", limit = limit,
            serializer = ListSerializer(Message.serializer()),
        ).getOrThrow().reversed()
    }

    /** A thread's messages, oldest first. */
    suspend fun threadMessages(
        endpoint: Endpoint,
        token: String,
        threadId: String,
        limit: Int = PAGE,
    ): Result<List<Message>> = runCatching {
        rest.select(
            endpoint, token, "messages", MESSAGE_COLUMNS,
            listOf("thread_id" to "eq.$threadId", "is_deleted" to "eq.false"),
            order = "created_at.asc", limit = limit,
            serializer = ListSerializer(Message.serializer()),
        ).getOrThrow()
    }

    /** Sends into a channel or thread; a thread send also names its parent for the counters. */
    suspend fun sendChannelMessage(
        endpoint: Endpoint,
        token: String,
        channelId: String,
        text: String,
        replyTo: String? = null,
        threadId: String? = null,
    ): Result<Message> = runCatching {
        val rows = insertMessage(endpoint, token, text, channelId = channelId, replyTo = replyTo, threadId = threadId)
        rows.first()
    }

    /** Sends into a direct conversation. */
    suspend fun sendDirectMessage(
        endpoint: Endpoint,
        token: String,
        conversationId: String,
        text: String,
        replyTo: String? = null,
        threadId: String? = null,
    ): Result<Message> = runCatching {
        val rows = insertMessage(endpoint, token, text, conversationId = conversationId, replyTo = replyTo, threadId = threadId)
        rows.first()
    }

    private fun insertMessage(
        endpoint: Endpoint,
        token: String,
        text: String,
        channelId: String? = null,
        conversationId: String? = null,
        replyTo: String? = null,
        threadId: String? = null,
    ): List<Message> {
        // `content` is a JSON list of parts, exactly as the web client writes it.
        val escaped = JsonPrimitiveText.quote(text)
        val body = buildString {
            append("{")
            if (channelId != null) append("\"channel_id\":\"$channelId\",")
            if (conversationId != null) append("\"conversation_id\":\"$conversationId\",")
            if (replyTo != null) append("\"reply_to\":\"$replyTo\",")
            if (threadId != null) append("\"thread_id\":\"$threadId\",")
            append("\"content\":[{\"type\":\"text\",\"text\":$escaped}]")
            append("}")
        }
        return rest.insert(
            endpoint, token, "messages", body,
            serializer = ListSerializer(Message.serializer()),
        ).getOrThrow()
    }

    suspend fun editMessage(endpoint: Endpoint, token: String, messageId: String, text: String): Result<Message> = runCatching {
        val escaped = JsonPrimitiveText.quote(text)
        rest.update(
            endpoint, token, "messages", listOf("id" to "eq.$messageId"),
            patch = """{"content":[{"type":"text","text":$escaped}],"updated_at":"${nowIso()}"}""",
            serializer = ListSerializer(Message.serializer()),
        ).getOrThrow().first()
    }

    suspend fun deleteMessage(endpoint: Endpoint, token: String, messageId: String): Result<Unit> = runCatching {
        rest.update(
            endpoint, token, "messages", listOf("id" to "eq.$messageId"),
            patch = """{"is_deleted":true,"updated_at":"${nowIso()}"}""",
            serializer = ListSerializer(Message.serializer()),
        ).getOrThrow()
        Unit
    }

    suspend fun pin(endpoint: Endpoint, token: String, messageId: String, pinned: Boolean): Result<Unit> = runCatching {
        rest.update(
            endpoint, token, "messages", listOf("id" to "eq.$messageId"),
            patch = if (pinned) """{"is_pinned":true,"pinned_at":"${nowIso()}"}""" else """{"is_pinned":false,"pinned_at":null}""",
            serializer = ListSerializer(Message.serializer()),
        ).getOrThrow()
        Unit
    }

    /** The channel's pinned messages, newest pin first, as the pins panel lists them. */
    suspend fun pinned(endpoint: Endpoint, token: String, channelId: String): Result<List<Message>> = runCatching {
        rest.select(
            endpoint, token, "messages", MESSAGE_COLUMNS,
            listOf("channel_id" to "eq.$channelId", "is_pinned" to "eq.true"),
            order = "pinned_at.desc", limit = 50,
            serializer = ListSerializer(Message.serializer()),
        ).getOrThrow()
    }

    // ---- threads --------------------------------------------------------------------------

    suspend fun threads(endpoint: Endpoint, token: String, channelId: String): Result<List<Thread>> = runCatching {
        rest.select(
            endpoint, token, "threads", THREAD_COLUMNS,
            listOf("channel_id" to "eq.$channelId"),
            order = "last_activity_at.desc", limit = 50,
            serializer = ListSerializer(Thread.serializer()),
        ).getOrThrow()
    }

    suspend fun thread(endpoint: Endpoint, token: String, threadId: String): Result<Thread?> = runCatching {
        rest.select(
            endpoint, token, "threads", THREAD_COLUMNS, listOf("id" to "eq.$threadId"),
            serializer = ListSerializer(Thread.serializer()),
        ).getOrThrow().firstOrNull()
    }

    /** Starts a thread off a message, naming its parent, as the web client's Create thread does. */
    suspend fun createThread(
        endpoint: Endpoint,
        token: String,
        channelId: String,
        parentMessageId: String,
        name: String? = null,
        serverId: String? = null,
    ): Result<Thread> = runCatching {
        val body = buildString {
            append("{")
            append("\"channel_id\":\"$channelId\",")
            append("\"parent_message_id\":\"$parentMessageId\",")
            if (serverId != null) append("\"server_id\":\"$serverId\",")
            append("\"name\":${name?.let { JsonPrimitiveText.quote(it) } ?: "null"},")
            append("\"message_count\":0,")
            append("\"last_activity_at\":\"${nowIso()}\"")
            append("}")
        }
        rest.insert(
            endpoint, token, "threads", body,
            serializer = ListSerializer(Thread.serializer()),
        ).getOrThrow().first()
    }

    // ---- reactions ------------------------------------------------------------------------

    suspend fun reactionsFor(
        endpoint: Endpoint,
        token: String,
        messageIds: Collection<String>,
    ): Map<String, List<Reaction>> = runCatching {
        if (messageIds.isEmpty()) return@runCatching emptyMap()
        val rows = rest.select(
            endpoint, token, "reactions", "id,message_id,user_id,emoji,created_at",
            listOf("message_id" to "in.(${messageIds.joinToString(",")})"),
            serializer = ListSerializer(Reaction.serializer()),
        ).getOrThrow()
        rows.groupBy { it.messageId }
    }.getOrDefault(emptyMap())

    suspend fun addReaction(
        endpoint: Endpoint,
        token: String,
        messageId: String,
        userId: String,
        emoji: String,
    ): Result<Unit> = runCatching {
        val body = """{"message_id":"$messageId","user_id":"$userId","emoji":${JsonPrimitiveText.quote(emoji)}}"""
        rest.insert(
            endpoint, token, "reactions", body,
            serializer = ListSerializer(Reaction.serializer()),
        ).getOrThrow()
        Unit
    }

    suspend fun removeReaction(
        endpoint: Endpoint,
        token: String,
        messageId: String,
        userId: String,
        emoji: String,
    ): Result<Unit> = runCatching {
        rest.delete(
            endpoint, token, "reactions",
            listOf(
                "message_id" to "eq.$messageId",
                "user_id" to "eq.$userId",
                "emoji" to "eq.$emoji",
            ),
        ).getOrThrow()
    }

    // ---- direct messages ------------------------------------------------------------------

    /** The account's conversations with the people in them, most recent first. */
    suspend fun conversations(
        endpoint: Endpoint,
        token: String,
        authUserId: String,
        profiles: ProfileRepository,
    ): Result<List<DirectConversation>> = runCatching {
        val mine = rest.select(
            endpoint, token, "conversation_participants", PARTICIPANT_COLUMNS,
            listOf("user_id" to "eq.$authUserId", "left_at" to "is.null"),
            serializer = ListSerializer(ConversationParticipant.serializer()),
        ).getOrThrow()
        val conversationIds = mine.map { it.conversationId }.distinct()
        if (conversationIds.isEmpty()) return@runCatching emptyList()
        val conversations = rest.select(
            endpoint, token, "conversations", CONVERSATION_COLUMNS,
            listOf("id" to "in.(${conversationIds.joinToString(",")})", "is_active" to "eq.true"),
            order = "updated_at.desc",
            serializer = ListSerializer(Conversation.serializer()),
        ).getOrThrow().associateBy { it.id }
        val participants = rest.select(
            endpoint, token, "conversation_participants", PARTICIPANT_COLUMNS,
            listOf("conversation_id" to "in.(${conversationIds.joinToString(",")})", "left_at" to "is.null"),
            serializer = ListSerializer(ConversationParticipant.serializer()),
        ).getOrThrow()
        val byAuthId = profiles.byAuthUserIds(
            endpoint, token,
            participants.map { it.userId }.filter { it != authUserId },
        )
        conversationIds.mapNotNull { id ->
            val conversation = conversations[id] ?: return@mapNotNull null
            val others = participants.filter { it.conversationId == id && it.userId != authUserId }
                .mapNotNull { byAuthId[it.userId] }
            DirectConversation(conversation, others)
        }
    }

    suspend fun conversation(
        endpoint: Endpoint,
        token: String,
        conversationId: String,
        authUserId: String,
        profiles: ProfileRepository,
    ): DirectConversation? = runCatching {
        val conversation = rest.select(
            endpoint, token, "conversations", CONVERSATION_COLUMNS,
            listOf("id" to "eq.$conversationId"),
            serializer = ListSerializer(Conversation.serializer()),
        ).getOrThrow().firstOrNull() ?: return@runCatching null
        val participants = rest.select(
            endpoint, token, "conversation_participants", PARTICIPANT_COLUMNS,
            listOf("conversation_id" to "eq.$conversationId", "left_at" to "is.null"),
            serializer = ListSerializer(ConversationParticipant.serializer()),
        ).getOrThrow()
        val byAuthId = profiles.byAuthUserIds(
            endpoint, token, participants.map { it.userId }.filter { it != authUserId },
        )
        DirectConversation(
            conversation,
            participants.filter { it.userId != authUserId }.mapNotNull { byAuthId[it.userId] },
        )
    }.getOrNull()

    suspend fun directMessages(
        endpoint: Endpoint,
        token: String,
        conversationId: String,
        before: String? = null,
        limit: Int = PAGE,
    ): Result<List<Message>> = runCatching {
        val filters = buildList {
            add("conversation_id" to "eq.$conversationId")
            add("is_deleted" to "eq.false")
            if (before != null) add("created_at" to "lt.$before")
        }
        rest.select(
            endpoint, token, "messages", MESSAGE_COLUMNS, filters,
            order = "created_at.desc", limit = limit,
            serializer = ListSerializer(Message.serializer()),
        ).getOrThrow().reversed()
    }

    /**
     * Opens (or reuses) the direct conversation with someone, as `useDM.ts` does: an existing
     * one-to-one conversation wins, otherwise a new one is created with both participants.
     */
    suspend fun openDirectConversation(
        endpoint: Endpoint,
        token: String,
        myAuthUserId: String,
        otherAuthUserId: String,
    ): Result<String> = runCatching {
        val mine = rest.select(
            endpoint, token, "conversation_participants", PARTICIPANT_COLUMNS,
            listOf("user_id" to "eq.$myAuthUserId", "left_at" to "is.null"),
            serializer = ListSerializer(ConversationParticipant.serializer()),
        ).getOrThrow()
        if (mine.isNotEmpty()) {
            val theirs = rest.select(
                endpoint, token, "conversation_participants", PARTICIPANT_COLUMNS,
                listOf(
                    "user_id" to "eq.$otherAuthUserId",
                    "conversation_id" to "in.(${mine.joinToString(",") { it.conversationId }})",
                    "left_at" to "is.null",
                ),
                serializer = ListSerializer(ConversationParticipant.serializer()),
            ).getOrThrow()
            val candidates = mine.map { it.conversationId }.toSet()
            val direct = theirs.firstOrNull { it.conversationId in candidates }
            if (direct != null) {
                val conversations = rest.select(
                    endpoint, token, "conversations", CONVERSATION_COLUMNS,
                    listOf("id" to "eq.${direct.conversationId}"),
                    serializer = ListSerializer(Conversation.serializer()),
                ).getOrThrow()
                val existing = conversations.firstOrNull()
                if (existing != null && existing.type == "direct") return@runCatching existing.id
            }
        }
        val created = rest.insert(
            endpoint, token, "conversations",
            """{"type":"direct","created_by":"$myAuthUserId","is_active":true}""",
            serializer = ListSerializer(Conversation.serializer()),
        ).getOrThrow().first()
        rest.insert(
            endpoint, token, "conversation_participants",
            """[{"conversation_id":"${created.id}","user_id":"$myAuthUserId","role":"member"},""" +
                """{"conversation_id":"${created.id}","user_id":"$otherAuthUserId","role":"member"}]""",
            serializer = ListSerializer(ConversationParticipant.serializer()),
        ).getOrThrow()
        created.id
    }

    // ---- read state -----------------------------------------------------------------------

    /** The account's unread channels and conversations, from the database's own counts. */
    suspend fun unreadCounts(endpoint: Endpoint, token: String): List<UnreadCountRow> = runCatching {
        rest.rpc(
            endpoint, token, "get_unread_counts",
            serializer = ListSerializer(UnreadCountRow.serializer()),
        ).getOrThrow()
    }.getOrDefault(emptyList())

    suspend fun markChannelRead(endpoint: Endpoint, token: String, channelId: String, messageId: String?) {
        runCatching {
            rest.rpcUnit(
                endpoint, token, "mark_channel_as_read",
                """{"p_channel_id":"$channelId","p_message_id":${messageId?.let { "\"$it\"" } ?: "null"}}""",
            )
        }
    }

    suspend fun markConversationRead(endpoint: Endpoint, token: String, conversationId: String, messageId: String?) {
        runCatching {
            rest.rpcUnit(
                endpoint, token, "mark_conversation_as_read",
                """{"p_conversation_id":"$conversationId","p_message_id":${messageId?.let { "\"$it\"" } ?: "null"}}""",
            )
        }
    }

    // ---- search ---------------------------------------------------------------------------

    /** Full-text search across the channels this account can read, via the search RPC. */
    suspend fun search(endpoint: Endpoint, token: String, query: String, limit: Int = 25): List<Message> = runCatching {
        rest.rpc(
            endpoint, token, "search_messages",
            """{"p_query":${JsonPrimitiveText.quote(query)},"p_limit":$limit}""",
            serializer = ListSerializer(Message.serializer()),
        ).getOrThrow()
    }.getOrDefault(emptyList())

    /** Typing presence is ephemeral: it goes over realtime only, never into a table. */
    fun typingTopic(channelId: String) = "typing:channel:$channelId"

    fun channelMessageTopic(channelId: String) = "messages:channel:$channelId"

    companion object {
        const val PAGE = 50

        private const val MESSAGE_COLUMNS =
            "id,channel_id,conversation_id,thread_id,user_id,bot_id,reply_to,content,is_deleted," +
                "is_system,is_pinned,pinned_at,encrypted,reactions,metadata,media_paths,created_at,updated_at"

        private const val THREAD_COLUMNS =
            "id,channel_id,parent_message_id,server_id,name,message_count,created_at,last_activity_at"

        private const val PARTICIPANT_COLUMNS =
            "id,conversation_id,user_id,role,last_read_at,last_read_message_id,is_muted,left_at"

        private const val CONVERSATION_COLUMNS =
            "id,name,type,created_by,is_active,metadata,created_at,updated_at"
    }
}

/** A JSON string literal for the payloads built by hand above. */
internal object JsonPrimitiveText {
    fun quote(value: String): String = Json.encodeToString(
        kotlinx.serialization.json.JsonPrimitive.serializer(),
        kotlinx.serialization.json.JsonPrimitive(value),
    )
}

internal fun nowIso(): String {
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
    fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return fmt.format(java.util.Date())
}

/** `Profile` is the postgrest shape; this keeps the DM participant resolution readable above. */
private typealias ParticipantProfiles = Map<String, Profile>
