package lol.mony.harmony.data.repo

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.api.RestApi
import lol.mony.harmony.data.model.*

/** Channel, DM and thread messages using Harmony's existing tables and RPCs. */
class ChatRepository(private val rest: RestApi) {
    suspend fun channelMessages(endpoint: Endpoint, token: String, channelId: String,
                                before: String? = null, limit: Int = PAGE): Result<List<Message>> = runCatching {
        rest.select(endpoint, token, "messages", filters = buildList {
            add("channel_id" to "eq.$channelId"); add("thread_id" to "is.null")
            add("is_deleted" to "eq.false")
            before?.let { add("created_at" to "lt.$it") }
        }, order = "created_at.desc,id.desc", limit = limit.coerceIn(1, PAGE),
            serializer = ListSerializer(Message.serializer())).getOrThrow().reversed()
    }

    suspend fun directMessages(endpoint: Endpoint, token: String, conversationId: String,
                               before: String? = null, limit: Int = PAGE): Result<List<Message>> = runCatching {
        rest.select(endpoint, token, "messages", filters = buildList {
            add("conversation_id" to "eq.$conversationId"); add("is_deleted" to "eq.false")
            before?.let { add("created_at" to "lt.$it") }
        }, order = "created_at.desc,id.desc", limit = limit.coerceIn(1, PAGE),
            serializer = ListSerializer(Message.serializer())).getOrThrow().reversed()
    }

    /**
     * A page of a thread's messages, oldest-to-newest, ending at the newest.
     *
     * The newest page is what a thread opens on, so the query orders *descending* and takes the
     * first page, exactly as the web client's `ThreadService.getThreadMessages` does. Ordering
     * ascending and taking the first page instead returns the *oldest* messages, which is why a
     * long thread looked like it had stopped receiving replies.
     */
    suspend fun threadMessages(endpoint: Endpoint, token: String, threadId: String,
                               before: String? = null, limit: Int = PAGE): Result<List<Message>> =
        rest.select(endpoint, token, "messages", filters = buildList {
            add("thread_id" to "eq.$threadId"); add("is_deleted" to "eq.false")
            before?.let { add("created_at" to "lt.$it") }
        }, order = "created_at.desc,id.desc", limit = limit.coerceIn(1, PAGE),
            serializer = ListSerializer(Message.serializer())).map { page -> page.reversed() }

    suspend fun sendChannelMessage(endpoint: Endpoint, token: String, channelId: String,
                                   text: String, replyTo: String? = null, threadId: String? = null,
                                   nonce: String? = null): Result<Message> = runCatching {
        // Fail closed until this client implements Harmony's own encrypted envelope.
        val encryption = rest.rpcValue(endpoint, token, "effective_channel_encryption",
            buildJsonObject { put("p_channel_id", channelId) }.toString(), JsonElement.serializer()).getOrThrow()
        if (requiresEncryption(encryption)) error("This channel requires encryption. Encrypted messaging is not implemented in this client yet; nothing was sent.")
        insertMessage(endpoint, token, text, channelId, null, replyTo, threadId, nonce)
    }

    suspend fun sendDirectMessage(endpoint: Endpoint, token: String, conversationId: String,
                                  text: String, replyTo: String? = null, threadId: String? = null,
                                  nonce: String? = null): Result<Message> = runCatching {
        val encryption = rest.select(endpoint, token, "conversation_encryption_settings",
            filters = listOf("conversation_id" to "eq.$conversationId"),
            serializer = ListSerializer(JsonObject.serializer())).getOrThrow()
        if (encryption.any { (it["encryption_enabled"] as? JsonPrimitive)?.booleanOrNull == true }) {
            error("This conversation requires encryption. Encrypted messaging is not implemented in this client yet; nothing was sent.")
        }
        insertMessage(endpoint, token, text, null, conversationId, replyTo, threadId, nonce)
    }

    /**
     * Inserts one message, carrying the client nonce in `metadata` as the web client does.
     *
     * The nonce is what lets a retry be recognised as the same message: the reference's outbox
     * reads `metadata.client_nonce` back to match an acknowledged row to the row it drew while
     * the send was in flight.
     */
    private suspend fun insertMessage(endpoint: Endpoint, token: String, text: String,
                                      channelId: String?, conversationId: String?, replyTo: String?,
                                      threadId: String?, nonce: String?): Message {
        require(text.isNotBlank()) { "Write a message first." }
        val profileId = ProfileRepository(rest).mine(endpoint, token)?.profile?.id ?: error("Your profile could not be loaded. Nothing was sent.")
        val body = buildJsonObject {
            put("user_id", profileId)
            channelId?.let { put("channel_id", it) }; conversationId?.let { put("conversation_id", it) }
            replyTo?.let { put("reply_to", it) }; threadId?.let { put("thread_id", it) }
            nonce?.let { value ->
                put("metadata", buildJsonObject {
                    put("created_via", "harmony_android")
                    put("client_nonce", value)
                })
            }
            put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) })
        }
        return rest.insert(endpoint, token, "messages", body.toString(),
            serializer = ListSerializer(Message.serializer())).getOrThrow().firstOrNull()
            ?: error("The instance did not accept this message. It may have been blocked by moderation.")
    }

    suspend fun editMessage(endpoint: Endpoint, token: String, messageId: String, text: String): Result<Message> = runCatching {
        require(text.isNotBlank()) { "A message cannot be empty." }
        val original = rest.select(endpoint, token, "messages", filters = listOf("id" to "eq.$messageId"),
            serializer = ListSerializer(Message.serializer())).getOrThrow().firstOrNull()
            ?: error("This message is no longer available.")
        require(!original.encrypted && original.content.all { it.type == "text" }) {
            "Editing encrypted or structured/media messages is not implemented; nothing was changed."
        }
        rest.update(endpoint, token, "messages", listOf("id" to "eq.$messageId"),
            buildJsonObject {
                put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) })
            }.toString(), serializer = ListSerializer(Message.serializer())).getOrThrow().firstOrNull()
            ?: error("This message could not be edited. Check your permissions.")
    }

    suspend fun deleteMessage(endpoint: Endpoint, token: String, messageId: String): Result<Unit> = runCatching {
        val changed = rest.update(endpoint, token, "messages", listOf("id" to "eq.$messageId"),
            buildJsonObject {
                put("is_deleted", true)
                put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "[deleted]") }) })
            }.toString(), serializer = ListSerializer(Message.serializer())).getOrThrow()
        check(changed.isNotEmpty()) { "This message could not be deleted. Check your permissions." }
    }

    suspend fun pin(endpoint: Endpoint, token: String, messageId: String, pinned: Boolean): Result<Unit> =
        rest.rpcUnit(endpoint, token, if (pinned) "pin_message" else "unpin_message",
            buildJsonObject { put("p_message_id", messageId) }.toString())

    suspend fun pinned(endpoint: Endpoint, token: String, channelId: String): Result<List<Message>> = rest.select(
        endpoint, token, "messages", filters = listOf("channel_id" to "eq.$channelId", "is_pinned" to "eq.true"),
        order = "pinned_at.desc", limit = PAGE, serializer = ListSerializer(Message.serializer()))

    suspend fun threads(endpoint: Endpoint, token: String, channelId: String): Result<List<Thread>> = rest.select(
        endpoint, token, "threads", filters = listOf("channel_id" to "eq.$channelId"),
        order = "last_message_at.desc.nullslast,created_at.desc", limit = PAGE, serializer = ListSerializer(Thread.serializer()))

    suspend fun thread(endpoint: Endpoint, token: String, threadId: String): Result<Thread?> = runCatching {
        rest.select(endpoint, token, "threads", filters = listOf("id" to "eq.$threadId"),
            serializer = ListSerializer(Thread.serializer())).getOrThrow().firstOrNull()
    }

    suspend fun createThread(endpoint: Endpoint, token: String, channelId: String, parentMessageId: String,
                             name: String? = null, serverId: String? = null): Result<Thread> = runCatching {
        val id = rest.rpcValue(endpoint, token, "create_thread", buildJsonObject {
            put("p_message_id", parentMessageId); put("p_name", name?.takeIf { it.isNotBlank() } ?: "Discussion")
            put("p_auto_archive_duration", 1440)
        }.toString(), String.serializer()).getOrThrow()
        thread(endpoint, token, id).getOrThrow() ?: error("The new thread could not be loaded.")
    }

    suspend fun reactionsFor(endpoint: Endpoint, token: String, messageIds: Collection<String>): Result<Map<String, List<Reaction>>> = runCatching {
        if (messageIds.isEmpty()) return@runCatching emptyMap()
        rest.select(endpoint, token, "reactions", filters = listOf("message_id" to "in.(${messageIds.take(PAGE).joinToString(",")})"),
            serializer = ListSerializer(Reaction.serializer())).getOrThrow().groupBy { it.messageId }
    }

    suspend fun addReaction(endpoint: Endpoint, token: String, messageId: String, userId: String, emoji: String): Result<Unit> = runCatching {
        rest.insert(endpoint, token, "reactions", buildJsonObject {
            put("message_id", messageId); put("user_id", userId); put("custom_emoji_content", emoji)
        }.toString(), serializer = ListSerializer(Reaction.serializer())).getOrThrow()
        Unit
    }

    suspend fun removeReaction(endpoint: Endpoint, token: String, messageId: String, userId: String, emoji: String): Result<Unit> =
        rest.delete(endpoint, token, "reactions", listOf("message_id" to "eq.$messageId", "user_id" to "eq.$userId", "custom_emoji_content" to "eq.$emoji"))

    suspend fun conversations(endpoint: Endpoint, token: String, profileId: String,
                              profiles: ProfileRepository): Result<List<DirectConversation>> = runCatching {
        val mine = rest.select(endpoint, token, "conversation_participants",
            filters = listOf("user_id" to "eq.$profileId", "left_at" to "is.null", "hidden_at" to "is.null"),
            limit = PAGE, serializer = ListSerializer(ConversationParticipant.serializer())).getOrThrow()
        if (mine.isEmpty()) return@runCatching emptyList()
        val ids = mine.map { it.conversationId }.distinct()
        val rows = rest.select(endpoint, token, "conversations", filters = listOf("id" to "in.(${ids.joinToString(",")})", "is_active" to "eq.true"),
            order = "updated_at.desc", limit = PAGE, serializer = ListSerializer(Conversation.serializer())).getOrThrow()
        val participants = rest.select(endpoint, token, "conversation_participants",
            filters = listOf("conversation_id" to "in.(${ids.joinToString(",")})", "left_at" to "is.null"),
            serializer = ListSerializer(ConversationParticipant.serializer())).getOrThrow()
        val people = profiles.byIds(endpoint, token, participants.map { it.userId }.distinct())
        rows.map { conversation -> DirectConversation(conversation,
            participants.filter { it.conversationId == conversation.id && it.userId != profileId }.mapNotNull { people[it.userId] }) }
    }

    suspend fun conversation(endpoint: Endpoint, token: String, conversationId: String,
                             profileId: String, profiles: ProfileRepository): DirectConversation? =
        conversations(endpoint, token, profileId, profiles).getOrThrow().find { it.conversation.id == conversationId }

    suspend fun openDirectConversation(endpoint: Endpoint, token: String, myProfileId: String, otherProfileId: String): Result<String> =
        rest.rpcValue(endpoint, token, "create_or_get_direct_conversation", buildJsonObject {
            put("user1_uuid", myProfileId); put("user2_uuid", otherProfileId)
        }.toString(), String.serializer())

    suspend fun unreadCounts(endpoint: Endpoint, token: String): List<UnreadCountRow> = rest.rpc(
        endpoint, token, "get_unread_counts", serializer = ListSerializer(UnreadCountRow.serializer())).getOrThrow()

    suspend fun markChannelRead(endpoint: Endpoint, token: String, channelId: String, messageId: String?) =
        rest.rpcUnit(endpoint, token, "mark_channel_as_read", buildJsonObject {
            put("p_channel_id", channelId); put("p_message_id", messageId?.let(::JsonPrimitive) ?: JsonNull)
        }.toString()).getOrThrow()

    suspend fun markConversationRead(endpoint: Endpoint, token: String, conversationId: String, messageId: String?) =
        rest.rpcUnit(endpoint, token, "mark_conversation_as_read", buildJsonObject {
            put("p_conversation_id", conversationId); put("p_message_id", messageId?.let(::JsonPrimitive) ?: JsonNull)
        }.toString()).getOrThrow()

    suspend fun search(endpoint: Endpoint, token: String, query: String, limit: Int = 25,
                       channelId: String? = null): Result<List<Message>> = rest.rpc(endpoint, token, "search_messages", buildJsonObject {
        put("p_query", query); put("p_limit", limit.coerceIn(1, PAGE)); channelId?.let { put("p_channel_id", it) }
    }.toString(), ListSerializer(Message.serializer()))

    companion object { const val PAGE = 50 }
}

internal fun requiresEncryption(element: JsonElement): Boolean = when (element) {
    is JsonArray -> element.any(::requiresEncryption)
    is JsonObject -> (element["messages_encrypted"] as? JsonPrimitive)?.booleanOrNull == true
    is JsonPrimitive -> element.booleanOrNull == true
    else -> false
}

internal object JsonPrimitiveText { fun quote(value: String): String = JsonPrimitive(value).toString() }
internal fun nowIso(): String = java.time.Instant.now().toString()
