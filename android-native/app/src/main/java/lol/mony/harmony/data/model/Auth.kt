package lol.mony.harmony.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** GoTrue's `/token?grant_type=password` body and its refresh sibling. */
@Serializable
data class AuthSession(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long = 3600,
    @SerialName("token_type") val tokenType: String = "bearer",
    val user: AuthUser? = null,
) {
    /** When the access token stops being accepted, from the moment the response was read. */
    fun expiresAtMillis(now: Long = System.currentTimeMillis()): Long = now + expiresIn * 1000
}

@Serializable
data class AuthUser(
    val id: String,
    val email: String? = null,
    @SerialName("email_confirmed_at") val emailConfirmedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("user_metadata") val userMetadata: Map<String, String> = emptyMap(),
    @SerialName("app_metadata") val appMetadata: Map<String, String> = emptyMap(),
)

/** GoTrue's error shape, also used by its `/signup` and `/recover` responses. */
@Serializable
data class AuthError(
    val error: String? = null,
    @SerialName("error_description") val description: String? = null,
    val message: String? = null,
    @SerialName("msg") val shortMessage: String? = null,
    val code: String? = null,
) {
    /** The sentence to show a person: GoTrue's most specific field, else a generic failure. */
    fun sentence(): String = description ?: message ?: shortMessage ?: error ?: "Something went wrong."
}
