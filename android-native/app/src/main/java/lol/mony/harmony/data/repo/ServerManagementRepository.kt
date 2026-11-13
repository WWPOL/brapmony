package lol.mony.harmony.data.repo

import java.security.SecureRandom
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lol.mony.harmony.data.api.ApiException
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.api.RestApi
import lol.mony.harmony.data.model.*

/**
 * The web client's PostgREST contracts, always as the caller's USER JWT. Each public suspend
 * operation offloads the synchronous foundation RestApi to IO. There are no service credentials,
 * write retries, swallowed permission errors, or success inferred from an empty representation.
 */
class ServerManagementRepository(private val rest: RestApi) {
    suspend fun profileId(endpoint: Endpoint, token: String, authUserId: String): Result<String> = io {
        rest.select(endpoint, token, "profiles", "id,username", listOf("auth_user_id" to "eq.$authUserId"),
            limit = 1, serializer = ListSerializer(ServerManagementPerson.serializer())).getOrThrow()
            .singleOrNull()?.id ?: error("Your profile could not be loaded. Sign in again.")
    }

    suspend fun overview(endpoint: Endpoint, token: String, profileId: String, serverId: String): Result<ServerManagementOverview> = io {
        val server = server(endpoint, token, serverId)
        val membership = rest.select(endpoint, token, "user_servers", MEMBERSHIP_COLUMNS,
            listOf("server_id" to "eq.$serverId", "user_id" to "eq.$profileId"), limit = 1,
            serializer = ListSerializer(ServerManagementMembership.serializer())).getOrThrow().singleOrNull()
        val permissions = scalar(endpoint, token, "get_user_permissions", args {
            put("p_user_id", profileId); put("p_server_id", serverId)
        }, MapSerializer(String.serializer(), Boolean.serializer()))
        val rank = scalar(endpoint, token, "get_user_highest_role_position", args {
            put("p_user_id", profileId); put("p_server_id", serverId)
        }, Int.serializer())
        val settings = rest.select(endpoint, token, "server_settings", "id,server_id,invite_permissions",
            listOf("server_id" to "eq.$serverId"), limit = 1,
            serializer = ListSerializer(ServerManagementSettings.serializer())).getOrThrow().singleOrNull()
        val admin = server.owner == profileId || permissions["ADMINISTRATOR"] == true
        val policy = settings?.invitePolicy ?: ServerManagementInvitePolicy()
        ServerManagementOverview(server, ServerManagementPermissions(permissions), profileId, membership, rank,
            if (admin) policy.copy(maxExpiration = 0, maxUses = 0, allowTemporary = true) else policy)
    }

    suspend fun createServer(endpoint: Endpoint, token: String, profileId: String, name: String, description: String, isPublic: Boolean): Result<ServerManagementServer> = io {
        require(validateServerManagementInfo(name, description, creating = true) == null) {
            validateServerManagementInfo(name, description, creating = true).orEmpty()
        }
        // add_server_owner_membership runs in this same INSERT transaction. Never insert or retry a membership.
        rest.insert(endpoint, token, "servers", args {
            put("name", name.trim()); putNullable("description", description.trim().ifBlank { null })
            put("owner", profileId); put("public", isPublic)
        }, returning = SERVER_COLUMNS, serializer = ListSerializer(ServerManagementServer.serializer()))
            .getOrThrow().one("The server was not returned. Check your server list before creating again.")
    }

    suspend fun publicServers(endpoint: Endpoint, token: String, search: String = "", offset: Int = 0): Result<ServerManagementPage<ServerManagementServer>> = io {
        val filters = mutableListOf("public" to "eq.true", "is_local_server" to "eq.true", "suspended_at" to "is.null")
        if (search.isNotBlank()) filters += "name" to "ilike.*${search.trim().replace(Regex("[%*_,()]"), "")}*"
        page(rest.select(endpoint, token, "servers", SERVER_COLUMNS, filters, order = "name.asc,id.asc",
            limit = PAGE_SIZE + 1, offset = offset.coerceAtLeast(0),
            serializer = ListSerializer(ServerManagementServer.serializer())).getOrThrow())
    }

    suspend fun previewInvite(endpoint: Endpoint, token: String, code: String): Result<ServerManagementInvitePreview> = io {
        scalar(endpoint, token, "get_invite_preview", args { put("p_code", code) },
            ServerManagementInvitePreview.serializer()).requireValid()
    }

    suspend fun redeemInvite(endpoint: Endpoint, token: String, code: String): Result<ServerManagementJoinResult> = io {
        scalar(endpoint, token, "redeem_invite", args { put("p_code", code) }, ServerManagementJoinResult.serializer())
            .also { check(it.serverId.isNotBlank()) { "The instance did not return a joined server." } }
    }

    suspend fun joinPublicServer(endpoint: Endpoint, token: String, serverId: String): Result<ServerManagementJoinResult> = io {
        scalar(endpoint, token, "join_public_server", args { put("p_server_id", serverId) }, ServerManagementJoinResult.serializer())
            .also { check(it.serverId == serverId) { "The instance returned a different server." } }
    }

    suspend fun saveInfo(endpoint: Endpoint, token: String, serverId: String, name: String, description: String, isPublic: Boolean, rules: List<String>): Result<ServerManagementServer> = io {
        validateServerManagementInfo(name, description, creating = false)?.let { error(it) }
        require(rules.size <= 25) { "Use at most 25 server rules." }
        // update_server returns a JSON object containing only writable fields, not an entire servers row.
        scalar(endpoint, token, "update_server", args {
            put("p_server_id", serverId)
            put("p_changes", buildJsonObject {
                put("name", name.trim()); putNullable("description", description.trim().ifBlank { null })
                put("public", isPublic); put("rules", JsonArray(rules.map(::JsonPrimitive)))
            })
        }, ServerManagementServer.serializer()).also { check(it.id == serverId) { "The instance returned a different server." } }
        server(endpoint, token, serverId)
    }

    suspend fun channels(endpoint: Endpoint, token: String, serverId: String, offset: Int = 0): Result<ServerManagementPage<ServerManagementChannel>> = io {
        page(rest.select(endpoint, token, "channels", CHANNEL_COLUMNS, listOf("server_id" to "eq.$serverId"),
            order = "order.asc,id.asc", limit = PAGE_SIZE + 1, offset = offset.coerceAtLeast(0),
            serializer = ListSerializer(ServerManagementChannel.serializer())).getOrThrow())
    }

    suspend fun categories(endpoint: Endpoint, token: String, serverId: String, offset: Int = 0): Result<ServerManagementPage<ServerManagementCategory>> = io {
        page(rest.select(endpoint, token, "channel_categories", "id,server_id,name,order", listOf("server_id" to "eq.$serverId"),
            order = "order.asc,id.asc", limit = PAGE_SIZE + 1, offset = offset.coerceAtLeast(0),
            serializer = ListSerializer(ServerManagementCategory.serializer())).getOrThrow())
    }

    suspend fun createCategory(endpoint: Endpoint, token: String, serverId: String, name: String): Result<ServerManagementCategory> = io {
        require(name.trim().isNotEmpty() && name.length <= 100) { "Use 1 to 100 characters for the category name." }
        val last = rest.select(endpoint, token, "channel_categories", "id,server_id,name,order",
            listOf("server_id" to "eq.$serverId"), order = "order.desc", limit = 1,
            serializer = ListSerializer(ServerManagementCategory.serializer())).getOrThrow().firstOrNull()
        rest.insert(endpoint, token, "channel_categories", args {
            put("server_id", serverId); put("name", name.trim()); put("order", (last?.order ?: -1) + 1)
        }, returning = "id,server_id,name,order", serializer = ListSerializer(ServerManagementCategory.serializer()))
            .getOrThrow().one("The category was not returned. Refresh before trying again.")
    }

    suspend fun saveChannel(endpoint: Endpoint, token: String, serverId: String, channelId: String?, name: String, description: String, type: Int, categoryId: String?, slowmode: Int): Result<ServerManagementChannel> = io {
        require(name.isNotBlank() && name.length <= 100) { "Use 1 to 100 characters for the channel name." }
        require(type in 0..1 && slowmode in 0..21600) { "Slowmode must be 0 to 21600 seconds." }
        if (channelId == null) {
            scalar(endpoint, token, "create_channel", args {
                put("p_server_id", serverId); put("p_name", name.trim()); put("p_type", type)
                putNullable("p_category", categoryId); put("p_private", false)
                put("p_description", description.trim()); put("p_slowmode_seconds", slowmode)
            }, ServerManagementChannel.serializer())
        } else {
            rest.update(endpoint, token, "channels", listOf("id" to "eq.$channelId", "server_id" to "eq.$serverId"),
                args { put("name", name.trim()); putNullable("description", description.trim().ifBlank { null })
                    putNullable("category", categoryId); put("slowmode_seconds", slowmode) },
                returning = CHANNEL_COLUMNS, serializer = ListSerializer(ServerManagementChannel.serializer()))
                .getOrThrow().one("The channel was not updated. You may not have permission.")
        }
    }

    suspend fun deleteChannel(endpoint: Endpoint, token: String, serverId: String, channelId: String): Result<Unit> = io {
        deleteAndVerify(endpoint, token, "channels", listOf("id" to "eq.$channelId", "server_id" to "eq.$serverId"))
    }

    suspend fun members(endpoint: Endpoint, token: String, serverId: String, offset: Int = 0): Result<ServerManagementPage<ServerManagementMember>> = io {
        val memberships = rest.select(endpoint, token, "user_servers", MEMBERSHIP_COLUMNS,
            listOf("server_id" to "eq.$serverId", "status" to "eq.accepted"), order = "id.asc",
            limit = PAGE_SIZE + 1, offset = offset.coerceAtLeast(0),
            serializer = ListSerializer(ServerManagementMembership.serializer())).getOrThrow()
        val rows = memberships.take(PAGE_SIZE)
        val people = if (rows.isEmpty()) emptyMap() else rest.select(endpoint, token, "profiles", "id,username,display_name",
            listOf("id" to "in.(${rows.joinToString(",") { it.userId }})"), limit = PAGE_SIZE,
            serializer = ListSerializer(ServerManagementPerson.serializer())).getOrThrow().associateBy { it.id }
        ServerManagementPage(rows.map { ServerManagementMember(it, people[it.userId]) }, memberships.size > PAGE_SIZE)
    }

    suspend fun roles(endpoint: Endpoint, token: String, serverId: String, offset: Int = 0): Result<ServerManagementPage<ServerManagementRole>> = io {
        page(rest.select(endpoint, token, "server_roles", ROLE_COLUMNS, listOf("server_id" to "eq.$serverId"),
            order = "position.desc,id.asc", limit = PAGE_SIZE + 1, offset = offset.coerceAtLeast(0),
            serializer = ListSerializer(ServerManagementRole.serializer())).getOrThrow())
    }

    suspend fun memberRoles(endpoint: Endpoint, token: String, serverId: String, profileId: String): Result<List<ServerManagementUserRole>> = io {
        // Only this member's assignments; callers can page the role catalog separately.
        rest.select(endpoint, token, "user_roles", "id,user_id,server_id,role_id",
            listOf("server_id" to "eq.$serverId", "user_id" to "eq.$profileId"), limit = 1000,
            serializer = ListSerializer(ServerManagementUserRole.serializer())).getOrThrow()
            .also { check(it.size < 1000) { "This member has too many roles for the native editor." } }
    }

    suspend fun saveRole(endpoint: Endpoint, token: String, serverId: String, roleId: String?, name: String, permissions: Long, position: Int, hoist: Boolean, mentionable: Boolean): Result<ServerManagementRole> = io {
        require(name.isNotBlank() && name.length <= 100) { "Use 1 to 100 characters for the role name." }
        val patch = args { put("name", name.trim()); put("permissions", permissions); put("hoist", hoist); put("mentionable", mentionable)
            if (roleId == null) { put("server_id", serverId); put("position", position) } }
        val result = if (roleId == null) rest.insert(endpoint, token, "server_roles", patch, returning = ROLE_COLUMNS,
            serializer = ListSerializer(ServerManagementRole.serializer()))
        else rest.update(endpoint, token, "server_roles", listOf("server_id" to "eq.$serverId", "id" to "eq.$roleId"), patch,
            returning = ROLE_COLUMNS, serializer = ListSerializer(ServerManagementRole.serializer()))
        result.getOrThrow().one("The role was not saved. You may not have permission.")
    }

    suspend fun deleteRole(endpoint: Endpoint, token: String, serverId: String, role: ServerManagementRole): Result<Unit> = io {
        require(!role.isDefault && !role.isAdmin) { "Protected roles cannot be deleted." }
        deleteAndVerify(endpoint, token, "server_roles", listOf("server_id" to "eq.$serverId", "id" to "eq.${role.id}"))
    }

    suspend fun assignRole(endpoint: Endpoint, token: String, serverId: String, profileId: String, roleId: String): Result<ServerManagementUserRole> = io {
        rest.insert(endpoint, token, "user_roles", args { put("server_id", serverId); put("user_id", profileId); put("role_id", roleId) },
            returning = "id,user_id,server_id,role_id", serializer = ListSerializer(ServerManagementUserRole.serializer()))
            .getOrThrow().one("The role assignment was not returned. Refresh before trying again.")
    }

    suspend fun removeRole(endpoint: Endpoint, token: String, serverId: String, profileId: String, role: ServerManagementRole): Result<Unit> = io {
        require(!role.isDefault) { "The everyone role is assigned automatically." }
        deleteAndVerify(endpoint, token, "user_roles", listOf("server_id" to "eq.$serverId", "user_id" to "eq.$profileId", "role_id" to "eq.${role.id}"))
    }

    suspend fun invites(endpoint: Endpoint, token: String, serverId: String, profileId: String, offset: Int = 0): Result<ServerManagementPage<ServerManagementInvite>> = io {
        page(rest.select(endpoint, token, "invites", INVITE_COLUMNS,
            listOf("server_id" to "eq.$serverId", "created_by" to "eq.$profileId"), order = "created_at.desc,id.desc",
            limit = PAGE_SIZE + 1, offset = offset.coerceAtLeast(0),
            serializer = ListSerializer(ServerManagementInvite.serializer())).getOrThrow())
    }

    suspend fun createInvite(endpoint: Endpoint, token: String, serverId: String, profileId: String, expiryMinutes: Int, maxUses: Int, policy: ServerManagementInvitePolicy): Result<ServerManagementInvite> = io {
        validateServerManagementInviteOptions(expiryMinutes, maxUses, policy)?.let { error(it) }
        val random = SecureRandom()
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val code = (1..8).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        rest.insert(endpoint, token, "invites", args {
            put("server_id", serverId); put("created_by", profileId); put("code", code)
            putNullable("expires_at", if (expiryMinutes > 0) Instant.now().plusSeconds(expiryMinutes.toLong() * 60).toString() else null)
            put("max_uses", if (maxUses > 0) JsonPrimitive(maxUses) else JsonNull)
            put("uses", 0); put("used", false); put("temporary", false)
        }, returning = INVITE_COLUMNS, serializer = ListSerializer(ServerManagementInvite.serializer()))
            .getOrThrow().one("The invite was not returned. Refresh before trying again.")
    }

    suspend fun revokeInvite(endpoint: Endpoint, token: String, serverId: String, profileId: String, inviteId: String): Result<ServerManagementInvite> = io {
        rest.update(endpoint, token, "invites",
            listOf("id" to "eq.$inviteId", "server_id" to "eq.$serverId", "created_by" to "eq.$profileId"),
            args { put("used", true) }, returning = INVITE_COLUMNS, serializer = ListSerializer(ServerManagementInvite.serializer()))
            .getOrThrow().one("The invite was not revoked. You may not have permission.").also { check(it.used) }
    }

    suspend fun moderate(endpoint: Endpoint, token: String, serverId: String, profileId: String, ban: Boolean, reason: String): Result<ServerManagementModerationResult> = io {
        scalar(endpoint, token, if (ban) "ban_server_member" else "kick_server_member", args {
            put("p_server_id", serverId); put("p_user_id", profileId); putNullable("p_reason", reason.trim().ifBlank { null })
            put("p_delete_message_seconds", 0)
        }, ServerManagementModerationResult.serializer()).requireSuccess()
    }

    /** The existing RPC returns a jsonb array, with no backend pagination parameter. */
    suspend fun bans(endpoint: Endpoint, token: String, serverId: String): Result<List<ServerManagementBan>> = io {
        rest.rpc(endpoint, token, "get_server_bans", args { put("p_server_id", serverId) },
            ListSerializer(ServerManagementBan.serializer())).getOrThrow()
    }

    suspend fun unban(endpoint: Endpoint, token: String, serverId: String, profileId: String): Result<ServerManagementModerationResult> = io {
        scalar(endpoint, token, "unban_server_member", args { put("p_server_id", serverId); put("p_user_id", profileId) },
            ServerManagementModerationResult.serializer()).requireSuccess()
    }

    suspend fun leaveServer(endpoint: Endpoint, token: String, serverId: String, profileId: String): Result<Unit> = io {
        val server = server(endpoint, token, serverId)
        require(server.owner != profileId) { "The owner must transfer ownership before leaving." }
        require(server.isLocal) { "Leaving a server hosted elsewhere requires federation support, which is not available here yet." }
        deleteAndVerify(endpoint, token, "user_servers", listOf("server_id" to "eq.$serverId", "user_id" to "eq.$profileId"))
    }

    private suspend fun server(endpoint: Endpoint, token: String, serverId: String): ServerManagementServer =
        rest.select(endpoint, token, "servers", SERVER_COLUMNS, listOf("id" to "eq.$serverId"), limit = 1,
            serializer = ListSerializer(ServerManagementServer.serializer())).getOrThrow()
            .one("This server is unavailable or you no longer have access.")

    private suspend fun <T> scalar(endpoint: Endpoint, token: String, function: String, args: String, serializer: KSerializer<T>): T =
        rest.rpc(endpoint, token, function, args, ServerManagementScalarResponseSerializer(serializer)).getOrThrow()
            .one("The instance returned no result for $function.")

    private suspend fun deleteAndVerify(endpoint: Endpoint, token: String, table: String, filters: List<Pair<String, String>>) {
        suspend fun exists(): Boolean = rest.select(endpoint, token, table, "id", filters, limit = 1,
            serializer = ListSerializer(JsonObject.serializer())).getOrThrow().isNotEmpty()
        check(exists()) { "This item no longer exists or you cannot access it. Refresh and try again." }
        val result = rest.delete(endpoint, token, table, filters)
        val error = result.exceptionOrNull()
        // Foundation execute() rejects an otherwise successful empty 204. Do not retry a committed DELETE.
        if (error != null && (error !is ApiException || error.status != 204)) throw error
        check(!exists()) { "The item was not removed. You may not have permission." }
    }

    private suspend fun <T> io(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        try { Result.success(block()) } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
    }

    private fun <T> List<T>.one(message: String): T = singleOrNull() ?: error(message)
    private fun <T> page(rows: List<T>): ServerManagementPage<T> = ServerManagementPage(rows.take(PAGE_SIZE), rows.size > PAGE_SIZE)
    private fun args(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String = buildJsonObject(block).toString()
    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: String?) = put(key, value?.let(::JsonPrimitive) ?: JsonNull)

    companion object {
        const val PAGE_SIZE = 50
        private const val SERVER_COLUMNS = "id,name,description,owner,public,icon,banner,rules,is_local_server,host_domain,member_count,federation_enabled,federation_discoverable,suspended_at"
        private const val MEMBERSHIP_COLUMNS = "id,server_id,user_id,status,nickname"
        private const val CHANNEL_COLUMNS = "id,server_id,name,description,type,category,order,slowmode_seconds"
        private const val ROLE_COLUMNS = "id,server_id,name,color,position,permissions,hoist,mentionable,is_default,is_admin"
        private const val INVITE_COLUMNS = "id,code,server_id,created_by,expires_at,max_uses,uses,used,temporary"
    }
}

/**
 * RestApi.rpc's public contract is List<T>. These specific SQL functions instead return a
 * scalar jsonb/composite value. Decode that typed value without discarding its success/error
 * fields. A one-row array from a composite function is accepted too; callers require one row.
 */
internal class ServerManagementScalarResponseSerializer<T>(serializer: KSerializer<T>) :
    JsonTransformingSerializer<List<T>>(ListSerializer(serializer)) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonArray) element else JsonArray(listOf(element))
}
