package lol.mony.harmony

import java.time.Instant
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import lol.mony.harmony.data.api.ApiException
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.api.RestApi
import lol.mony.harmony.data.model.HarmonyJson
import lol.mony.harmony.data.model.Notification
import lol.mony.harmony.data.repo.NotificationRepository
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class NotificationRepositoryTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(20)

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var endpoint: Endpoint
    private lateinit var repository: NotificationRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
        endpoint = Endpoint(server.url("/").toString(), "test-anon-key")
        repository = NotificationRepository(RestApi(client))
    }

    @After
    fun tearDown() {
        server.shutdown()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    @Test
    fun fetchUsesTheVisibilityRpcWithProfileAndAllRequestedArguments() = runBlocking {
        enqueue(notificationRows())

        val rows = repository.fetchNotifications(
            endpoint, TOKEN, PROFILE_ID,
            limit = 7, offset = 14, unreadOnly = true,
            notificationTypes = listOf("activitypub_mention", "activitypub_follow_request"),
        )

        val request = request("POST", "/rest/v1/rpc/get_user_notifications")
        assertEquals(
            json("""{
                "p_user_id":"$PROFILE_ID", "p_limit":7, "p_offset":14, "p_unread_only":true,
                "p_notification_types":["activitypub_mention","activitypub_follow_request"]
            }"""),
            body(request),
        )
        assertEquals(1, rows.size)
        assertEquals(NOTIFICATION_ID, rows.single().id)
        assertEquals(PROFILE_ID, rows.single().userId)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun fetchDefaultsSendAnExplicitNullTypeFilter() = runBlocking {
        enqueue("[]")

        assertTrue(repository.fetchNotifications(endpoint, TOKEN, PROFILE_ID).isEmpty())

        assertEquals(
            json("""{
                "p_user_id":"$PROFILE_ID", "p_limit":20, "p_offset":0,
                "p_unread_only":false, "p_notification_types":null
            }"""),
            body(request("POST", "/rest/v1/rpc/get_user_notifications")),
        )
    }

    @Test
    fun anExplicitEmptyTypeFilterRemainsAnArrayRatherThanBecomingNoFilter() = runBlocking {
        enqueue("[]")

        repository.fetchNotifications(endpoint, TOKEN, PROFILE_ID, notificationTypes = emptyList())

        assertEquals(
            json("""{"types":[]}""")["types"],
            body(request("POST", "/rest/v1/rpc/get_user_notifications"))["p_notification_types"],
        )
    }

    @Test
    fun rpcErrorsPropagateWithoutFallingBackToANotificationsGet() = runBlocking {
        enqueue("""{"message":"notifications denied","details":"visibility RPC failed"}""", status = 403)
        // A forbidden fallback would finish instead of hanging, and make the assertions fail.
        enqueue(notificationRows())

        val failure = runCatching { repository.fetchNotifications(endpoint, TOKEN, PROFILE_ID) }.exceptionOrNull()

        assertTrue("Expected the RPC error, got $failure", failure is ApiException)
        assertEquals(403, (failure as ApiException).status)
        assertTrue(failure.message.orEmpty().contains("notifications denied"))
        request("POST", "/rest/v1/rpc/get_user_notifications")
        assertEquals("RPC failure must never issue a direct table read", 1, server.requestCount)
    }

    @Test
    fun invalidRpcResponsesAlsoFailWithoutADirectTableFallback() = runBlocking {
        enqueue("""{"unexpected":"not a row array"}""")
        enqueue(notificationRows())

        val failure = runCatching { repository.fetchNotifications(endpoint, TOKEN, PROFILE_ID) }.exceptionOrNull()

        assertTrue("Expected a decoding failure, got $failure", failure is SerializationException)
        request("POST", "/rest/v1/rpc/get_user_notifications")
        assertEquals(1, server.requestCount)
    }

    @Test
    fun markAsReadPatchesOnlyTheOwnedNotificationAndReturnsTheRepresentation() = runBlocking {
        enqueue(notificationRows(isRead = true))
        val before = Instant.now()

        val row = repository.markAsRead(endpoint, TOKEN, PROFILE_ID, NOTIFICATION_ID)

        val after = Instant.now()
        val patch = body(ownedNotificationPatch())
        assertEquals(setOf("is_read", "read_at"), patch.keys)
        assertEquals(JsonPrimitive(true), patch["is_read"])
        assertTimestamp(patch, "read_at", before, after)
        assertTrue(row.isRead)
        assertEquals(STORED_READ_AT, row.readAt)
    }

    @Test
    fun markAsUnreadClearsReadAtWithAnExplicitJsonNull() = runBlocking {
        enqueue(notificationRows())

        val row = repository.markAsUnread(endpoint, TOKEN, PROFILE_ID, NOTIFICATION_ID)

        assertEquals(json("""{"is_read":false,"read_at":null}"""), body(ownedNotificationPatch()))
        assertEquals(false, row.isRead)
        assertEquals(null, row.readAt)
    }

    @Test
    fun markAsClickedMarksReadAndClickedInOneOwnedPatch() = runBlocking {
        enqueue(notificationRows(isRead = true, isClicked = true))
        val before = Instant.now()

        val row = repository.markAsClicked(endpoint, TOKEN, PROFILE_ID, NOTIFICATION_ID)

        val after = Instant.now()
        val patch = body(ownedNotificationPatch())
        assertEquals(setOf("is_clicked", "is_read", "read_at"), patch.keys)
        assertEquals(JsonPrimitive(true), patch["is_clicked"])
        assertEquals(JsonPrimitive(true), patch["is_read"])
        assertTimestamp(patch, "read_at", before, after)
        assertTrue(row.isClicked)
        assertTrue(row.isRead)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun emptyRepresentationsFailAllGuardedSingleNotificationUpdates() = runBlocking {
        val actions: List<suspend () -> Notification> = listOf(
            { repository.markAsRead(endpoint, TOKEN, PROFILE_ID, NOTIFICATION_ID) },
            { repository.markAsUnread(endpoint, TOKEN, PROFILE_ID, NOTIFICATION_ID) },
            { repository.markAsClicked(endpoint, TOKEN, PROFILE_ID, NOTIFICATION_ID) },
        )
        for (action in actions) {
            enqueue("[]")

            val failure = runCatching { action() }.exceptionOrNull()

            assertTrue("An owned update matching no row must fail, got $failure", failure is IllegalStateException)
            ownedNotificationPatch()
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun markAllReadFiltersByProfileAndUnreadStateAndCountsReturnedIds() = runBlocking {
        enqueue("""[{"id":"first"},{"id":"second"}]""")
        val before = Instant.now()

        val count = repository.markAllAsRead(endpoint, TOKEN, PROFILE_ID)

        val after = Instant.now()
        val patch = body(request(
            "PATCH", "/rest/v1/notifications",
            mapOf("select" to "id", "user_id" to "eq.$PROFILE_ID", "is_read" to "eq.false"),
        ))
        assertEquals(2, count)
        assertEquals(setOf("is_read", "read_at"), patch.keys)
        assertEquals(JsonPrimitive(true), patch["is_read"])
        assertTimestamp(patch, "read_at", before, after)
    }

    @Test
    fun markAllReadCanSucceedWithNoUnreadNotifications() = runBlocking {
        enqueue("[]")

        assertEquals(0, repository.markAllAsRead(endpoint, TOKEN, PROFILE_ID))
        request(
            "PATCH", "/rest/v1/notifications",
            mapOf("select" to "id", "user_id" to "eq.$PROFILE_ID", "is_read" to "eq.false"),
        )
        Unit
    }

    @Test
    fun acceptingFollowRequestPatchesOnlyAnOwnedPendingFollowAndSelectsId() = runBlocking {
        enqueue("""[{"id":"follow-1"}]""")
        val before = Instant.now()

        repository.respondToFollowRequest(endpoint, TOKEN, PROFILE_ID, FOLLOWER_ID, accept = true)

        val after = Instant.now()
        val patch = body(followRequestPatch())
        assertEquals(setOf("status", "accepted_at"), patch.keys)
        assertEquals(JsonPrimitive("accepted"), patch["status"])
        assertTimestamp(patch, "accepted_at", before, after)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun rejectingFollowRequestUpdatesPendingStatusWithoutAcceptedAtOrDelete() = runBlocking {
        enqueue("""[{"id":"follow-1"}]""")

        repository.respondToFollowRequest(endpoint, TOKEN, PROFILE_ID, FOLLOWER_ID, accept = false)

        assertEquals(json("""{"status":"rejected"}"""), body(followRequestPatch()))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun acceptingOrRejectingANoLongerPendingFollowFailsRatherThanClaimingSuccess() = runBlocking {
        for (accept in listOf(true, false)) {
            enqueue("[]")

            val failure = runCatching {
                repository.respondToFollowRequest(endpoint, TOKEN, PROFILE_ID, FOLLOWER_ID, accept)
            }.exceptionOrNull()

            assertTrue("No-op follow response must fail (accept=$accept), got $failure", failure is IllegalStateException)
            followRequestPatch()
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun deleteOneUsesBothNotificationAndProfileOwnershipFilters() = runBlocking {
        // Keep ownership coverage independent of RestApi's separate blank-204 handling.
        enqueue("{}")

        repository.deleteNotification(endpoint, TOKEN, PROFILE_ID, NOTIFICATION_ID)

        request(
            "DELETE", "/rest/v1/notifications",
            mapOf("select" to "*", "user_id" to "eq.$PROFILE_ID", "id" to "eq.$NOTIFICATION_ID"),
        )
        Unit
    }

    @Test
    fun deleteAllUsesOnlyTheActiveProfileOwnershipFilter() = runBlocking {
        enqueue("{}")

        repository.deleteAllNotifications(endpoint, TOKEN, PROFILE_ID)

        request("DELETE", "/rest/v1/notifications", mapOf("select" to "*", "user_id" to "eq.$PROFILE_ID"))
        Unit
    }

    @Test
    fun everyPublicSuspendOperationExecutesHttpOutsideTheCallerDispatcher() {
        val httpThreads = Collections.synchronizedList(mutableListOf<Thread>())
        val observedClient = client.newBuilder().addInterceptor { chain ->
            httpThreads.add(Thread.currentThread())
            chain.proceed(chain.request())
        }.build()
        val observedRepository = NotificationRepository(RestApi(observedClient))
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "notification-test-caller") }
            .asCoroutineDispatcher().use { callerDispatcher ->
                runBlocking(callerDispatcher) {
                    val callerThread = Thread.currentThread()
                    enqueue("[]")
                    observedRepository.fetchNotifications(endpoint, TOKEN, PROFILE_ID)
                    enqueue(notificationRows(isRead = true))
                    observedRepository.markAsRead(endpoint, TOKEN, PROFILE_ID, NOTIFICATION_ID)
                    enqueue(notificationRows())
                    observedRepository.markAsUnread(endpoint, TOKEN, PROFILE_ID, NOTIFICATION_ID)
                    enqueue(notificationRows(isRead = true, isClicked = true))
                    observedRepository.markAsClicked(endpoint, TOKEN, PROFILE_ID, NOTIFICATION_ID)
                    enqueue("[]")
                    observedRepository.markAllAsRead(endpoint, TOKEN, PROFILE_ID)
                    enqueue("{}")
                    observedRepository.deleteNotification(endpoint, TOKEN, PROFILE_ID, NOTIFICATION_ID)
                    enqueue("{}")
                    observedRepository.deleteAllNotifications(endpoint, TOKEN, PROFILE_ID)
                    enqueue("""[{"id":"follow-1"}]""")
                    observedRepository.respondToFollowRequest(endpoint, TOKEN, PROFILE_ID, FOLLOWER_ID, accept = true)
                    enqueue(PROFILE_ROWS)
                    observedRepository.fetchProfilePreview(endpoint, TOKEN, PROFILE_ID)
                    enqueue("""[{
                        "id":"post-1","author_id":"$PROFILE_ID","content":"A post",
                        "created_at":"2026-10-01T10:00:00Z"
                    }]""")
                    enqueue(PROFILE_ROWS)
                    observedRepository.fetchPostPreview(endpoint, TOKEN, "post-1")

                    assertEquals("One request per operation plus the post author lookup", 11, httpThreads.size)
                    httpThreads.forEachIndexed { index, httpThread ->
                        assertNotSame("HTTP request ${index + 1} ran on the caller dispatcher", callerThread, httpThread)
                    }
                }
            }
    }

    private fun ownedNotificationPatch(): RecordedRequest = request(
        "PATCH", "/rest/v1/notifications",
        mapOf("select" to "*", "user_id" to "eq.$PROFILE_ID", "id" to "eq.$NOTIFICATION_ID"),
    )

    private fun followRequestPatch(): RecordedRequest = request(
        "PATCH", "/rest/v1/follows",
        mapOf(
            "select" to "id", "follower_id" to "eq.$FOLLOWER_ID",
            "following_id" to "eq.$PROFILE_ID", "status" to "eq.pending",
        ),
    )

    private fun request(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
    ): RecordedRequest {
        val recorded = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull("Expected $method $path", recorded)
        val request = requireNotNull(recorded)
        val url = requireNotNull(request.requestUrl)
        assertEquals(method, request.method)
        assertEquals(path, url.encodedPath)
        assertEquals(query.keys, url.queryParameterNames)
        query.forEach { (key, value) -> assertEquals("query $key", listOf(value), url.queryParameterValues(key)) }
        assertEquals("Bearer $TOKEN", request.getHeader("Authorization"))
        assertEquals(endpoint.anonKey, request.getHeader("apikey"))
        if (method == "PATCH") assertEquals("return=representation", request.getHeader("Prefer"))
        return request
    }

    private fun enqueue(body: String, status: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body))
    }

    private fun body(request: RecordedRequest): JsonObject = json(request.body.readUtf8())

    private fun json(text: String): JsonObject = HarmonyJson.decodeFromString(JsonObject.serializer(), text)

    private fun assertTimestamp(patch: JsonObject, field: String, before: Instant, after: Instant) {
        val value = (patch[field] as? JsonPrimitive)?.contentOrNull
        assertNotNull("$field must contain an ISO timestamp", value)
        val instant = Instant.parse(requireNotNull(value))
        assertTrue("$field must be generated by this write", !instant.isBefore(before) && !instant.isAfter(after))
    }

    private fun notificationRows(isRead: Boolean = false, isClicked: Boolean = false): String = """[{
        "id":"$NOTIFICATION_ID", "user_id":"$PROFILE_ID", "type":"activitypub_reaction", "data":{},
        "is_read":$isRead, "is_clicked":$isClicked, "created_at":"2026-10-01T10:00:00Z",
        "updated_at":null, "expires_at":null, "read_at":${if (isRead) "\"$STORED_READ_AT\"" else "null"}
    }]"""

    private companion object {
        // Deliberately distinct from an auth user id or the opaque access token.
        const val PROFILE_ID = "profile-owner-not-auth-user"
        const val FOLLOWER_ID = "requesting-profile"
        const val NOTIFICATION_ID = "notification-1"
        const val TOKEN = "test-access-token"
        const val STORED_READ_AT = "2026-10-01T11:00:00Z"
        const val PROFILE_ROWS = """[{"id":"$PROFILE_ID","username":"owner","is_local":true}]"""
    }
}
