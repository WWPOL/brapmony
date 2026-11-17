package lol.mony.harmony.data.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.model.HarmonyJson
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Corrupt preferences are replaced with an empty store rather than thrown.
 *
 * Without this, one truncated write -- a kill or a crash in the middle of `edit` -- leaves a
 * file that cannot be decoded, and *every* read throws `CorruptionException`. The app has
 * nowhere to fall back to: `HarmonyRoot` turns the throw into "Could not load this / Unable to
 * parse preferences proto.", and its own "Try again" re-reads the same file, so the screen is
 * permanent and only a reinstall clears it. Replacing the file costs a sign-in (the tokens are
 * sealed with a Keystore key and the drafts and appearance cache are conveniences), which is
 * the right trade against an app that cannot start.
 */
private val Context.sessionData by preferencesDataStore(
    name = "brapmony-session",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

@Serializable
data class StoredSession(
    val endpoint: Endpoint,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
    val userId: String?,
    val email: String?,
)

/**
 * How stored session tokens are sealed at rest.
 *
 * Production always uses [AndroidKeystoreSealer]: a per-install AES-GCM key in the Android
 * Keystore, excluded from backup. The interface exists so that JVM tests and the headless
 * Compose render harness can run the real repositories; nothing in the app selects another
 * implementation.
 */
interface SessionSealer {
    fun seal(plaintext: String): String
    fun unseal(sealed: String): String
}

class AndroidKeystoreSealer : SessionSealer {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun seal(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return java.util.Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }
    override fun unseal(sealed: String): String {
        val bytes = java.util.Base64.getDecoder().decode(sealed)
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
    companion object { private const val KEY_ALIAS = "brapmony.session.v1" }
}

/** Tokens are sealed with an install-specific Android Keystore key, excluded from backup. */
class SessionStore(private val context: Context, private val sealer: SessionSealer = AndroidKeystoreSealer()) {
    private object Keys {
        val URL = stringPreferencesKey("instance.url")
        val ANON = stringPreferencesKey("instance.anon")
        val NAME = stringPreferencesKey("instance.name")
        val SEALED = stringPreferencesKey("session.sealed.v1")
    }
    val endpointFlow: Flow<Endpoint?> = context.sessionData.data.map {
        val url = it[Keys.URL] ?: return@map null
        Endpoint(url, it[Keys.ANON] ?: return@map null)
    }
    val instanceNameFlow: Flow<String?> = context.sessionData.data.map { it[Keys.NAME] }
    val sessionFlow: Flow<StoredSession?> = context.sessionData.data.map { preferences ->
        preferences[Keys.SEALED]?.let { sealed ->
            // A restored/invalidated key cannot decrypt a session: require sign-in, not plaintext fallback.
            runCatching { HarmonyJson.decodeFromString(StoredSession.serializer(), sealer.unseal(sealed)) }.getOrNull()
        }
    }
    suspend fun endpoint(): Endpoint? = endpointFlow.first()
    suspend fun session(): StoredSession? = sessionFlow.first()
    suspend fun instanceName(): String? = instanceNameFlow.first()

    suspend fun saveInstance(endpoint: Endpoint, name: String? = null) {
        context.sessionData.edit {
            if (it[Keys.URL] != endpoint.baseUrl.trimEnd('/')) it.remove(Keys.SEALED)
            it[Keys.URL] = endpoint.baseUrl.trimEnd('/')
            it[Keys.ANON] = endpoint.anonKey
            if (name != null) it[Keys.NAME] = name else it.remove(Keys.NAME)
        }
    }
    suspend fun saveSession(session: StoredSession) {
        val sealed = sealer.seal(HarmonyJson.encodeToString(StoredSession.serializer(), session))
        context.sessionData.edit {
            it[Keys.URL] = session.endpoint.baseUrl.trimEnd('/')
            it[Keys.ANON] = session.endpoint.anonKey
            it[Keys.SEALED] = sealed
        }
    }
    suspend fun clearSession() { context.sessionData.edit { it.remove(Keys.SEALED) } }

    private suspend fun accountKey(suffix: String): Preferences.Key<String> {
        val session = session() ?: error("Sign in before changing device preferences.")
        val scope = session.endpoint.baseUrl + "|" + session.userId
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(scope.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return stringPreferencesKey("$hash.$suffix")
    }
    val appearanceScopeFlow: Flow<String?> = context.sessionData.data.map { it[accountKey("appearance-sync")] }
    suspend fun setAppearanceScope(deviceOnly: Boolean) {
        val key = accountKey("appearance-sync")
        context.sessionData.edit { if (deviceOnly) it[key] = "device" else it.remove(key) }
    }
    suspend fun appearance(profileId: String): JsonElement? {
        val key = accountKey("appearance.$profileId")
        val text = context.sessionData.data.first()[key] ?: return null
        return runCatching { HarmonyJson.parseToJsonElement(text) }.getOrNull()
    }
    suspend fun saveAppearance(profileId: String, json: JsonElement) {
        val key = accountKey("appearance.$profileId")
        context.sessionData.edit { it[key] = json.toString() }
    }
    suspend fun draft(room: String): String {
        val key = accountKey("draft.$room")
        return context.sessionData.data.first()[key].orEmpty()
    }
    suspend fun saveDraft(room: String, text: String) {
        val key = accountKey("draft.$room")
        context.sessionData.edit { if (text.isEmpty()) it.remove(key) else it[key] = text }
    }
}
