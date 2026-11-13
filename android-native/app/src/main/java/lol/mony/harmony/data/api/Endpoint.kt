package lol.mony.harmony.data.api

/**
 * Where an instance lives.
 *
 * Harmony instances serve GoTrue under `/auth/v1` and PostgREST under `/rest/v1` behind their
 * gateway, so one base URL plus the instance's anon key is all a client needs - the same pair
 * the web client's `/config.json` carries.
 */
data class Endpoint(val baseUrl: String, val anonKey: String) {
    val authUrl: String get() = baseUrl.trimEnd('/') + "/auth/v1"
    val restUrl: String get() = baseUrl.trimEnd('/') + "/rest/v1"
    val realtimeUrl: String
        get() {
            val scheme = if (baseUrl.startsWith("https")) "wss" else "ws"
            val host = baseUrl.substringAfter("://").trimEnd('/')
            return "$scheme://$host/realtime/v1/websocket"
        }
}
