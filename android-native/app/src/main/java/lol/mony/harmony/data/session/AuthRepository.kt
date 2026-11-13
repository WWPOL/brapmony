package lol.mony.harmony.data.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lol.mony.harmony.data.api.AuthApi
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.model.AuthSession
import lol.mony.harmony.data.model.AuthUser
import lol.mony.harmony.data.model.Profile
import lol.mony.harmony.data.repo.ProfileRepository

/** Who is signed in, and the token every other repository borrows. */
sealed interface AuthState {
    /** Before the stored session has been read. */
    data object Loading : AuthState

    /** No instance chosen yet: the first-run instance picker. */
    data object NeedsInstance : AuthState

    /** An instance is chosen and nobody is signed in: the sign-in screen. */
    data class SignedOut(val endpoint: Endpoint, val instanceName: String? = null) : AuthState

    data class SignedIn(
        val endpoint: Endpoint,
        val user: AuthUser,
        val profile: Profile?,
    ) : AuthState
}

/**
 * Sign in, sign up, sign out and restore a session.
 *
 * The instance is chosen first and stored with its anon key, as the Android WebView client's
 * picker does; then this holds the session and refreshes it when it is about to expire, which
 * is what `supabase-js` does for the web client.
 */
class AuthRepository(
    private val store: SessionStore,
    private val authApi: AuthApi,
    private val profiles: ProfileRepository,
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val refreshMutex = Mutex()
    private var current: StoredSession? = null

    /** The access token repositories use, or null when signed out. */
    fun token(): String? = current?.accessToken

    fun endpoint(): Endpoint? = current?.endpoint ?: _state.value.let {
        when (it) {
            is AuthState.SignedIn -> it.endpoint
            is AuthState.SignedOut -> it.endpoint
            else -> null
        }
    }

    /** The signed-in account's auth user id, from the JWT's `sub`. */
    fun authUserId(): String? = current?.userId

    /** Reads the stored session and validates it, or sends the person to sign in. */
    suspend fun restore(defaultEndpoint: Endpoint?) {
        val stored = store.session()
        val endpoint = stored?.endpoint ?: store.endpoint() ?: defaultEndpoint?.also {
            store.saveInstance(it)
        }
        if (endpoint == null) {
            _state.value = AuthState.NeedsInstance
            return
        }
        val name = store.instanceName().orEmpty().ifBlank { null }
        if (stored == null) {
            _state.value = AuthState.SignedOut(endpoint, name)
            return
        }
        current = stored
        // A token past its expiry is refreshed before any screen asks for data.
        val usable = if (stored.expiresAtMillis - 60_000 <= System.currentTimeMillis()) {
            refresh(stored)
        } else {
            stored
        }
        if (usable == null) {
            current = null
            _state.value = AuthState.SignedOut(endpoint, name)
            return
        }
        val user = authApi.user(usable.endpoint, usable.accessToken)
        when (user) {
            is AuthApi.Result.Ok -> {
                current = usable
                _state.value = AuthState.SignedIn(
                    usable.endpoint,
                    user.value,
                    profiles.mine(usable.endpoint, usable.accessToken)?.profile,
                )
            }
            is AuthApi.Result.Failed -> {
                // The refresh token may still work when the access token was revoked.
                val refreshed = refresh(usable)
                if (refreshed == null) {
                    store.clearSession()
                    current = null
                    _state.value = AuthState.SignedOut(endpoint, name)
                } else {
                    signInSucceeded(refreshed)
                }
            }
        }
    }

    suspend fun chooseInstance(endpoint: Endpoint, name: String? = null) {
        store.saveInstance(endpoint, name)
        _state.value = AuthState.SignedOut(endpoint, name)
    }

    suspend fun signIn(email: String, password: String): AuthApi.Result<Unit> {
        val endpoint = endpoint() ?: return AuthApi.Result.Failed("Choose an instance first.")
        return when (val result = authApi.signInWithPassword(endpoint, email, password)) {
            is AuthApi.Result.Failed -> result
            is AuthApi.Result.Ok -> {
                adopt(endpoint, result.value)
                AuthApi.Result.Ok(Unit)
            }
        }
    }

    suspend fun signUp(email: String, password: String, username: String): AuthApi.Result<Unit> {
        val endpoint = endpoint() ?: return AuthApi.Result.Failed("Choose an instance first.")
        return when (val result = authApi.signUp(endpoint, email, password, username)) {
            is AuthApi.Result.Failed -> result
            is AuthApi.Result.Ok -> {
                adopt(endpoint, result.value)
                AuthApi.Result.Ok(Unit)
            }
        }
    }

    suspend fun requestPasswordReset(email: String): AuthApi.Result<Unit> {
        val endpoint = endpoint() ?: return AuthApi.Result.Failed("Choose an instance first.")
        return authApi.recover(endpoint, email)
    }

    suspend fun signOut() {
        val session = current
        if (session != null) authApi.signOut(session.endpoint, session.accessToken)
        store.clearSession()
        current = null
        val endpoint = endpoint()
        _state.value = if (endpoint != null) {
            AuthState.SignedOut(endpoint)
        } else {
            AuthState.NeedsInstance
        }
    }

    /** A fresh token for one request; refreshes first when the current one is nearly expired. */
    suspend fun freshToken(): String? {
        val session = current ?: return null
        if (session.expiresAtMillis - 30_000 > System.currentTimeMillis()) return session.accessToken
        return refresh(session)?.accessToken
    }

    private suspend fun adopt(endpoint: Endpoint, auth: AuthSession) {
        val stored = StoredSession(
            endpoint = endpoint,
            accessToken = auth.accessToken,
            refreshToken = auth.refreshToken,
            expiresAtMillis = auth.expiresAtMillis(),
            userId = auth.user?.id,
            email = auth.user?.email,
        )
        store.saveSession(stored)
        signInSucceeded(stored, auth.user)
    }

    private suspend fun signInSucceeded(stored: StoredSession, user: AuthUser? = null) {
        current = stored
        val resolved = user ?: when (val result = authApi.user(stored.endpoint, stored.accessToken)) {
            is AuthApi.Result.Ok -> result.value
            is AuthApi.Result.Failed -> null
        }
        _state.value = if (resolved != null) {
            AuthState.SignedIn(
                stored.endpoint,
                resolved,
                profiles.mine(stored.endpoint, stored.accessToken)?.profile,
            )
        } else {
            AuthState.SignedOut(stored.endpoint)
        }
    }

    private suspend fun refresh(stored: StoredSession): StoredSession? = refreshMutex.withLock {
        when (val result = authApi.refresh(stored.endpoint, stored.refreshToken)) {
            is AuthApi.Result.Failed -> null
            is AuthApi.Result.Ok -> {
                val next = stored.copy(
                    accessToken = result.value.accessToken,
                    refreshToken = result.value.refreshToken,
                    expiresAtMillis = result.value.expiresAtMillis(),
                )
                store.saveSession(next)
                current = next
                next
            }
        }
    }

    /** The account's own profile, once, for screens that need the name or avatar before a load. */
    suspend fun refreshProfile() {
        val state = _state.value as? AuthState.SignedIn ?: return
        val token = freshToken() ?: return
        val loaded = profiles.mine(state.endpoint, token) ?: return
        _state.update { current ->
            if (current is AuthState.SignedIn) current.copy(profile = loaded.profile) else current
        }
    }
}

