package lol.mony.harmony.data.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import lol.mony.harmony.data.model.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** GoTrue calls, with user credentials sent only to the selected instance over its configured transport. */
class AuthApi(private val client: OkHttpClient, private val json: Json = HarmonyJson) {
    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data class Failed(val message: String, val code: String? = null) : Result<Nothing>
    }
    private val media = "application/json; charset=utf-8".toMediaType()

    suspend fun signInWithPassword(endpoint: Endpoint, email: String, password: String): Result<AuthSession> =
        session(endpoint, "/token?grant_type=password", buildJsonObject {
            put("email", email.trim()); put("password", password)
        })
    suspend fun refresh(endpoint: Endpoint, refreshToken: String): Result<AuthSession> =
        session(endpoint, "/token?grant_type=refresh_token", buildJsonObject { put("refresh_token", refreshToken) })
    suspend fun signUp(endpoint: Endpoint, email: String, password: String, username: String): Result<AuthSession> =
        session(endpoint, "/signup", buildJsonObject {
            put("email", email.trim()); put("password", password)
            put("data", buildJsonObject { put("username", username) })
        })
    suspend fun signOut(endpoint: Endpoint, accessToken: String): Result<Unit> = request(
        endpoint, "/logout", JsonObject(emptyMap()), accessToken) { Unit }
    suspend fun recover(endpoint: Endpoint, email: String): Result<Unit> = request(
        endpoint, "/recover", buildJsonObject { put("email", email.trim()) }) { Unit }
    suspend fun user(endpoint: Endpoint, accessToken: String): Result<AuthUser> = request(
        endpoint, "/user", token = accessToken) { json.decodeFromString(AuthUser.serializer(), it) }

    private suspend fun session(endpoint: Endpoint, path: String, body: JsonObject): Result<AuthSession> =
        request(endpoint, path, body) { text ->
            val objectBody = json.parseToJsonElement(text).jsonObject
            if (objectBody["access_token"] == null) throw ApiException(
                "Check your email to confirm the account, then sign in.")
            json.decodeFromString(AuthSession.serializer(), text)
        }

    private suspend fun <T> request(endpoint: Endpoint, path: String, body: JsonObject? = null,
                                    token: String? = null, parse: (String) -> T): Result<T> {
        val request = Request.Builder().url(endpoint.authUrl + path).header("apikey", endpoint.anonKey)
            .apply {
                token?.let { header("Authorization", "Bearer $it") }
                if (body == null) get() else post(body.toString().toRequestBody(media))
            }.build()
        return networkResult {
            val response = client.answer(request)
            if (!response.successful) {
                val error = runCatching { json.parseToJsonElement(response.text).jsonObject }.getOrNull()
                val message = listOf("error_description", "msg", "message", "error")
                    .firstNotNullOfOrNull { (error?.get(it) as? JsonPrimitive)?.contentOrNull }
                    ?: "The instance answered ${response.status}."
                throw ApiException(message, response.status)
            }
            parse(response.text)
        }.fold({ Result.Ok(it) }, { Result.Failed(it.message ?: "Could not reach the instance.",
            (it as? ApiException)?.status?.toString()) })
    }
}
