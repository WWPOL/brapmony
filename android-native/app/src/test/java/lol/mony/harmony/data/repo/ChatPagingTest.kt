package lol.mony.harmony.data.repo

import kotlinx.coroutines.runBlocking
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.api.RestApi
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Paging for channels, DMs and threads.
 *
 * The bug this pins: a thread opened on its oldest messages, so a long discussion looked as
 * though it had stopped receiving replies. The web client orders descending and takes the
 * newest page, then reverses it for display; the native client did the opposite.
 */
class ChatPagingTest {
    private lateinit var server: MockWebServer
    private lateinit var endpoint: Endpoint
    private lateinit var repo: ChatRepository

    @Before fun setup() {
        server = MockWebServer(); server.start()
        endpoint = Endpoint(server.url("/").toString().trimEnd('/'), "test-anon")
        repo = ChatRepository(RestApi(OkHttpClient()))
    }
    @After fun teardown() { server.shutdown() }

    private fun answer(body: String) = server.enqueue(
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body))

    private fun token() = "e30.e30.sig"

    /** A thread page is the newest messages, returned oldest-first for drawing. */
    @Test fun aThreadOpensOnItsNewestMessages() = runBlocking {
        // PostgREST answers the descending query: newest first.
        answer("[" +
            """{"id":"new","channel_id":"c1","thread_id":"t1","user_id":"u1","created_at":"2026-01-01T00:09:00Z","content":[{"type":"text","text":"new"}]},""" +
            """{"id":"mid","channel_id":"c1","thread_id":"t1","user_id":"u1","created_at":"2026-01-01T00:05:00Z","content":[{"type":"text","text":"mid"}]}""" +
            "]")
        val page = repo.threadMessages(endpoint, token(), "t1").getOrThrow()

        assertEquals(listOf("mid", "new"), page.map { it.id })
        val path = java.net.URLDecoder.decode(server.takeRequest().path.orEmpty(), "UTF-8")
        assertTrue(path, path.contains("order=created_at.desc"))
    }

    /** Asking for older thread messages pages backwards from the oldest row on screen. */
    @Test fun olderThreadMessagesPageBackwards() = runBlocking {
        answer("[]")
        repo.threadMessages(endpoint, token(), "t1", before = "2026-01-01T00:05:00Z")
        val path = java.net.URLDecoder.decode(server.takeRequest().path.orEmpty(), "UTF-8")
        assertTrue(path, path.contains("created_at=lt.2026-01-01T00:05:00Z"))
    }

    /** A channel still pages backwards from the newest, unchanged. */
    @Test fun aChannelPageIsTheNewestMessages() = runBlocking {
        answer("""[{"id":"new","channel_id":"c1","user_id":"u1","created_at":"2026-01-01T00:09:00Z","content":[{"type":"text","text":"new"}]},""" +
            """{"id":"old","channel_id":"c1","user_id":"u1","created_at":"2026-01-01T00:01:00Z","content":[{"type":"text","text":"old"}]}]""")
        val page = repo.channelMessages(endpoint, token(), "c1").getOrThrow()
        assertEquals(listOf("old", "new"), page.map { it.id })
    }

    /** A channel never shows thread replies in its own history. */
    @Test fun aChannelExcludesThreadReplies() = runBlocking {
        answer("[]")
        repo.channelMessages(endpoint, token(), "c1")
        val path = java.net.URLDecoder.decode(server.takeRequest().path.orEmpty(), "UTF-8")
        assertTrue(path, path.contains("thread_id=is.null"))
    }
}
