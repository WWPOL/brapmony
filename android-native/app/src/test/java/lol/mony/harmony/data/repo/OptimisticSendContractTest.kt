package lol.mony.harmony.data.repo

import kotlinx.coroutines.runBlocking
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.api.RestApi
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the client must believe when the instance acknowledges a write it does not echo back.
 *
 * The fuzzer found this shape against the real stack: PostgREST can answer an insert with 201 and
 * an empty body. The row is stored; only the representation is missing. A client that reads the
 * empty answer as a failure marks a message that landed as unsent, and a retry then either
 * duplicates it or gives up. The contract is the web client's outbox: look the send up by the
 * nonce it carried before deciding anything.
 */
class OptimisticSendContractTest {
    private lateinit var server: MockWebServer
    private lateinit var endpoint: Endpoint
    private lateinit var chat: ChatRepository

    /** Every request the client made, in order, so a test can assert what was *not* sent. */
    private val requests = mutableListOf<RecordedRequest>()

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        endpoint = Endpoint(server.url("/").toString().trimEnd('/'), "test-anon")
        chat = ChatRepository(RestApi(OkHttpClient()))
    }

    @After fun tearDown() { server.shutdown() }

    private fun token(): String {
        val claims = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"sub":"auth-1"}""".toByteArray())
        return "e30." + claims + ".sig"
    }

    private fun profileRow() = """[{"id":"p1","auth_user_id":"auth-1","username":"noah",""" +
        """"display_name":"Noah","avatar_url":null,"banner_url":null,"bio":null,"pronouns":null,""" +
        """"domain":"example.test","is_local":true,"appearance_settings":null,""" +
        """"created_at":"2026-01-01T00:00:00+00:00","status":0,"custom_status":null}]"""

    private fun messageRow(id: String, text: String, nonce: String) =
        """[{"id":""" + id + ""","user_id":"p1","channel_id":"c1","created_at":"2026-01-01T00:00:01+00:00",""" +
            """"content":[{"type":"text","text":""" + text + """}],""" +
            """"metadata":{"created_via":"harmony_client","client_nonce":""" + nonce + """}}]"""

    /**
     * Routes by path rather than by enqueue order.
     *
     * A send makes three requests -- the encryption probe, the profile lookup and the insert --
     * and a retry adds a fourth. Enqueuing them in order would make the test fail for the wrong
     * reason the moment the client reorders two independent reads.
     */
    private fun route(storedBody: String? = null) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.requestUrl!!.encodedPath
                val nonce = request.requestUrl!!.queryParameter("metadata->>client_nonce")
                return when {
                    path.endsWith("/rpc/effective_channel_encryption") -> json("false")
                    path.endsWith("/profiles") -> json(profileRow())
                    path.endsWith("/messages") && request.method == "POST" -> json("[]")
                    path.endsWith("/messages") && nonce != null && storedBody != null -> json(storedBody)
                    path.endsWith("/messages") && nonce != null -> json("[]")
                    else -> MockResponse().setResponseCode(404).setBody("""{"message":"no route"}""")
                }
            }
        }
    }

    private fun json(body: String) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/json").setBody(body)

    @Test fun anAcknowledgedInsertWithNoRepresentationAdoptsTheStoredRow() = runBlocking {
        route(storedBody = messageRow("m1", "hello", "nonce-1"))
        val sent = chat.sendChannelMessage(endpoint, token(), "c1", "hello", nonce = "nonce-1").getOrThrow()

        assertEquals("the stored row, not an error", "m1", sent.id)
        assertEquals("hello", sent.plainText)
        assertEquals("nonce-1", sent.clientNonce)
    }

    @Test fun aSendCarriesTheNonceAndTheReservedCreatedVia() = runBlocking {
        route(storedBody = messageRow("m1", "hello", "nonce-1"))
        chat.sendChannelMessage(endpoint, token(), "c1", "hello", nonce = "nonce-1").getOrThrow()

        val insert = requests.first { it.method == "POST" && it.path!!.startsWith("/rest/v1/messages") }
        val body = insert.body.readUtf8()
        // `created_via` is a reserved metadata key: the instance refuses anything but the web
        // client's own value with 42501, and the send is lost. The fuzzer found that.
        assertTrue(body, body.contains(""""created_via":"harmony_client""""))
        assertTrue(body, body.contains(""""client_nonce":"nonce-1""""))
    }

    @Test fun aSendWhoseRowCannotBeFoundIsStillReportedAsAFailure() = runBlocking {
        route(storedBody = null)
        val result = chat.sendChannelMessage(endpoint, token(), "c1", "hello", nonce = "nonce-1")
        assertTrue("an empty answer with no stored row is not a success", result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("did not accept"))
    }

    @Test fun aRetryFindsTheRowTheFirstAttemptStoredInsteadOfPostingAgain() = runBlocking {
        route(storedBody = messageRow("m1", "hello", "nonce-1"))
        val found = chat.findByNonce(endpoint, token(), "c1", null, "nonce-1")

        assertNotNull("the nonce must name the row that landed", found)
        assertEquals("m1", found!!.id)
        // Nothing was written: a retry that adopts a row must not insert a second one.
        assertTrue("a lookup must not insert", requests.none { it.method == "POST" })
    }

    @Test fun aNonceThatNeverLandedFindsNothing() = runBlocking {
        route(storedBody = null)
        assertNull(chat.findByNonce(endpoint, token(), "c1", null, "never-sent"))
    }

    @Test fun aDirectMessageLooksItsNonceUpInTheConversationNotTheChannel() = runBlocking {
        route(storedBody = null)
        chat.findByNonce(endpoint, token(), null, "conv-1", "nonce-1")
        val lookup = requests.last()
        assertEquals("conv-1", lookup.requestUrl!!.queryParameter("conversation_id")?.removePrefix("eq."))
        assertNull("a DM lookup must not filter by channel", lookup.requestUrl!!.queryParameter("channel_id"))
    }

    @Test fun theReactionLimitReadsAsASentenceRatherThanTheDatabasesWords() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400)
            .setBody("""{"code":"P0001","message":"Maximum emoji reactions per message reached (20)"}"""))
        val result = chat.addReaction(endpoint, token(), "m1", "p1", "👍")

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue(message, message.contains("20-reaction limit"))
        assertTrue("the database's own wording must not reach the person", !message.contains("Maximum emoji"))
    }

    @Test fun anOrdinaryReactionFailureKeepsItsOwnReason() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403)
            .setBody("""{"code":"42501","message":"Permission denied"}"""))
        val result = chat.addReaction(endpoint, token(), "m1", "p1", "👍")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Permission denied"))
    }
}