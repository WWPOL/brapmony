package lol.mony.harmony.data.repo

import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.api.RestApi
import lol.mony.harmony.data.model.Notification
import lol.mony.harmony.data.model.NotificationPost
import lol.mony.harmony.data.model.NotificationPostPreview
import lol.mony.harmony.data.model.NotificationWriteResult
import lol.mony.harmony.data.model.Profile

/**
 * The inbox uses the RPC, never a notifications SELECT fallback: the RPC is where the server
 * hides blocked/muted senders and muted channels/categories/conversations. Writes are also
 * scoped to the active profile, in addition to the server's RLS.
 *
 * RestApi is synchronous. Every public suspend boundary moves its complete request onto IO;
 * errors are rethrown, including empty representations from guarded single-row updates.
 */
class NotificationRepository(private val rest: RestApi) {
    suspend fun fetchNotifications(
        endpoint: Endpoint,
        token: String,
        profileId: String,
        limit: Int = 20,
        offset: Int = 0,
        unreadOnly: Boolean = false,
        notificationTypes: List<String>? = null,
    ): List<Notification> = withContext(Dispatchers.IO) {
        require(profileId.isNotBlank()) { "A profile is required to load notifications." }
        require(limit in 1..100) { "Notification page size must be between 1 and 100." }
        require(offset >= 0) { "Notification offset cannot be negative." }
        val args = buildJsonObject {
            put("p_user_id", profileId)
            put("p_limit", limit)
            put("p_offset", offset)
            put("p_unread_only", unreadOnly)
            put("p_notification_types", notificationTypes?.let { JsonArray(it.map(::JsonPrimitive)) } ?: JsonNull)
        }
        rest.rpc(endpoint, token, "get_user_notifications", args.toString(), ListSerializer(Notification.serializer()))
            .getOrThrow()
    }

    suspend fun markAsRead(
        endpoint: Endpoint, token: String, profileId: String, notificationId: String,
    ): Notification = withContext(Dispatchers.IO) {
        updateNotification(endpoint, token, profileId, notificationId, buildJsonObject {
            put("is_read", true)
            put("read_at", Instant.now().toString())
        })
    }

    suspend fun markAsUnread(
        endpoint: Endpoint, token: String, profileId: String, notificationId: String,
    ): Notification = withContext(Dispatchers.IO) {
        updateNotification(endpoint, token, profileId, notificationId, buildJsonObject {
            put("is_read", false)
            put("read_at", JsonNull)
        })
    }

    /** Opening a notification marks both read and clicked in one write. */
    suspend fun markAsClicked(
        endpoint: Endpoint, token: String, profileId: String, notificationId: String,
    ): Notification = withContext(Dispatchers.IO) {
        updateNotification(endpoint, token, profileId, notificationId, buildJsonObject {
            put("is_clicked", true)
            put("is_read", true)
            put("read_at", Instant.now().toString())
        })
    }

    suspend fun markAllAsRead(endpoint: Endpoint, token: String, profileId: String): Int = withContext(Dispatchers.IO) {
        require(profileId.isNotBlank())
        rest.update(
            endpoint, token, "notifications",
            filters = listOf("user_id" to "eq.$profileId", "is_read" to "eq.false"),
            patch = buildJsonObject {
                put("is_read", true)
                put("read_at", Instant.now().toString())
            }.toString(),
            returning = "id",
            serializer = ListSerializer(NotificationWriteResult.serializer()),
        ).getOrThrow().size
    }

    suspend fun deleteNotification(
        endpoint: Endpoint, token: String, profileId: String, notificationId: String,
    ): Unit = withContext(Dispatchers.IO) {
        require(profileId.isNotBlank() && notificationId.isNotBlank())
        rest.delete(endpoint, token, "notifications", ownedNotification(profileId, notificationId)).getOrThrow()
    }

    suspend fun deleteAllNotifications(endpoint: Endpoint, token: String, profileId: String): Unit = withContext(Dispatchers.IO) {
        require(profileId.isNotBlank())
        rest.delete(endpoint, token, "notifications", listOf("user_id" to "eq.$profileId")).getOrThrow()
    }

    /**
     * Mirrors CoreInteractionService. Rejection is an UPDATE, not DELETE: the federation
     * backend needs the pending -> rejected transition to send a Reject. The DB trigger
     * removes corresponding request notifications after either response.
     */
    suspend fun respondToFollowRequest(
        endpoint: Endpoint, token: String, profileId: String, followerId: String, accept: Boolean,
    ): Unit = withContext(Dispatchers.IO) {
        require(profileId.isNotBlank() && followerId.isNotBlank())
        val rows = rest.update(
            endpoint, token, "follows",
            filters = listOf(
                "follower_id" to "eq.$followerId",
                "following_id" to "eq.$profileId",
                "status" to "eq.pending",
            ),
            patch = buildJsonObject {
                put("status", if (accept) "accepted" else "rejected")
                if (accept) put("accepted_at", Instant.now().toString())
            }.toString(),
            returning = "id",
            serializer = ListSerializer(NotificationWriteResult.serializer()),
        ).getOrThrow()
        check(rows.isNotEmpty()) { "Follow request not found or already processed. Refresh the inbox." }
    }

    suspend fun fetchProfilePreview(endpoint: Endpoint, token: String, profileId: String): Profile = withContext(Dispatchers.IO) {
        loadProfile(endpoint, token, profileId)
    }

    suspend fun fetchPostPreview(endpoint: Endpoint, token: String, postId: String): NotificationPostPreview = withContext(Dispatchers.IO) {
        require(postId.isNotBlank())
        val post = rest.select(
            endpoint, token, "posts",
            columns = "id,author_id,content,content_warning,is_sensitive,created_at,url,media_attachments",
            filters = listOf("id" to "eq.$postId", "is_deleted" to "eq.false"),
            limit = 1,
            serializer = ListSerializer(NotificationPost.serializer()),
        ).getOrThrow().singleOrNull() ?: error("This post was deleted or is not visible to this account.")
        NotificationPostPreview(post, loadProfile(endpoint, token, post.authorId))
    }

    private suspend fun updateNotification(
        endpoint: Endpoint, token: String, profileId: String, notificationId: String, patch: JsonObject,
    ): Notification {
        require(profileId.isNotBlank() && notificationId.isNotBlank())
        return rest.update(
            endpoint, token, "notifications",
            filters = ownedNotification(profileId, notificationId),
            patch = patch.toString(),
            serializer = ListSerializer(Notification.serializer()),
        ).getOrThrow().singleOrNull() ?: error("Notification no longer exists. Refresh the inbox.")
    }

    private fun ownedNotification(profileId: String, notificationId: String) =
        listOf("user_id" to "eq.$profileId", "id" to "eq.$notificationId")

    private suspend fun loadProfile(endpoint: Endpoint, token: String, profileId: String): Profile {
        require(profileId.isNotBlank())
        return rest.select(
            endpoint, token, "profiles",
            columns = "id,username,display_name,avatar_url,bio,pronouns,domain,is_local",
            filters = listOf("id" to "eq.$profileId"),
            limit = 1,
            serializer = ListSerializer(Profile.serializer()),
        ).getOrThrow().singleOrNull() ?: error("This profile is no longer available to this account.")
    }
}
