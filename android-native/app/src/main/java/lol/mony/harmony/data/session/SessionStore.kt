package lol.mony.harmony.data.session

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.model.HarmonyJson

private val Context.sessionData: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> by
    preferencesDataStore(name = "harmony-session")

/**
 * What a signed-in install remembers: where the instance is, who is signed in and the tokens.
 *
 * The instance and its anon key are stored even while signed out - a native client asks which
 * instance to use before it can sign in, the same way the desktop and Android WebView builds
 * do (the web client's "Choose an instance").
 */
data class StoredSession(
    val endpoint: Endpoint,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
    val userId: String?,
    val email: String?,
)

class SessionStore(private val context: Context, private val json: Json = HarmonyJson) {
    private object Keys {
        val BASE_URL = stringPreferencesKey("instance.baseUrl")
        val ANON_KEY = stringPreferencesKey("instance.anonKey")
        val INSTANCE_NAME = stringPreferencesKey("instance.name")
        val ACCESS = stringPreferencesKey("auth.accessToken")
        val REFRESH = stringPreferencesKey("auth.refreshToken")
        val EXPIRES = stringPreferencesKey("auth.expiresAt")
        val USER_ID = stringPreferencesKey("auth.userId")
        val EMAIL = stringPreferencesKey("auth.email")
        // The web client's own key for "this device keeps its own look".
        val APPEARANCE_SYNC = stringPreferencesKey("appearance-sync")
    }

    /** The stored instance, if one was chosen; the default is the debug build's local stack. */
    val endpointFlow: Flow<Endpoint?> = context.sessionData.data.map { prefs ->
        val base = prefs[Keys.BASE_URL] ?: return@map null
        val key = prefs[Keys.ANON_KEY] ?: return@map null
        Endpoint(base, key)
    }

    val instanceNameFlow: Flow<String?> = context.sessionData.data.map { it[Keys.INSTANCE_NAME] }

    val sessionFlow: Flow<StoredSession?> = context.sessionData.data.map { prefs -> read(prefs) }

    suspend fun session(): StoredSession? = sessionFlow.first()

    suspend fun endpoint(): Endpoint? = endpointFlow.first()

    suspend fun instanceName(): String? = instanceNameFlow.first()

    suspend fun saveInstance(endpoint: Endpoint, name: String? = null) {
        context.sessionData.edit { prefs ->
            val previous = prefs[Keys.BASE_URL]
            prefs[Keys.BASE_URL] = endpoint.baseUrl.trimEnd('/')
            prefs[Keys.ANON_KEY] = endpoint.anonKey
            if (name != null) prefs[Keys.INSTANCE_NAME] = name
            // Another instance means another account: never carry tokens across.
            if (previous != null && previous.trimEnd('/') != endpoint.baseUrl.trimEnd('/')) {
                prefs.remove(Keys.ACCESS)
                prefs.remove(Keys.REFRESH)
                prefs.remove(Keys.EXPIRES)
                prefs.remove(Keys.USER_ID)
                prefs.remove(Keys.EMAIL)
            }
        }
    }

    suspend fun saveSession(session: StoredSession) {
        context.sessionData.edit { prefs ->
            prefs[Keys.BASE_URL] = session.endpoint.baseUrl.trimEnd('/')
            prefs[Keys.ANON_KEY] = session.endpoint.anonKey
            prefs[Keys.ACCESS] = session.accessToken
            prefs[Keys.REFRESH] = session.refreshToken
            prefs[Keys.EXPIRES] = session.expiresAtMillis.toString()
            session.userId?.let { prefs[Keys.USER_ID] = it } ?: prefs.remove(Keys.USER_ID)
            session.email?.let { prefs[Keys.EMAIL] = it } ?: prefs.remove(Keys.EMAIL)
        }
    }

    suspend fun clearSession() {
        context.sessionData.edit { prefs ->
            prefs.remove(Keys.ACCESS)
            prefs.remove(Keys.REFRESH)
            prefs.remove(Keys.EXPIRES)
            prefs.remove(Keys.USER_ID)
            prefs.remove(Keys.EMAIL)
        }
    }

    /** `device` while this device keeps its own look; absent while it follows the account. */
    val appearanceScopeFlow: Flow<String?> = context.sessionData.data.map { it[Keys.APPEARANCE_SYNC] }

    suspend fun setAppearanceScope(deviceOnly: Boolean) {
        context.sessionData.edit { prefs ->
            if (deviceOnly) prefs[Keys.APPEARANCE_SYNC] = "device" else prefs.remove(Keys.APPEARANCE_SYNC)
        }
    }

    private fun read(prefs: androidx.datastore.preferences.core.Preferences): StoredSession? {
        val base = prefs[Keys.BASE_URL] ?: return null
        val key = prefs[Keys.ANON_KEY] ?: return null
        val access = prefs[Keys.ACCESS] ?: return null
        val refresh = prefs[Keys.REFRESH] ?: return null
        return StoredSession(
            endpoint = Endpoint(base, key),
            accessToken = access,
            refreshToken = refresh,
            expiresAtMillis = prefs[Keys.EXPIRES]?.toLongOrNull() ?: 0L,
            userId = prefs[Keys.USER_ID],
            email = prefs[Keys.EMAIL],
        )
    }
}
