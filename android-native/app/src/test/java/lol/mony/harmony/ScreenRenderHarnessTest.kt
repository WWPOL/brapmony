package lol.mony.harmony

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import lol.mony.harmony.data.api.AuthApi
import lol.mony.harmony.data.session.SessionSealer
import lol.mony.harmony.data.session.SessionStore
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.ui.AppState
import lol.mony.harmony.ui.ProvideAppState
import lol.mony.harmony.ui.screens.chat.ChatWorkspace
import lol.mony.harmony.ui.screens.drive.DriveScreen
import lol.mony.harmony.ui.screens.notifications.NotificationsScreen
import lol.mony.harmony.ui.screens.server.ServerManagementScreen
import lol.mony.harmony.ui.screens.social.SocialHomeScreen
import lol.mony.harmony.ui.screens.you.YouScreen
import lol.mony.harmony.ui.theme.HarmonyTheme
import lol.mony.harmony.ui.theme.VisualThemeSettings
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Headless screenshot harness for the real Composables, drawn by Robolectric's native graphics.
 *
 * This is supporting evidence only: it proves what a screen draws and which accessibility nodes
 * it exposes, not that touch, IME, lifecycle or device appearance work. Those still need the APK
 * on an emulator. It signs in to the disposable local Harmony stack with a throwaway fixture
 * account, so the pixels come from real data:
 *
 *   HARMONY_RENDER_EMAIL / HARMONY_RENDER_PASSWORD   fixture.users.alice
 *   HARMONY_RENDER_URL                               default http://localhost:54480
 *   HARMONY_RENDER_OUT                               PNG output directory
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w320dp-h640dp-xhdpi")
class ScreenRenderHarnessTest {
    @get:Rule val compose = createComposeRule()
    private val output = File(System.getenv("HARMONY_RENDER_OUT") ?: System.getProperty("java.io.tmpdir"))
    private lateinit var state: AppState

    private fun signIn(): AppState = runBlocking {
        val email = System.getenv("HARMONY_RENDER_EMAIL")
        val password = System.getenv("HARMONY_RENDER_PASSWORD")
        assumeTrue("Render harness needs HARMONY_RENDER_EMAIL/PASSWORD",
            !email.isNullOrBlank() && !password.isNullOrBlank())
        // Robolectric has no Android Keystore, so the container takes a JVM sealer.
        val container = AppContainer(ApplicationProvider.getApplicationContext<Context>(),
            SessionStore(ApplicationProvider.getApplicationContext(), TestSealer()))
        val app = AppState(container)
        val url = System.getenv("HARMONY_RENDER_URL") ?: "http://localhost:54480"
        app.auth.chooseInstance(Endpoint(url, "local-e2e"), "Local Harmony")
        val result = app.auth.signIn(requireNotNull(email), requireNotNull(password))
        check(result is AuthApi.Result.Ok) {
            "sign in failed: " + (result as AuthApi.Result.Failed).message
        }
        app.onSignedIn(app.auth.state.value as lol.mony.harmony.data.session.AuthState.SignedIn)
        app
    }

    /** The JVM has no Android Keystore; this keeps the production sealing contract in shape. */
    private class TestSealer : SessionSealer {
        private val key = javax.crypto.spec.SecretKeySpec(
            java.util.Base64.getDecoder().decode("MDEyMzQ1Njc4OWFiY2RlZg=="), "AES")
        override fun seal(plaintext: String): String {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
            return java.util.Base64.getEncoder().encodeToString(
                cipher.iv + cipher.doFinal(plaintext.toByteArray()))
        }
        override fun unseal(sealed: String): String {
            val bytes = java.util.Base64.getDecoder().decode(sealed)
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key,
                javax.crypto.spec.GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        }
    }

    /**
     * Draws one screen and saves its PNG.
     *
     * [settle] waits for text that only appears once the screen's own repositories have answered,
     * so a screenshot never records a loading spinner in place of the real state.
     */
    private fun shoot(name: String, theme: VisualThemeSettings = VisualThemeSettings(),
                      settle: String? = null, content: @Composable () -> Unit) {
        compose.setContent {
            HarmonyTheme(theme) {
                Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    ProvideAppState(state) { content() }
                }
            }
        }
        compose.waitForIdle()
        if (settle != null) {
            compose.waitUntil(timeoutMillis = 180_000) {
                compose.onAllNodesWithText(settle, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitForIdle()
        }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        output.mkdirs()
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        var nonBlack = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if (bitmap.getPixel(x, y) and 0x00FFFFFF != 0) nonBlack++
        }
        assertTrue("$name drew nothing", nonBlack > bitmap.width)
    }

    @Test fun chatsDestination() {
        state = signIn()
        shoot("chats", settle = "E2E Server", content = { ChatWorkspace(state, VisualThemeSettings()) })
    }

    @Test fun chatsInLightTheme() {
        state = signIn()
        shoot("chats-light", VisualThemeSettings(theme = "light"), settle = "E2E Server") { ChatWorkspace(state, VisualThemeSettings(theme = "light")) }
    }

    @Test fun socialDestination() {
        state = signIn()
        shoot("social", settle = "Your following feed is quiet", content = { SocialHomeScreen(state) })
    }

    @Test fun notificationsDestination() {
        state = signIn()
        shoot("notifications", settle = "loaded", content = { NotificationsScreen(state) })
    }

    @Test fun youDestination() {
        state = signIn()
        shoot("you", settle = "Your profile", content = { YouScreen(state, VisualThemeSettings(), { _, _ -> }, {}) })
    }

    @Test fun driveDestination() {
        state = signIn()
        shoot("drive", settle = "No files here", content = { DriveScreen(state, onBack = {}) })
    }

    @Test fun serverManagement() = runBlocking {
        state = signIn()
        val signedIn = state.auth.state.value as lol.mony.harmony.data.session.AuthState.SignedIn
        val token = requireNotNull(state.auth.freshToken())
        val servers = state.servers.servers(signedIn.endpoint, token, signedIn.user.id).getOrThrow()
        assumeTrue("fixture account has no server", servers.isNotEmpty())
        shoot("server-management", settle = "About this server") { ServerManagementScreen(state, servers.first().server.id) }
    }
}
