package lol.mony.harmony.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The instance's voice configuration, from `GET /api/livekit/config`.
 *
 * The web client reads the same endpoint (`fetchLiveKitConfig` in
 * `src/services/livekitTokens.ts`). `mode` says which transport the instance runs: `sfu` means
 * every participant talks to LiveKit, `p2p` means browsers connect directly, and `hybrid` lets
 * the instance choose per room. This client only speaks the LiveKit (SFU) transport, so a `p2p`
 * instance is reported as unsupported rather than silently degraded.
 */
@Serializable
data class VoiceConfig(
    val enabled: Boolean = false,
    val mode: String = "hybrid",
    @SerialName("wsUrl") val wsUrl: String? = null,
    @SerialName("allowFederatedVoice") val allowFederatedVoice: Boolean = true,
) {
    /** True when this client can join a room on this instance. */
    val supportsThisClient: Boolean get() = enabled && mode != "p2p"
}

/**
 * What `POST /api/livekit/token` answers: the join token, where to use it, and the room name.
 *
 * `identity` is the participant identity the instance chose for this person, which is what the
 * roster and the web client's participant list key on.
 */
@Serializable
data class VoiceToken(
    val token: String,
    @SerialName("wsUrl") val wsUrl: String,
    val roomName: String,
    val identity: String,
)

/**
 * One row of `voice_channel_participants`: who is in a channel's call right now.
 *
 * The web client upserts this row on join and deletes it on leave, and the database's own
 * triggers remove it when a member leaves the server or is timed out. Reading it is therefore
 * the same truth the web client shows, and writing it is what makes this client's presence
 * visible there.
 */
@Serializable
data class VoiceParticipant(
    val id: String? = null,
    @SerialName("channel_id") val channelId: String,
    @SerialName("server_id") val serverId: String? = null,
    @SerialName("user_id") val userId: String,
    @SerialName("joined_at") val joinedAt: String? = null,
    @SerialName("is_muted") val isMuted: Boolean = false,
    @SerialName("is_deafened") val isDeafened: Boolean = false,
    @SerialName("is_video_enabled") val isVideoEnabled: Boolean = false,
    @SerialName("is_screen_sharing") val isScreenSharing: Boolean = false,
    val metadata: JsonElement? = null,
)

/** Which of a participant's tracks this client is showing. */
enum class VoiceTransport { LiveKit }

/** What the call screen offers: the media a participant may publish. */
data class VoiceCapabilities(
    val canPublishAudio: Boolean = true,
    val canPublishVideo: Boolean = true,
    val canShareScreen: Boolean = true,
    val canSubscribe: Boolean = true,
)
