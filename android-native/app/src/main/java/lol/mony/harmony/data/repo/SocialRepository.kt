package lol.mony.harmony.data.repo

import java.net.URI
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.api.RestApi
import lol.mony.harmony.data.model.*

/**
 * The same PostgREST operations as CorePostService, CoreInteractionService, activityPubService,
 * TrendingService and useActivityPub. No fallback turns a denied/failed request into an empty feed.
 * Every public suspend operation confines the synchronous REST client to Dispatchers.IO.
 */
class SocialRepository(private val api: RestApi) {
    private val postRows = ListSerializer(SocialPost.serializer())
    private val profileRows = ListSerializer(SocialProfile.serializer())
    private val interactionRows = ListSerializer(SocialInteraction.serializer())
    private val followRows = ListSerializer(SocialFollow.serializer())
    private val listRows = ListSerializer(SocialList.serializer())
    private val memberRows = ListSerializer(SocialListMember.serializer())
    private val objectRows = ListSerializer(JsonObject.serializer())
    private val postColumns = "*,author:profiles!posts_author_id_fkey(*),my_interactions:post_interactions!left(interaction_type)"

    suspend fun timeline(
        endpoint: Endpoint, token: String, profileId: String, timeline: SocialTimeline,
        cursor: SocialCursor? = null, limit: Int = 20,
    ): SocialPage = withContext(Dispatchers.IO) {
        validId(profileId)
        val size = limit.coerceIn(1, 50)
        if (timeline == SocialTimeline.Bookmarks) return@withContext bookmarks(endpoint, token, profileId, cursor, size)
        val args = buildJsonObject {
            put("p_limit", size + 1)
            when (timeline) {
                SocialTimeline.Home -> put("p_before", cursor?.createdAt?.let(::JsonPrimitive) ?: JsonNull)
                SocialTimeline.Local, SocialTimeline.Federated -> {
                    put("p_user_id", profileId)
                    put("p_max_id", cursor?.id?.also(::validId)?.let(::JsonPrimitive) ?: JsonNull)
                    if (timeline == SocialTimeline.Local) put("p_timeline_type", "local")
                }
                SocialTimeline.Explore -> {
                    put("p_hours", 24); put("p_media_only", false); put("p_local_only", false)
                    put("p_domain", JsonNull); put("p_offset", 0)
                    put("p_as_of", cursor?.asOf?.let(::JsonPrimitive) ?: JsonNull)
                    if (cursor != null) {
                        put("p_after_score", cursor.score ?: error("Missing trending cursor score"))
                        put("p_after_created_at", cursor.createdAt); put("p_after_id", cursor.id.also(::validId))
                    }
                }
                SocialTimeline.Bookmarks -> Unit
            }
        }
        val function = when (timeline) {
            SocialTimeline.Home -> "get_home_timeline_page"
            SocialTimeline.Local -> "get_enhanced_timeline_posts"
            SocialTimeline.Federated -> "get_federated_timeline"
            SocialTimeline.Explore -> "get_trending_posts"
            SocialTimeline.Bookmarks -> error("Handled above")
        }
        val rows = api.rpc(endpoint, token, function, args.toString(), postRows).getOrThrow()
        page(rows, size)
    }

    suspend fun post(endpoint: Endpoint, token: String, profileId: String, postId: String): SocialPost = withContext(Dispatchers.IO) {
        readPost(endpoint, token, profileId, postId) ?: error("Post not found, deleted, or hidden by the instance's access rules.")
    }

    /** Ancestors are read as the caller; unreadable/deleted ancestors stop the chain, never leak it. */
    suspend fun thread(endpoint: Endpoint, token: String, profileId: String, postId: String): SocialThread = withContext(Dispatchers.IO) {
        val main = post(endpoint, token, profileId, postId)
        val ancestors = mutableListOf<SocialPost>()
        val seen = mutableSetOf(postId)
        var parentId = main.inReplyTo
        while (parentId != null && ancestors.size < 20 && seen.add(parentId)) {
            val parent = readPost(endpoint, token, profileId, parentId) ?: break
            ancestors.add(0, parent)
            parentId = parent.inReplyTo
        }
        SocialThread(main, ancestors, replies(endpoint, token, profileId, postId))
    }

    suspend fun replies(
        endpoint: Endpoint, token: String, profileId: String, postId: String,
        cursor: SocialCursor? = null, limit: Int = 20,
    ): SocialPage = withContext(Dispatchers.IO) {
        validId(postId)
        val size = limit.coerceIn(1, 50)
        val filters = mutableListOf("in_reply_to" to "eq.$postId", "is_deleted" to "eq.false")
        addCursor(filters, cursor, ascending = true)
        val rows = selectPosts(endpoint, token, profileId, filters, size + 1, "created_at.asc,id.asc")
        page(rows, size)
    }

    /** Text, tags and known account mentions become MessageParts; server triggers link their ids. */
    suspend fun publish(
        endpoint: Endpoint, token: String, profileId: String, text: String,
        visibility: String = "public", contentWarning: String = "", sensitive: Boolean = false,
        replyTo: String? = null,
    ): SocialPost = withContext(Dispatchers.IO) {
        validId(profileId); replyTo?.let(::validId)
        require(visibility in setOf("public", "unlisted", "followers", "direct")) { "Choose a valid visibility." }
        validateText(endpoint, token, text)
        val content = parseContent(endpoint, token, text)
        val row = buildJsonObject {
            put("author_id", profileId); put("content", content); put("visibility", visibility)
            put("content_warning", contentWarning.trim().takeIf(String::isNotEmpty)?.let(::JsonPrimitive) ?: JsonNull)
            put("is_sensitive", sensitive); put("in_reply_to", replyTo?.let(::JsonPrimitive) ?: JsonNull)
            put("is_local", true); put("is_federated", true); put("language", "en")
            put("metadata", buildJsonObject { put("created_via", "harmony_android"); put("content_format", "message_parts_v1") })
        }
        api.insert(endpoint, token, "posts", row.toString(), "*,author:profiles!posts_author_id_fkey(*)", postRows)
            .getOrThrow().singleOrNull() ?: error("The instance did not confirm that the post was published.")
    }

    suspend fun edit(
        endpoint: Endpoint, token: String, profileId: String, original: SocialPost,
        text: String, contentWarning: String, sensitive: Boolean,
    ): SocialPost = withContext(Dispatchers.IO) {
        require(original.authorId == profileId) { "Only the author can edit a post." }
        require(socialContentParts(original.content).all { it["type"]?.toString() in setOf("\"text\"", "\"mention\"", "\"hashtag\"", "\"url\"", "\"link\"") }) {
            "This post contains rich content the text editor cannot preserve. Edit it in the web client."
        }
        validateText(endpoint, token, text)
        val patch = buildJsonObject {
            put("content", parseContent(endpoint, token, text))
            put("content_warning", contentWarning.trim().takeIf(String::isNotEmpty)?.let(::JsonPrimitive) ?: JsonNull)
            put("is_sensitive", sensitive); put("updated_at", Instant.now().toString())
        }
        api.update(endpoint, token, "posts", ownedPost(original.id, profileId), patch.toString(), "*,author:profiles!posts_author_id_fkey(*)", postRows)
            .getOrThrow().singleOrNull()?.copy(isFavorited = original.isFavorited, isReblogged = original.isReblogged, isBookmarked = original.isBookmarked)
            ?: error("Post not updated: it is no longer yours, or the instance denied access.")
    }

    suspend fun deletePost(endpoint: Endpoint, token: String, profileId: String, postId: String): Unit = withContext(Dispatchers.IO) {
        val patch = buildJsonObject {
            put("is_deleted", true); put("deleted_at", Instant.now().toString())
            put("content", JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", "[deleted]") })))
        }
        val result = api.update(endpoint, token, "posts", ownedPost(postId, profileId), patch.toString(), "id", objectRows).getOrThrow()
        check(result.isNotEmpty()) { "Post not deleted: it is no longer yours, or the instance denied access." }
    }

    /** Setting desired state avoids a second tap racing a probe-based toggle. Counts are read back. */
    suspend fun setInteraction(
        endpoint: Endpoint, token: String, profileId: String, postId: String, type: String, enabled: Boolean,
    ): SocialPost = withContext(Dispatchers.IO) {
        require(type in setOf("favorite", "bookmark")) { "Use setBoost to change boosts." }
        validId(profileId); validId(postId)
        val filters = interactionFilters(profileId, postId, type)
        val held = api.select(endpoint, token, "post_interactions", "id,interaction_type", filters, serializer = interactionRows).getOrThrow()
        if (enabled && held.isEmpty()) {
            val row = buildJsonObject { put("user_id", profileId); put("post_id", postId); put("interaction_type", type); put("is_local", true) }
            api.insert(endpoint, token, "post_interactions", row.toString(), serializer = interactionRows).getOrThrow()
        } else if (!enabled && held.isNotEmpty()) {
            api.delete(endpoint, token, "post_interactions", filters).getOrThrow()
        }
        post(endpoint, token, profileId, postId)
    }

    /** A boost is both a 'reblog' interaction and an Announce posts row, as on the web. */
    suspend fun setBoost(
        endpoint: Endpoint, token: String, profileId: String, postId: String, enabled: Boolean,
    ): SocialPost = withContext(Dispatchers.IO) {
        val target = post(endpoint, token, profileId, postId).actionPost
        validId(target.id); validId(profileId)
        val boosts = api.select(endpoint, token, "posts", "*", listOf(
            "author_id" to "eq.$profileId", "metadata->>reblog_of" to "eq.${target.id}", "is_deleted" to "eq.false",
        ), serializer = postRows).getOrThrow().filterNot { it.isQuote }
        val filters = interactionFilters(profileId, target.id, "reblog")
        val held = api.select(endpoint, token, "post_interactions", "id,interaction_type", filters, serializer = interactionRows).getOrThrow()
        if (enabled) {
            require(target.visibility in setOf("public", "unlisted")) { "Only public or unlisted posts can be boosted." }
            // The server copies the canonical content and reblog snapshot (guard_post_client_write).
            if (boosts.isEmpty()) {
                val row = buildJsonObject {
                    put("author_id", profileId); put("content", target.content); put("visibility", target.visibility)
                    put("is_local", true); put("is_federated", true)
                    put("metadata", buildJsonObject { put("reblog_of", target.id) })
                }
                api.insert(endpoint, token, "posts", row.toString(), serializer = postRows).getOrThrow()
            }
            if (held.isEmpty()) {
                val row = buildJsonObject { put("user_id", profileId); put("post_id", target.id); put("interaction_type", "reblog"); put("is_local", true) }
                api.insert(endpoint, token, "post_interactions", row.toString(), serializer = interactionRows).getOrThrow()
            }
        } else {
            // Never retract quote posts, even when they carry the same reblog_of id.
            for (boost in boosts) {
                val patch = buildJsonObject { put("is_deleted", true); put("deleted_at", Instant.now().toString()) }
                val changed = api.update(endpoint, token, "posts", ownedPost(boost.id, profileId), patch.toString(), "id", objectRows).getOrThrow()
                check(changed.isNotEmpty()) { "A boost could not be retracted. Refresh before trying again." }
            }
            if (held.isNotEmpty()) api.delete(endpoint, token, "post_interactions", filters).getOrThrow()
        }
        // Partial multi-request failures propagate; the UI offers refresh, never claims success.
        post(endpoint, token, profileId, target.id)
    }

    suspend fun bookmarks(
        endpoint: Endpoint, token: String, profileId: String, cursor: SocialCursor? = null, limit: Int = 20,
    ): SocialPage = withContext(Dispatchers.IO) {
        validId(profileId)
        val size = limit.coerceIn(1, 50)
        val filters = mutableListOf("user_id" to "eq.$profileId", "interaction_type" to "eq.bookmark")
        addCursor(filters, cursor)
        val rows = api.select(endpoint, token, "post_interactions", "id,post_id,interaction_type,created_at", filters,
            "created_at.desc,id.desc", size + 1, serializer = interactionRows).getOrThrow()
        val included = rows.take(size)
        val posts = postsByIds(endpoint, token, profileId, included.mapNotNull { it.postId })
        val last = included.lastOrNull()
        SocialPage(posts, if (rows.size > size && last?.id != null) SocialCursor(last.createdAt, last.id) else null)
    }

    suspend fun profile(endpoint: Endpoint, token: String, profileId: String): SocialProfile = withContext(Dispatchers.IO) {
        validId(profileId)
        api.select(endpoint, token, "profiles", filters = listOf("id" to "eq.$profileId"), limit = 1, serializer = profileRows)
            .getOrThrow().singleOrNull() ?: error("Account not found or unavailable.")
    }

    suspend fun profilePosts(
        endpoint: Endpoint, token: String, viewerId: String, authorId: String, cursor: SocialCursor? = null, limit: Int = 20,
    ): SocialPage = withContext(Dispatchers.IO) {
        validId(authorId)
        val size = limit.coerceIn(1, 50)
        val filters = mutableListOf("author_id" to "eq.$authorId", "is_deleted" to "eq.false", "visibility" to "in.(public,unlisted,followers)")
        addCursor(filters, cursor)
        page(selectPosts(endpoint, token, viewerId, filters, size + 1), size)
    }

    suspend fun relationship(endpoint: Endpoint, token: String, profileId: String, targetId: String): SocialRelationship = withContext(Dispatchers.IO) {
        validId(profileId); validId(targetId)
        val follow = api.select(endpoint, token, "follows", "*", listOf("follower_id" to "eq.$profileId", "following_id" to "eq.$targetId"), limit = 1, serializer = followRows).getOrThrow().singleOrNull()
        SocialRelationship(follow?.status == "accepted", follow?.status == "pending")
    }

    suspend fun setFollowing(endpoint: Endpoint, token: String, profileId: String, targetId: String, enabled: Boolean): SocialRelationship = withContext(Dispatchers.IO) {
        validId(profileId); validId(targetId)
        require(profileId != targetId) { "You cannot follow yourself." }
        val filters = listOf("follower_id" to "eq.$profileId", "following_id" to "eq.$targetId")
        val existing = api.select(endpoint, token, "follows", "*", filters, serializer = followRows).getOrThrow().singleOrNull()
        if (!enabled || existing?.status == "rejected") api.delete(endpoint, token, "follows", filters).getOrThrow()
        if (enabled && (existing == null || existing.status == "rejected")) {
            val row = buildJsonObject { put("follower_id", profileId); put("following_id", targetId) }
            val follow = api.insert(endpoint, token, "follows", row.toString(), serializer = followRows).getOrThrow().singleOrNull()
                ?: error("The instance did not confirm your follow request.")
            return@withContext SocialRelationship(follow.status == "accepted", follow.status == "pending")
        }
        if (enabled) SocialRelationship(existing?.status == "accepted", existing?.status == "pending") else SocialRelationship()
    }

    suspend fun people(
        endpoint: Endpoint, token: String, profileId: String, followers: Boolean,
        pending: Boolean = false, cursor: SocialCursor? = null, limit: Int = 20,
    ): SocialPeoplePage = withContext(Dispatchers.IO) {
        validId(profileId)
        val size = limit.coerceIn(1, 50)
        val key = if (followers) "following_id" else "follower_id"
        val relation = if (followers) "follows_follower_id_fkey" else "follows_following_id_fkey"
        val filters = mutableListOf(key to "eq.$profileId", "status" to if (pending) "eq.pending" else "eq.accepted")
        addCursor(filters, cursor)
        val rows = api.select(endpoint, token, "follows", "*,profile:profiles!$relation(*)", filters,
            "created_at.desc,id.desc", size + 1, serializer = followRows).getOrThrow()
        val last = rows.take(size).lastOrNull()
        SocialPeoplePage(rows.take(size).mapNotNull { it.profile }, if (rows.size > size && last?.id != null) SocialCursor(last.createdAt, last.id) else null)
    }

    suspend fun respondToFollowRequest(endpoint: Endpoint, token: String, profileId: String, followerId: String, accept: Boolean): Unit = withContext(Dispatchers.IO) {
        validId(profileId); validId(followerId)
        val patch = buildJsonObject {
            put("status", if (accept) "accepted" else "rejected")
            if (accept) put("accepted_at", Instant.now().toString())
        }
        val rows = api.update(endpoint, token, "follows", listOf("following_id" to "eq.$profileId", "follower_id" to "eq.$followerId", "status" to "eq.pending"),
            patch.toString(), "id", objectRows).getOrThrow()
        check(rows.isNotEmpty()) { "Follow request is already handled or the instance denied access." }
    }

    suspend fun searchPeople(endpoint: Endpoint, token: String, query: String): List<SocialProfile> = withContext(Dispatchers.IO) {
        val term = query.trim().removePrefix("@").take(80)
        require(term.isNotBlank()) { "Enter an account name." }
        // Literal contains; user-controlled commas and percent signs never enter an or expression.
        val escaped = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        api.select(endpoint, token, "profiles", filters = listOf("username" to "ilike.%$escaped%", "is_suspended" to "eq.false"),
            order = "username.asc", limit = 30, serializer = profileRows).getOrThrow()
    }

    suspend fun lists(endpoint: Endpoint, token: String, profileId: String): List<SocialList> = withContext(Dispatchers.IO) {
        validId(profileId)
        api.select(endpoint, token, "user_lists", filters = listOf("user_id" to "eq.$profileId"), order = "created_at.desc", serializer = listRows).getOrThrow()
    }

    suspend fun saveList(
        endpoint: Endpoint, token: String, profileId: String, title: String, description: String,
        listId: String? = null, repliesPolicy: String = "list", exclusive: Boolean = false, public: Boolean = false,
    ): SocialList = withContext(Dispatchers.IO) {
        validId(profileId)
        require(title.isNotBlank()) { "Give the list a name." }
        require(repliesPolicy in setOf("followed", "list", "none")) { "Choose a valid replies policy." }
        val row = buildJsonObject {
            if (listId == null) put("user_id", profileId)
            put("title", title.trim()); put("description", description.trim().takeIf(String::isNotEmpty)?.let(::JsonPrimitive) ?: JsonNull)
            put("replies_policy", repliesPolicy); put("is_exclusive", exclusive); put("is_public", public)
        }
        val rows = if (listId == null) api.insert(endpoint, token, "user_lists", row.toString(), serializer = listRows)
        else api.update(endpoint, token, "user_lists", listOf("id" to "eq.${listId.also(::validId)}", "user_id" to "eq.$profileId"), row.toString(), serializer = listRows)
        rows.getOrThrow().singleOrNull() ?: error("List not saved: the instance did not confirm the change.")
    }

    suspend fun deleteList(endpoint: Endpoint, token: String, profileId: String, listId: String): Unit = withContext(Dispatchers.IO) {
        validId(profileId); validId(listId)
        // Verify ownership first, since PostgREST DELETE returns no row when RLS denies it.
        check(lists(endpoint, token, profileId).any { it.id == listId }) { "List not found or not owned by you." }
        api.delete(endpoint, token, "user_lists", listOf("id" to "eq.$listId", "user_id" to "eq.$profileId")).getOrThrow()
        check(lists(endpoint, token, profileId).none { it.id == listId }) { "The instance did not delete the list." }
    }

    suspend fun listMembers(endpoint: Endpoint, token: String, listId: String): List<SocialListMember> = withContext(Dispatchers.IO) {
        validId(listId)
        api.select(endpoint, token, "user_list_members", "*,account:profiles(*)", listOf("list_id" to "eq.$listId"),
            "created_at.desc", serializer = memberRows).getOrThrow()
    }

    suspend fun setListMember(endpoint: Endpoint, token: String, profileId: String, listId: String, accountId: String, enabled: Boolean): Unit = withContext(Dispatchers.IO) {
        validId(listId); validId(accountId)
        check(lists(endpoint, token, profileId).any { it.id == listId }) { "You can only change your own lists." }
        val filters = listOf("list_id" to "eq.$listId", "account_id" to "eq.$accountId")
        if (enabled) {
            check(relationship(endpoint, token, profileId, accountId).following) { "Follow this account before adding it to a list." }
            if (listMembers(endpoint, token, listId).none { it.accountId == accountId }) {
                val row = buildJsonObject { put("list_id", listId); put("account_id", accountId) }
                api.insert(endpoint, token, "user_list_members", row.toString(), serializer = memberRows).getOrThrow()
            }
        } else api.delete(endpoint, token, "user_list_members", filters).getOrThrow()
        check(listMembers(endpoint, token, listId).any { it.accountId == accountId } == enabled) { "The instance did not confirm the membership change." }
    }

    suspend fun listTimeline(endpoint: Endpoint, token: String, profileId: String, listId: String, cursor: SocialCursor? = null, limit: Int = 20): SocialPage = withContext(Dispatchers.IO) {
        val members = listMembers(endpoint, token, listId)
        if (members.isEmpty()) return@withContext SocialPage(emptyList())
        val ids = members.map { it.accountId.also(::validId) }
        val size = limit.coerceIn(1, 50)
        val filters = mutableListOf("author_id" to "in.(${ids.joinToString(",")})", "is_deleted" to "eq.false", "visibility" to "in.(public,unlisted)")
        addCursor(filters, cursor)
        page(selectPosts(endpoint, token, profileId, filters, size + 1), size)
    }

    suspend fun trendingHashtags(endpoint: Endpoint, token: String): List<SocialHashtag> = withContext(Dispatchers.IO) {
        api.rpc(endpoint, token, "get_trending_hashtags", "{\"p_hours\":168,\"p_limit\":20}", ListSerializer(SocialHashtag.serializer())).getOrThrow()
    }

    suspend fun hashtagPosts(endpoint: Endpoint, token: String, profileId: String, tag: String, cursor: SocialCursor? = null, limit: Int = 20): SocialPage = withContext(Dispatchers.IO) {
        val normalized = tag.trim().removePrefix("#").lowercase(java.util.Locale.ROOT)
        require(normalized.isNotEmpty()) { "Enter a hashtag." }
        val tags = api.select(endpoint, token, "hashtags", "id,tag,normalized_tag", listOf("normalized_tag" to "eq.$normalized"), limit = 1, serializer = ListSerializer(SocialHashtag.serializer())).getOrThrow()
        val tagId = tags.singleOrNull()?.id ?: return@withContext SocialPage(emptyList())
        val size = limit.coerceIn(1, 50)
        val filters = mutableListOf("hashtag_id" to "eq.$tagId")
        if (cursor != null) filters.add("created_at" to "lt.${cursor.createdAt}")
        val edges = api.select(endpoint, token, "post_hashtags", "post_id,created_at", filters, "created_at.desc", size + 1, serializer = ListSerializer(SocialTagEdge.serializer())).getOrThrow()
        val included = edges.take(size)
        val posts = postsByIds(endpoint, token, profileId, included.map { it.postId }).filter { it.visibility == "public" }
        val last = included.lastOrNull()
        SocialPage(posts, if (edges.size > size && last != null) SocialCursor(last.createdAt, last.postId) else null)
    }

    private suspend fun readPost(endpoint: Endpoint, token: String, profileId: String, id: String): SocialPost? {
        validId(id)
        return selectPosts(endpoint, token, profileId, listOf("id" to "eq.$id", "is_deleted" to "eq.false"), 1).singleOrNull()
    }

    private suspend fun selectPosts(endpoint: Endpoint, token: String, profileId: String, filters: List<Pair<String, String>>, limit: Int? = null, order: String? = "created_at.desc,id.desc"): List<SocialPost> {
        validId(profileId)
        return api.select(endpoint, token, "posts", postColumns, filters + ("my_interactions.user_id" to "eq.$profileId"), order, limit, serializer = postRows)
            .getOrThrow().map { it.withInteractionFlags() }
    }

    private suspend fun postsByIds(endpoint: Endpoint, token: String, profileId: String, ids: List<String>): List<SocialPost> {
        if (ids.isEmpty()) return emptyList()
        ids.forEach(::validId)
        val posts = selectPosts(endpoint, token, profileId, listOf("id" to "in.(${ids.joinToString(",")})", "is_deleted" to "eq.false")).associateBy { it.id }
        return ids.mapNotNull(posts::get)
    }

    private fun page(rows: List<SocialPost>, limit: Int): SocialPage {
        val included = rows.take(limit)
        val last = included.lastOrNull()
        return SocialPage(included.filterNot { it.isDeleted || it.author?.isSuspended == true }.map { it.withInteractionFlags() },
            if (rows.size > limit && last != null) SocialCursor(last.createdAt, last.id, last.score, last.asOf) else null)
    }

    private suspend fun validateText(endpoint: Endpoint, token: String, text: String) {
        require(text.isNotBlank()) { "Write something before publishing." }
        val config = api.select(endpoint, token, "instance_config", "config_value", listOf("config_key" to "eq.max_post_length"), limit = 1, serializer = objectRows).getOrThrow()
        val raw = config.singleOrNull()?.get("config_value") as? JsonPrimitive
        val limit = (raw?.intOrNull ?: raw?.contentOrNull?.toIntOrNull() ?: 500).coerceIn(1, 50_000)
        require(text.length <= limit) { "Post is too long (${text.length} / $limit characters)." }
    }

    private suspend fun parseContent(endpoint: Endpoint, token: String, text: String): JsonArray {
        val pattern = Regex("""https?://[^\s]+|(?<![\p{L}\p{N}_/\\])#([\p{L}\p{N}_]+)|(?<![\p{L}\p{N}_/@\\])@([A-Za-z0-9_.-]+)(?:@([A-Za-z0-9.-]+(?::[0-9]+)?))?""")
        val parts = mutableListOf<JsonElement>()
        val resolved = mutableMapOf<String, SocialProfile>()
        var before = 0
        for (match in pattern.findAll(text)) {
            if (match.range.first > before) parts.add(textPart(text.substring(before, match.range.first)))
            val tag = match.groupValues[1]
            val username = match.groupValues[2]
            when {
                match.value.startsWith("http") -> parts.add(buildJsonObject { put("type", "url"); put("url", match.value) })
                tag.isNotEmpty() -> parts.add(buildJsonObject { put("type", "hashtag"); put("name", tag); put("normalized", tag.lowercase(java.util.Locale.ROOT)); put("id", "new") })
                else -> {
                    val domain = match.groupValues[3].lowercase(java.util.Locale.ROOT)
                    val local = domain.isEmpty() || domain == URI(endpoint.baseUrl).authority?.lowercase(java.util.Locale.ROOT)
                    val key = "$username@$domain"
                    val person = resolved[key] ?: api.select(endpoint, token, "profiles", filters = listOf("username" to "eq.$username") +
                        if (local) listOf("is_local" to "eq.true") else listOf("domain" to "eq.$domain"), limit = 1, serializer = profileRows).getOrThrow().singleOrNull()
                        ?: error("Cannot resolve ${match.value}. Find the account first or remove the mention.")
                    resolved[key] = person
                    parts.add(buildJsonObject { put("type", "mention"); put("userId", person.id); put("username", person.username); put("domain", person.domain ?: URI(endpoint.baseUrl).authority); put("isLocal", person.isLocal) })
                }
            }
            before = match.range.last + 1
        }
        if (before < text.length) parts.add(textPart(text.substring(before)))
        return JsonArray(parts)
    }

    private fun textPart(text: String) = buildJsonObject { put("type", "text"); put("text", text) }
    private fun interactionFilters(profileId: String, postId: String, type: String) = listOf("user_id" to "eq.$profileId", "post_id" to "eq.$postId", "interaction_type" to "eq.$type")
    private fun ownedPost(postId: String, profileId: String) = listOf("id" to "eq.${postId.also(::validId)}", "author_id" to "eq.${profileId.also(::validId)}")
    private fun validId(id: String) { require(runCatching { UUID.fromString(id).toString().equals(id, true) }.getOrDefault(false)) { "Invalid account or post id." } }
    private fun addCursor(filters: MutableList<Pair<String, String>>, cursor: SocialCursor?, ascending: Boolean = false) {
        if (cursor == null) return
        validId(cursor.id)
        // Timestamps are quoted inside PostgREST logic trees, as reserved ':' and '.' are legal.
        Instant.parse(cursor.createdAt)
        val op = if (ascending) "gt" else "lt"
        val at = "\"${cursor.createdAt}\""
        filters.add("or" to "(created_at.$op.$at,and(created_at.eq.$at,id.$op.${cursor.id}))")
    }
}
