package lol.mony.harmony.data.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The same type groups as src/utils/notificationInbox.ts, passed into the RPC before paging. */
enum class NotificationInboxFilter(val label: String, val types: List<String>?) {
    All("All", null),
    Mentions("Mentions", listOf("mention", "reply", "thread_reply", "activitypub_mention", "activitypub_reply")),
    Social("Social", listOf(
        "activitypub_follow", "activitypub_favorite", "activitypub_reblog", "activitypub_mention",
        "activitypub_reply", "activitypub_quote", "activitypub_reaction", "activitypub_follow_request",
        "activitypub_follow_accepted",
    )),
}

data class NotificationCopy(val title: String, val preview: String?, val context: String?)

/** Plain, non-HTML inbox copy from the structured and legacy payloads used by the web formatter. */
object NotificationFormatter {
    fun format(notification: Notification): NotificationCopy {
        val data = notification.data
        val actor = notification.actor?.name ?: "Someone"
        val location = data.notificationObject("location")
        val channel = location?.notificationText("channel_name") ?: data.notificationText("channel_name")
        val server = location?.notificationText("server_name") ?: data.notificationText("server_name")
            ?: data.notificationObject("server")?.notificationText("name")
        val where = channel?.let { " in #$it" }.orEmpty()
        val message = data.notificationObject("message")
        val isCall = message?.notificationObject("metadata")?.notificationText("type") == "call_started" ||
            notificationContentText(data["preview"] ?: message?.get("content_preview")) == "started a call"
        val title = when (notification.type) {
            "mention" -> "$actor mentioned you$where"
            "reply" -> "$actor replied to your message$where"
            "thread_reply" -> "$actor replied in a thread$where"
            "dm" -> when {
                data.notificationFlag("is_invite") == true -> "$actor added you to ${data.notificationObject("conversation")?.notificationText("name") ?: "a conversation"}"
                isCall -> "$actor is calling you"
                else -> "$actor sent you a message"
            }
            "chat_message" -> if (isCall) "$actor started a call" else "$actor sent a message"
            "channel_message" -> "$actor sent a message$where"
            "reaction" -> "$actor reacted to your message"
            "activitypub_follow" -> "$actor started following you"
            "activitypub_follow_request", "friend_request" -> "$actor wants to follow you"
            "activitypub_follow_accepted" -> "$actor accepted your follow request"
            "activitypub_favorite" -> "$actor favorited your post"
            "activitypub_reblog" -> "$actor reblogged your post"
            "activitypub_mention" -> "$actor mentioned you"
            "activitypub_reply" -> "$actor replied to your post"
            "activitypub_quote" -> "$actor quoted your post"
            "activitypub_reaction" -> "$actor reacted to your post"
            "server_invite" -> "$actor invited you to ${server ?: "a server"}"
            "voice_channel_activity" -> "Voice activity${channel?.let { " in #$it" }.orEmpty()}"
            "server_update" -> data.notificationText("title") ?: "Server update"
            "emoji_added" -> "New emoji: ${data.notificationObject("emoji")?.notificationText("name") ?: "emoji"}"
            "newcomer_message" -> "$actor sent their first message$where"
            "security" -> securityTitle(data.notificationText("event"))
            "move" -> "${data.notificationObject("origin")?.notificationText("display_name", "username") ?: "An account you follow"} moved accounts"
            "moderation_warning" -> "A warning from the moderation team"
            "report_update" -> when (data.notificationText("status")) {
                "investigating" -> "Your report is being reviewed"
                "resolved" -> "Your report has been resolved"
                "dismissed" -> "Your report has been reviewed"
                else -> "Report status updated"
            }
            "server_join_rejected" -> "${server ?: "A server"} declined your join request"
            "server_federation_closed" -> "${server ?: "A server"} stopped federating"
            "server_member_removed" -> "You were removed from ${server ?: "a server"}"
            "server_instance_removed" -> "${server ?: "A server"} is no longer available on this instance"
            "outgoing_webhook_disabled" -> "${data.notificationObject("webhook")?.notificationText("name") ?: "A webhook"} was disabled"
            "discord_migration_admin_change" -> "Administrator role ${data.notificationText("change") ?: "changed"} in ${server ?: "a server"}"
            "discord_migration_paused" -> "Discord sync paused for ${server ?: "a server"}"
            "admin_pending_donation" -> "A donation needs review"
            else -> data.notificationText("title") ?: notification.type.replace('_', ' ').replaceFirstChar { it.titlecase() }
        }
        val preview = when {
            data.notificationFlag("encrypted") == true || message?.notificationFlag("encrypted") == true -> "Encrypted message"
            data.notificationObject("post")?.notificationText("content_warning") != null ->
                "Content warning: ${data.notificationObject("post")?.notificationText("content_warning")}"
            isCall -> "Incoming call"
            notification.isFollowRequest -> notification.actor?.handle?.let { "$it sent you a follow request" }
            notification.type == "activitypub_follow" -> notification.actor?.handle
            notification.type == "activitypub_follow_accepted" -> notification.actor?.handle?.let { "You are now following $it" }
            notification.type == "moderation_warning" -> data.notificationText("text")
            notification.type == "security" -> listOfNotNull(data.notificationText("reason"), data.notificationText("ip")).joinToString(" · ").ifBlank { null }
            notification.type == "move" -> data.notificationObject("target")?.let {
                val target = it.notificationText("handle", "username") ?: return@let null
                if (data.notificationText("follow_status") == "pending") "Follow request sent to $target" else "You are now following $target"
            }
            notification.type == "report_update" -> listOfNotNull(
                data.notificationText("resolved_by_server")?.let { "Reviewed by $it" },
                data.notificationText("resolution_note"),
            ).joinToString(". ").ifBlank { null }
            else -> sequenceOf(
                message?.get("content_preview"), data["post_content"], data.notificationObject("post")?.get("content_preview"),
                data["message_preview"], data["preview"], data["content_preview"], message?.get("content"),
                data.notificationObject("post")?.get("content"), data["content"], data["message"], data["reason"],
                data["last_error"],
            ).mapNotNull(::notificationContentText).firstOrNull { it.isNotBlank() }
        }
        return NotificationCopy(
            title = maskNotificationSpoilers(title),
            preview = preview?.let(::maskNotificationSpoilers),
            context = listOfNotNull(server, channel?.let { "#$it" }).joinToString(" / ").ifBlank { null },
        )
    }

    private fun securityTitle(event: String?): String = when (event) {
        "new_sign_in" -> "New sign-in to your account"
        "mfa_enabled" -> "Two-factor authentication enabled"
        "mfa_disabled" -> "Two-factor authentication disabled"
        "recovery_code_used" -> "A recovery code was used"
        "recovery_codes_regenerated" -> "Recovery codes regenerated"
        "password_changed" -> "Your password was changed"
        else -> "Account security update"
    }
}

/** Content may be text, message parts, or a JSON-encoded parts array. Never show raw objects. */
fun notificationContentText(content: JsonElement?): String? {
    val text = when (content) {
        is JsonPrimitive -> {
            val source = content.contentOrNull ?: return null
            if (source.startsWith('[')) {
                val decoded = runCatching { HarmonyJson.parseToJsonElement(source) }.getOrNull()
                if (decoded is JsonArray) return notificationContentText(decoded)
            }
            source
        }
        is JsonArray -> {
            val parts = content.mapNotNull { it as? JsonObject }
            val poll = parts.firstOrNull { it.notificationText("type") == "poll" && it.notificationText("question") != null }
            if (poll != null) return maskNotificationSpoilers("Poll: ${poll.notificationText("question")}")
            parts.mapNotNull { part ->
                when (part.notificationText("type")) {
                    "text" -> part.notificationText("text")
                    "mention" -> part.notificationText("username")?.let { "@$it" }
                    "hashtag" -> part.notificationText("name")?.let { "#$it" }
                    "emoji" -> (part.notificationObject("emoji")?.notificationText("name") ?: part.notificationText("emoji", "name"))?.let { ":$it:" }
                    "url" -> part.notificationText("label", "text", "url")
                    "sticker" -> part.notificationText("name")?.let { "Sticker: $it" }
                    else -> null
                }
            }.joinToString(" ")
        }
        else -> return null
    }
    return maskNotificationSpoilers(text).trim().takeIf { it.isNotEmpty() }
}

private fun maskNotificationSpoilers(text: String): String =
    text.replace(Regex("\\|\\|.*?\\|\\|", RegexOption.DOT_MATCHES_ALL), "[spoiler]")
