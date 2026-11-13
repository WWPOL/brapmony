package lol.mony.harmony.data.repo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import lol.mony.harmony.data.api.ApiException
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.api.Http
import lol.mony.harmony.data.api.RestApi
import lol.mony.harmony.data.model.AccountAuthIdentity
import lol.mony.harmony.data.model.AccountRecoveryStatus
import lol.mony.harmony.data.model.AccountSession
import lol.mony.harmony.data.model.HarmonyJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Typed implementations of AccountSecurityService and GoTrue's updateUser contract. */
class AccountSecurityRepository(
    private val rest: RestApi,
    private val client: OkHttpClient = Http.client,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun sessions(endpoint: Endpoint, token: String): Result<List<AccountSession>> = io {
        rest.rpc(endpoint, token, "list_my_sessions", serializer = ListSerializer(AccountSession.serializer())).getOrThrow()
    }

    suspend fun revokeSession(endpoint: Endpoint, token: String, sessionId: String): Result<Boolean> = io {
        scalar(endpoint, token, "revoke_my_session", buildJsonObject { put("p_session_id", sessionId) }, Boolean.serializer())
    }

    suspend fun signOutOtherSessions(endpoint: Endpoint, token: String): Result<Int> = io {
        scalar(endpoint, token, "sign_out_my_sessions", buildJsonObject { put("p_scope", "others") }, Int.serializer())
    }

    suspend fun recoveryStatus(endpoint: Endpoint, token: String): Result<AccountRecoveryStatus> = io {
        scalar(endpoint, token, "get_mfa_recovery_status", JsonObject(emptyMap()), AccountRecoveryStatus.serializer())
    }

    suspend fun identity(endpoint: Endpoint, token: String): Result<AccountAuthIdentity> = io {
        val request = authorized(endpoint, token).get().build()
        executeIdentity(request)
    }

    /** Never re-signs in to check the old password (that would create an unrelated session). */
    suspend fun changePassword(
        endpoint: Endpoint, token: String, currentPassword: String, newPassword: String,
    ): Result<Unit> = io {
        require(newPassword.length >= 8) { "Use at least eight characters for the new password." }
        val identity = executeIdentity(authorized(endpoint, token).get().build())
        if (identity.hasPassword) {
            require(currentPassword.isNotEmpty()) { "Enter your current password." }
            require(currentPassword != newPassword) { "Choose a different password from your current one." }
            val verified = scalar(endpoint, token, "verify_my_password", buildJsonObject {
                put("p_password", currentPassword)
            }, Boolean.serializer())
            check(verified) { "Your current password was not accepted." }
        }
        val body = buildJsonObject { put("password", newPassword) }.toString()
        val request = authorized(endpoint, token)
            .put(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        executeIdentity(request)
        Unit
    }

    private fun authorized(endpoint: Endpoint, token: String) = Request.Builder()
        .url("${endpoint.authUrl}/user")
        .header("apikey", endpoint.anonKey)
        .header("Authorization", "Bearer $token")

    private fun executeIdentity(request: Request): AccountAuthIdentity = client.newCall(request).execute().use { response ->
        val text = response.body.string()
        if (!response.isSuccessful) {
            val body = runCatching { HarmonyJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
            val code = body?.get("error_code")?.jsonPrimitive?.contentOrNull
            val message = when (code) {
                "user_sso_managed" -> "Your sign-in provider manages your password. Change it with that provider."
                else -> body?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: body?.get("msg")?.jsonPrimitive?.contentOrNull
                    ?: "The instance refused the security change (${response.code})."
            }
            throw ApiException(message, response.code)
        }
        HarmonyJson.decodeFromString(AccountAuthIdentity.serializer(), text)
    }

    private suspend fun <T> scalar(
        endpoint: Endpoint, token: String, function: String, args: JsonObject, serializer: KSerializer<T>,
    ): T = rest.rpc(
        endpoint, token, function, args.toString(), AccountScalarResponseSerializer(serializer),
    ).getOrThrow().single()

    private suspend fun <T> io(block: suspend () -> T): Result<T> = withContext(ioDispatcher) {
        try { Result.success(block()) } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
    }
}

/** RestApi's list-returning RPC adapter, without pretending scalar JSON is an SQL row array. */
internal class AccountScalarResponseSerializer<T>(serializer: KSerializer<T>) :
    JsonTransformingSerializer<List<T>>(ListSerializer(serializer)) {
    override fun transformDeserialize(element: JsonElement): JsonElement = JsonArray(listOf(element))
}
