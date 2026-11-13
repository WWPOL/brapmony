package lol.mony.harmony.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Profile ids, not auth-user ids, identify every social relationship and post author. */
@Serializable
data class SocialProfile(
    val id: String,
    val username: String = "",
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("banner_url") val bannerUrl: String? = null,
    val domain: String? = null,
    val bio: String? = null,
    @SerialName("is_local") val isLocal: Boolean = true,
    @SerialName("is_suspended") val isSuspended: Boolean = false,
    @SerialName("followers_count") val followersCount: Int = 0,
    @SerialName("following_count") val followingCount: Int = 0,
    @SerialName("posts_count") val postsCount: Int = 0,
    @SerialName("manually_approves_followers") val manuallyApprovesFollowers: Boolean = false,
) {
    val shownName: String get() = displayName?.takeIf(String::isNotBlank) ?: username.ifBlank { "Account" }
    val handle: String get() = "@$username" + if (!isLocal && !domain.isNullOrBlank()) "@$domain" else ""
}

/** Both table reads and the three timeline RPCs return this shape. Content stays lossless JSON. */
@Serializable
data class SocialPost(
    val id: String,
    @SerialName("author_id") val authorId: String = "",
    val author: SocialProfile? = null,
    val content: JsonElement = JsonArray(emptyList()),
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String? = null,
    val visibility: String = "public",
    @SerialName("content_warning") val contentWarning: String? = null,
    @SerialName("is_sensitive") val isSensitive: Boolean = false,
    @SerialName("is_deleted") val isDeleted: Boolean = false,
    @SerialName("is_local") val isLocal: Boolean = true,
    @SerialName("is_pinned") val isPinned: Boolean = false,
    @SerialName("in_reply_to") val inReplyTo: String? = null,
    @SerialName("reply_context") val replyContext: JsonObject? = null,
    @SerialName("favorites_count") val favoritesCount: Int = 0,
    @SerialName("reblogs_count") val reblogsCount: Int = 0,
    @SerialName("replies_count") val repliesCount: Int = 0,
    @SerialName("is_favorited") val isFavorited: Boolean = false,
    @SerialName("is_reblogged") val isReblogged: Boolean = false,
    @SerialName("is_bookmarked") val isBookmarked: Boolean = false,
    @SerialName("my_interactions") val myInteractions: List<SocialInteraction>? = null,
    @SerialName("media_attachments") val mediaAttachments: List<SocialMedia> = emptyList(),
    val metadata: JsonObject = JsonObject(emptyMap()),
    val reblog: SocialPost? = null,
    @SerialName("reblog_author") val reblogAuthor: SocialProfile? = null,
    val url: String? = null,
    @SerialName("ap_id") val apId: String? = null,
    val language: String = "en",
    val score: Double? = null,
    @SerialName("as_of") val asOf: String? = null,
) {
    val plainText: String get() = socialContentText(content)
    val isQuote: Boolean get() = (metadata["is_quote"] as? JsonPrimitive)?.let {
        it.booleanOrNull == true || it.contentOrNull == "true"
    } == true
    val isBoost: Boolean get() = (metadata["reblog_of"] as? JsonPrimitive)?.contentOrNull != null && !isQuote
    val actionPost: SocialPost get() {
        val original = if (isBoost) reblog else null
        val originalAuthor = reblogAuthor ?: original?.author
        return original?.copy(author = originalAuthor, authorId = original.authorId.ifBlank { originalAuthor?.id.orEmpty() }) ?: this
    }

    /** Direct embeds carry my_interactions; RPCs instead carry the three booleans. */
    fun withInteractionFlags(): SocialPost {
        val types = myInteractions?.map { it.type }?.toSet() ?: return this
        return copy(isFavorited = "favorite" in types, isReblogged = "reblog" in types, isBookmarked = "bookmark" in types)
    }
}

@Serializable
data class SocialMedia(
    val id: String? = null,
    val type: String = "unknown",
    val url: String,
    @SerialName("preview_url") val previewUrl: String? = null,
    val description: String? = null,
    val filename: String? = null,
)

@Serializable
data class SocialInteraction(
    val id: String? = null,
    @SerialName("post_id") val postId: String? = null,
    @SerialName("interaction_type") val type: String,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class SocialFollow(
    val id: String? = null,
    @SerialName("follower_id") val followerId: String = "",
    @SerialName("following_id") val followingId: String = "",
    val status: String = "pending",
    @SerialName("created_at") val createdAt: String = "",
    val profile: SocialProfile? = null,
)

@Serializable
data class SocialList(
    val id: String,
    @SerialName("user_id") val userId: String = "",
    val title: String,
    val description: String? = null,
    @SerialName("replies_policy") val repliesPolicy: String = "list",
    @SerialName("is_exclusive") val isExclusive: Boolean = false,
    @SerialName("is_public") val isPublic: Boolean = false,
)

@Serializable
data class SocialListMember(
    val id: String? = null,
    @SerialName("list_id") val listId: String,
    @SerialName("account_id") val accountId: String,
    val account: SocialProfile? = null,
)

@Serializable
data class SocialHashtag(
    val id: String? = null,
    val tag: String,
    @SerialName("normalized_tag") val normalizedTag: String? = null,
    @SerialName("uses_count") val usesCount: Int = 0,
    @SerialName("unique_users") val uniqueUsers: Int = 0,
)

@Serializable
data class SocialTagEdge(
    @SerialName("post_id") val postId: String,
    @SerialName("created_at") val createdAt: String,
)

enum class SocialTimeline(val label: String) {
    Home("Following"), Local("Local"), Federated("Federated"), Explore("Explore"), Bookmarks("Saved"),
}

/** The cursor always advances over raw rows, including hidden/deleted edge targets. */
data class SocialCursor(val createdAt: String, val id: String, val score: Double? = null, val asOf: String? = null)
data class SocialPage(val posts: List<SocialPost>, val next: SocialCursor? = null)
data class SocialPeoplePage(val people: List<SocialProfile>, val next: SocialCursor? = null)
data class SocialThread(val post: SocialPost, val ancestors: List<SocialPost>, val replies: SocialPage)
data class SocialRelationship(val following: Boolean = false, val pending: Boolean = false)

/** Legacy rows can hold a raw string or a string-encoded MessagePart array. */
fun socialContentParts(content: JsonElement): List<JsonObject> = when (content) {
    is JsonArray -> content.filterIsInstance<JsonObject>()
    is JsonPrimitive -> {
        val text = content.contentOrNull.orEmpty()
        val parsed = if (text.trimStart().startsWith("[")) runCatching { HarmonyJson.parseToJsonElement(text) }.getOrNull() else null
        if (parsed is JsonArray) socialContentParts(parsed)
        else listOf(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(text))))
    }
    else -> emptyList()
}

fun socialContentText(content: JsonElement): String = socialContentParts(content).joinToString("") { part ->
    fun field(key: String) = (part[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    when (field("type")) {
        "text" -> field("text")
        "hashtag" -> "#${field("name")}"
        "mention" -> "@${field("username")}" + if ((part["isLocal"] as? JsonPrimitive)?.booleanOrNull == false && field("domain").isNotEmpty()) "@${field("domain")}" else ""
        "url", "link" -> field("url")
        "emoji" -> (part["emoji"] as? JsonObject)?.get("name")?.let { ":${it.jsonPrimitive.content}:" } ?: field("name").let { ":$it:" }
        "file", "image", "video", "audio" -> "[${field("name").ifBlank { field("type") }}]"
        else -> field("text").ifBlank { "[${field("type").ifBlank { "content" }}]" }
    }
}
