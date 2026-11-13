package lol.mony.harmony

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import lol.mony.harmony.data.model.HarmonyJson
import lol.mony.harmony.data.model.Notification
import lol.mony.harmony.data.model.NotificationFormatter
import lol.mony.harmony.data.model.NotificationInboxFilter
import lol.mony.harmony.data.model.notificationContentText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFormatterTest {
    @Test fun encryptedMessageNeverRendersPlaintextAtAnyPreviewFallback() {
        for (flag in listOf("\"encrypted\":true,", "\"message\":{\"encrypted\":true,\"content_preview\":\"private\"},")) {
            val copy = NotificationFormatter.format(notification("dm", "{$flag\"preview\":\"private\",\"content\":\"private\"}"))
            assertEquals("Encrypted message", copy.preview)
            assertFalse(copy.toString().contains("private"))
        }
    }

    @Test fun spoilersAreMaskedInTextAndEncodedMessageParts() {
        assertEquals("Hello [spoiler]", notificationContentText(JsonPrimitive("Hello ||secret||")))
        val encoded = JsonPrimitive("""[{"type":"text","text":"||secret||"},{"type":"mention","username":"river"}]""")
        assertEquals("[spoiler] @river", notificationContentText(encoded))
    }

    @Test fun contentPartsHandleStructuredEmojiPollAndUnknownObjectsWithoutLeakingJson() {
        assertEquals(":wave: #harmony", notificationContentText(HarmonyJson.parseToJsonElement("""[
            {"type":"emoji","emoji":{"name":"wave"}}, {"type":"hashtag","name":"harmony"},
            {"type":"future","secret":"not a text part"}
        ]""")))
        assertEquals("Poll: Pick one", notificationContentText(HarmonyJson.parseToJsonElement("""[{"type":"poll","pollId":"id","question":"Pick one"}]""")))
        assertNull(notificationContentText(HarmonyJson.parseToJsonElement("""{"secret":"not text"}""")))
    }

    @Test fun mentionsUseActualActorLocationAndStructuredMessagePreview() {
        val copy = NotificationFormatter.format(notification("mention", """{
            "sender":{"id":"actor","display_name":"River"},
            "location":{"server_name":"Harmony","channel_name":"general"},
            "message":{"content_preview":[{"type":"text","text":"Hello"}]}
        }"""))
        assertEquals("River mentioned you in #general", copy.title)
        assertEquals("Hello", copy.preview)
        assertEquals("Harmony / #general", copy.context)
    }

    @Test fun postContentWarningWinsOverPostPreview() {
        val copy = NotificationFormatter.format(notification("activitypub_quote", """{
            "sender":{"username":"river"},"post":{"content_warning":"Spoilers","content_preview":"secret plot"}
        }"""))
        assertEquals("river quoted your post", copy.title)
        assertEquals("Content warning: Spoilers", copy.preview)
        assertFalse(copy.toString().contains("secret plot"))
    }

    @Test fun incomingCallContractIsRecognizedWithoutInventingMessagePreview() {
        val copy = NotificationFormatter.format(notification("dm", """{"sender":{"username":"river"},"preview":"started a call"}"""))
        assertEquals("river is calling you", copy.title)
        assertEquals("Incoming call", copy.preview)
    }

    @Test fun unknownTypePreservesActualCopyAndEmptyPayloadDoesNotInventContent() {
        val copy = NotificationFormatter.format(notification("future_type", """{"title":"Actual title","message":"Actual message"}"""))
        assertEquals("Actual title", copy.title)
        assertEquals("Actual message", copy.preview)
        assertNull(NotificationFormatter.format(notification("future_type", "{}")).preview)
    }

    @Test fun filterGroupsIncludeRepliesQuotesReactionsAndFollowRequests() {
        assertNull(NotificationInboxFilter.All.types)
        assertTrue(NotificationInboxFilter.Mentions.types!!.containsAll(listOf("mention", "reply", "thread_reply", "activitypub_mention", "activitypub_reply")))
        assertTrue(NotificationInboxFilter.Social.types!!.containsAll(listOf("activitypub_quote", "activitypub_reaction", "activitypub_follow_request", "activitypub_follow_accepted")))
        assertFalse(NotificationInboxFilter.Mentions.types!!.contains("activitypub_favorite"))
    }

    private fun notification(type: String, data: String) = Notification(
        id = "notification", userId = "profile", type = type,
        data = HarmonyJson.parseToJsonElement(data) as JsonObject, createdAt = "2026-10-01T12:00:00Z",
    )
}
