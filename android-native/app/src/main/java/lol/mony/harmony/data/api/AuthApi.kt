package lol.mony.harmony.data.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import lol.mony.harmony.data.model.AuthError
import lol.mony.harmony.data.model.AuthSession
import lol.mony.harmony.data.model.AuthUser
import lol.mony.harmony.data.model.HarmonyJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * GoTrue, the auth service an instance serves under `/auth/v1`.
 *
 * The calls mirror `@supabase/supabase-js`'s, which the web client uses, so the account a person
 * creates here is the account they can sign in with on the web.
 */
class AuthApi(private val client: OkHttpClient, private val json: Json = HarmonyJson) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** The result of an auth call: a session, or the error to show. */
    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data class Failed(val message: String, val code: String? = null) : Result<Nothing>
    }

    fun signInWithPassword(endpoint: Endpoint, email: String, password: String): Result<AuthSession> {
        val body = buildJsonObject {
            put("email", email.trim())
            put("password", password)
        }
        return postToken(endpoint, body, grantType = "password")
    }

    fun signUp(endpoint: Endpoint, email: String, password: String, username: String): Result<AuthSession> {
        val body = buildJsonObject {
            put("email", email.trim())
            put("password", password)
            put("data", buildJsonObject { put("username", username) })
        }
        val request = Request.Builder()
            .url("${endpoint.authUrl}/signup")
            .header("apikey", endpoint.anonKey)
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(jsonMedia))
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) return@use failureOf(text, response.code)
                parseSession(text)
            }
        }.getOrElse { Result.Failed(it.message ?: "Could not reach the instance.") }
    }

    fun refresh(endpoint: Endpoint, refreshToken: String): Result<AuthSession> {
        val body = buildJsonObject { put("refresh_token", refreshToken) }
        return postToken(endpoint, body, grantType = "refresh_token")
    }

    /** Ends the session server-side; the local copy is cleared whether or not this succeeds. */
    fun signOut(endpoint: Endpoint, accessToken: String): Result<Unit> {
        val request = Request.Builder()
            .url("${endpoint.authUrl}/logout")
            .header("apikey", endpoint.anonKey)
            .header("Authorization", "Bearer $accessToken")
            .post("{}".toRequestBody(jsonMedia))
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) Result.Ok(Unit)
                else Result.Failed("Sign out was refused.", response.code.toString())
            }
        }.getOrElse { Result.Ok(Unit) }
    }

    /** GoTrue's password reset mail; the web client's `/reset-password` page is the landing. */
    fun recover(endpoint: Endpoint, email: String): Result<Unit> {
        val body = buildJsonObject { put("email", email.trim()) }
        val request = Request.Builder()
            .url("${endpoint.authUrl}/recover")
            .header("apikey", endpoint.anonKey)
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(jsonMedia))
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) Result.Ok(Unit)
                else Result.Failed("Could not send the reset mail.", response.code.toString())
            }
        }.getOrElse { Result.Failed(it.message ?: "Could not reach the instance.") }
    }

    /** The account behind an access token; used to validate a restored session on launch. */
    fun user(endpoint: Endpoint, accessToken: String): Result<AuthUser> {
        val request = Request.Builder()
            .url("${endpoint.authUrl}/user")
            .header("apikey", endpoint.anonKey)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) return@use failureOf(text, response.code)
                Result.Ok(json.decodeFromString(AuthUser.serializer(), text))
            }
        }.getOrElse { Result.Failed(it.message ?: "Could not reach the instance.") }
    }

    private fun postToken(endpoint: Endpoint, body: JsonObject, grantType: String): Result<AuthSession> {
        val request = Request.Builder()
            .url("${endpoint.authUrl}/token?grant_type=$grantType")
            .header("apikey", endpoint.anonKey)
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(jsonMedia))
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) return@use failureOf(text, response.code)
                parseSession(text)
            }
        }.getOrElse { Result.Failed(it.message ?: "Could not reach the instance.") }
    }

    private fun parseSession(text: String): Result<AuthSession> = runCatching {
        Result.Ok(json.decodeFromString(AuthSession.serializer(), text))
    }.getOrElse {
        // A sign-up on an instance that requires email confirmation returns the user with no
        // session; say so rather than "malformed response".
        if (text.contains("\"confirmation_sent\"") || text.contains("confirmation")) {
            Result.Failed("Check your email to confirm the account, then sign in.")
        } else {
            Result.Failed("The instance answered with something unexpected.")
        }
    }

    private fun failureOf(text: String, status: Int): Result.Failed {
        val parsed = runCatching { json.decodeFromString(AuthError.serializer(), text) }.getOrNull()
        val message = parsed?.sentence()
        return Result.Failed(message ?: "The instance answered $status.", parsed?.code)
    }

    /** Reads a value out of an arbitrary GoTrue body (used by the sign-up confirmation case). */
    internal fun rawUser(text: String): String? =
        runCatching { json.parseToJsonElement(text).jsonObject["user"]?.jsonPrimitive?.content }.getOrNull()
}
