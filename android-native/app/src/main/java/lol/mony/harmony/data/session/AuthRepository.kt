package lol.mony.harmony.data.session

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import lol.mony.harmony.data.api.*
import lol.mony.harmony.data.model.*
import lol.mony.harmony.data.repo.ProfileRepository
import lol.mony.harmony.data.repo.Jwt

sealed interface AuthState {
    data object Loading : AuthState
    data object NeedsInstance : AuthState
    data class SignedOut(val endpoint: Endpoint, val instanceName: String? = null) : AuthState
    data class SignedIn(val endpoint: Endpoint, val user: AuthUser, val profile: Profile?) : AuthState
}

/** One active instance/account, with serialized refresh and no AAL1 bypass of MFA. */
class AuthRepository(private val store: SessionStore, private val authApi: AuthApi,
                     private val profiles: ProfileRepository) {
    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()
    private val refreshMutex = Mutex()
    private var current: StoredSession? = null
    fun token(): String? = current?.accessToken
    fun authUserId(): String? = current?.userId
    fun endpoint(): Endpoint? = current?.endpoint ?: when (val state = _state.value) {
        is AuthState.SignedIn -> state.endpoint
        is AuthState.SignedOut -> state.endpoint
        else -> null
    }

    suspend fun restore(defaultEndpoint: Endpoint?) {
        val saved = store.session()
        val endpoint = saved?.endpoint ?: store.endpoint() ?: defaultEndpoint?.also { store.saveInstance(it) }
        if (endpoint == null) { _state.value = AuthState.NeedsInstance; return }
        if (saved == null) { _state.value = AuthState.SignedOut(endpoint, store.instanceName()); return }
        current = saved
        val session = if (saved.expiresAtMillis < System.currentTimeMillis() + 60_000) {
            refresh(saved) ?: run { current = null; _state.value = AuthState.SignedOut(endpoint); return }
        } else saved
        when (val user = authApi.user(session.endpoint, session.accessToken)) {
            is AuthApi.Result.Failed -> {
                // Do not erase refresh credentials just because the network is unavailable.
                current = null
                _state.value = AuthState.SignedOut(endpoint)
            }
            is AuthApi.Result.Ok -> {
                enforceMfa(user.value, session.accessToken)
                val profile = profiles.mine(endpoint, session.accessToken)?.profile
                _state.value = AuthState.SignedIn(endpoint, user.value, profile)
            }
        }
    }

    suspend fun chooseInstance(endpoint: Endpoint, name: String? = null) {
        current = null
        store.clearSession()
        store.saveInstance(endpoint, name)
        _state.value = AuthState.SignedOut(endpoint, name)
    }

    suspend fun signIn(email: String, password: String): AuthApi.Result<Unit> {
        val endpoint = endpoint() ?: return AuthApi.Result.Failed("Choose an instance first.")
        return when (val result = authApi.signInWithPassword(endpoint, email, password)) {
            is AuthApi.Result.Failed -> result
            is AuthApi.Result.Ok -> adopt(endpoint, result.value)
        }
    }
    suspend fun signUp(email: String, password: String, username: String): AuthApi.Result<Unit> {
        val endpoint = endpoint() ?: return AuthApi.Result.Failed("Choose an instance first.")
        return when (val result = authApi.signUp(endpoint, email, password, username)) {
            is AuthApi.Result.Failed -> result
            is AuthApi.Result.Ok -> adopt(endpoint, result.value)
        }
    }
    suspend fun requestPasswordReset(email: String): AuthApi.Result<Unit> = endpoint()?.let {
        authApi.recover(it, email)
    } ?: AuthApi.Result.Failed("Choose an instance first.")

    suspend fun signOut() {
        val endpoint = endpoint()
        val old = current
        // Local credentials are cleared even when the server cannot be reached.
        current = null
        store.clearSession()
        _state.value = endpoint?.let { AuthState.SignedOut(it) } ?: AuthState.NeedsInstance
        if (old != null) authApi.signOut(old.endpoint, old.accessToken)
    }

    suspend fun freshToken(): String? {
        val saved = current ?: return null
        return if (saved.expiresAtMillis > System.currentTimeMillis() + 30_000) saved.accessToken
        else refresh(saved)?.accessToken
    }

    private suspend fun adopt(endpoint: Endpoint, session: AuthSession): AuthApi.Result<Unit> {
        val user = session.user ?: when (val result = authApi.user(endpoint, session.accessToken)) {
            is AuthApi.Result.Ok -> result.value
            is AuthApi.Result.Failed -> return result
        }
        return try {
            enforceMfa(user, session.accessToken)
            val profile = profiles.mine(endpoint, session.accessToken)?.profile
            val stored = StoredSession(endpoint, session.accessToken, session.refreshToken,
                session.expiresAtMillis(), user.id, user.email)
            store.saveSession(stored)
            current = stored
            _state.value = AuthState.SignedIn(endpoint, user, profile)
            AuthApi.Result.Ok(Unit)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AuthApi.Result.Failed(error.message ?: "Could not load this account. Nothing was saved.")
        }
    }

    private suspend fun refresh(stored: StoredSession): StoredSession? = refreshMutex.withLock {
        // Another caller may already have refreshed while we waited for the lock.
        current?.takeIf { it.accessToken != stored.accessToken &&
            it.expiresAtMillis > System.currentTimeMillis() + 30_000 }?.let { return@withLock it }
        when (val result = authApi.refresh(stored.endpoint, stored.refreshToken)) {
            is AuthApi.Result.Failed -> null
            is AuthApi.Result.Ok -> stored.copy(accessToken = result.value.accessToken,
                refreshToken = result.value.refreshToken, expiresAtMillis = result.value.expiresAtMillis())
                .also { store.saveSession(it); current = it }
        }
    }

    suspend fun refreshProfile() {
        val state = _state.value as? AuthState.SignedIn ?: return
        val token = freshToken() ?: return
        _state.value = state.copy(profile = profiles.mine(state.endpoint, token)?.profile)
    }
}

/** Do not grant native access to a password-only session of an MFA-enrolled account. */
internal fun enforceMfa(user: AuthUser, token: String) {
    if (user.factors.none { it.status == "verified" }) return
    val body = HarmonyJson.parseToJsonElement(String(java.util.Base64.getUrlDecoder().decode(token.split('.')[1]), Charsets.UTF_8)).jsonObject
    val aal2 = body["aal"]?.jsonPrimitive?.content == "aal2"
    val validatedEarlier = (body["amr"] as? JsonArray).orEmpty().any {
        (it as? JsonObject)?.get("method")?.jsonPrimitive?.content == "totp"
    }
    require(aal2 || validatedEarlier) {
        "Two-factor authentication is required. This client does not yet implement its challenge screen; no session was saved."
    }
}
