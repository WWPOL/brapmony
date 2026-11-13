package lol.mony.harmony

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.runBlocking
import lol.mony.harmony.RenderHarness.capture
import lol.mony.harmony.RenderHarness.differing
import lol.mony.harmony.RenderHarness.fresh
import lol.mony.harmony.RenderHarness.shot
import lol.mony.harmony.RenderHarness.signedIn
import lol.mony.harmony.RenderHarness.waitUntilComposerEmpty
import lol.mony.harmony.data.api.AuthApi
import lol.mony.harmony.data.session.AuthState
import lol.mony.harmony.ui.SignedInShell
import lol.mony.harmony.ui.screens.chat.ChatWorkspace
import lol.mony.harmony.ui.screens.signin.AuthFormScreen
import lol.mony.harmony.ui.theme.VisualThemeSettings
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Drives the native screens the way a person does: tap a destination, open a conversation, type,
 * send, and check what the instance stored.
 *
 * These are interaction tests, not screenshots. Each step uses the same semantics a TalkBack user
 * and a touch target reach, and the sending journeys end by reading the row back over the API, so
 * a pass means the round trip happened. What they still cannot cover - the software keyboard,
 * system back gestures, process death, real push - is named in the parity audit.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w320dp-h640dp-xhdpi")
class NativeJourneyTest {
    @get:Rule val compose = createComposeRule()

    private fun settle(marker: String) = compose.waitUntil(180_000) {
        compose.onAllNodesWithText(marker, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    /** The bottom bar switches destinations, and each one loads its own data. */
    @Test fun bottomNavigationReachesEveryDestination() {
        val state = signedIn()
        compose.shot("journey-chats", state, settle = "Direct message") {
            SignedInShell(state, VisualThemeSettings(), { _, _ -> }, {})
        }
        for ((tag, marker) in listOf(
            "nav-Social" to "Your following feed is quiet",
            "nav-Alerts" to "Notifications",
            "nav-You" to "Your profile",
            "nav-Chats" to "Direct message",
        )) {
            compose.onNodeWithTag(tag).performClick()
            settle(marker)
            compose.waitForIdle()
            compose.capture("journey-" + tag.removePrefix("nav-").lowercase())
        }
    }

    /** Open the fixture direct message from the list, read it, and send one back. */
    @Test fun directMessageRoundTrip() {
        val state = signedIn()
        val session = state.auth.state.value as AuthState.SignedIn
        compose.shot("journey-dm-list", state, settle = "Bob E2E") { ChatWorkspace(state, VisualThemeSettings()) }
        compose.onNodeWithText("Bob E2E").performClick()
        settle("dm message from alice")
        compose.capture("journey-dm-room")

        val text = "native journey " + System.currentTimeMillis()
        compose.onNodeWithTag("message-composer").performTextInput(text)
        compose.onNodeWithTag("message-send").performClick()
        compose.waitUntilComposerEmpty()
        settle(text)
        compose.capture("journey-dm-sent")

        // The message is in the instance, not only in the composable's state.
        val token = requireNotNull(runBlocking { state.auth.freshToken() })
        val conversationId = requireNotNull(state.openConversationId.value)
        val stored = runBlocking { state.chats.directMessages(session.endpoint, token, conversationId).getOrThrow() }
        assertTrue("the sent message is stored on the instance", stored.any { it.plainText == text })
    }

    /** Pick the server in the rail, open its channel, and send a message into it. */
    @Test fun channelRoundTrip() {
        val state = signedIn()
        val session = state.auth.state.value as AuthState.SignedIn
        compose.shot("journey-rail", state, settle = "Direct message") { ChatWorkspace(state, VisualThemeSettings()) }
        compose.onNodeWithTag("rail-server-E2E Server").performClick()
        compose.waitUntil(180_000) { compose.onAllNodesWithTag("channel-list").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.capture("journey-channels")

        compose.onNodeWithTag("channel-general").performClick()
        settle("channel message from alice")
        compose.capture("journey-channel-room")

        val text = "native channel journey " + System.currentTimeMillis()
        compose.onNodeWithTag("message-composer").performTextInput(text)
        compose.onNodeWithTag("message-send").performClick()
        compose.waitUntilComposerEmpty()
        settle(text)
        compose.capture("journey-channel-sent")

        val token = requireNotNull(runBlocking { state.auth.freshToken() })
        val channelId = requireNotNull(state.openChannelId.value)
        val stored = runBlocking { state.chats.channelMessages(session.endpoint, token, channelId).getOrThrow() }
        assertTrue("the sent message is stored in the channel", stored.any { it.plainText == text })
    }

    /** A refused sign-in keeps the person on the form and shows the instance's own sentence. */
    @Test fun refusedSignInStaysOnTheForm() {
        val state = fresh()
        val email = System.getenv("HARMONY_RENDER_EMAIL") ?: "fx_alice@e2e.harmony.local"
        compose.shot("journey-signin", state, settle = "Welcome back") {
            AuthFormScreen(
                instanceName = "Local Harmony", instanceDomain = RenderHarness.instanceUrl,
                onSignIn = { _, _ -> AuthApi.Result.Failed("Invalid login credentials") },
                onRegister = { _, _, _ -> AuthApi.Result.Failed("Registration is closed") },
                onForgotPassword = { AuthApi.Result.Ok(Unit) },
                onChangeInstance = {},
            )
        }
        compose.onNodeWithTag("signin-email").performTextInput(email)
        compose.onNodeWithTag("signin-password").performTextInput("definitely-not-the-password")
        compose.onNodeWithTag("signin-submit").performClick()
        settle("Invalid login credentials")
        compose.onNodeWithTag("signin-error").assertIsDisplayed()
        compose.capture("journey-signin-error")
    }

    /**
     * The appearance panel's own preview repaints when a theme is chosen.
     *
     * This is the account look's real feedback loop: the chips drive a nested HarmonyTheme, so a
     * change that did not reach the theme would leave the preview's pixels identical.
     */
    @Test fun appearancePreviewRepaints() {
        val state = signedIn()
        compose.shot("journey-appearance", state, settle = "Your profile") {
            lol.mony.harmony.ui.screens.you.YouScreen(state, VisualThemeSettings(), { _, _ -> }, {})
        }
        compose.onNodeWithText("Appearance").performClick()
        settle("Your look")
        val dark = compose.capture("journey-appearance-dark")
        compose.onNodeWithText("Light").performClick()
        compose.waitForIdle()
        val light = compose.capture("journey-appearance-light")
        val changed = differing(dark, light)
        assertTrue("choosing Light repainted the preview (%d of %d pixels changed)"
            .format(changed, dark.width * dark.height), changed > dark.width * dark.height / 50)
    }
}
