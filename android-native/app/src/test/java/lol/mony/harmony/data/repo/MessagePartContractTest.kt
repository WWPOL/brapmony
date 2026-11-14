package lol.mony.harmony.data.repo

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.api.RestApi
import lol.mony.harmony.data.model.ContentPart
import lol.mony.harmony.data.model.HarmonyJson
import lol.mony.harmony.data.model.Message
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * The message-part contract, against the shapes the instance actually stores.
 *
 * This is the regression for the bug that stopped the `nerds` channel loading on a phone:
 * `content[1].emoji` was an object (`{"id":null,"url":"..."}`), the client declared it as a
 * string, and because [ChatRepository] decodes the whole page in one call, one ordinary emoji
 * failed the entire history with `Unexpected JSON token at offset 17640: expected beginning of
 * string but got {`. Suppressing the exception or dropping the part would have hidden the next
 * one; these tests require the parts to decode *and* to render.
 */
class MessagePartContractTest {
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

    private fun message(id: String, content: String) =
        """{"id":"$id","channel_id":"c1","user_id":"u1","created_at":"2026-11-13T05:39:18.248722+00:00","content":$content}"""

    /**
     * A page shaped like the one that failed: an ordinary channel history with text, an emoji
     * object whose `id` is null, and every other part kind the client claims to support.
     */
    private val mixedHistory = """[
      ${message("m1", """[{"type":"text","text":"look at this "},{"type":"emoji","emoji":{"id":null,"name":"blobcat","url":"https://example.test/storage/v1/object/public/emojis/blobcat.png"}}]""")},
      ${message("m2", """[{"type":"text","text":"hey "},{"type":"mention","userId":"u2","username":"bob","domain":"localhost","isLocal":true},{"type":"text","text":" see "},{"type":"channel_mention","channelId":"c2","serverId":"s1","name":"general"},{"type":"text","text":" and "},{"type":"hashtag","name":"harmony","id":"h1","count":4}]""")},
      ${message("m3", """[{"type":"role_mention","roleId":"r1","roleName":"Mods","roleColor":"#ff0000"},{"type":"text","text":" please read "},{"type":"url","url":"https://example.test/post","preview":false}]""")},
      ${message("m4", """[{"type":"file","url":"https://example.test/a.png","fileType":"image","fileName":"a.png","fileSize":1234,"width":64,"height":64}]""")},
      ${message("m5", """[{"type":"sticker","id":"s1","url":"https://example.test/s.webp","name":"wave","description":"A waving hand","width":128,"height":128}]""")},
      ${message("m6", """[{"type":"poll","pollId":"p1","question":"Lunch?","options":["Pizza","Tacos"],"allowMultiple":false,"expiresAt":null},{"type":"text","text":" vote"}]""")},
      ${message("m7", """[{"type":"system","event_type":"join","user":{"id":"u3","username":"carol","display_name":"Carol"},"timestamp":"2026-11-13T05:39:18+00:00"}]""")},
      ${message("m8", """[{"type":"emoji","emoji":":legacy:"},{"type":"text","text":" an older bare-string emoji"}]""")},
      ${message("m9", """[{"type":"emoji","emoji":null},{"type":"text","text":" and a null one"}]""")},
      ${message("m10", """[{"type":"something_new","payload":{"deep":[1,2,3]}},{"type":"text","text":" an unknown part must not fail the page"}]""")},
      ${message("m11", """[{"type":"emoji","emoji":{"id":"e1","name":"party","url":"https://example.test/p.gif","animated":true,"extra":{"x":1}}},{"type":"emoji","emoji":{"content":"\uD83C\uDF89","name":"tada"}}]""")}
    ]"""

    @Test fun objectEmojiHistoryLoadsThroughTheRealRepository() = runBlocking {
        answer(mixedHistory)
        val page = repo.channelMessages(endpoint, "token", "c1").getOrThrow()
        assertEquals("every row in the page must survive one emoji object", 11, page.size)
    }

    @Test fun theEmojiPartKeepsItsObjectAndRendersAsAShortcode() = runBlocking {
        answer(mixedHistory)
        val first = repo.channelMessages(endpoint, "token", "c1").getOrThrow().single { it.id == "m1" }
        val emoji = first.content.single { it.type == "emoji" }.emoji
        assertNotNull("the emoji object must be decoded, not dropped", emoji)
        assertNull("a bridged emoji's id is null in the stored row", emoji!!.id)
        assertEquals("blobcat", emoji.name)
        assertEquals("https://example.test/storage/v1/object/public/emojis/blobcat.png", emoji.url)
        assertTrue("an emoji with a file draws as an image", emoji.hasImage)
        assertEquals(":blobcat:", emoji.shortcode)
        assertEquals("look at this :blobcat:", first.plainText)
    }

    @Test fun aUnicodeEmojiRendersAsItsGlyphAndNotAnImage() {
        val part = HarmonyJson.decodeFromString(ContentPart.serializer(),
            """{"type":"emoji","emoji":{"name":"tada","content":"\uD83C\uDF89"}}""")
        assertEquals("\uD83C\uDF89", part.emoji!!.shortcode)
        assertFalse(part.emoji!!.hasImage)
    }

    @Test fun legacyStringEmojiAndNullEmojiBothDecode() {
        val legacy = HarmonyJson.decodeFromString(ContentPart.serializer(), """{"type":"emoji","emoji":":wave:"}""")
        assertEquals("wave", legacy.emoji!!.name)
        assertEquals(":wave:", legacy.emoji!!.shortcode)
        val nulled = HarmonyJson.decodeFromString(ContentPart.serializer(), """{"type":"emoji","emoji":null}""")
        assertNull(nulled.emoji)
        assertEquals(":emoji:", nulled.asText)
    }

    @Test fun everyKnownPartDecodesWithItsOwnFields() = runBlocking {
        answer(mixedHistory)
        val page = repo.channelMessages(endpoint, "token", "c1").getOrThrow()
        val parts = page.flatMap { it.content }
        val mention = parts.single { it.type == "mention" }
        assertEquals("bob", mention.username); assertEquals("u2", mention.userId)
        val channel = parts.single { it.type == "channel_mention" }
        assertEquals("general", channel.name); assertEquals("c2", channel.channelId)
        val hashtag = parts.single { it.type == "hashtag" }
        assertEquals("harmony", hashtag.name); assertEquals(4, hashtag.count)
        val role = parts.single { it.type == "role_mention" }
        assertEquals("Mods", role.roleName); assertEquals("#ff0000", role.roleColor)
        val url = parts.single { it.type == "url" }
        assertEquals("https://example.test/post", url.url); assertEquals(false, url.preview)
        val file = parts.single { it.type == "file" }
        assertEquals("a.png", file.fileName); assertEquals(1234L, file.fileSize)
        val sticker = parts.single { it.type == "sticker" }
        assertEquals("A waving hand", sticker.description)
        val poll = parts.single { it.type == "poll" }
        assertEquals(listOf("Pizza", "Tacos"), poll.options)
        val system = parts.single { it.type == "system" }
        assertEquals("join", system.eventType); assertEquals("carol", system.user!!["username"]!!.jsonPrimitive.content)
    }

    /**
     * Every part kind's own fields survive, checked against `src/types/chat.ts`.
     *
     * The union there is `Text | Url | Embed | Mention | RoleMention | Emoji | Hashtag |
     * ChannelMention | File | Sticker | System | Poll`; a field the model cannot name is
     * a part the client cannot draw, so this pins the ones the renderer uses.
     */
    @Test fun everyPartKindCarriesTheFieldsTheWebContractNames() {
        fun part(json: String) = HarmonyJson.decodeFromString(ContentPart.serializer(), json)
        val url = part("""{"type":"url","url":"https://example.test/x","preview":true,"label":"the post","embedId":"em1"}""")
        assertEquals("the post", url.label)
        assertEquals("the post", url.asText)
        assertEquals("em1", url.embedId)
        assertTrue(url.preview!!)
        val bare = part("""{"type":"url","url":"https://example.test/y","preview":false}""")
        assertEquals("https://example.test/y", bare.asText)
        val embed = part("""{"type":"embed","url":"https://example.test/p","provider":"harmony-post","previewId":"pv1","collapsed":true}""")
        assertEquals("harmony-post", embed.provider)
        assertEquals("pv1", embed.previewId)
        assertTrue(embed.collapsed!!)
        assertTrue("an embed stands on its own line", embed.isBlock)
        val system = part("""{"type":"system","event_type":"join","user":{"id":"u3","username":"carol","display_name":"Carol"},"timestamp":"2026-11-13T05:39:18+00:00"}""")
        assertEquals("2026-11-13T05:39:18+00:00", system.timestamp)
        assertEquals("Carol joined", system.asText)
        val left = part("""{"type":"system","event_type":"leave","user":{"id":"u4","username":"dave"}}""")
        assertEquals("dave left", left.asText)
        val hashtag = part("""{"type":"hashtag","name":"harmony","id":"h1","count":4,"last_updated":"2026-11-13T05:00:00+00:00","normalized":"harmony"}""")
        assertEquals("2026-11-13T05:00:00+00:00", hashtag.lastUpdated)
        assertEquals("harmony", hashtag.normalized)
        val shared = part("""{"type":"channel_mention","channelId":"c2","serverId":"s1","name":"general","messageId":"m9"}""")
        assertEquals("m9", shared.messageId)
        val sticker = part("""{"type":"sticker","id":"s1","url":"https://example.test/s.webp","name":"wave","description":"A waving hand","mimeType":"image/webp","domain":"other.test"}""")
        assertEquals("image/webp", sticker.mimeType)
        assertEquals("other.test", sticker.domain)
        val file = part("""{"type":"file","url":"https://example.test/a.png","fileType":"image","path":"media/a.png","altText":"a red square","stickerId":"s1"}""")
        assertEquals("media/a.png", file.path)
        assertEquals("a red square", file.altText)
        assertEquals("s1", file.stickerId)
        val mention = part("""{"type":"mention","userId":"u2","username":"bob","domain":"other.test","isLocal":false,"displayName":"Bob"}""")
        assertFalse(mention.isLocal!!)
        assertEquals("other.test", mention.domain)
        assertEquals("Bob", mention.displayName)
    }

    @Test fun theWholeHistoryReadsAsTextNotJustItsTextParts() = runBlocking {
        answer(mixedHistory)
        val page = repo.channelMessages(endpoint, "token", "c1").getOrThrow()
        val byId = page.associateBy { it.id }
        assertEquals("hey @bob see #general and #harmony", byId.getValue("m2").plainText)
        assertEquals("@Mods please read https://example.test/post", byId.getValue("m3").plainText)
        assertEquals("[image: attachment]", byId.getValue("m4").plainText)
        assertEquals("[Poll: Lunch?] vote", byId.getValue("m6").plainText)
        assertEquals(":legacy: an older bare-string emoji", byId.getValue("m8").plainText)
        assertEquals(":emoji: and a null one", byId.getValue("m9").plainText)
        assertEquals(" an unknown part must not fail the page", byId.getValue("m10").plainText)
    }

    @Test fun anUnknownPartKeepsItsJsonRatherThanBeingLost() = runBlocking {
        answer(mixedHistory)
        val unknown = repo.channelMessages(endpoint, "token", "c1").getOrThrow()
            .flatMap { it.content }.single { it.type == "something_new" }
        assertEquals(3, unknown.raw["payload"]!!.jsonObject["deep"]!!.jsonArray.size)
    }

    /**
     * A part this build has never seen, with every field a different type from the one declared,
     * must still decode: the next schema change must not be able to empty a channel again.
     */
    @Test fun hostileFieldTypesDoNotFailThePage() {
        val part = HarmonyJson.decodeFromString(ContentPart.serializer(),
            """{"type":"emoji","emoji":{"id":7,"name":["x"],"url":{"a":1}},"count":"many","width":"wide"}""")
        assertEquals("emoji", part.type)
        assertEquals("7", part.emoji!!.id)
        assertNull(part.count)
        assertNull(part.width)
    }

    /** The whole-page decode must not depend on a field ordering or an absent `content` field. */
    @Test fun aMessageWithoutContentDecodes() {
        val row = HarmonyJson.decodeFromString(Message.serializer(),
            """{"id":"m","created_at":"2026-11-13T05:39:18+00:00"}""")
        assertEquals(emptyList<ContentPart>(), row.content)
        assertEquals("", row.plainText)
    }
}
