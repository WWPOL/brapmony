package lol.mony.harmony

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.session.SessionSealer
import lol.mony.harmony.data.session.SessionStore
import lol.mony.harmony.data.session.StoredSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A preferences file that cannot be decoded must cost a sign-in, not the app.
 *
 * The bug this pins was found on an emulator, not by reasoning: the real debug APK launched to
 * a permanent "Could not load this / Unable to parse preferences proto." screen. The store's
 * `edit` writes a temp file and renames it, so a kill mid-write leaves a truncated
 * `brapmony-session.preferences_pb`; every subsequent read then threw `CorruptionException`,
 * which `HarmonyRoot` turns into an error state whose own **Try again** re-reads the same
 * broken file. Nothing in the app could clear it -- only a reinstall.
 *
 * The first test drives the production delegate, so it fails if the corruption handler is ever
 * removed from `SessionStore`. The second is the mutation check: it builds the same store
 * *without* a handler and requires the read to throw, so a passing first test cannot be an
 * artefact of DataStore quietly tolerating the file by itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SessionStoreRecoveryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** A truncated write: a valid header, then a length that promises bytes that are not there. */
    private fun writeTruncatedStore() {
        val file = File(context.filesDir, "datastore/brapmony-session.preferences_pb")
        file.parentFile?.mkdirs()
        // Protobuf field 1 (the preferences map), wire type 2, length 64, and 4 bytes of body.
        file.writeBytes(byteArrayOf(0x0A, 0x40, 0x0A, 0x02, 0x08, 0x01))
    }

    private fun sealer() = object : SessionSealer {
        override fun seal(plaintext: String): String = java.util.Base64.getEncoder()
            .encodeToString(plaintext.toByteArray(Charsets.UTF_8))
        override fun unseal(sealed: String): String =
            String(java.util.Base64.getDecoder().decode(sealed), Charsets.UTF_8)
    }

    @Test fun aTruncatedPreferencesFileReadsAsAnEmptyStoreInsteadOfThrowing() = runBlocking {
        writeTruncatedStore()
        val store = SessionStore(context, sealer())

        assertNull("a corrupt file has no instance to restore", store.endpoint())
        assertNull("a corrupt file has no session to restore", store.session())
        assertNull("a corrupt file has no instance name to restore", store.instanceName())
    }

    @Test fun theRecoveredStoreStillSavesAndRestoresASession() = runBlocking {
        writeTruncatedStore()
        val store = SessionStore(context, sealer())
        val endpoint = Endpoint("http://10.0.2.2:54480", "anon-key")
        val session = StoredSession(
            endpoint = endpoint,
            accessToken = "access",
            refreshToken = "refresh",
            expiresAtMillis = 1_900_000_000_000,
            userId = "user-1",
            email = "alice@e2e.harmony.local",
        )

        store.saveSession(session)

        // The write replaced the broken file, so this is a real round trip and not the handler
        // answering again: the instance and the sealed session both come back.
        assertEquals(endpoint.baseUrl, store.endpoint()?.baseUrl)
        assertEquals("access", store.session()?.accessToken)
        assertEquals("alice@e2e.harmony.local", store.session()?.email)
    }

    /**
     * The mutation check. Without `ReplaceFileCorruptionHandler`, the same file is fatal, which
     * is what the app did before this fix.
     */
    @Test fun withoutTheHandlerTheSameFileThrows() {
        writeTruncatedStore()
        val file = File(context.filesDir, "datastore/brapmony-session.preferences_pb")
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { file },
        )
        try {
            runBlocking { store.data.collect { } }
            fail("a truncated preferences file must throw without a corruption handler")
        } catch (expected: Exception) {
            // CorruptionException, or the read error it is wrapped in; either way it is fatal
            // to the caller, which is the point.
            assertTrue(
                "expected a corruption failure, got ${expected::class.java.name}",
                generateSequence<Throwable>(expected) { it.cause }
                    .any { it::class.java.name.contains("Corruption", ignoreCase = true) },
            )
        }
    }

    /** The handler the production store installs, so its effect is stated in one place. */
    @Test fun theHandlerTheAppInstallsReplacesRatherThanThrows() {
        val handler = ReplaceFileCorruptionHandler { emptyPreferences() }
        writeTruncatedStore()
        val file = File(context.filesDir, "datastore/brapmony-session.preferences_pb")
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            corruptionHandler = handler,
            produceFile = { file },
        )
        val preferences = runBlocking { store.data.first() }
        assertTrue("the handler answers with an empty store", preferences.asMap().isEmpty())
    }
}
