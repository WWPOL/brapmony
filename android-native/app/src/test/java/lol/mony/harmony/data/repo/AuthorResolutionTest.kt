package lol.mony.harmony.data.repo

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.api.RestApi
import lol.mony.harmony.data.model.Bot
import lol.mony.harmony.data.model.Message
import lol.mony.harmony.data.model.Profile
import lol.mony.harmony.data.model.author
import lol.mony.harmony.data.model.authorKey
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Who a message row is shown as, against the shapes the instance actually stores.
 *
 * The bug this pins: every row read "Unknown account" with no picture. The cause was not a
 * missing lookup but a lookup that always failed -- `Profile` declared `status` and
 * `custom_status` as strings while the schema stores a `smallint` and a `jsonb`, so the first
 * profile threw and no author ever resolved. These tests read real row shapes through the real
 * repository and require the displayed name and avatar for every kind of author.
 */
class AuthorResolutionTest {
    private lateinit var server: MockWebServer
    private lateinit var endpoint: Endpoint
    private lateinit var profiles: ProfileRepository

    @Before fun setup() {
        server = MockWebServer(); server.start()
        endpoint = Endpoint(server.url("/").toString().trimEnd('/'), "test-anon")
        profiles = ProfileRepository(RestApi(OkHttpClient()))
    }
    @After fun teardown() { server.shutdown() }

    private fun answer(body: String) = server.enqueue(
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body))

    private fun token(): String {
        val claims = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"sub":"auth-1"}""".toByteArray())
        return "e30.$claims.sig"
    }

    /** A `profiles` row with the column types the baseline migration declares. */
    private fun profileRow(
        id: String,
        username: String = "user",
        displayName: String? = null,
        avatar: String? = null,
        status: String = "0",
        customStatus: String = "null",
    ): String = """{"id":"$id","auth_user_id":"auth-$id","username":"$username",""" +
        """"display_name":${displayName?.let { "\"$it\"" } ?: "null"},""" +
        """"avatar_url":${avatar?.let { "\"$it\"" } ?: "null"},"banner_url":null,"bio":null,""" +
        """"pronouns":null,"domain":"example.test","is_local":true,"appearance_settings":null,""" +
        """"created_at":"2026-01-01T00:00:00+00:00","status":$status,"custom_status":$customStatus}"""

    private fun message(json: String): Message = Json.decodeFromString(Message.serializer(), json)

    private fun profile(id: String, username: String, displayName: String? = null, avatar: String? = null) =
        Profile(id = id, authUserId = "auth-$id", username = username, displayName = displayName, avatarUrl = avatar)

    // ---- the reported bug: a real profile row must decode -----------------------------------

    @Test fun aRealProfileRowDecodesDespiteItsSmallintAndJsonbColumns() = runBlocking {
        answer("[" + profileRow("p1", username = "noah", displayName = "Noah", avatar = "/avatars/p1.webp") + "]")
        val people = profiles.byIds(endpoint, token(), listOf("p1"))

        assertEquals("the profile must resolve, not throw", 1, people.size)
        assertEquals("Noah", people.getValue("p1").shownName)
        assertEquals("/avatars/p1.webp", people.getValue("p1").avatarUrl)
    }

    @Test fun aProfileWithACustomStatusObjectDecodes() = runBlocking {
        // custom_status is jsonb: an object, not a string. This is the exact shape that threw.
        answer("[" + profileRow("p1", customStatus = "{\"text\":\"shipping\",\"emoji\":null}") + "]")
        val people = profiles.byIds(endpoint, token(), listOf("p1"))
        assertEquals(1, people.size)
    }

    @Test fun aProfileWithANumericStatusDecodes() = runBlocking {
        // status is smallint: 0 is offline, 1 online. Declaring it as a string threw here.
        answer("[" + profileRow("p1", status = "1") + "]")
        assertEquals(1, profiles.byIds(endpoint, token(), listOf("p1")).size)
    }

    // ---- author kinds ----------------------------------------------------------------------

    @Test fun anOrdinaryAuthorTakesTheProfilesNameAndAvatar() {
        val people = mapOf("p1" to profile("p1", username = "noah", displayName = "Noah", avatar = "/a.webp"))
        val author = message("""{"id":"m1","user_id":"p1","created_at":"2026-01-01T00:00:00Z"}""")
            .author(people)

        assertEquals("Noah", author.name)
        assertEquals("/a.webp", author.avatarPath)
        assertFalse(author.isDeleted)
    }

    @Test fun aMissingProfileIsDeletedRatherThanUnknown() {
        val author = message("""{"id":"m1","user_id":"gone","created_at":"2026-01-01T00:00:00Z"}""")
            .author(emptyMap())
        assertTrue("a resolved-away author must say so, not read 'Unknown account'", author.isDeleted)
        assertEquals("Deleted account", author.name)
    }

    @Test fun aMessageWithNoAuthorAtAllIsDeleted() {
        val author = message("""{"id":"m1","created_at":"2026-01-01T00:00:00Z"}""").author(emptyMap())
        assertTrue(author.isDeleted)
    }

    @Test fun aBotAuthorResolvesThroughBotIdNotUserId() {
        val bots = mapOf("b1" to Bot(id = "b1", username = "weather", displayName = "Weather", avatarUrl = "https://cdn.test/w.png"))
        val author = message("""{"id":"m1","bot_id":"b1","created_at":"2026-01-01T00:00:00Z"}""")
            .author(emptyMap(), bots)

        assertEquals("Weather", author.name)
        assertEquals("https://cdn.test/w.png", author.avatarPath)
        assertTrue(author.isBot)
    }

    @Test fun anUnresolvedBotStillReadsAsABot() {
        val author = message("""{"id":"m1","bot_id":"b9","created_at":"2026-01-01T00:00:00Z"}""").author(emptyMap())
        assertEquals("Bot", author.name)
        assertTrue(author.isBot)
    }

    @Test fun aBridgedDiscordAuthorCarriesItsOwnNameAndPicture() {
        val json = """{"id":"m1","bot_id":"b1","created_at":"2026-01-01T00:00:00Z",""" +
            """"metadata":{"discord_user":{"id":"d1","username":"ally","display_name":"Ally",""" +
            """"avatar_url":"https://cdn.discord.test/a.png"}}}"""
        val author = message(json).author(emptyMap())
        assertEquals("Ally", author.name)
        assertEquals("https://cdn.discord.test/a.png", author.avatarPath)
        assertTrue(author.isBridged)
    }

    @Test fun aWebhookAuthorUsesTheNameItPostedUnder() {
        val json = """{"id":"m1","bot_id":"b1","created_at":"2026-01-01T00:00:00Z",""" +
            """"metadata":{"webhook":{"id":"w1","name":"Deploys","avatar_url":"/hook.png"}}}"""
        val author = message(json).author(emptyMap())
        assertEquals("Deploys", author.name)
        assertEquals("/hook.png", author.avatarPath)
        assertTrue(author.isBot)
    }

    @Test fun aRemoteAuthorInAFederatedCopyIsNamed() {
        val json = """{"id":"m1","created_at":"2026-01-01T00:00:00Z",""" +
            """"metadata":{"remote_author":{"kind":"bot","id":"r1","name":"Remote Bot",""" +
            """"avatar_url":"https://other.test/r.png"}}}"""
        val author = message(json).author(emptyMap())
        assertEquals("Remote Bot", author.name)
        assertEquals("https://other.test/r.png", author.avatarPath)
    }

    @Test fun aFederatedMemberResolvesThroughItsProfileRow() {
        val people = mapOf("p2" to profile("p2", username = "rae", displayName = "Rae", avatar = "https://other.test/rae.png")
            .copy(domain = "other.test", isLocal = false))
        val author = message("""{"id":"m1","user_id":"p2","created_at":"2026-01-01T00:00:00Z"}""").author(people)
        assertEquals("Rae", author.name)
        assertEquals("https://other.test/rae.png", author.avatarPath)
    }

    @Test fun aProfileIsFoundByAuthUserIdAsWellAsItsOwnId() {
        // messages.user_id is profiles.id, but a caller holding an auth id must still resolve.
        val people = mapOf(
            "p1" to profile("p1", username = "noah"),
            "auth-1" to profile("p1", username = "noah"),
        )
        val author = message("""{"id":"m1","user_id":"auth-1","created_at":"2026-01-01T00:00:00Z"}""")
            .author(people)
        assertEquals("noah", author.name)
        assertFalse(author.isDeleted)
    }

    // ---- grouping --------------------------------------------------------------------------

    @Test fun consecutiveMessagesFromOneAuthorGroupUnderOneHeader() {
        val a = message("""{"id":"m1","user_id":"p1","created_at":"2026-01-01T00:00:00Z"}""")
        val b = message("""{"id":"m2","user_id":"p1","created_at":"2026-01-01T00:01:00Z"}""")
        assertEquals(a.authorKey(), b.authorKey())
    }

    @Test fun aBotAndAPersonWithTheSameIdDoNotGroupTogether() {
        val person = message("""{"id":"m1","user_id":"x","created_at":"2026-01-01T00:00:00Z"}""")
        val bot = message("""{"id":"m2","bot_id":"x","created_at":"2026-01-01T00:00:00Z"}""")
        assertFalse(person.authorKey() == bot.authorKey())
    }

    // ---- the lookups must not throw --------------------------------------------------------

    @Test fun readingBotsReturnsTheirNamesAndPictures() = runBlocking {
        answer("""[{"id":"b1","username":"weather","display_name":"Weather","avatar_url":"/w.png"}]""")
        val bots = BotRepository(RestApi(OkHttpClient())).byIds(endpoint, token(), listOf("b1"))
        assertEquals("Weather", bots.getValue("b1").shownName)
        assertEquals("/w.png", bots.getValue("b1").avatarUrl)
    }

    @Test fun askingForNoBotsMakesNoRequest() = runBlocking {
        val bots = BotRepository(RestApi(OkHttpClient())).byIds(endpoint, token(), emptyList())
        assertTrue(bots.isEmpty())
        assertEquals("no request for an empty id list", 0, server.requestCount)
    }

    // ---- the lookups read only what they need ----------------------------------------------

    @Test fun theProfileQueryAsksForTheColumnsTheRowDraws() = runBlocking {
        answer("""[]""")
        profiles.byIds(endpoint, token(), listOf("p1"))
        // The query parameter is percent-encoded, so read it the way the server would.
        val select = java.net.URLDecoder.decode(server.takeRequest().path.orEmpty(), "UTF-8")
        // `status` and `custom_status` are read, but as JSON, so their SQL type cannot break us.
        assertTrue(select, select.contains("select=id,auth_user_id,username,display_name,avatar_url"))
        assertTrue(select, select.contains("status"))
    }
}
