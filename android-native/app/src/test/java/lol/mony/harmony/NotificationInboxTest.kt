package lol.mony.harmony

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.api.RestApi
import lol.mony.harmony.data.model.HarmonyJson
import lol.mony.harmony.data.model.NotificationInboxFilter
import lol.mony.harmony.data.repo.NotificationRepository
import lol.mony.harmony.ui.screens.notifications.NotificationInbox
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NotificationInboxTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var repository: NotificationRepository
    private lateinit var endpoint: Endpoint

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
        endpoint = Endpoint(server.url("/").toString(), "test-key")
        repository = NotificationRepository(RestApi(client))
    }

    @After fun tearDown() {
        server.shutdown()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    @Test fun failedInitialLoadIsAnErrorNotAnEmptySuccessAndRetryRecovers() = runBlocking {
        val inbox = inbox()
        enqueue("""{"message":"Unavailable"}""", 503)
        inbox.load()
        assertNotNull(inbox.state.value.loadError)
        assertTrue(inbox.state.value.items.isEmpty())
        assertFalse(inbox.state.value.loading)
        enqueue(rows(1))
        inbox.retryLoad()
        assertNull(inbox.state.value.loadError)
        assertEquals(1, inbox.state.value.items.size)
        assertFalse(inbox.state.value.hasMore)
    }

    @Test fun failedNextPagePreservesRowsAndRetryUsesTheSameOffset() = runBlocking {
        val inbox = inbox()
        enqueue(rows(20))
        inbox.load()
        takeOffset(0)
        enqueue("""{"message":"Unavailable"}""", 503)
        inbox.load(reset = false)
        takeOffset(20)
        assertEquals(20, inbox.state.value.items.size)
        assertEquals(20, inbox.state.value.nextOffset)
        assertNotNull(inbox.state.value.loadError)
        enqueue(rows(1, start = 20))
        inbox.retryLoad()
        takeOffset(20)
        assertEquals(21, inbox.state.value.items.size)
        assertEquals(21, inbox.state.value.nextOffset)
        assertFalse(inbox.state.value.hasMore)
    }

    @Test fun openingUnreadItemPersistsBeforeCallbackAndAdjustsPagingMembership() = runBlocking {
        val inbox = inbox(unreadOnly = true)
        enqueue(rows(20))
        inbox.load()
        takeOffset(0)
        var opened = false
        enqueue("[${row(0, read = true, clicked = true)}]")
        inbox.open(inbox.state.value.items.first()) { notification ->
            assertTrue(notification.isRead)
            assertTrue(notification.isClicked)
            opened = true
        }
        assertTrue(opened)
        assertEquals(19, inbox.state.value.items.size)
        assertEquals(19, inbox.state.value.nextOffset)
        assertEquals("PATCH", server.takeRequest(2, TimeUnit.SECONDS)!!.method)
        enqueue(rows(1, start = 20))
        inbox.load(reset = false)
        takeOffset(19)
    }

    @Test fun failedReadActionDoesNotChangeLocalReadStateAndCanBeRetried() = runBlocking {
        val inbox = inbox()
        enqueue(rows(1))
        inbox.load()
        enqueue("""{"message":"Unavailable"}""", 503)
        inbox.setRead(inbox.state.value.items.first())
        assertNotNull(inbox.state.value.actionError)
        assertFalse(inbox.state.value.items.first().isRead)
        enqueue("[${row(0, read = true)}]")
        inbox.retryAction()
        assertNull(inbox.state.value.actionError)
        assertTrue(inbox.state.value.items.first().isRead)
        assertFalse(inbox.state.value.busy)
    }

    private fun inbox(unreadOnly: Boolean = false) = NotificationInbox(
        repository, endpoint, "profile", { "test-token" }, NotificationInboxFilter.All, unreadOnly,
    )
    private fun enqueue(body: String, status: Int = 200) = server.enqueue(MockResponse().setResponseCode(status).setBody(body))
    private fun rows(count: Int, start: Int = 0) = (start until start + count).joinToString(prefix = "[", postfix = "]") { row(it) }
    private fun row(id: Int, read: Boolean = false, clicked: Boolean = false) =
        """{"id":"n$id","user_id":"profile","type":"activitypub_reply","data":{},"is_read":$read,"is_clicked":$clicked,"created_at":"2026-10-01T12:00:00Z"}"""
    private fun takeOffset(expected: Int) {
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        val args = HarmonyJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(expected.toString(), args.getValue("p_offset").jsonPrimitive.content)
    }
}
