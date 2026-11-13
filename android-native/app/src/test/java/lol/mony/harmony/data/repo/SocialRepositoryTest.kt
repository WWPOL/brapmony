package lol.mony.harmony.data.repo

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.api.RestApi
import lol.mony.harmony.data.model.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SocialRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var endpoint: Endpoint
    private lateinit var repo: SocialRepository
    private val networkThreads = CopyOnWriteArrayList<String>()
    private val profile = "e2e00000-0000-0000-0000-000000000001"
    private val other = "e2e00000-0000-0000-0000-000000000002"
    private val post = "e2e00000-0000-0000-0000-000000000101"
    private val post2 = "e2e00000-0000-0000-0000-000000000102"
    private val at = "2026-11-13T06:44:38.189148+00:00"

    @Before fun setup() {
        server = MockWebServer(); server.start()
        endpoint = Endpoint(server.url("/").toString().trimEnd('/'), "test-anon")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            networkThreads.add(Thread.currentThread().name)
            chain.proceed(chain.request())
        }.build()
        repo = SocialRepository(RestApi(client))
    }
    @After fun teardown() { server.shutdown() }
    private fun answer(body: String, status: Int = 200) = server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body))
    private fun row(id: String = post, extra: String = "") = """{"id":"$id","author_id":"$profile","created_at":"$at","content":[{"type":"text","text":"hello"}]$extra}"""

    @Test fun homeUsesCallerRpcAndNeverNetworksOnCallingThread() = runBlocking {
        val caller = Thread.currentThread().name
        answer("[${row(extra = ",\"my_interactions\":[{\"interaction_type\":\"favorite\"}]")}]")
        val page = repo.timeline(endpoint, "user-token", profile, SocialTimeline.Home)
        assertTrue(page.posts.single().isFavorited)
        val request = server.takeRequest()
        assertEquals("/rest/v1/rpc/get_home_timeline_page", request.requestUrl!!.encodedPath)
        val args = HarmonyJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertFalse(args.containsKey("p_user_id"))
        assertEquals("Bearer user-token", request.getHeader("Authorization"))
        assertTrue(networkThreads.isNotEmpty())
        assertTrue(networkThreads.all { it != caller })
    }

    @Test fun deniedTimelineDoesNotBecomeEmptySuccess() = runBlocking {
        answer("""{"code":"42501","message":"permission denied for posts"}""", 403)
        val error = runCatching { repo.timeline(endpoint, "token", profile, SocialTimeline.Local) }.exceptionOrNull()
        assertNotNull(error); assertTrue(error!!.message!!.contains("permission denied"))
    }

    @Test fun suspendedRawRowStillAdvancesHomeCursor() = runBlocking {
        answer("[${row(extra = ",\"author\":{\"id\":\"$profile\",\"is_suspended\":true}")},${row(post2)}]")
        val page = repo.timeline(endpoint, "token", profile, SocialTimeline.Home, limit = 1)
        assertTrue(page.posts.isEmpty())
        assertEquals(post, page.next!!.id)
        assertEquals(at, page.next!!.createdAt)
    }

    @Test fun favoriteReadsAuthoritativeFederatedCountNotLocalRowCount() = runBlocking {
        answer("[]")
        answer("""[{"id":"$post2","interaction_type":"favorite"}]""", 201)
        answer("[${row(extra = ",\"favorites_count\":554,\"my_interactions\":[{\"interaction_type\":\"favorite\"}]")}]")
        val updated = repo.setInteraction(endpoint, "token", profile, post, "favorite", true)
        assertEquals(554, updated.favoritesCount); assertTrue(updated.isFavorited)
        server.takeRequest()
        val inserted = HarmonyJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(profile, inserted["user_id"]!!.jsonPrimitive.content)
        val read = server.takeRequest().requestUrl!!
        assertEquals("eq.$profile", read.queryParameter("my_interactions.user_id"))
    }

    @Test fun noRowUpdateIsRlsFailureNotSuccessfulDelete() = runBlocking {
        answer("[]")
        val error = runCatching { repo.deletePost(endpoint, "token", profile, post) }.exceptionOrNull()
        assertNotNull(error); assertTrue(error!!.message!!.contains("not deleted"))
        val request = server.takeRequest()
        assertEquals("eq.$profile", request.requestUrl!!.queryParameter("author_id"))
    }

    @Test fun publishUsesStructuredHashtagsAndResolvedProfileMention() = runBlocking {
        answer("""[{"config_value":500}]""")
        answer("""[{"id":"$other","username":"bob","domain":"localhost","is_local":true}]""")
        answer("[${row()}]", 201)
        repo.publish(endpoint, "token", profile, "Hello @bob #Harmony")
        server.takeRequest(); server.takeRequest()
        val body = HarmonyJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val parts = body["content"]!!.jsonArray.map { it.jsonObject }
        val mention = parts.single { it["type"]!!.jsonPrimitive.content == "mention" }
        assertEquals(other, mention["userId"]!!.jsonPrimitive.content)
        val tag = parts.single { it["type"]!!.jsonPrimitive.content == "hashtag" }
        assertEquals("harmony", tag["normalized"]!!.jsonPrimitive.content)
        assertEquals(profile, body["author_id"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("ap_id"))
    }

    @Test fun postLengthLimitIsLoadedAndEnforcedBeforeInsert() = runBlocking {
        answer("""[{"config_value":3}]""")
        val error = runCatching { repo.publish(endpoint, "token", profile, "longer") }.exceptionOrNull()
        assertNotNull(error); assertTrue(error!!.message!!.contains("6 / 3"))
        assertEquals(1, server.requestCount)
    }

    @Test fun boostLeavesApIdAndSnapshotToServerAndCreatesInteraction() = runBlocking {
        answer("[${row()}]") // canonical post
        answer("[]") // own boost wrappers
        answer("[]") // interaction
        answer("[${row(post2)}]", 201)
        answer("""[{"id":"$post2","interaction_type":"reblog"}]""", 201)
        answer("[${row(extra = ",\"my_interactions\":[{\"interaction_type\":\"reblog\"}],\"reblogs_count\":7")}]")
        val updated = repo.setBoost(endpoint, "token", profile, post, true)
        assertTrue(updated.isReblogged); assertEquals(7, updated.reblogsCount)
        repeat(3) { server.takeRequest() }
        val boost = HarmonyJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertFalse(boost.containsKey("ap_id")); assertFalse(boost.containsKey("reblog"))
        assertEquals(post, boost["metadata"]!!.jsonObject["reblog_of"]!!.jsonPrimitive.content)
        assertEquals("reblog", HarmonyJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["interaction_type"]!!.jsonPrimitive.content)
    }

    @Test fun bookmarkPaginationAdvancesEvenWhenTargetIsDeletedOrHidden() = runBlocking {
        answer("""[{"id":"$post","post_id":"$post","interaction_type":"bookmark","created_at":"$at"},{"id":"$post2","post_id":"$post2","interaction_type":"bookmark","created_at":"$at"}]""")
        answer("[]")
        val page = repo.bookmarks(endpoint, "token", profile, limit = 1)
        assertTrue(page.posts.isEmpty()); assertEquals(post, page.next!!.id)
    }

    @Test fun followStatusComesFromDatabaseNotAssumedAccepted() = runBlocking {
        answer("[]")
        answer("""[{"id":"$post","follower_id":"$profile","following_id":"$other","status":"pending"}]""", 201)
        val relation = repo.setFollowing(endpoint, "token", profile, other, true)
        assertFalse(relation.following); assertTrue(relation.pending)
        server.takeRequest()
        val inserted = HarmonyJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertFalse(inserted.containsKey("status")); assertFalse(inserted.containsKey("ap_id"))
    }

    @Test fun postCursorBreaksTimestampTiesWithId() = runBlocking {
        answer("[]")
        repo.profilePosts(endpoint, "token", profile, other, SocialCursor(at, post))
        val request = server.takeRequest().requestUrl!!
        assertEquals("(created_at.lt.\"$at\",and(created_at.eq.\"$at\",id.lt.$post))", request.queryParameter("or"))
    }
}
