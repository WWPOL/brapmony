package lol.mony.harmony.domain

import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.api.RestApi
import lol.mony.harmony.data.model.AccountDmPolicy
import lol.mony.harmony.data.model.AccountProfile
import lol.mony.harmony.data.model.AccountProfileField
import lol.mony.harmony.data.model.HarmonyJson
import lol.mony.harmony.data.repo.AccountRepository
import lol.mony.harmony.data.repo.AccountSecurityRepository
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in against two throwaway GoTrue users, never seeded staff or a service key.
 * HARMONY_NATIVE_ACCOUNT_E2E_FILE points to a private JSON fixture (not committed):
 * {baseUrl,anonKey,users:[{email,password,username,token,authId,profileId}, ...]}.
 * Creates no accounts itself and only edits those provided. The first password is changed and
 * its private fixture updated so the fixture owner can clean up. No credential is logged.
 */
class AccountLocalBackendContractTest {
    @Test fun realUserJwtAccountContracts() = runBlocking {
        val path = System.getenv("HARMONY_NATIVE_ACCOUNT_E2E_FILE")
        assumeTrue("Local account backend contract is opt-in", !path.isNullOrBlank())
        val file = File(requireNotNull(path))
        var fixture = HarmonyJson.decodeFromString(AccountLiveFixture.serializer(), file.readText())
        val owner = fixture.users[0]
        val other = fixture.users[1]
        val endpoint = Endpoint(fixture.baseUrl, fixture.anonKey)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            // Complete request sequence/status, deliberately no auth headers or password bodies.
            println("account HTTP ${request.method} ${request.url.encodedPath} -> ${response.code}")
            response
        }.build()
        val rest = RestApi(client)
        val repository = AccountRepository(rest)
        val security = AccountSecurityRepository(rest, client)
        val mine = repository.profile(endpoint, owner.token, owner.authId).getOrThrow()
        assertEquals(owner.profileId, mine.id)
        assertNotEquals("The fixture must exercise distinct profile/auth ids", owner.authId, mine.id)
        val edit = mine.edit().copy(
            displayName = "Native contract person", bio = "Native bio with \"quotes\" and a newline\nVerified.",
            pronouns = "they/them", color = "#1A96A6", fields = listOf(AccountProfileField("Site", "https://example.test/native")),
        )
        val savedProfile = repository.updateProfile(endpoint, owner.token, mine.id, mine.edit(), edit).getOrThrow()
        assertEquals(edit.displayName, savedProfile.displayName)
        assertEquals(edit.bio, savedProfile.bio)
        assertEquals(edit.fields, savedProfile.profileFields)
        assertEquals(edit.pronouns, repository.profile(endpoint, owner.token, owner.authId).getOrThrow().pronouns)
        println("PASS real profile edit/readback with distinct profile/auth ids")

        val beforePrivacy = AccountRepository.privacy(savedProfile)
        val privateProfile = repository.savePrivacy(endpoint, owner.token, mine.id, beforePrivacy,
            beforePrivacy.copy(requireFollowApproval = true, dmPolicy = AccountDmPolicy.Known)).getOrThrow()
        assertTrue(privateProfile.manuallyApprovesFollowers == true)
        assertEquals("known", privateProfile.dmPolicy)
        if (repository.blockedPeople(endpoint, owner.token, mine.id).getOrThrow().none { it.id == other.profileId }) {
            rest.insert(endpoint, owner.token, "user_blocks", buildJsonObject {
                put("blocker_id", mine.id); put("blocked_user_id", other.profileId)
            }.toString(), serializer = ListSerializer(JsonObject.serializer())).getOrThrow()
        }
        if (repository.mutedPeople(endpoint, owner.token, mine.id).getOrThrow().none { it.id == other.profileId }) {
            rest.insert(endpoint, owner.token, "user_mutes", buildJsonObject {
                put("muter_id", mine.id); put("muted_user_id", other.profileId)
            }.toString(), serializer = ListSerializer(JsonObject.serializer())).getOrThrow()
        }
        assertTrue(repository.blockedPeople(endpoint, owner.token, mine.id).getOrThrow().any { it.id == other.profileId })
        assertTrue(repository.mutedPeople(endpoint, owner.token, mine.id).getOrThrow().any { it.id == other.profileId })
        repository.unblock(endpoint, owner.token, mine.id, other.profileId).getOrThrow()
        repository.unmute(endpoint, owner.token, mine.id, other.profileId).getOrThrow()
        assertFalse(repository.blockedPeople(endpoint, owner.token, mine.id).getOrThrow().any { it.id == other.profileId })
        assertFalse(repository.mutedPeople(endpoint, owner.token, mine.id).getOrThrow().any { it.id == other.profileId })
        println("PASS real follower/DM privacy and unblock/unmute, including 204 verification")

        fun seedAppearance(future: String): JsonObject = buildJsonObject {
            put("appearance_settings", buildJsonObject {
                put("theme", "dark"); put("animateEmoji", false); put("zoomLevel", 135)
                put("future_native_contract", buildJsonObject { put("value", future) })
                put("customSkinCss", "body { --future: retained; }")
            })
        }
        rest.update(endpoint, owner.token, "profiles", listOf("id" to "eq.${mine.id}"), seedAppearance("old").toString(),
            returning = AccountRepository.PROFILE_COLUMNS, serializer = ListSerializer(AccountProfile.serializer())).getOrThrow()
        val baseline = repository.appearance(endpoint, owner.token, mine.id).getOrThrow()
        rest.update(endpoint, owner.token, "profiles", listOf("id" to "eq.${mine.id}"), seedAppearance("new").toString(),
            returning = AccountRepository.PROFILE_COLUMNS, serializer = ListSerializer(AccountProfile.serializer())).getOrThrow()
        val look = repository.saveAppearance(endpoint, owner.token, mine.id, baseline, baseline.copy(
            theme = "midnight", fontSize = 19, showTimestamps = false, use24HourTime = true,
            compactMode = true, highContrast = true, reduceMotion = true,
        )).getOrThrow()
        assertEquals("midnight", look.theme)
        assertEquals(19, look.fontSize)
        assertEquals(135, look.zoomLevel)
        assertFalse(look.animateEmoji)
        assertEquals(JsonPrimitive("new"), (look.unknown["future_native_contract"] as JsonObject)["value"])
        assertTrue(look.highContrast && look.reduceMotion && look.compactMode && look.use24HourTime)
        println("PASS real appearance merge preserves concurrent unknown keys and web-only fields")

        // Exercise the genuinely absent-row branch too, not just a profile trigger's default row.
        rest.delete(endpoint, owner.token, "notification_preferences", listOf("user_id" to "eq.${mine.id}")).getOrThrow()
        val notifications = repository.notifications(endpoint, owner.token, mine.id).getOrThrow()
        val desired = notifications.copy(push = false, pushOfflineOnly = true, dnd = true, quietStart = "22:15:00", quietEnd = "07:45:00")
        repository.saveNotifications(endpoint, owner.token, mine.id, notifications, desired).getOrThrow()
        val readback = repository.notifications(endpoint, owner.token, mine.id).getOrThrow()
        assertEquals(desired, readback)
        repository.saveNotifications(endpoint, owner.token, mine.id, readback, readback.copy(push = true)).getOrThrow()
        assertTrue(repository.notifications(endpoint, owner.token, mine.id).getOrThrow().push)
        println("PASS real notification initial insert and existing-row PATCH with profile-id ownership")

        assertEquals(owner.authId, security.identity(endpoint, owner.token).getOrThrow().id)
        assertFalse(security.recoveryStatus(endpoint, owner.token).getOrThrow().mfaEnabled)
        val ownSessions = security.sessions(endpoint, owner.token).getOrThrow()
        assertTrue(ownSessions.any { it.isCurrent })
        val foreignSessions = security.sessions(endpoint, other.token).getOrThrow()
        assertFalse(security.revokeSession(endpoint, owner.token, foreignSessions.first().id).getOrThrow())
        assertEquals(foreignSessions.map { it.id }, security.sessions(endpoint, other.token).getOrThrow().map { it.id })
        fun signIn(password: String): String {
            val body = buildJsonObject { put("email", owner.email); put("password", password) }.toString()
            val request = Request.Builder().url("${endpoint.authUrl}/token?grant_type=password")
                .header("apikey", endpoint.anonKey).post(body.toRequestBody("application/json".toMediaType())).build()
            return client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Throwaway-user password sign-in failed (${response.code})." }
                HarmonyJson.parseToJsonElement(response.body.string()).jsonObject.getValue("access_token").jsonPrimitive.content
            }
        }
        val secondToken = signIn(owner.password)
        val revocable = security.sessions(endpoint, secondToken).getOrThrow().first { it.isCurrent }
        assertTrue(security.revokeSession(endpoint, owner.token, revocable.id).getOrThrow())
        assertTrue(security.sessions(endpoint, secondToken).isFailure)
        val thirdToken = signIn(owner.password)
        val expectedOthers = security.sessions(endpoint, owner.token).getOrThrow().count { !it.isCurrent }
        assertTrue(expectedOthers >= 1)
        assertEquals(expectedOthers, security.signOutOtherSessions(endpoint, owner.token).getOrThrow())
        assertTrue(security.sessions(endpoint, thirdToken).isFailure)
        println("PASS real session listing/revoke/others scalar responses and foreign-session authorization")

        assertTrue(security.changePassword(endpoint, owner.token, "known-wrong-password", "UnusedNativePass123!").isFailure)
        val nextPassword = "Native-" + UUID.randomUUID().toString() + "-Aa1"
        security.changePassword(endpoint, owner.token, owner.password, nextPassword).getOrThrow()
        fixture = fixture.copy(users = fixture.users.mapIndexed { index, user -> if (index == 0) user.copy(password = nextPassword) else user })
        file.writeText(HarmonyJson.encodeToString(AccountLiveFixture.serializer(), fixture))
        signIn(nextPassword)
        println("PASS real current-password verification and GoTrue password update/readback sign-in")
    }
}

@Serializable
private data class AccountLiveFixture(val baseUrl: String, val anonKey: String, val users: List<AccountLiveUser>)
@Serializable
private data class AccountLiveUser(val email: String, val password: String, val username: String, val token: String, val authId: String, val profileId: String)
