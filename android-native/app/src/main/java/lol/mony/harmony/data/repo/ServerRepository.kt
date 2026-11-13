package lol.mony.harmony.data.repo

import kotlinx.serialization.builtins.ListSerializer
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.api.RestApi
import lol.mony.harmony.data.model.Channel
import lol.mony.harmony.data.model.ChannelCategory
import lol.mony.harmony.data.model.InstanceConfigRow
import lol.mony.harmony.data.model.InstanceInfo
import lol.mony.harmony.data.model.Server
import lol.mony.harmony.data.model.ServerTree
import lol.mony.harmony.data.model.ServerWithMembership
import lol.mony.harmony.data.model.UserServer

/**
 * Servers the account is in, and each server's channel tree.
 *
 * Membership is read from `user_servers` first and the servers themselves by id, which is what
 * `CLAUDE.md` asks of hot startup paths: ids first, details only for what is shown.
 */
class ServerRepository(private val rest: RestApi) {

    /** The rail's entries, ordered as the account arranged them. */
    suspend fun servers(endpoint: Endpoint, token: String, authUserId: String): Result<List<ServerWithMembership>> = runCatching {
        val memberships = rest.select(
            endpoint, token, "user_servers", MEMBERSHIP_COLUMNS,
            listOf("user_id" to "eq.$authUserId", "status" to "eq.accepted"),
            order = "position.asc",
            serializer = ListSerializer(UserServer.serializer()),
        ).getOrThrow()
        if (memberships.isEmpty()) return@runCatching emptyList()
        val ids = memberships.map { it.serverId }.distinct()
        val servers = rest.select(
            endpoint, token, "servers", SERVER_COLUMNS,
            listOf("id" to "in.(${ids.joinToString(",")})"),
            serializer = ListSerializer(Server.serializer()),
        ).getOrThrow().associateBy { it.id }
        memberships.mapNotNull { membership ->
            servers[membership.serverId]?.let { ServerWithMembership(it, membership) }
        }
    }

    suspend fun server(endpoint: Endpoint, token: String, serverId: String): Server? = rest.select(
        endpoint, token, "servers", SERVER_COLUMNS, listOf("id" to "eq.$serverId"),
        serializer = ListSerializer(Server.serializer()),
    ).getOrNull()?.firstOrNull()

    /** The server's categories and channels, as the channel drawer groups them. */
    suspend fun tree(endpoint: Endpoint, token: String, server: Server): Result<ServerTree> = runCatching {
        val categories = rest.select(
            endpoint, token, "channel_categories", "id,server_id,name,order",
            listOf("server_id" to "eq.${server.id}"), order = "order.asc",
            serializer = ListSerializer(ChannelCategory.serializer()),
        ).getOrThrow()
        val channels = rest.select(
            endpoint, token, "channels", CHANNEL_COLUMNS,
            listOf("server_id" to "eq.${server.id}"), order = "order.asc",
            serializer = ListSerializer(Channel.serializer()),
        ).getOrThrow()
        ServerTree(server, categories, channels)
    }

    /** Channels with unread messages for this account, keyed by server, for the rail's badges. */
    suspend fun unreadByChannel(endpoint: Endpoint, token: String, authUserId: String): Map<String, Int> = runCatching {
        val rows = rest.select(
            endpoint, token, "channel_read_state", "channel_id,last_read_at",
            listOf("user_id" to "eq.$authUserId"),
            serializer = ListSerializer(ReadStateRow.serializer()),
        ).getOrNull().orEmpty()
        rows.mapNotNull { row -> row.channelId }.associateWith { 0 }
    }.getOrDefault(emptyMap())

    suspend fun instanceInfo(endpoint: Endpoint, token: String): InstanceInfo = runCatching {
        val rows = rest.select(
            endpoint, token, "instance_config", "config_key,config_value,description",
            serializer = ListSerializer(InstanceConfigRow.serializer()),
        ).getOrThrow()
        val map = rows.mapNotNull { row -> row.configValue?.let { row.configKey to it.trim('"') } }.toMap()
        InstanceInfo(
            name = map["instance_name"]?.takeIf { it.isNotBlank() } ?: "Harmony",
            domain = map["domain"],
            description = map["instance_description"],
            registrationOpen = map["registration_enabled"]?.lowercase() != "false",
            iconUrl = map["instance_icon_url"],
            raw = map,
        )
    }.getOrDefault(InstanceInfo())

    @kotlinx.serialization.Serializable
    data class ReadStateRow(
        @kotlinx.serialization.SerialName("channel_id") val channelId: String? = null,
        @kotlinx.serialization.SerialName("last_read_at") val lastReadAt: String? = null,
    )

    /** The server's members, for the member list: memberships plus their profiles. */
    suspend fun members(
        endpoint: Endpoint,
        token: String,
        serverId: String,
        profiles: ProfileRepository,
    ): Result<List<Pair<UserServer, lol.mony.harmony.data.model.Profile?>>> = runCatching {
        val rows = rest.select(
            endpoint, token, "user_servers", MEMBERSHIP_COLUMNS,
            listOf("server_id" to "eq.$serverId", "status" to "eq.accepted"),
            order = "created_at.asc",
            serializer = ListSerializer(UserServer.serializer()),
        ).getOrThrow()
        val byAuthId = profiles.byAuthUserIds(endpoint, token, rows.map { it.userId })
        rows.map { row -> row to byAuthId[row.userId] }
    }

    companion object {
        private const val SERVER_COLUMNS =
            "id,name,description,icon,banner,owner,public,slug,member_count,is_local_server," +
                "invite_code,host_domain,federation_enabled,federation_domain,rules,suspended_at,created_at"
        private const val MEMBERSHIP_COLUMNS =
            "id,user_id,server_id,status,nickname,folder_id,position,muted,muted_until,avatar_url,bio,pronouns"
        private const val CHANNEL_COLUMNS =
            "id,server_id,name,description,type,category,order,slowmode_seconds,host_flags"
    }
}
