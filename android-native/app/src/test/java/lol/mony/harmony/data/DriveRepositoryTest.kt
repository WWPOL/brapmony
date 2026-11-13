package lol.mony.harmony.data

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.api.RestApi
import lol.mony.harmony.data.model.DriveKind
import lol.mony.harmony.data.model.DriveQuery
import lol.mony.harmony.data.model.DriveSort
import lol.mony.harmony.data.model.DriveView
import lol.mony.harmony.data.model.HarmonyJson
import lol.mony.harmony.data.repo.DriveRepository
import lol.mony.harmony.data.repo.driveLinkUrl
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DriveRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var endpoint: Endpoint
    private lateinit var repository: DriveRepository

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        endpoint = Endpoint(server.url("/").toString().trimEnd('/'), "test-anon")
        val client = OkHttpClient()
        repository = DriveRepository(RestApi(client), client)
    }
    @After fun tearDown() { server.shutdown() }

    @Test fun `list uses exact web RPC arguments and parses rows not a fabricated table`() = runBlocking {
        server.enqueue(answer("""[{"file":$fileJson,"owner":null,"total_count":72}]"""))
        val page = repository.files(endpoint, "test-user", DriveQuery(view = DriveView.Channel, contextId = "room", search = "  cats  ",
            kind = DriveKind.Image, sort = DriveSort.Size, limit = 50, offset = 50)).getOrThrow()
        assertEquals(72L, page.total)
        assertTrue(page.hasMore)
        val request = server.takeRequest()
        assertEquals("/rest/v1/rpc/drive_list_files", request.path)
        assertEquals("Bearer test-user", request.getHeader("Authorization"))
        assertEquals("test-anon", request.getHeader("apikey"))
        val args = HarmonyJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("channel", args.getValue("p_view").jsonPrimitive.content)
        assertEquals("room", args.getValue("p_context").jsonPrimitive.content)
        assertEquals(JsonNull, args["p_folder"])
        assertEquals("cats", args.getValue("p_query").jsonPrimitive.content)
        assertEquals("image", args.getValue("p_kind").jsonPrimitive.content)
        assertEquals("size", args.getValue("p_sort").jsonPrimitive.content)
        assertEquals("50", args.getValue("p_offset").jsonPrimitive.content)
    }

    @Test fun `usage and scalar counts use scalar RPC decoding`() = runBlocking {
        server.enqueue(answer(usageJson))
        assertTrue(repository.usage(endpoint, "test-user").getOrThrow().enabled)
        server.enqueue(answer("0"))
        assertEquals(0, repository.keep(endpoint, "test-user", listOf("file", "file")).getOrThrow())
        server.takeRequest()
        val keep = server.takeRequest()
        assertEquals("/rest/v1/rpc/drive_keep_files", keep.path)
        assertEquals("""{"p_files":["file"]}""", keep.body.readUtf8())
    }

    @Test fun `name and link parameters are JSON escaped and explicit nulls`() = runBlocking {
        server.enqueue(answer(fileJson))
        repository.renameFile(endpoint, "test-user", "file", " a \"photo\" ").getOrThrow()
        val rename = HarmonyJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("a \"photo\"", rename.getValue("p_name").jsonPrimitive.content)
        server.enqueue(answer("""{"token":"test-link-token","public":false,"link_expires_at":null,"show_owner":false}"""))
        repository.setLinkAccess(endpoint, "test-user", "file", false).getOrThrow()
        val link = HarmonyJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(JsonNull, link["p_expires_at"])
        assertEquals("false", link.getValue("p_public").jsonPrimitive.content)
        assertEquals("false", link.getValue("p_show_owner").jsonPrimitive.content)
    }

    @Test fun `missing or denied card is not treated as an owned file`() = runBlocking {
        server.enqueue(answer("""{"access":false}"""))
        val denied = repository.card(endpoint, "test-user", "file").getOrThrow()
        assertFalse(denied.access)
        assertFalse(denied.own)
        server.enqueue(answer("""{"code":"42501","message":"Permission denied"}""").setResponseCode(403))
        assertTrue(repository.renameFile(endpoint, "test-user", "other-file", "x").isFailure)
    }

    @Test fun `folder responses and void revoke match PostgREST contracts`() = runBlocking {
        server.enqueue(answer("""{"id":"folder","owner_id":"owner","parent_id":null,"name":"Notes","created_at":"","updated_at":""}"""))
        assertEquals("Notes", repository.createFolder(endpoint, "test-user", "Notes").getOrThrow().name)
        val args = HarmonyJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(JsonNull, args["p_parent"])
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(repository.unshare(endpoint, "test-user", "share").isSuccess)
    }

    @Test fun `private view URL is signed for an hour and never made public`() = runBlocking {
        server.enqueue(answer("""{"signedURL":"/object/sign/message_media/u/owner/file.jpg?token=test-signature"}"""))
        val url = repository.viewUrl(endpoint, "test-user", driveFile()).getOrThrow()
        assertEquals(endpoint.baseUrl + "/storage/v1/object/sign/message_media/u/owner/file.jpg?token=test-signature", url)
        val sign = server.takeRequest()
        assertEquals("/storage/v1/object/sign/message_media/u/owner/file.jpg", sign.path)
        assertEquals("Bearer test-user", sign.getHeader("Authorization"))
        assertEquals("""{"expiresIn":3600}""", sign.body.readUtf8())
    }

    @Test fun `signed URL may be absolute but cannot point at another origin or object`() = runBlocking {
        server.enqueue(answer("""{"signedURL":"https://attacker.invalid/object/sign/message_media/file?token=x"}"""))
        assertTrue(repository.viewUrl(endpoint, "test-user", driveFile()).isFailure)
        server.enqueue(answer("""{"signedURL":"/object/sign/message_media/u/other/file.jpg?token=x"}"""))
        assertTrue(repository.viewUrl(endpoint, "test-user", driveFile()).isFailure)
        val valid = endpoint.baseUrl + "/storage/v1/object/sign/message_media/u/owner/file.jpg?token=x"
        server.enqueue(answer("""{"signedURL":"$valid"}"""))
        assertEquals(valid, repository.viewUrl(endpoint, "test-user", driveFile()).getOrThrow())
    }

    @Test fun `save streams bytes without account headers on the signed download and suppresses credential URL logs`() = runBlocking {
        val lines = mutableListOf<String>()
        val logger = HttpLoggingInterceptor { lines.add(it) }.apply { level = HttpLoggingInterceptor.Level.BASIC }
        val client = OkHttpClient.Builder().addInterceptor(logger).build()
        repository = DriveRepository(RestApi(client), client)
        server.enqueue(answer("""{"signedURL":"/object/sign/message_media/u/owner/file.jpg?token=test-signature"}"""))
        server.enqueue(MockResponse().setBody("exact file bytes"))
        val output = ByteArrayOutputStream()
        assertEquals(16L, repository.saveTo(endpoint, "test-user", driveFile(), output).getOrThrow())
        assertEquals("exact file bytes", output.toString("UTF-8"))
        server.takeRequest()
        val download = server.takeRequest()
        assertNull(download.getHeader("Authorization"))
        assertNull(download.getHeader("apikey"))
        assertTrue(lines.none { "test-signature" in it })
    }

    @Test fun `storage redirect refusal cannot forward credentials and traversal is rejected`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://attacker.invalid/"))
        assertTrue(repository.viewUrl(endpoint, "test-user", driveFile()).isFailure)
        assertEquals(1, server.requestCount)
        assertTrue(repository.viewUrl(endpoint, "test-user", driveFile().copy(path = "u/../secret")).isFailure)
        assertEquals(1, server.requestCount)
    }

    @Test fun `public post media encodes individual path segments and link uses web origin`() = runBlocking {
        val file = driveFile().copy(bucket = "user_media", path = "auth/posts/a & # +.png")
        val url = repository.viewUrl(endpoint, "test-user", file).getOrThrow()
        val parsed = url.toHttpUrl()
        assertEquals("a & # +.png", parsed.pathSegments.last())
        assertNull(parsed.query)
        assertNull(parsed.fragment)
        assertTrue(parsed.encodedPath.contains("%23"))
        assertEquals(0, server.requestCount)
        assertEquals("https://web.example/d/opaque-token", driveLinkUrl(Endpoint(endpoint.baseUrl, "test-anon", "https://web.example"), "opaque-token"))
    }

    @Test fun `HTTP work is not performed on callers thread`() = runBlocking {
        val caller = Thread.currentThread().id
        var networkThread: Long? = null
        val client = OkHttpClient.Builder().addInterceptor { chain -> networkThread = Thread.currentThread().id; chain.proceed(chain.request()) }.build()
        repository = DriveRepository(RestApi(client), client)
        server.enqueue(answer(usageJson))
        repository.usage(endpoint, "test-user").getOrThrow()
        assertNotEquals(caller, networkThread)
    }

    private fun answer(body: String): MockResponse = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
