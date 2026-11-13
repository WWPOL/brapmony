package lol.mony.harmony.data.model

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class SocialModelTest {
    @Test fun structuredContentPreservesMentionsTagsAndUrlsInEditorText() {
        val post = HarmonyJson.decodeFromString(SocialPost.serializer(), """{
            "id":"post","content":[{"type":"text","text":"Hi "},
            {"type":"mention","userId":"person","username":"bob","isLocal":false,"domain":"example.org"},
            {"type":"text","text":" "},{"type":"hashtag","name":"Harmony"},
            {"type":"text","text":" "},{"type":"url","url":"https://example.org"}]
        }""")
        assertEquals("Hi @bob@example.org #Harmony https://example.org", post.plainText)
    }

    @Test fun legacyRawAndSerializedTextRemainReadable() {
        assertEquals("old text", socialContentText(JsonPrimitive("old text")))
        assertEquals("stored text", socialContentText(JsonPrimitive("""[{"type":"text","text":"stored text"}]""")))
    }

    @Test fun boostSnapshotIsOriginalActionTargetButQuoteIsNot() {
        val boost = HarmonyJson.decodeFromString(SocialPost.serializer(), """{
            "id":"wrapper","author_id":"booster","metadata":{"reblog_of":"root"},
            "reblog":{"id":"root","content":[]},"reblog_author":{"id":"author","username":"alice"}
        }""")
        assertTrue(boost.isBoost); assertEquals("root", boost.actionPost.id)
        assertEquals("author", boost.actionPost.author!!.id)
        val quote = boost.copy(metadata = HarmonyJson.parseToJsonElement("""{"reblog_of":"root","is_quote":true}""") as kotlinx.serialization.json.JsonObject)
        assertTrue(quote.isQuote); assertFalse(quote.isBoost); assertEquals("wrapper", quote.actionPost.id)
    }

    @Test fun rpcFlagsAreNotErasedWhenNoInteractionEmbedExists() {
        val post = HarmonyJson.decodeFromString(SocialPost.serializer(), """{"id":"post","is_favorited":true,"is_bookmarked":true}""")
        assertTrue(post.withInteractionFlags().isFavorited)
        assertTrue(post.withInteractionFlags().isBookmarked)
        assertFalse(post.copy(myInteractions = emptyList()).withInteractionFlags().isFavorited)
    }
}
