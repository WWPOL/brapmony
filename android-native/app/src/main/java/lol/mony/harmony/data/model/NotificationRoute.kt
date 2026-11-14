package lol.mony.harmony.data.model

import kotlinx.serialization.json.JsonObject

/**
 * Where a notification opens inside the app.
 *
 * The reference's `resolveNotificationRoute` (`src/utils/notificationRoute.ts`) is the contract:
 * a security notice opens the security settings, a follow request opens the requests list, a
 * follow or a move opens the person's profile, an ActivityPub post opens the post, and anything
 * carrying a conversation opens that conversation. Only after those does it fall back to the
 * channel, the thread, and finally the chat list.
 *
 * This client resolves the same data into a destination it can actually draw. A destination it
 * cannot reach is reported as [Unavailable] with a reason rather than sending the person to a
 * landing page that has nothing to do with what they tapped.
 */
sealed interface NotificationDestination {
    data class Conversation(val conversationId: String, val messageId: String? = null) : NotificationDestination
    data class Channel(val serverId: String?, val channelId: String, val messageId: String? = null) : NotificationDestination
    data class Thread(val serverId: String?, val channelId: String?, val threadId: String, val messageId: String? = null) : NotificationDestination
    data class Post(val postId: String) : NotificationDestination
    data class Profile(val profileId: String?, val handle: String?) : NotificationDestination
    data object Security : NotificationDestination
    data object FollowRequests : NotificationDestination
    /** The chat list: the notification names nothing more specific. */
    data object Chats : NotificationDestination
    /** Nothing in this app can show what the notification is about. */
    data class Unavailable(val reason: String) : NotificationDestination
}

object NotificationRouter {
    /**
     * The destination a notification names, by the reference's precedence.
     *
     * Every read is defensive: notification payloads come from several services and older rows
     * carry flattened keys, so a missing or mistyped field falls through to the next rule
     * instead of throwing.
     */
    fun destination(notification: Notification): NotificationDestination {
        val type = notification.type
        val data = notification.data
        if (type == "security") return NotificationDestination.Security
        if (type == "activitypub_follow_request") return NotificationDestination.FollowRequests

        val postId = data.notificationText("post_id") ?: data.notificationObject("post")?.notificationText("id")
        if (type.startsWith("activitypub_") && postId != null) return NotificationDestination.Post(postId)

        val person = data.notificationObject("follower") ?: data.notificationObject("sender")
            ?: data.notificationObject("target") ?: data.notificationObject("actor")
        if (type == "activitypub_follow" || type == "activitypub_follow_accepted" || type == "move") {
            val id = person?.notificationText("id", "user_id") ?: notification.followerId
            val handle = person?.notificationText("username") ?: data.notificationText("username")
            if (id != null || handle != null) return NotificationDestination.Profile(id, handle)
        }

        val messageId = data.notificationObject("message")?.notificationText("id")
            ?: data.notificationText("message_id")
        val conversationId = data.notificationObject("conversation")?.notificationText("id")
            ?: data.notificationText("conversation_id")
        if (conversationId != null) return NotificationDestination.Conversation(conversationId, messageId)

        val serverId = data.notificationObject("location")?.notificationText("server_id")
            ?: data.notificationText("server_id")
        val channelId = data.notificationObject("location")?.notificationText("channel_id")
            ?: data.notificationText("channel_id")
        val threadId = data.notificationObject("thread")?.notificationText("id")
            ?: data.notificationText("thread_id")
        if (threadId != null) return NotificationDestination.Thread(serverId, channelId, threadId, messageId)
        if (channelId != null) return NotificationDestination.Channel(serverId, channelId, messageId)

        if (type.startsWith("activitypub_")) return NotificationDestination.Chats
        return NotificationDestination.Chats
    }

    /**
     * Whether the payload names something this app can open.
     *
     * A notification with no target is not a failure -- a follower count or a system notice
     * legitimately names nothing -- so this only refuses destinations that were *claimed* and
     * cannot be resolved.
     */
    fun isOpenable(destination: NotificationDestination): Boolean = destination !is NotificationDestination.Unavailable
}

/**
 * The channel a notification's Android notification goes to, so a person can silence one kind
 * without silencing the rest. The ids are stable strings the system stores against the app's
 * settings, so they must never be renamed without a migration.
 */
object NotificationChannelIds {
    const val MESSAGES = "harmony-messages"
    const val MENTIONS = "harmony-mentions"
    const val SOCIAL = "harmony-social"
    const val CALLS = "harmony-calls"

    /** The channel a notification belongs to, from its type. */
    fun of(type: String): String = when {
        type == "dm" || type == "chat_message" || type == "channel_message" -> MESSAGES
        type == "mention" || type == "activitypub_mention" -> MENTIONS
        type == "incoming_call" || type == "missed_call" -> CALLS
        else -> SOCIAL
    }
}

/** What the app can say about the system's notification permission. */
enum class NotificationPermissionState {
    /** Not asked yet; the app may ask once, at a moment the request makes sense. */
    NotAsked,
    /** Granted; delivery is the app's job from here. */
    Granted,
    /**
     * Refused. The app must not ask again: Android stops showing the prompt, and repeating it
     * is the nagging the platform guidance forbids. The settings route is the way back.
     */
    Denied,
    /** A channel the person turned off; the app respects it and says so rather than re-enabling. */
    ChannelsDisabled,
}
