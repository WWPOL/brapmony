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
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import lol.mony.harmony.data.api.AuthApi
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.session.AuthState
import lol.mony.harmony.data.session.SessionSealer
import lol.mony.harmony.data.session.SessionStore
import lol.mony.harmony.ui.AppState
import lol.mony.harmony.ui.ProvideAppState
import lol.mony.harmony.ui.theme.HarmonyTheme
import lol.mony.harmony.ui.theme.VisualThemeSettings
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import java.io.File

/**
 * Shared plumbing for the headless render tests: a signed-in [AppState] against the real local
 * Harmony stack, and a shot() that draws a composable and writes its PNG.
 *
 * These tests are **supporting evidence only**. They prove what a composable draws and which
 * accessibility nodes it exposes, given real rows from the instance. They cannot prove touch,
 * IME, lifecycle, system appearance, push or device performance: those need the APK on a device.
 * Every test that needs the stack skips itself (a JUnit assumption) when its environment is
 * absent, so an offline test run still passes honestly rather than pretending.
 */
internal object RenderHarness {
    /** Where PNGs go; the runner points this at an evidence directory. */
    val output: File get() = File(System.getenv("HARMONY_RENDER_OUT") ?: System.getProperty("java.io.tmpdir"))

    val instanceUrl: String get() = System.getenv("HARMONY_RENDER_URL") ?: "http://localhost:54480"

    /**
     * The JVM has no Android Keystore, so tests seal with a fixed key. Production code never
     * constructs this: SessionStore's default sealer is AndroidKeystoreSealer.
     */
    private class TestSealer : SessionSealer {
        private val key = javax.crypto.spec.SecretKeySpec(
            java.util.Base64.getDecoder().decode("MDEyMzQ1Njc4OWFiY2RlZg=="), "AES")
        override fun seal(plaintext: String): String {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
            return java.util.Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plaintext.toByteArray()))
        }
        override fun unseal(sealed: String): String {
            val bytes = java.util.Base64.getDecoder().decode(sealed)
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }
    }

    /** The container with a JVM sealer, so the real repositories run under Robolectric. */
    fun container(): AppContainer {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return AppContainer(context, SessionStore(context, TestSealer()))
    }

    /**
     * A container with no session, for the signed-out screens.
     *
     * It still talks to the real stack: the instance picker and the auth form make their own
     * requests, so this is the same app graph a fresh install builds.
     */
    fun fresh(): AppState = AppState(container())

    /**
     * Signs in to the local stack as the fixture account and completes the signed-in setup.
     *
     * The account is chosen by environment variable so a journey can sign in as a second person
     * (Bob, Mallory) without a second class.
     */
    fun signedIn(emailKey: String = "HARMONY_RENDER_EMAIL", passwordKey: String = "HARMONY_RENDER_PASSWORD"): AppState =
        runBlocking {
            val email = System.getenv(emailKey)?.takeIf { it.isNotBlank() }
            val password = System.getenv(passwordKey)?.takeIf { it.isNotBlank() }
            assumeTrue("render harness needs $emailKey/$passwordKey", email != null && password != null)
            val app = AppState(container())
            app.auth.chooseInstance(Endpoint(instanceUrl, "local-e2e"), "Local Harmony")
            when (val result = app.auth.signIn(email!!, password!!)) {
                is AuthApi.Result.Failed -> error("sign in failed: ${result.message}")
                is AuthApi.Result.Ok -> Unit
            }
            app.onSignedIn(app.auth.state.value as AuthState.SignedIn)
            app
        }

    /**
     * The look the account itself stores, as Harmony's `profiles.appearance_settings`.
     *
     * This is the value the app must honour: a person who set a theme on the web should
     * find it here, not the app's own default.
     */
    fun accountAppearance(state: AppState): VisualThemeSettings {
        val session = state.auth.state.value as AuthState.SignedIn
        val profile = session.profile ?: error("signed in without a profile")
        return VisualThemeSettings.fromJson(profile.appearanceSettings)
    }

    /**
     * Draws [content] inside the app's real theme and saves it as `<name>.png`.
     *
     * [settle] is text that only appears once the screen's own repositories have answered, so a
     * screenshot never records a spinner in place of the state a person would see.
     */
    fun ComposeContentTestRule.shot(
        name: String,
        state: AppState,
        theme: VisualThemeSettings = VisualThemeSettings(),
        settle: String? = null,
        content: @Composable () -> Unit,
    ): Bitmap {
        setContent {
            HarmonyTheme(theme) {
                Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    ProvideAppState(state) { content() }
                }
            }
        }
        waitForIdle()
        if (settle != null) {
            waitUntil(timeoutMillis = 180_000) {
                onAllNodesWithText(settle, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            waitForIdle()
        }
        return capture(name)
    }

    /**
     * Draws [content] under a theme the test can change afterwards, and captures it.
     *
     * A Compose rule may only call `setContent` once, so a screen that must be compared
     * under two looks has to repaint in place. That is also what the app does when the
     * account's look arrives after the first frame.
     */
    fun ComposeContentTestRule.shotThenRetheme(
        firstName: String,
        secondName: String,
        state: AppState,
        first: VisualThemeSettings,
        second: VisualThemeSettings,
        settle: String? = null,
        content: @Composable (VisualThemeSettings) -> Unit,
    ): Pair<Bitmap, Bitmap> {
        val theme = androidx.compose.runtime.mutableStateOf(first)
        setContent {
            HarmonyTheme(theme.value) {
                Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    ProvideAppState(state) { content(theme.value) }
                }
            }
        }
        waitForIdle()
        if (settle != null) {
            waitUntil(timeoutMillis = 180_000) {
                onAllNodesWithText(settle, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            waitForIdle()
        }
        val before = capture(firstName)
        theme.value = second
        waitForIdle()
        return before to capture(secondName)
    }

    /**
     *
     * Waiting on the message text alone is not enough: the text field already holds the draft, so
     * the condition would be met before the send and the capture would show the message unsent.
     */
    fun ComposeContentTestRule.waitUntilComposerEmpty(tag: String = "message-composer") {
        waitUntil(timeoutMillis = 180_000) {
            val nodes = onAllNodesWithTag(tag).fetchSemanticsNodes()
            nodes.isNotEmpty() && nodes.all {
                it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.EditableText) { androidx.compose.ui.text.AnnotatedString("") }.text.isEmpty()
            }
        }
    }

    /** Captures the whole tree to `<name>.png` and fails when it drew nothing. */
    fun ComposeContentTestRule.capture(name: String): Bitmap {
        val bitmap = onRoot().captureToImage().asAndroidBitmap()
        output.mkdirs()
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("$name drew nothing", nonBlack(bitmap) > bitmap.width)
        return bitmap
    }

    /** How many pixels two captures of the same size disagree on. */
    fun differing(a: Bitmap, b: Bitmap): Int {
        require(a.width == b.width && a.height == b.height) { "captures differ in size" }
        var count = 0
        for (y in 0 until a.height) for (x in 0 until a.width) {
            if (a.getPixel(x, y) != b.getPixel(x, y)) count++
        }
        return count
    }

    /** Pixels that are not fully transparent black, the cheapest proof that something drew. */
    fun nonBlack(bitmap: Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if (bitmap.getPixel(x, y) and 0x00FFFFFF != 0) count++
        }
        return count
    }
}
