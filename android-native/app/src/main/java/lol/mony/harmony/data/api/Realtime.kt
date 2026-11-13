package lol.mony.harmony.data.api

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import lol.mony.harmony.data.model.HarmonyJson
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.atomic.AtomicInteger

sealed interface LiveUpdate {
    data object Connected : LiveUpdate
    data object Changed : LiveUpdate
    data class Unavailable(val reason: String) : LiveUpdate
}

/** Private Harmony message broadcasts, not public/unfiltered postgres subscriptions. */
class Realtime(private val client: OkHttpClient) {
    fun room(endpoint: Endpoint, token: String, roomId: String, direct: Boolean): Flow<LiveUpdate> = callbackFlow {
        val name = if (direct) "dm-conversation-$roomId" else "channel-messages-$roomId"
        val topic = "realtime:$name"
        val refs = AtomicInteger()
        val url = (endpoint.baseUrl.trimEnd('/') + "/realtime/v1/websocket").toHttpUrl().newBuilder()
            .addQueryParameter("apikey", endpoint.anonKey).addQueryParameter("vsn", "1.0.0").build()
        fun frame(topic: String, event: String, payload: JsonObject): String = buildJsonObject {
            put("topic", topic); put("event", event); put("payload", payload)
            put("ref", refs.incrementAndGet().toString())
        }.toString()
        val socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(frame(topic, "phx_join", buildJsonObject {
                    put("access_token", token)
                    put("config", buildJsonObject {
                        put("private", true)
                        put("broadcast", buildJsonObject { put("ack", false); put("self", false) })
                        put("presence", buildJsonObject { put("key", "") })
                        put("postgres_changes", JsonArray(emptyList()))
                    })
                }))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = runCatching { HarmonyJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                val event = message["event"]?.jsonPrimitive?.content
                val payload = message["payload"] as? JsonObject ?: return
                if (event == "phx_reply" && message["topic"]?.jsonPrimitive?.content == topic) {
                    if (payload["status"]?.jsonPrimitive?.content == "ok") trySend(LiveUpdate.Connected)
                    else trySend(LiveUpdate.Unavailable("Live updates were refused by the instance. Refresh to update."))
                }
                if (event == "broadcast" && payload["event"]?.jsonPrimitive?.content in
                    setOf("message_event", "reaction_event", "poll_event")) trySend(LiveUpdate.Changed)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                response?.close()
                trySend(LiveUpdate.Unavailable("Live connection unavailable. Refresh to update."))
                close()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { close() }
        })
        val heartbeat = launch {
            while (true) {
                delay(25_000)
                socket.send(frame("phoenix", "heartbeat", JsonObject(emptyMap())))
            }
        }
        awaitClose { heartbeat.cancel(); socket.close(1000, "Leaving room") }
    }
}
