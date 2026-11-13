package lol.mony.harmony.data.repo

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.api.RestApi
import lol.mony.harmony.data.model.HarmonyJson
import lol.mony.harmony.data.model.ServerManagementChannel
import lol.mony.harmony.data.model.ServerManagementInvitePolicy
import lol.mony.harmony.data.model.ServerManagementPermission
import lol.mony.harmony.data.model.ServerManagementRole
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real HTTP against a loopback stub, testing request contracts without Android or a backend fixture. */
class ServerManagementRepositoryTest {
    private data class Reply(val status: Int, val body: String)
    private data class Request(val method: String, val path: String, val query: String?, val bearer: String?, val apiKey: String?, val body: String)
    private lateinit var server: HttpServer
    private lateinit var endpoint: Endpoint
    private lateinit var repository: ServerManagementRepository
    private lateinit var client: OkHttpClient
    private val replies = LinkedBlockingQueue<Reply>()
    private val requests = CopyOnWriteArrayList<Request>()
    private val callThreads = CopyOnWriteArrayList<String>()

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests += Request(exchange.requestMethod, exchange.requestURI.path, exchange.requestURI.query,
                exchange.requestHeaders.getFirst("Authorization"), exchange.requestHeaders.getFirst("apikey"),
                exchange.requestBody.bufferedReader().use { it.readText() })
            val reply = replies.poll() ?: Reply(500, "{\"message\":\"Unexpected request\"}")
            exchange.responseHeaders.set("Content-Type", "application/json")
            val bytes = reply.body.toByteArray()
            exchange.sendResponseHeaders(reply.status, if (reply.status == 204) -1 else bytes.size.toLong())
            exchange.responseBody.use { if (reply.status != 204) it.write(bytes) }
            exchange.close()
        }
        server.start()
        endpoint = Endpoint("http://127.0.0.1:${server.address.port}", "public-anon-key")
        client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callStart(call: Call) { callThreads += Thread.currentThread().name }
        }).build()
        repository = ServerManagementRepository(RestApi(client))
    }

    @After fun stop() {
        server.stop(0)
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private fun reply(body: String, status: Int = 200) { replies += Reply(status, body) }
    private fun body(index: Int = 0): JsonObject = HarmonyJson.parseToJsonElement(requests[index].body).jsonObject

    @Test fun `create inserts exactly once with profile owner and user bearer off caller thread`() = runBlocking {
        reply("""[{"id":"server-id","name":"My server","owner":"profile-id"}]""")
        val callerThread = Thread.currentThread().name
        val server = repository.createServer(endpoint, TOKEN, "profile-id", " My server ", "", true).getOrThrow()
        assertEquals("server-id", server.id)
        assertEquals(1, requests.size)
        assertEquals("POST", requests[0].method)
        assertEquals("/rest/v1/servers", requests[0].path)
        assertEquals("profile-id", body()["owner"]?.jsonPrimitive?.content)
        assertEquals("My server", body()["name"]?.jsonPrimitive?.content)
        assertEquals("Bearer $TOKEN", requests[0].bearer)
        assertEquals("public-anon-key", requests[0].apiKey)
        assertTrue(callThreads.all { it != callerThread })
    }

    @Test fun `ambiguous create failure does not insert again or add membership`() = runBlocking {
        reply("{\"message\":\"not allowed\"}", 403)
        assertTrue(repository.createServer(endpoint, TOKEN, "profile-id", "Server", "", false).isFailure)
        assertEquals(1, requests.size)
    }

    @Test fun `create validation fails before HTTP`() = runBlocking {
        assertTrue(repository.createServer(endpoint, TOKEN, "profile-id", "A", "", false).isFailure)
        assertEquals(0, requests.size)
    }

    @Test fun `invite preview decodes scalar object and refuses revoked payload`() = runBlocking {
        reply("""{"status":"valid","server_id":"server-id","name":"Private server","rules":["Be kind"]}""")
        val preview = repository.previewInvite(endpoint, TOKEN, "AbC123").getOrThrow()
        assertEquals("Private server", preview.name)
        assertEquals(listOf("Be kind"), preview.rules)
        assertEquals("AbC123", body()["p_code"]?.jsonPrimitive?.content)
        reply("""{"status":"revoked"}""")
        assertTrue(repository.previewInvite(endpoint, TOKEN, "Revoked").isFailure)
    }

    @Test fun `redeem reads returned server id and joined false as already joined`() = runBlocking {
        reply("""{"server_id":"server-id","joined":false}""")
        val result = repository.redeemInvite(endpoint, TOKEN, "ABC").getOrThrow()
        assertEquals("server-id", result.serverId)
        assertFalse(result.joined)
        assertEquals("/rest/v1/rpc/redeem_invite", requests[0].path)
    }

    @Test fun `redeem missing server id is not success and does not retry`() = runBlocking {
        reply("""{"joined":true}""")
        assertTrue(repository.redeemInvite(endpoint, TOKEN, "ABC").isFailure)
        assertEquals(1, requests.size)
    }

    @Test fun `moderation success false is surfaced including scalar error`() = runBlocking {
        reply("""{"success":false,"error":"Cannot ban owner"}""")
        val result = repository.moderate(endpoint, TOKEN, "server-id", "target-profile", true, "Reason")
        assertTrue(result.isFailure)
        assertEquals("Cannot ban owner", result.exceptionOrNull()?.message)
        assertEquals("/rest/v1/rpc/ban_server_member", requests[0].path)
        assertEquals("target-profile", body()["p_user_id"]?.jsonPrimitive?.content)
        assertEquals("0", body()["p_delete_message_seconds"]?.jsonPrimitive?.content)
    }

    @Test fun `unban no op payload is not reported as success`() = runBlocking {
        reply("""{"success":false,"error":"User is not banned"}""")
        assertEquals("User is not banned", repository.unban(endpoint, TOKEN, "server-id", "target-profile").exceptionOrNull()?.message)
    }

    @Test fun `channel creation accepts composite object and returns normalized name`() = runBlocking {
        reply("""{"id":"channel-id","server_id":"server-id","name":"new-channel","type":0,"slowmode_seconds":10}""")
        val channel = repository.saveChannel(endpoint, TOKEN, "server-id", null, "New channel", "Topic", 0, null, 10).getOrThrow()
        assertEquals("new-channel", channel.name)
        assertEquals("/rest/v1/rpc/create_channel", requests[0].path)
        assertEquals("false", body()["p_private"]?.jsonPrimitive?.content)
        assertEquals("10", body()["p_slowmode_seconds"]?.jsonPrimitive?.content)
    }

    @Test fun `scalar serializer also accepts one row composite array`() {
        val result = HarmonyJson.decodeFromString(ServerManagementScalarResponseSerializer(ServerManagementChannel.serializer()),
            """[{"id":"channel-id","server_id":"server-id","name":"text"}]""")
        assertEquals("channel-id", result.single().id)
    }

    @Test fun `channel edit RLS zero rows fails closed`() = runBlocking {
        reply("[]")
        assertTrue(repository.saveChannel(endpoint, TOKEN, "server-id", "channel-id", "Name", "", 0, null, 0).isFailure)
        assertEquals("PATCH", requests[0].method)
        assertTrue(requests[0].query.orEmpty().contains("server_id=eq.server-id"))
    }

    @Test fun `delete handles blank 204 once and verifies absence`() = runBlocking {
        reply("""[{"id":"channel-id"}]""")
        reply("", 204)
        reply("[]")
        repository.deleteChannel(endpoint, TOKEN, "server-id", "channel-id").getOrThrow()
        assertEquals(listOf("GET", "DELETE", "GET"), requests.map { it.method })
        assertTrue(requests.all { it.query.orEmpty().contains("server_id=eq.server-id") })
    }

    @Test fun `RLS silent delete refusal is not reported as removed`() = runBlocking {
        reply("""[{"id":"channel-id"}]""")
        reply("", 204)
        reply("""[{"id":"channel-id"}]""")
        val result = repository.deleteChannel(endpoint, TOKEN, "server-id", "channel-id")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("not removed"))
    }

    @Test fun `explicit delete denial is not masked by later existence check`() = runBlocking {
        reply("""[{"id":"channel-id"}]""")
        reply("{\"message\":\"denied\"}", 403)
        assertTrue(repository.deleteChannel(endpoint, TOKEN, "server-id", "channel-id").isFailure)
        assertEquals(2, requests.size)
    }

    @Test fun `members page uses profile ids not auth ids and stable bounded reads`() = runBlocking {
        reply("""[{"id":"membership-id","server_id":"server-id","user_id":"profile-not-auth","status":"accepted"}]""")
        reply("""[{"id":"profile-not-auth","username":"member","display_name":"A member"}]""")
        val result = repository.members(endpoint, TOKEN, "server-id", offset = 50).getOrThrow()
        assertEquals("A member", result.items.single().shownName)
        assertTrue(requests[0].query.orEmpty().contains("limit=51"))
        assertTrue(requests[0].query.orEmpty().contains("offset=50"))
        assertTrue(requests[0].query.orEmpty().contains("order=id.asc"))
        assertTrue(requests[1].query.orEmpty().contains("id=in.(profile-not-auth)"))
        assertFalse(requests[1].query.orEmpty().contains("auth_user_id"))
    }

    @Test fun `member page overflow is not silently included or dropped without hasMore`() = runBlocking {
        val members = (1..51).joinToString(",") { """{"id":"m$it","server_id":"server-id","user_id":"p$it","status":"accepted"}""" }
        reply("[$members]")
        reply("[]")
        val page = repository.members(endpoint, TOKEN, "server-id").getOrThrow()
        assertEquals(50, page.items.size)
        assertTrue(page.hasMore)
    }

    @Test fun `role update sends 64 bit permissions and rejects empty representation`() = runBlocking {
        reply("[]")
        val bits = ServerManagementPermission.MANAGE_NICKNAMES.mask
        assertTrue(repository.saveRole(endpoint, TOKEN, "server-id", "role-id", "Role", bits, 1, false, false).isFailure)
        assertEquals("8589934592", body()["permissions"]?.jsonPrimitive?.content)
        assertTrue(requests[0].query.orEmpty().contains("server_id=eq.server-id"))
    }

    @Test fun `protected role deletion never makes an HTTP request`() = runBlocking {
        for (role in listOf(
            ServerManagementRole("role-id", "server-id", "Everyone", isDefault = true),
            ServerManagementRole("role-id", "server-id", "Admin", isAdmin = true),
        )) assertTrue(repository.deleteRole(endpoint, TOKEN, "server-id", role).isFailure)
        assertEquals(0, requests.size)
    }

    @Test fun `invite unlimited expiry cannot bypass cap or make HTTP request`() = runBlocking {
        assertTrue(repository.createInvite(endpoint, TOKEN, "server-id", "profile-id", 0, 1,
            ServerManagementInvitePolicy(maxExpiration = 60)).isFailure)
        assertEquals(0, requests.size)
    }

    @Test fun `invite insert uses profile id null unlimited and never temporary membership`() = runBlocking {
        reply("""[{"id":"invite-id","server_id":"server-id","created_by":"profile-id","code":"SERVER12"}]""")
        repository.createInvite(endpoint, TOKEN, "server-id", "profile-id", 0, 0, ServerManagementInvitePolicy()).getOrThrow()
        assertEquals("profile-id", body()["created_by"]?.jsonPrimitive?.content)
        assertEquals("null", body()["max_uses"].toString())
        assertEquals("null", body()["expires_at"].toString())
        assertEquals("false", body()["temporary"]?.jsonPrimitive?.content)
        assertTrue(Regex("[A-Z0-9]{8}").matches(body()["code"]!!.jsonPrimitive.content))
    }

    @Test fun `category is appended using highest current order`() = runBlocking {
        reply("""[{"id":"old","server_id":"server-id","name":"Old","order":7}]""")
        reply("""[{"id":"new","server_id":"server-id","name":"New","order":8}]""")
        val result = repository.createCategory(endpoint, TOKEN, "server-id", "New").getOrThrow()
        assertEquals(8, result.order)
        assertTrue(requests[0].query.orEmpty().contains("order=order.desc"))
        assertEquals("8", body(1)["order"]?.jsonPrimitive?.content)
    }

    companion object { private const val TOKEN = "fresh-user-token-for-test" }
}
