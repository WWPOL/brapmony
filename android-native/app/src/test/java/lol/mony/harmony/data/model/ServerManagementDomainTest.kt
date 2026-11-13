package lol.mony.harmony.data.model

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM checks: no Android objects, HTTP calls, wall-clock time, or service credentials. */
class ServerManagementDomainTest {
    @Test
    fun `bare invite codes preserve case and trim surrounding whitespace`() {
        assertEquals(
            ServerManagementInviteInput.Local("AbC_12-z"),
            parseServerManagementInvite("  AbC_12-z\n", ORIGIN),
        )
        assertEquals(ServerManagementInviteInput.Local("abc"), parseServerManagementInvite("abc", ORIGIN))
        val longest = "A".repeat(64)
        assertEquals(ServerManagementInviteInput.Local(longest), parseServerManagementInvite(longest, ORIGIN))
        assertFalse(parseServerManagementInvite("A".repeat(65), ORIGIN) is ServerManagementInviteInput.Local)
    }

    @Test
    fun `same origin links accept optional trailing slash and implicit HTTPS`() {
        for (link in listOf(
            "$ORIGIN/invite/AbC_12-z",
            "$ORIGIN/invite/AbC_12-z/",
            "harmony.example/invite/AbC_12-z",
            "https://HARMONY.EXAMPLE/invite/AbC_12-z",
        )) {
            assertEquals(link, ServerManagementInviteInput.Local("AbC_12-z"), parseServerManagementInvite(link, ORIGIN))
        }
    }

    @Test
    fun `default HTTPS and HTTP ports are equivalent to omitted ports`() {
        val cases = listOf(
            "$ORIGIN:443/invite/ABC" to ORIGIN,
            "$ORIGIN/invite/ABC" to "$ORIGIN:443",
            "http://harmony.example:80/invite/ABC" to "http://harmony.example",
            "http://harmony.example/invite/ABC" to "http://harmony.example:80",
            "https://harmony.example:8443/invite/ABC" to "https://harmony.example:8443",
        )
        for ((link, origin) in cases) {
            assertEquals("$link against $origin", ServerManagementInviteInput.Local("ABC"), parseServerManagementInvite(link, origin))
        }
    }

    @Test
    fun `URL scheme is case insensitive just like the hostname`() {
        assertEquals(
            ServerManagementInviteInput.Local("ABC"),
            parseServerManagementInvite("HTTPS://HARMONY.EXAMPLE:443/invite/ABC", ORIGIN),
        )
    }

    @Test
    fun `another origin is never reduced to a locally redeemable invite code`() {
        for (link in listOf(
            "https://remote.example/invite/ABC",
            "https://harmony.example.evil/invite/ABC",
            "https://sub.harmony.example/invite/ABC",
            "http://harmony.example/invite/ABC",
            "https://harmony.example:8443/invite/ABC",
            "https://remote.example/invite/ABC?next=$ORIGIN/invite/ABC",
        )) {
            assertSame(link, ServerManagementInviteInput.RemoteUnsupported, parseServerManagementInvite(link, ORIGIN))
        }
    }

    @Test
    fun `non HTTP schemes user information and malformed local paths are invalid`() {
        for (link in listOf(
            "",
            "ftp://harmony.example/invite/ABC",
            "file://harmony.example/invite/ABC",
            "content://harmony.example/invite/ABC",
            "javascript:alert(1)",
            "mailto:person@harmony.example",
            "https://user@harmony.example/invite/ABC",
            "https://harmony.example@remote.example/invite/ABC",
            "$ORIGIN/invite/ab",
            "$ORIGIN/invite/${"A".repeat(65)}",
            "$ORIGIN/invite/ABC/extra",
            "$ORIGIN/invite/ABC%2FDEF",
            "$ORIGIN/not-an-invite/ABC",
        )) {
            assertSame(link, ServerManagementInviteInput.Invalid, parseServerManagementInvite(link, ORIGIN))
        }
    }

    @Test
    fun `unlimited invites are active until revoked`() {
        val unlimited = invite()
        assertTrue(unlimited.active(NOW))
        assertTrue(unlimited.copy(maxUses = 0, uses = 100).active(NOW))
        assertFalse(unlimited.copy(used = true).active(NOW))
    }

    @Test
    fun `invite expires at the boundary rather than after it`() {
        val expiring = invite(expiresAt = NOW.plusSeconds(1).toString())
        assertTrue(expiring.active(NOW))
        assertFalse(expiring.active(NOW.plusSeconds(1)))
        assertFalse(expiring.active(NOW.plusSeconds(2)))
        assertFalse(expiring.copy(expiresAt = "not-a-timestamp").active(NOW))
    }

    @Test
    fun `invite use limit and revoked flag each independently make it inactive`() {
        val limited = invite(maxUses = 3, uses = 2, expiresAt = NOW.plusSeconds(60).toString())
        assertTrue(limited.active(NOW))
        assertFalse(limited.copy(uses = 3).active(NOW))
        assertFalse(limited.copy(uses = 4).active(NOW))
        assertFalse(limited.copy(used = true).active(NOW))
        assertFalse(limited.copy(expiresAt = NOW.minusSeconds(1).toString()).active(NOW))
    }

    @Test
    fun `invite snake case fields determine active status`() {
        val decoded = HarmonyJson.decodeFromString(
            ServerManagementInvite.serializer(),
            """{"id":"invite-1","code":"ABC","server_id":"server-1","created_by":"profile-1","expires_at":"2030-01-01T00:01:00Z","max_uses":2,"uses":2,"used":false}""",
        )
        assertEquals("server-1", decoded.serverId)
        assertEquals("profile-1", decoded.createdBy)
        assertEquals(2, decoded.maxUses)
        assertFalse(decoded.active(NOW))
    }

    @Test
    fun `typed invite preview exposes each refusal instead of treating JSON as success`() {
        val messages = mapOf(
            "revoked" to "revoked",
            "expired" to "expired",
            "exhausted" to "use limit",
            "not_found" to "not found",
            "unexpected-status" to "not found",
        )
        for ((status, message) in messages) {
            val preview = HarmonyJson.decodeFromString(
                ServerManagementInvitePreview.serializer(),
                """{"status":"$status","server_id":"server-1"}""",
            )
            val error = assertThrows(IllegalArgumentException::class.java) { preview.requireValid() }
            assertTrue("$status: ${error.message}", error.message.orEmpty().contains(message, ignoreCase = true))
        }
    }

    @Test
    fun `valid preview still requires an actual server ID`() {
        val valid = ServerManagementInvitePreview(status = "valid", serverId = "server-1")
        assertSame(valid, valid.requireValid())
        for (serverId in listOf(null, "", "  ")) {
            assertThrows(IllegalArgumentException::class.java) { valid.copy(serverId = serverId).requireValid() }
        }
    }

    @Test
    fun `moderation JSON with success false is a failed action even with a parsed response`() {
        val result = HarmonyJson.decodeFromString(
            ServerManagementModerationResult.serializer(),
            """{"success":false,"error":"Cannot ban the server owner","messages_deleted":0}""",
        )
        val error = assertThrows(IllegalStateException::class.java) { result.requireSuccess() }
        assertEquals("Cannot ban the server owner", error.message)
    }

    @Test
    fun `moderation without an error message still fails closed`() {
        val error = assertThrows(IllegalStateException::class.java) {
            ServerManagementModerationResult(success = false).requireSuccess()
        }
        assertTrue(error.message.orEmpty().contains("did not apply"))
        val result = HarmonyJson.decodeFromString(
            ServerManagementModerationResult.serializer(),
            """{"success":true,"messages_deleted":7}""",
        )
        assertEquals(7, result.messagesDeleted)
        assertSame(result, result.requireSuccess())
    }

    @Test
    fun `permission positions match the SQL bigint contract including positions above 31`() {
        val namesInBitOrder = listOf(
            "ADMINISTRATOR", "VIEW_CHANNEL", "MANAGE_CHANNELS", "MANAGE_ROLES",
            "MANAGE_EMOJIS", "VIEW_AUDIT_LOG", "MANAGE_WEBHOOKS", "MANAGE_SERVER",
            "CREATE_INVITE", "KICK_MEMBERS", "BAN_MEMBERS", "TIMEOUT_MEMBERS",
            "SEND_MESSAGES", "SEND_MESSAGES_IN_THREADS", "CREATE_PUBLIC_THREADS", "CREATE_PRIVATE_THREADS",
            "EMBED_LINKS", "ATTACH_FILES", "ADD_REACTIONS", "USE_EXTERNAL_EMOJIS",
            "MENTION_EVERYONE", "MANAGE_MESSAGES", "READ_MESSAGE_HISTORY", "PIN_MESSAGES",
            "CONNECT", "SPEAK", "STREAM", "MUTE_MEMBERS", "DEAFEN_MEMBERS", "MOVE_MEMBERS",
            "USE_SOUNDBOARD", "USE_EXTERNAL_SOUNDS", "CHANGE_NICKNAME", "MANAGE_NICKNAMES",
        )
        namesInBitOrder.forEachIndexed { bit, name ->
            val permission = ServerManagementPermission.valueOf(name)
            assertEquals(name, bit, permission.bit)
            assertEquals(name, 1L shl bit, permission.mask)
        }
        assertEquals(2_147_483_648L, ServerManagementPermission.USE_EXTERNAL_SOUNDS.mask)
        assertEquals(4_294_967_296L, ServerManagementPermission.CHANGE_NICKNAME.mask)
        assertEquals(8_589_934_592L, ServerManagementPermission.MANAGE_NICKNAMES.mask)
    }

    @Test
    fun `permission edits preserve unknown future bits and unrelated known bits`() {
        val unknown = (1L shl 45) or (1L shl 62)
        val existing = unknown or ServerManagementPermission.VIEW_CHANNEL.mask
        val toggled = ServerManagementPermission.MANAGE_NICKNAMES.setIn(existing, true)
        assertTrue(ServerManagementPermission.MANAGE_NICKNAMES.isIn(toggled))
        assertTrue(ServerManagementPermission.VIEW_CHANNEL.isIn(toggled))
        assertEquals(unknown, toggled and unknown)
        assertEquals(toggled, ServerManagementPermission.MANAGE_NICKNAMES.setIn(toggled, true))
        assertEquals(existing, ServerManagementPermission.MANAGE_NICKNAMES.setIn(toggled, false))
        var all = unknown
        for (permission in ServerManagementPermission.entries) all = permission.setIn(all, true)
        for (permission in ServerManagementPermission.entries) {
            assertTrue(permission.name, permission.isIn(all))
            all = permission.setIn(all, false)
        }
        assertEquals(unknown, all)
    }

    @Test
    fun `role bigint survives decoding without narrowing to a 32 bit integer`() {
        val bits = (1L shl 45) or ServerManagementPermission.MANAGE_NICKNAMES.mask
        val decoded = HarmonyJson.decodeFromString(
            ServerManagementRole.serializer(),
            """{"id":"role-1","server_id":"server-1","name":"Moderator","permissions":$bits}""",
        )
        assertEquals(bits, decoded.permissions)
        assertTrue(ServerManagementPermission.MANAGE_NICKNAMES.isIn(decoded.permissions))
    }

    @Test
    fun `accepted local member needs the specific management permission`() {
        val manager = overview(flags = mapOf("MANAGE_SERVER" to true))
        assertTrue(manager.canManage)
        assertFalse(manager.can(ServerManagementPermission.MANAGE_ROLES))
        assertFalse(manager.canEdit(role(position = 1)))
        assertFalse(overview().canManage)
        assertFalse(manager.copy(membership = null).canManage)
        for (status in listOf("pending", "banned", "rejected")) {
            assertFalse(status, manager.copy(membership = membership(status)).canManage)
        }
    }

    @Test
    fun `role manager may edit lower roles but not equal or higher roles`() {
        val manager = overview(flags = mapOf("MANAGE_ROLES" to true), rank = 10)
        assertTrue(manager.canEdit(role(position = 9)))
        assertFalse(manager.canEdit(role(position = 10)))
        assertFalse(manager.canEdit(role(position = 11)))
        assertTrue(manager.canEdit(role(position = 0, isDefault = true)))
        assertFalse(manager.canEdit(role(position = 1, isAdmin = true)))
    }

    @Test
    fun `administrator permission grants management but does not bypass role hierarchy`() {
        val administrator = overview(flags = mapOf("ADMINISTRATOR" to true), rank = 10)
        assertTrue(administrator.canManage)
        assertTrue(administrator.can(ServerManagementPermission.BAN_MEMBERS))
        assertTrue(administrator.canEdit(role(position = 9)))
        assertFalse(administrator.canEdit(role(position = 10)))
        assertFalse(administrator.canEdit(role(position = 11)))
        assertFalse(ServerManagementPermissions(mapOf("MANAGE_ROLES" to true)).allows(ServerManagementPermission.ADMINISTRATOR))
    }

    @Test
    fun `local owner uses profile identity and bypasses membership and role rank`() {
        val owner = overview(owner = PROFILE_ID).copy(membership = null, highestRolePosition = 0)
        assertTrue(owner.isOwner)
        assertTrue(owner.canManage)
        assertTrue(owner.canEdit(role(position = Int.MAX_VALUE, isAdmin = true)))
        assertTrue(owner.canEdit(role(position = 0, isDefault = true)))
        assertFalse(overview(owner = "different-auth-user-id").isOwner)
    }

    @Test
    fun `remote copy and suspended server deny writes even to owner or administrator`() {
        val ownerAdmin = overview(owner = PROFILE_ID, flags = mapOf("ADMINISTRATOR" to true))
        val remote = ownerAdmin.copy(server = ownerAdmin.server.copy(isLocal = false))
        val suspended = ownerAdmin.copy(server = ownerAdmin.server.copy(suspendedAt = NOW.toString()))
        for (readOnly in listOf(remote, suspended)) {
            for (permission in ServerManagementPermission.entries) {
                assertFalse(permission.name, readOnly.can(permission))
            }
            assertFalse(readOnly.canManage)
            assertFalse(readOnly.canEdit(role(position = 0, isDefault = true)))
        }
    }

    @Test
    fun `server creation name is trimmed and must contain 2 to 28 characters`() {
        for (name in listOf("", " ", "A", "A".repeat(29))) {
            assertNotNull(name, validateServerManagementInfo(name, "", creating = true))
        }
        for (name in listOf("AB", "A".repeat(28), "  AB  ")) {
            assertNull(name, validateServerManagementInfo(name, "", creating = true))
        }
    }

    @Test
    fun `server editing permits 1 to 100 trimmed name characters`() {
        for (name in listOf("", " \n ", "A".repeat(101))) {
            assertNotNull(name, validateServerManagementInfo(name, "", creating = false))
        }
        for (name in listOf("A", "A".repeat(100), "  A  ")) {
            assertNull(name, validateServerManagementInfo(name, "", creating = false))
        }
    }

    @Test
    fun `server description limit is 500 on both create and edit`() {
        for (creating in listOf(true, false)) {
            assertNull(validateServerManagementInfo("Server", "", creating))
            assertNull(validateServerManagementInfo("Server", "D".repeat(500), creating))
            assertNotNull(validateServerManagementInfo("Server", "D".repeat(501), creating))
        }
    }

    @Test
    fun `unrestricted invite policy allows unlimited expiry and unlimited uses`() {
        val policy = ServerManagementInvitePolicy(maxExpiration = 0, maxUses = 0)
        assertNull(validateServerManagementInviteOptions(0, 0, policy))
        assertNull(validateServerManagementInviteOptions(60, 10, policy))
        assertNull(validateServerManagementInviteOptions(Int.MAX_VALUE, Int.MAX_VALUE, policy))
    }

    @Test
    fun `invite expiry cap cannot be bypassed by choosing never`() {
        val policy = ServerManagementInvitePolicy(maxExpiration = 60, maxUses = 0)
        assertNull(validateServerManagementInviteOptions(1, 0, policy))
        assertNull(validateServerManagementInviteOptions(60, 0, policy))
        assertNotNull(validateServerManagementInviteOptions(61, 0, policy))
        assertNotNull(validateServerManagementInviteOptions(0, 0, policy))
    }

    @Test
    fun `invite use cap cannot be bypassed by choosing unlimited`() {
        val policy = ServerManagementInvitePolicy(maxExpiration = 0, maxUses = 5)
        assertNull(validateServerManagementInviteOptions(0, 1, policy))
        assertNull(validateServerManagementInviteOptions(0, 5, policy))
        assertNotNull(validateServerManagementInviteOptions(0, 6, policy))
        assertNotNull(validateServerManagementInviteOptions(0, 0, policy))
    }

    @Test
    fun `both invite caps apply independently and reject negative limits`() {
        val capped = ServerManagementInvitePolicy(maxExpiration = 60, maxUses = 5)
        assertNull(validateServerManagementInviteOptions(60, 5, capped))
        for ((expiry, uses) in listOf(0 to 0, 0 to 5, 60 to 0, 61 to 5, 60 to 6)) {
            assertNotNull("expiry=$expiry uses=$uses", validateServerManagementInviteOptions(expiry, uses, capped))
        }
        for (policy in listOf(capped, ServerManagementInvitePolicy())) {
            assertNotNull(validateServerManagementInviteOptions(-1, 1, policy))
            assertNotNull(validateServerManagementInviteOptions(1, -1, policy))
            assertNotNull(validateServerManagementInviteOptions(-1, -1, policy))
        }
    }

    private fun invite(
        expiresAt: String? = null,
        maxUses: Int? = null,
        uses: Int = 0,
    ) = ServerManagementInvite(
        id = "invite-1", code = "ABC", serverId = "server-1", createdBy = PROFILE_ID,
        expiresAt = expiresAt, maxUses = maxUses, uses = uses,
    )

    private fun membership(status: String = "accepted") = ServerManagementMembership(
        id = "membership-1", serverId = "server-1", userId = PROFILE_ID, status = status,
    )

    private fun overview(
        owner: String = "another-profile",
        flags: Map<String, Boolean> = emptyMap(),
        rank: Int = 10,
    ) = ServerManagementOverview(
        server = ServerManagementServer(id = "server-1", name = "Server", owner = owner),
        permissions = ServerManagementPermissions(flags), profileId = PROFILE_ID,
        membership = membership(), highestRolePosition = rank,
        invitePolicy = ServerManagementInvitePolicy(),
    )

    private fun role(position: Int, isDefault: Boolean = false, isAdmin: Boolean = false) = ServerManagementRole(
        id = "role-1", serverId = "server-1", name = "Role", position = position,
        isDefault = isDefault, isAdmin = isAdmin,
    )

    companion object {
        private const val ORIGIN = "https://harmony.example"
        private const val PROFILE_ID = "profile-id-not-auth-id"
        private val NOW: Instant = Instant.parse("2030-01-01T00:00:00Z")
    }
}
