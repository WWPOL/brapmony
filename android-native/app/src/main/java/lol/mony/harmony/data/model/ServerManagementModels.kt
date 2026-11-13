package lol.mony.harmony.data.model

import java.net.URI
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Server management uses profile IDs: the SQL API resolves the JWT via get_current_profile_id(). */
@Serializable
data class ServerManagementServer(
    val id: String,
    val name: String,
    val description: String? = null,
    val owner: String? = null,
    val public: Boolean = false,
    val icon: String? = null,
    val banner: String? = null,
    val rules: List<String> = emptyList(),
    @SerialName("is_local_server") val isLocal: Boolean = true,
    @SerialName("host_domain") val hostDomain: String? = null,
    @SerialName("member_count") val memberCount: Int = 0,
    @SerialName("federation_enabled") val federationEnabled: Boolean = false,
    @SerialName("federation_discoverable") val federationDiscoverable: Boolean = false,
    @SerialName("suspended_at") val suspendedAt: String? = null,
)

@Serializable
data class ServerManagementChannel(
    val id: String,
    @SerialName("server_id") val serverId: String,
    val name: String,
    val description: String? = null,
    val type: Int = 0,
    val category: String? = null,
    val order: Int = 0,
    @SerialName("slowmode_seconds") val slowmodeSeconds: Int = 0,
)

@Serializable
data class ServerManagementCategory(
    val id: String,
    @SerialName("server_id") val serverId: String,
    val name: String,
    val order: Int = 0,
)

@Serializable
data class ServerManagementMembership(
    val id: String,
    @SerialName("server_id") val serverId: String,
    @SerialName("user_id") val userId: String,
    val status: String,
    val nickname: String? = null,
)

@Serializable
data class ServerManagementPerson(
    val id: String,
    val username: String,
    @SerialName("display_name") val displayName: String? = null,
) {
    val shownName: String get() = displayName?.takeIf(String::isNotBlank) ?: username
}

data class ServerManagementMember(
    val membership: ServerManagementMembership,
    val person: ServerManagementPerson?,
) {
    val shownName: String get() = membership.nickname?.takeIf(String::isNotBlank)
        ?: person?.shownName ?: membership.userId
}

@Serializable
data class ServerManagementRole(
    val id: String,
    @SerialName("server_id") val serverId: String,
    val name: String,
    val color: String = "#99AAB5",
    val position: Int = 0,
    val permissions: Long = 0,
    val hoist: Boolean = false,
    val mentionable: Boolean = false,
    @SerialName("is_default") val isDefault: Boolean = false,
    @SerialName("is_admin") val isAdmin: Boolean = false,
)

@Serializable
data class ServerManagementUserRole(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("server_id") val serverId: String,
    @SerialName("role_id") val roleId: String,
)

/** Bit positions from RoleService.ts and public.permission_bit_names(), not Android permission flags. */
enum class ServerManagementPermission(val bit: Int, val label: String) {
    ADMINISTRATOR(0, "Administrator"), VIEW_CHANNEL(1, "View channels"),
    MANAGE_CHANNELS(2, "Manage channels"), MANAGE_ROLES(3, "Manage roles"),
    MANAGE_EMOJIS(4, "Manage emoji"), VIEW_AUDIT_LOG(5, "View audit log"),
    MANAGE_WEBHOOKS(6, "Manage webhooks"), MANAGE_SERVER(7, "Manage server"),
    CREATE_INVITE(8, "Create invites"), KICK_MEMBERS(9, "Kick members"),
    BAN_MEMBERS(10, "Ban members"), TIMEOUT_MEMBERS(11, "Timeout members"),
    SEND_MESSAGES(12, "Send messages"), SEND_MESSAGES_IN_THREADS(13, "Send in threads"),
    CREATE_PUBLIC_THREADS(14, "Create threads"), CREATE_PRIVATE_THREADS(15, "Create private threads (unused)"),
    EMBED_LINKS(16, "Embed links"), ATTACH_FILES(17, "Attach files"), ADD_REACTIONS(18, "Add reactions"),
    USE_EXTERNAL_EMOJIS(19, "Use external emoji"), MENTION_EVERYONE(20, "Mention everyone"),
    MANAGE_MESSAGES(21, "Manage messages"), READ_MESSAGE_HISTORY(22, "Read history"), PIN_MESSAGES(23, "Pin messages"),
    CONNECT(24, "Connect to voice"), SPEAK(25, "Speak"), STREAM(26, "Stream"),
    MUTE_MEMBERS(27, "Mute members"), DEAFEN_MEMBERS(28, "Deafen members"), MOVE_MEMBERS(29, "Move members"),
    USE_SOUNDBOARD(30, "Use soundboard"), USE_EXTERNAL_SOUNDS(31, "Use external sounds"),
    CHANGE_NICKNAME(32, "Change nickname"), MANAGE_NICKNAMES(33, "Manage nicknames");

    val mask: Long get() = 1L shl bit
    fun isIn(bits: Long): Boolean = bits and mask != 0L
    fun setIn(bits: Long, enabled: Boolean): Long = if (enabled) bits or mask else bits and mask.inv()
}

data class ServerManagementPermissions(val flags: Map<String, Boolean> = emptyMap()) {
    fun allows(permission: ServerManagementPermission): Boolean =
        flags["ADMINISTRATOR"] == true || flags[permission.name] == true
}

@Serializable
data class ServerManagementInvitePolicy(
    @SerialName("who_can_create") val whoCanCreate: String = "everyone",
    @SerialName("allowed_roles") val allowedRoles: List<String> = emptyList(),
    @SerialName("default_expiration") val defaultExpiration: Int = 1440,
    @SerialName("max_expiration") val maxExpiration: Int = 0,
    @SerialName("max_uses_limit") val maxUses: Int = 0,
    @SerialName("allow_temporary") val allowTemporary: Boolean = true,
)

@Serializable
data class ServerManagementSettings(
    val id: String,
    @SerialName("server_id") val serverId: String,
    @SerialName("invite_permissions") val invitePolicy: ServerManagementInvitePolicy = ServerManagementInvitePolicy(),
)

@Serializable
data class ServerManagementInvite(
    val id: String,
    val code: String,
    @SerialName("server_id") val serverId: String,
    @SerialName("created_by") val createdBy: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("max_uses") val maxUses: Int? = null,
    val uses: Int = 0,
    val used: Boolean = false,
    val temporary: Boolean = false,
) {
    fun active(now: Instant = Instant.now()): Boolean = !used &&
        (maxUses == null || maxUses <= 0 || uses < maxUses) &&
        (expiresAt == null || runCatching { Instant.parse(expiresAt).isAfter(now) }.getOrDefault(false))
}

@Serializable
data class ServerManagementInvitePreview(
    val status: String,
    val code: String? = null,
    @SerialName("server_id") val serverId: String? = null,
    val name: String? = null,
    val description: String? = null,
    val rules: List<String> = emptyList(),
    @SerialName("member_count") val memberCount: Int = 0,
    @SerialName("is_member") val isMember: Boolean = false,
) {
    fun requireValid(): ServerManagementInvitePreview {
        require(status == "valid" && !serverId.isNullOrBlank()) {
            when (status) {
                "revoked" -> "This invite was revoked. Ask for a new link."
                "expired" -> "This invite expired. Ask for a new link."
                "exhausted" -> "This invite has reached its use limit. Ask for a new link."
                else -> "This invite was not found on this instance."
            }
        }
        return this
    }
}

@Serializable
data class ServerManagementJoinResult(
    @SerialName("server_id") val serverId: String,
    val joined: Boolean,
)

@Serializable
data class ServerManagementModerationResult(
    val success: Boolean,
    val error: String? = null,
    @SerialName("messages_deleted") val messagesDeleted: Int = 0,
) {
    fun requireSuccess(): ServerManagementModerationResult {
        check(success) { error ?: "The instance did not apply this moderation action." }
        return this
    }
}

@Serializable
data class ServerManagementBan(
    val id: String,
    @SerialName("user_id") val userId: String,
    val username: String,
    @SerialName("display_name") val displayName: String? = null,
    val reason: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

data class ServerManagementPage<T>(val items: List<T>, val hasMore: Boolean)

data class ServerManagementOverview(
    val server: ServerManagementServer,
    val permissions: ServerManagementPermissions,
    val profileId: String,
    val membership: ServerManagementMembership?,
    val highestRolePosition: Int,
    val invitePolicy: ServerManagementInvitePolicy,
) {
    val isOwner: Boolean get() = server.owner == profileId
    val canManage: Boolean get() = can(ServerManagementPermission.MANAGE_SERVER)
    fun can(permission: ServerManagementPermission): Boolean = server.isLocal &&
        server.suspendedAt == null && (isOwner || (membership?.status == "accepted" && permissions.allows(permission)))
    fun canEdit(role: ServerManagementRole): Boolean = can(ServerManagementPermission.MANAGE_ROLES) &&
        (isOwner || (!role.isAdmin && (role.isDefault || role.position < highestRolePosition)))
}

/** Bare codes and same-origin invite links match the web input contract. Never redeem a remote code locally. */
sealed interface ServerManagementInviteInput {
    data class Local(val code: String) : ServerManagementInviteInput
    data object RemoteUnsupported : ServerManagementInviteInput
    data object Invalid : ServerManagementInviteInput
}

fun parseServerManagementInvite(raw: String, instanceUrl: String): ServerManagementInviteInput {
    val value = raw.trim()
    val code = Regex("[A-Za-z0-9_-]{3,64}")
    if (code.matches(value)) return ServerManagementInviteInput.Local(value)
    return runCatching {
        val uri = URI(if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(value)) value else "https://$value")
        val origin = URI(instanceUrl)
        fun port(u: URI): Int = if (u.port >= 0) u.port else if (u.scheme.equals("https", true)) 443 else 80
        if (uri.scheme?.lowercase() !in listOf("http", "https") || uri.host == null || uri.userInfo != null) {
            ServerManagementInviteInput.Invalid
        } else if (!uri.scheme.equals(origin.scheme, true) || !uri.host.equals(origin.host, true) || port(uri) != port(origin)) {
            ServerManagementInviteInput.RemoteUnsupported
        } else {
            val match = Regex("^/invite/([A-Za-z0-9_-]{3,64})/?$").matchEntire(uri.rawPath.orEmpty())
            match?.let { ServerManagementInviteInput.Local(it.groupValues[1]) } ?: ServerManagementInviteInput.Invalid
        }
    }.getOrDefault(ServerManagementInviteInput.Invalid)
}

fun validateServerManagementInfo(name: String, description: String, creating: Boolean): String? = when {
    name.trim().length < if (creating) 2 else 1 -> if (creating) "Use at least 2 characters for the server name." else "Enter a server name."
    name.trim().length > if (creating) 28 else 100 -> if (creating) "Use at most 28 characters for the server name." else "Use at most 100 characters for the server name."
    description.length > 500 -> "Use at most 500 characters for the description."
    else -> null
}

fun validateServerManagementInviteOptions(expiryMinutes: Int, maxUses: Int, policy: ServerManagementInvitePolicy): String? = when {
    expiryMinutes < 0 || maxUses < 0 -> "Invite limits cannot be negative."
    policy.maxExpiration > 0 && (expiryMinutes == 0 || expiryMinutes > policy.maxExpiration) -> "Choose an expiry within ${policy.maxExpiration} minutes."
    policy.maxUses > 0 && (maxUses == 0 || maxUses > policy.maxUses) -> "Choose a use limit from 1 to ${policy.maxUses}."
    else -> null
}
