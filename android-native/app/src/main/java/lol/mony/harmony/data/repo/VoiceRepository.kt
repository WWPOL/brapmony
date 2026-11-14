package lol.mony.harmony.data.repo

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lol.mony.harmony.data.api.ApiException
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.api.HttpAnswer
import lol.mony.harmony.data.api.RestApi
import lol.mony.harmony.data.api.answer
import lol.mony.harmony.data.model.HarmonyJson
import lol.mony.harmony.data.model.VoiceConfig
import lol.mony.harmony.data.model.VoiceParticipant
import lol.mony.harmony.data.model.VoiceToken
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Voice rooms, as the instance's own backend exposes them.
 *
 * Harmony's media plane is LiveKit, and the federation backend hands out room tokens through
 * `POST /api/livekit/token` after checking that the caller may be in that channel or DM. This
 * client uses exactly that endpoint, so a call it places is a call the web client can hear and
 * vice versa; nothing here invents a second signaling path.
 *
 * The roster is `voice_channel_participants`, which is the table the web client writes on join
 * and deletes on leave. Presence therefore stays visible across clients instead of being a
 * private list this app alone believes.
 */
class VoiceRepository(private val rest: RestApi, private val client: OkHttpClient) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val json: Json = HarmonyJson

    /** The instance's voice configuration, or null when it does not offer voice at all. */
    suspend fun config(endpoint: Endpoint, token: String): Result<VoiceConfig?> = runCatching {
        val url = endpoint.origin.trimEnd('/') + "/api/livekit/config"
        val request = Request.Builder().url(url.toHttpUrl())
            .header("Authorization", "Bearer $token")
            .header("apikey", endpoint.anonKey)
            .get().build()
        val answer: HttpAnswer = client.answer(request)
        // A gateway without the backend mounted answers 404; that is "no voice here", not an
        // error worth interrupting a person with.
        if (answer.status == 404) return@runCatching null
        if (!answer.successful) throw ApiException(explain(answer.text, answer.status), answer.status)
        runCatching { json.decodeFromString(VoiceConfig.serializer(), answer.text) }.getOrNull()
    }

    /**
     * A join token for one room.
     *
     * [roomType] is the reference's own vocabulary: `voice_channel` for a server channel,
     * `dm_call` for a direct conversation. [roomName] follows `liveKitRoomName`: a channel is
     * `channel-<id>` and a DM is the bare conversation id.
     */
    suspend fun token(endpoint: Endpoint, token: String, roomName: String, roomType: String): Result<VoiceToken> =
        runCatching {
            val url = endpoint.origin.trimEnd('/') + "/api/livekit/token"
            val body = buildJsonObject {
                put("roomName", roomName)
                put("roomType", roomType)
            }.toString()
            val request = Request.Builder().url(url.toHttpUrl())
                .header("Authorization", "Bearer $token")
                .header("apikey", endpoint.anonKey)
                .post(body.toRequestBody(jsonMedia))
                .build()
            val answer = client.answer(request)
            if (!answer.successful) throw ApiException(explain(answer.text, answer.status), answer.status)
            json.decodeFromString(VoiceToken.serializer(), answer.text)
        }

    /** Who is in a channel's call, from the table the web client keeps current. */
    suspend fun participants(endpoint: Endpoint, token: String, channelId: String): Result<List<VoiceParticipant>> =
        rest.select(endpoint, token, "voice_channel_participants",
            filters = listOf("channel_id" to "eq.$channelId"),
            serializer = ListSerializer(VoiceParticipant.serializer()))

    /** Announces this person in a channel's call, as the web client's join does. */
    suspend fun joinRoster(endpoint: Endpoint, token: String, channelId: String,
                           serverId: String, profileId: String): Result<Unit> = runCatching {
        val row = buildJsonObject {
            put("channel_id", channelId)
            put("server_id", serverId)
            put("user_id", profileId)
            put("joined_at", java.time.Instant.now().toString())
            put("is_federated", false)
            put("metadata", buildJsonObject { })
        }.toString()
        rest.insert(endpoint, token, "voice_channel_participants", row,
            serializer = ListSerializer(VoiceParticipant.serializer())).getOrThrow()
        Unit
    }

    /** Withdraws this person's roster row; the database also does this on a disconnect. */
    suspend fun leaveRoster(endpoint: Endpoint, token: String, channelId: String, profileId: String): Result<Unit> =
        rest.delete(endpoint, token, "voice_channel_participants",
            listOf("channel_id" to "eq.$channelId", "user_id" to "eq.$profileId"))

    /** Mirrors this client's own mute, deafen, camera and screen state into the roster row. */
    suspend fun updateRoster(endpoint: Endpoint, token: String, channelId: String, profileId: String,
                             muted: Boolean, deafened: Boolean, video: Boolean, sharing: Boolean): Result<Unit> =
        runCatching {
            rest.update(endpoint, token, "voice_channel_participants",
                filters = listOf("channel_id" to "eq.$channelId", "user_id" to "eq.$profileId"),
                patch = buildJsonObject {
                    put("is_muted", muted); put("is_deafened", deafened)
                    put("is_video_enabled", video); put("is_screen_sharing", sharing)
                }.toString(),
                serializer = ListSerializer(VoiceParticipant.serializer())).getOrThrow()
            Unit
        }

    private fun explain(text: String, status: Int): String {
        val body = runCatching { json.parseToJsonElement(text) }.getOrNull()
        val message = (body as? kotlinx.serialization.json.JsonObject)?.get("error")?.toString()?.trim('"')
            ?: (body as? kotlinx.serialization.json.JsonObject)?.get("message")?.toString()?.trim('"')
        return message?.takeIf { it.isNotBlank() } ?: "The instance refused the voice request ($status)."
    }

    companion object {
        /**
         * The LiveKit room a channel or conversation uses, exactly as the web client names it
         * (`liveKitRoomName` in `src/services/livekitTokens.ts`). Both clients must agree on
         * this string or they end up in two different rooms with the same channel open.
         */
        fun roomName(channelId: String, direct: Boolean): String =
            if (direct) channelId else "channel-$channelId"

        fun roomType(direct: Boolean): String = if (direct) "dm_call" else "voice_channel"
    }
}
