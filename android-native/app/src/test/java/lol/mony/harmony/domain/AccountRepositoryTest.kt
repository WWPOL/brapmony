package lol.mony.harmony.domain

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.api.RestApi
import lol.mony.harmony.data.model.AccountNotifications
import lol.mony.harmony.data.model.AccountProfileEdit
import lol.mony.harmony.data.model.HarmonyJson
import lol.mony.harmony.data.repo.AccountRepository
import lol.mony.harmony.data.repo.AccountSecurityRepository
import lol.mony.harmony.ui.theme.VisualThemeSettings
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AccountRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var endpoint: Endpoint
    private lateinit var repository: AccountRepository
    private lateinit var security: AccountSecurityRepository
    private val networkThread = AtomicReference<String>()

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        endpoint = Endpoint(server.url("/").toString(), "public-anon-key")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            networkThread.set(Thread.currentThread().name)
            chain.proceed(chain.request())
        }.build()
        val rest = RestApi(client)
        repository = AccountRepository(rest)
        security = AccountSecurityRepository(rest, client)
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun accountReadUsesUserJwtAndAuthFilterOffCallerThread() = runBlocking {
        val callerThread = Thread.currentThread().name
        respond("""[{"id":"profile-id","auth_user_id":"auth-id","username":"person"}]""")
        val profile = repository.profile(endpoint, "USER-JWT", "auth-id").getOrThrow()
        assertEquals("profile-id", profile.id)
        val request = take()
        assertEquals("Bearer USER-JWT", request.getHeader("Authorization"))
        assertEquals("public-anon-key", request.getHeader("apikey"))
        assertEquals("eq.auth-id", request.requestUrl!!.queryParameter("auth_user_id"))
        assertNotEquals(callerThread, networkThread.get())
    }

    @Test fun profileSavePatchesOnlyEditedFieldsByProfileId() = runBlocking {
        respond("""[{"id":"profile-id","display_name":"New"}]""")
        repository.updateProfile(endpoint, "USER-JWT", "profile-id", AccountProfileEdit(displayName = "Old"), AccountProfileEdit(displayName = "New")).getOrThrow()
        val request = take()
        assertEquals("PATCH", request.method)
        assertEquals("eq.profile-id", request.requestUrl!!.queryParameter("id"))
        assertEquals(json("""{"display_name":"New"}"""), json(request.body.readUtf8()))
    }

    @Test fun zeroRowProfileWriteIsNotSuccess() = runBlocking {
        respond("[]")
        assertTrue(repository.updateProfile(endpoint, "USER-JWT", "profile-id", AccountProfileEdit(displayName = "Old"), AccountProfileEdit(displayName = "New")).isFailure)
    }

    @Test fun appearanceFreshlyMergesUnknownKeysWithoutStaleOverwrite() = runBlocking {
        respond("""[{"id":"profile-id","appearance_settings":{"theme":"dark","future":{"new":true},"zoomLevel":130,"animateEmoji":false}}]""")
        respond("""[{"id":"profile-id","appearance_settings":{"theme":"light","future":{"new":true},"zoomLevel":130,"animateEmoji":false}}]""")
        val baseline = VisualThemeSettings()
        repository.saveAppearance(endpoint, "USER-JWT", "profile-id", baseline, baseline.copy(theme = "light")).getOrThrow()
        assertEquals("GET", take().method)
        val patch = json(take().body.readUtf8())["appearance_settings"] as JsonObject
        assertEquals(JsonPrimitive(130), patch["zoomLevel"])
        assertEquals(JsonPrimitive(false), patch["animateEmoji"])
        assertEquals(json("""{"new":true}"""), patch["future"])
        assertEquals(JsonPrimitive("light"), patch["theme"])
        assertFalse(patch.containsKey("showTimestamps"))
    }

    @Test fun existingNotificationPreferencesArePatchedNotInserted() = runBlocking {
        respond("""[{"push_notifications":true}]""")
        respond("""[{"push_notifications":false}]""")
        repository.saveNotifications(endpoint, "USER-JWT", "profile-id", AccountNotifications(), AccountNotifications(push = false)).getOrThrow()
        assertEquals("GET", take().method)
        val request = take()
        assertEquals("PATCH", request.method)
        assertEquals("eq.profile-id", request.requestUrl!!.queryParameter("user_id"))
        assertEquals(json("""{"push_notifications":false}"""), json(request.body.readUtf8()))
    }

    @Test fun missingNotificationRowIsCreatedWithProfileNotAuthId() = runBlocking {
        respond("[]")
        respond("""[{"push_notifications":false}]""")
        repository.saveNotifications(endpoint, "USER-JWT", "profile-id", AccountNotifications(), AccountNotifications(push = false)).getOrThrow()
        take()
        val request = take()
        assertEquals("POST", request.method)
        assertEquals(JsonPrimitive("profile-id"), json(request.body.readUtf8())["user_id"])
    }

    @Test fun unblockAcceptsEmpty204OnlyAfterCheckingAbsence() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        respond("[]")
        repository.unblock(endpoint, "USER-JWT", "profile-id", "other-profile").getOrThrow()
        assertEquals("DELETE", take().method)
        assertEquals("GET", take().method)
    }

    @Test fun scalarSessionRevokeDistinguishesFalseFromSuccess() = runBlocking {
        respond("false")
        assertFalse(security.revokeSession(endpoint, "USER-JWT", "session-id").getOrThrow())
        val request = take()
        assertEquals("/rest/v1/rpc/revoke_my_session", request.requestUrl!!.encodedPath)
        assertEquals(json("""{"p_session_id":"session-id"}"""), json(request.body.readUtf8()))
    }

    @Test fun securityScalarObjectsAndCountsAreTyped() = runBlocking {
        respond("""{"mfa_enabled":false,"total":0,"remaining":0,"generated_at":null}""")
        respond("2")
        assertFalse(security.recoveryStatus(endpoint, "USER-JWT").getOrThrow().mfaEnabled)
        assertEquals(2, security.signOutOtherSessions(endpoint, "USER-JWT").getOrThrow())
        take()
        assertEquals(json("""{"p_scope":"others"}"""), json(take().body.readUtf8()))
    }

    @Test fun wrongCurrentPasswordNeverCallsGoTrueUpdateUser() = runBlocking {
        respond("""{"id":"auth-id","identities":[{"provider":"email"}],"app_metadata":{"providers":["email"]}}""")
        respond("false")
        assertTrue(security.changePassword(endpoint, "USER-JWT", "wrong-current", "new-long-password").isFailure)
        assertEquals("GET", take().method)
        assertEquals("/rest/v1/rpc/verify_my_password", take().requestUrl!!.encodedPath)
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test fun passwordChangeUsesVerificationThenAuthorizedPut() = runBlocking {
        respond("""{"id":"auth-id","identities":[{"provider":"email"}]}""")
        respond("true")
        respond("""{"id":"auth-id"}""")
        security.changePassword(endpoint, "USER-JWT", "current-password", "new-long-password").getOrThrow()
        take(); take()
        val request = take()
        assertEquals("PUT", request.method)
        assertEquals("/auth/v1/user", request.requestUrl!!.encodedPath)
        assertEquals("Bearer USER-JWT", request.getHeader("Authorization"))
        assertEquals(json("""{"password":"new-long-password"}"""), json(request.body.readUtf8()))
    }

    private fun respond(body: String) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body)) }
    private fun take() = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)) { "Expected request was not made" }
    private fun json(text: String) = HarmonyJson.parseToJsonElement(text) as JsonObject
}
