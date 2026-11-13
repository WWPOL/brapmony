package lol.mony.harmony.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire contracts from src/services/drive/driveService.ts and the drive_* SQL functions. */
@Serializable
data class DriveFile(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    val bucket: String,
    val path: String,
    val name: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    val kind: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    val sha256: String? = null,
    @SerialName("folder_id") val folderId: String? = null,
    val state: String,
    @SerialName("gone_reason") val goneReason: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("trashed_at") val trashedAt: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("link_token") val linkToken: String? = null,
    @SerialName("photo_details") val photoDetails: String? = null,
    @SerialName("photo_location") val photoLocation: Boolean? = null,
) {
    // Encrypted-room uploads intentionally have no Drive filename. Do not expose the object key.
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: "Unnamed file"
    val isActive: Boolean get() = state == "drive" || state == "ephemeral"
}

@Serializable
data class DriveOwner(
    val id: String,
    val username: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val domain: String? = null,
)

@Serializable
data class DriveListRow(
    val file: DriveFile,
    val owner: DriveOwner? = null,
    @SerialName("total_count") val totalCount: Long,
)

data class DrivePage(val entries: List<DriveListRow>, val total: Long, val offset: Int = 0) {
    val hasMore: Boolean get() = offset.toLong() + entries.size < total
}

@Serializable
data class DriveFolder(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("parent_id") val parentId: String? = null,
    val name: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class DriveUsage(
    // Servers before the Drive switch shipped no enabled field; Drive was on there.
    val enabled: Boolean = true,
    @SerialName("used_bytes") val usedBytes: Long,
    @SerialName("quota_bytes") val quotaBytes: Long,
    @SerialName("ephemeral_bytes") val ephemeralBytes: Long,
    @SerialName("ephemeral_recent_bytes") val ephemeralRecentBytes: Long,
    @SerialName("ephemeral_daily_bytes") val ephemeralDailyBytes: Long,
    @SerialName("ephemeral_days") val ephemeralDays: Int,
    @SerialName("trash_bytes") val trashBytes: Long,
    @SerialName("file_count") val fileCount: Long,
    @SerialName("link_sharing") val linkSharing: Boolean,
)

@Serializable
data class DriveContextTree(
    val servers: List<DriveServerContext> = emptyList(),
    val conversations: List<DriveConversationContext> = emptyList(),
    val fediverse: Long = 0,
    val shared: Long = 0,
)

@Serializable
data class DriveServerContext(
    val id: String,
    val name: String,
    val icon: String? = null,
    val files: Long,
    val channels: List<DriveChannelContext>? = null,
)

@Serializable
data class DriveChannelContext(val id: String, val name: String, val files: Long)

@Serializable
data class DriveConversationContext(val id: String, val type: String, val name: String? = null, val files: Long)

@Serializable
data class DriveShare(
    val id: String,
    val scope: String,
    @SerialName("target_id") val targetId: String? = null,
    val token: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("target_name") val targetName: String? = null,
)

@Serializable
data class DriveFileUsage(
    val messages: List<DriveMessageUsage> = emptyList(),
    val posts: List<DrivePostUsage> = emptyList(),
    val shares: List<DriveShare> = emptyList(),
)

@Serializable
data class DriveMessageUsage(
    @SerialName("message_id") val messageId: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("channel_id") val channelId: String? = null,
    @SerialName("channel_name") val channelName: String? = null,
    @SerialName("server_id") val serverId: String? = null,
    @SerialName("server_name") val serverName: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("conversation_name") val conversationName: String? = null,
    @SerialName("thread_id") val threadId: String? = null,
)

@Serializable
data class DrivePostUsage(
    @SerialName("post_id") val postId: String,
    @SerialName("created_at") val createdAt: String,
    val visibility: String? = null,
)

@Serializable
data class DriveLinkState(
    val token: String,
    @SerialName("public") val isPublic: Boolean,
    @SerialName("link_expires_at") val linkExpiresAt: String? = null,
    @SerialName("show_owner") val showOwner: Boolean = false,
)

/** A denied card deliberately carries no identity, object path, or owner. */
@Serializable
data class DriveFileCard(
    val access: Boolean,
    val via: String? = null,
    val id: String? = null,
    val bucket: String? = null,
    val path: String? = null,
    val name: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    val kind: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    val state: String? = null,
    @SerialName("gone_reason") val goneReason: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val own: Boolean = false,
    val owner: DriveOwner? = null,
    val token: String? = null,
    @SerialName("public") val isPublic: Boolean = false,
    @SerialName("link_expires_at") val linkExpiresAt: String? = null,
    @SerialName("show_owner") val showOwner: Boolean = false,
) {
    fun linkState(): DriveLinkState? = token?.let { DriveLinkState(it, isPublic, linkExpiresAt, showOwner) }
}

enum class DriveView(val wire: String, val label: String, val needsContext: Boolean = false) {
    Folder("folder", "My Drive"), All("all", "All files"), Recent("recent", "Recent"),
    Temporary("ephemeral", "Temporary"), Trash("trash", "Trash"),
    Server("server", "Server", true), Channel("channel", "Channel", true),
    Conversation("conversation", "Conversation", true), Dms("dms", "Direct messages"),
    Fediverse("fediverse", "Post media"), Shared("shared", "Shared with me"),
}

enum class DriveSort(val wire: String, val label: String) {
    Newest("newest", "Newest"), Oldest("oldest", "Oldest"), Name("name", "Name"), Size("size", "Largest"),
}

enum class DriveKind(val wire: String, val label: String) {
    Image("image", "Images"), Video("video", "Videos"), Audio("audio", "Audio"),
    Document("document", "Documents"), Other("other", "Other"),
}

data class DriveQuery(
    val view: DriveView = DriveView.Folder,
    val folderId: String? = null,
    val contextId: String? = null,
    val search: String = "",
    val kind: DriveKind? = null,
    val sort: DriveSort = DriveSort.Newest,
    val limit: Int = 50,
    val offset: Int = 0,
) {
    init {
        require(limit in 1..500) { "A Drive page must contain 1 to 500 files." }
        require(offset >= 0) { "A Drive offset cannot be negative." }
        require(!view.needsContext || !contextId.isNullOrBlank()) { "Choose a Drive context first." }
    }
}

enum class DriveFileAction { Preview, Save, Rename, Move, Trash, Restore, DeleteForever, Keep, Link }

/** UI affordances only; RPC and storage RLS remain the authority, including stale-permission races. */
fun DriveFile.actions(profileId: String?, linkSharing: Boolean): Set<DriveFileAction> = buildSet {
    if (isActive && bucket in setOf("message_media", "user_media")) {
        add(DriveFileAction.Save)
        if (kind == "image" && mimeType != "image/svg+xml") add(DriveFileAction.Preview)
    }
    if (profileId == null || ownerId != profileId) return@buildSet
    when (state) {
        "drive", "ephemeral" -> {
            add(DriveFileAction.Rename)
            add(DriveFileAction.Move)
            if (state == "drive") add(DriveFileAction.Trash) else {
                add(DriveFileAction.Keep)
                add(DriveFileAction.DeleteForever)
            }
            // Restricted access remains useful even after staff disables creation of public links.
            if (linkSharing || linkToken != null) add(DriveFileAction.Link)
        }
        "trashed" -> {
            add(DriveFileAction.Restore)
            add(DriveFileAction.DeleteForever)
        }
        // Unknown future state, like gone, fails closed: no destructive UI actions.
    }
}

/** A move target can be neither the folder itself nor any of its descendants. */
fun driveMoveTargets(folders: List<DriveFolder>, movingFolderId: String?): List<DriveFolder> {
    if (movingFolderId == null) return folders
    val forbidden = mutableSetOf(movingFolderId)
    do {
        val sizeBefore = forbidden.size
        folders.filter { it.parentId in forbidden }.forEach { forbidden.add(it.id) }
    } while (forbidden.size != sizeBefore)
    return folders.filterNot { it.id in forbidden }
}

/** Root-first folder path; a corrupt or cyclic tree stops rather than hanging the UI. */
fun driveBreadcrumbs(folders: List<DriveFolder>, folderId: String?): List<DriveFolder> {
    val byId = folders.associateBy { it.id }
    val visited = mutableSetOf<String>()
    val result = mutableListOf<DriveFolder>()
    var next = folderId
    while (next != null && visited.add(next)) {
        val folder = byId[next] ?: break
        result.add(folder)
        next = folder.parentId
    }
    return result.reversed()
}
