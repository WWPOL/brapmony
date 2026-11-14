package lol.mony.harmony

import lol.mony.harmony.data.model.Notification
import lol.mony.harmony.data.model.NotificationChannelIds
import lol.mony.harmony.data.model.NotificationDestination
import lol.mony.harmony.data.model.NotificationRouter
import lol.mony.harmony.data.model.Thread
import lol.mony.harmony.data.repo.VoiceRepository
import lol.mony.harmony.data.voice.DistanceModel
import lol.mony.harmony.data.voice.SpatialAudio
import lol.mony.harmony.data.voice.SpatialAudioSettings
import lol.mony.harmony.data.voice.UserPosition
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The rules that must agree with the reference and with the instance: which room a call uses,
 * how a spatial mix is computed, which destination a notification opens, and whether a thread
 * still belongs in the sidebar.
 */
class VoiceAndRoutingTest {
    // ---- the LiveKit room name, which both clients must agree on --------------------------

    @Test fun aChannelCallUsesTheSameRoomNameAsTheWebClient() {
        assertEquals("channel-abc", VoiceRepository.roomName("abc", direct = false))
        assertEquals("voice_channel", VoiceRepository.roomType(direct = false))
    }

    @Test fun aDirectCallUsesTheBareConversationId() {
        assertEquals("conv-1", VoiceRepository.roomName("conv-1", direct = true))
        assertEquals("dm_call", VoiceRepository.roomType(direct = true))
    }

    // ---- spatial audio, which must match src/stores/spatialAudio.ts -----------------------

    private val settings = SpatialAudioSettings(enabled = true, maxDistance = 300.0, rolloffFactor = 1.0)

    @Test fun spatialAudioIsOffByDefault() {
        assertFalse(SpatialAudioSettings.DEFAULT.enabled)
        assertEquals(1.0, SpatialAudio.gain(null, null, SpatialAudioSettings.DEFAULT), 0.0)
    }

    @Test fun beyondTheHearingRangeTheGainIsSilent() {
        val listener = UserPosition("a", 0.0, 0.0)
        val far = UserPosition("b", 400.0, 0.0)
        assertEquals(0.0, SpatialAudio.gain(listener, far, settings), 0.0001)
    }

    @Test fun theInverseModelDividesByTheReferencesFifty() {
        val listener = UserPosition("a", 0.0, 0.0)
        val near = UserPosition("b", 50.0, 0.0)
        // 1 / (1 + 1 * 50 / 50) = 0.5
        assertEquals(0.5, SpatialAudio.gain(listener, near, settings), 0.0001)
    }

    @Test fun theLinearModelFallsOffWithDistance() {
        val listener = UserPosition("a", 0.0, 0.0)
        val half = UserPosition("b", 150.0, 0.0)
        assertEquals(0.5, SpatialAudio.gain(listener, half, settings.copy(distanceModel = DistanceModel.Linear)), 0.0001)
    }

    @Test fun panIsClampedToOneAndFollowsTheSideTheSpeakerIsOn() {
        val listener = UserPosition("a", 0.0, 0.0)
        assertEquals(0.0, SpatialAudio.pan(listener, UserPosition("b", 0.0, 0.0), settings), 0.0)
        assertEquals(1.0, SpatialAudio.pan(listener, UserPosition("b", 500.0, 0.0), settings), 0.0)
        assertEquals(-1.0, SpatialAudio.pan(listener, UserPosition("b", -500.0, 0.0), settings), 0.0)
    }

    @Test fun anUnknownPositionIsHeardNormallyRatherThanSilently() {
        assertEquals(1.0, SpatialAudio.gain(null, null, settings), 0.0)
    }

    @Test fun equalPowerPanningPutsAllTheSignalOnOneSide() {
        val (left, right) = SpatialAudio.channelGains(-1.0)
        assertTrue(left > 0.99)
        assertTrue(right < 0.01)
        val (centreLeft, centreRight) = SpatialAudio.channelGains(0.0)
        assertEquals(centreLeft, centreRight, 0.0001)
    }

    // ---- notification routing, by the reference's precedence ------------------------------

    private fun notification(type: String, data: JsonObject) =
        Notification(id = "n1", userId = "u1", type = type, data = data, createdAt = "2026-01-01T00:00:00Z")

    @Test fun aConversationNotificationOpensThatConversationAndMessage() {
        val route = NotificationRouter.destination(notification("dm", buildJsonObject {
            put("conversation_id", "conv-9")
            put("message_id", "msg-9")
        }))
        assertEquals(NotificationDestination.Conversation("conv-9", "msg-9"), route)
    }

    @Test fun aChannelNotificationNamesItsChannelAndMessage() {
        val route = NotificationRouter.destination(notification("mention", buildJsonObject {
            put("server_id", "s1"); put("channel_id", "c1"); put("message_id", "m1")
        }))
        assertEquals(NotificationDestination.Channel("s1", "c1", "m1"), route)
    }

    @Test fun aThreadNotificationOpensTheThreadRatherThanTheChannel() {
        val route = NotificationRouter.destination(notification("thread_reply", buildJsonObject {
            put("server_id", "s1"); put("channel_id", "c1")
            put("thread", buildJsonObject { put("id", "t1") })
            put("message_id", "m2")
        }))
        assertEquals(NotificationDestination.Thread("s1", "c1", "t1", "m2"), route)
    }

    @Test fun aSecurityNoticeOpensSecuritySettings() {
        assertEquals(NotificationDestination.Security,
            NotificationRouter.destination(notification("security", JsonObject(emptyMap()))))
    }

    @Test fun aFollowRequestOpensTheRequestsList() {
        assertEquals(NotificationDestination.FollowRequests,
            NotificationRouter.destination(notification("activitypub_follow_request", JsonObject(emptyMap()))))
    }

    @Test fun aSocialPostNotificationOpensThePost() {
        val route = NotificationRouter.destination(notification("activitypub_reply", buildJsonObject {
            put("post_id", "p1")
        }))
        assertEquals(NotificationDestination.Post("p1"), route)
    }

    @Test fun aNotificationThatNamesNothingFallsBackToChats() {
        assertEquals(NotificationDestination.Chats,
            NotificationRouter.destination(notification("unknown_kind", JsonObject(emptyMap()))))
    }

    @Test fun eachKindGetsItsOwnNotificationChannel() {
        assertEquals(NotificationChannelIds.MESSAGES, NotificationChannelIds.of("dm"))
        assertEquals(NotificationChannelIds.MENTIONS, NotificationChannelIds.of("mention"))
        assertEquals(NotificationChannelIds.SOCIAL, NotificationChannelIds.of("activitypub_follow"))
        assertEquals(NotificationChannelIds.CALLS, NotificationChannelIds.of("incoming_call"))
    }

    // ---- a thread leaves the sidebar when it is closed or its clock runs out --------------

    private fun thread(archived: Boolean = false, lastAt: String?, minutes: Int?) = Thread(
        id = "t1", channelId = "c1", archived = archived, locked = false,
        autoArchiveDuration = minutes, lastActivityAt = lastAt,
    )

    @Test fun anArchivedThreadIsNotActive() {
        assertFalse(thread(archived = true, lastAt = Instant.now().toString(), minutes = 1440).isActive())
    }

    @Test fun aThreadPastItsAutoArchiveWindowIsNotActive() {
        val old = Instant.now().minusSeconds(3 * 60 * 60).toString()
        assertFalse(thread(lastAt = old, minutes = 60).isActive())
    }

    @Test fun aFreshThreadIsStillActive() {
        assertTrue(thread(lastAt = Instant.now().toString(), minutes = 1440).isActive())
    }

    @Test fun aThreadWithNoClockNeverExpires() {
        assertTrue(thread(lastAt = null, minutes = null).isActive())
        assertTrue(thread(lastAt = Instant.now().minusSeconds(999_999).toString(), minutes = null).isActive())
    }
}
