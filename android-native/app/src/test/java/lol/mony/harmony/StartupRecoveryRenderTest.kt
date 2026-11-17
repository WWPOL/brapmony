package lol.mony.harmony

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import lol.mony.harmony.data.session.SessionStore
import lol.mony.harmony.ui.AppState
import lol.mony.harmony.ui.HarmonyRoot
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The screen the user actually gets when the stored preferences cannot be decoded.
 *
 * `SessionStoreRecoveryTest` pins the store's own behaviour. This drives the real [HarmonyRoot]
 * -- the same composable the activity shows -- with a truncated preferences file in place, and
 * asserts the *reported* failure does not appear. That is the level the bug was reported at: a
 * person launches the app and reads a screen, not a `CorruptionException`.
 *
 * The pre-fix behaviour, for contrast, is what the device run recorded in
 * `docs/build/evidence/android-native-ux/20-brick.txt`: "Could not load this" / "Value not set."
 * / "Try again", where Try again re-read the same file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class StartupRecoveryRenderTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun corrupt() {
        val file = File(context.filesDir, "datastore/brapmony-session.preferences_pb")
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(0x0A, 0x40, 0x0A, 0x02, 0x08, 0x01))
    }

    @Test fun aCorruptPreferencesFileDoesNotStrandTheUserOnAnErrorScreen() {
        corrupt()
        val state = AppState(AppContainer(context, SessionStore(context, TestSealer())))

        compose.setContent { HarmonyRoot(state) }
        // Let the restore path run against the corrupt file and settle on a screen.
        compose.waitForIdle()
        Thread.sleep(2_000)
        compose.waitForIdle()

        // The reported failure was a permanent error state. Neither its heading nor its retry
        // button may be what the user is left with.
        compose.onAllNodesWithText("Could not load this", substring = true).fetchSemanticsNodes()
            .let { assertTrue("the error screen must not be the outcome; found ${it.size} nodes", it.isEmpty()) }
        compose.onAllNodesWithText("Try again", substring = true).fetchSemanticsNodes()
            .let { assertTrue("a retry button implies the brick; found ${it.size} nodes", it.isEmpty()) }
        compose.onAllNodesWithText("Unable to parse preferences proto", substring = true)
            .fetchSemanticsNodes()
            .let { assertTrue("the corruption must not reach the screen; found ${it.size} nodes", it.isEmpty()) }
    }

    @Test fun theCorruptStoreLeavesTheAppOnItsOwnStartupPath() {
        corrupt()
        val state = AppState(AppContainer(context, SessionStore(context, TestSealer())))

        compose.setContent { HarmonyRoot(state) }
        compose.waitForIdle()
        Thread.sleep(2_000)
        compose.waitForIdle()

        // A recovered store is a signed-out one, so the app is on a real startup screen -- the
        // instance picker or the sign-in form -- rather than an error with no way forward.
        val forward = listOf("Welcome back", "Sign in", "Instance", "Connect", "Brapmony")
        val seen = forward.sumOf { compose.onAllNodesWithText(it, substring = true).fetchSemanticsNodes().size }
        assertTrue("expected a startup screen to offer a way forward, found none of $forward", seen > 0)
    }

    private class TestSealer : lol.mony.harmony.data.session.SessionSealer {
        override fun seal(plaintext: String): String =
            java.util.Base64.getEncoder().encodeToString(plaintext.toByteArray(Charsets.UTF_8))
        override fun unseal(sealed: String): String =
            String(java.util.Base64.getDecoder().decode(sealed), Charsets.UTF_8)
    }
}
