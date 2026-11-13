package lol.mony.harmony.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** A row returned by get_user_notifications, which applies block and mute visibility rules. */
@Serializable
data class Notification(
    val id: String,
    @SerialName("user_id") val userId: String,
    // A string intentionally preserves notification types introduced by newer instances.
    val type: String,
    val data: JsonObject = JsonObject(emptyMap()),
    @SerialName("is_read") val isRead: Boolean = false,
    @SerialName("is_clicked") val isClicked: Boolean = false,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("read_at") val readAt: String? = null,
) {
    /** Follows use profile ids, not auth-user ids. The legacy flat payload is still supported. */
    val followerId: String?
        get() = data.notificationText("follower_id") ?: data.notificationObject("follower")?.notificationText("id", "user_id")

    val isFollowRequest: Boolean get() = type == "activitypub_follow_request"

    val actor: NotificationActor?
        get() {
            // Report resolution deliberately hides staff identity unless the resolver opted in.
            if (type == "moderation_warning") return null
            if (type == "report_update") {
                if (data.notificationFlag("show_resolver") != true) return null
                return NotificationActor(
                    id = null,
                    name = data.notificationText("resolver_display_name", "resolver_username") ?: "Admin/Moderator",
                    handle = null,
                    avatarUrl = data.notificationText("resolver_avatar_url"),
                )
            }
            val person = sequenceOf("sender", "actor", "reactor", "author", "user", "follower", "inviter")
                .mapNotNull { data.notificationObject(it) }.firstOrNull()
            val username = person?.notificationText("username") ?: data.notificationText("sender_username", "follower_username", "username")
            val name = person?.notificationText("display_name", "username")
                ?: data.notificationText("sender_display_name", "follower_display_name", "display_name") ?: username
            val id = person?.notificationText("id", "user_id") ?: data.notificationText("from_user_id", "follower_id")
            if (name == null && id == null) return null
            val domain = person?.notificationText("domain") ?: data.notificationText("follower_domain")
            val handle = person?.notificationText("handle") ?: data.notificationText("follower_handle") ?: username?.let {
                if (person?.notificationFlag("is_local") == false && domain != null) "@$it@$domain" else "@$it"
            }
            return NotificationActor(
                id = id,
                name = name ?: "Someone",
                handle = handle?.let { if (it.startsWith('@')) it else "@$it" },
                avatarUrl = person?.notificationText("avatar_url")
                    ?: data.notificationText("sender_avatar_url", "follower_avatar_url", "avatar_url"),
            )
        }

    val target: NotificationTarget?
        get() {
            if (type == "activitypub_follow" || isFollowRequest) {
                (followerId ?: actor?.id)?.let { return NotificationTarget.Profile(it) }
            }
            if (type == "activitypub_follow_accepted") {
                data.notificationObject("sender")?.notificationText("id", "user_id")?.let { return NotificationTarget.Profile(it) }
            }
            if (type.startsWith("activitypub_")) {
                (data.notificationText("post_id") ?: data.notificationObject("post")?.notificationText("id"))?.let {
                    return NotificationTarget.Post(it)
                }
            }
            return null
        }
}

data class NotificationActor(val id: String?, val name: String, val handle: String?, val avatarUrl: String?)

sealed interface NotificationTarget {
    data class Post(val id: String) : NotificationTarget
    data class Profile(val id: String) : NotificationTarget
}

/** Minimal real post data for the notification target preview, independent of the social slice. */
@Serializable
data class NotificationPost(
    val id: String,
    @SerialName("author_id") val authorId: String,
    val content: JsonElement,
    @SerialName("content_warning") val contentWarning: String? = null,
    @SerialName("is_sensitive") val isSensitive: Boolean = false,
    @SerialName("created_at") val createdAt: String,
    val url: String? = null,
    @SerialName("media_attachments") val mediaAttachments: JsonElement = JsonNull,
)

data class NotificationPostPreview(val post: NotificationPost, val author: Profile)

/** A representation is requested after every guarded write so a no-op is not called success. */
@Serializable
data class NotificationWriteResult(val id: String)

fun JsonObject.notificationText(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}

fun JsonObject.notificationObject(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.notificationFlag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
