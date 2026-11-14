package lol.mony.harmony

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.runBlocking
import lol.mony.harmony.RenderHarness.capture
import lol.mony.harmony.RenderHarness.signedIn
import lol.mony.harmony.data.model.ContentPart
import lol.mony.harmony.data.model.Message
import lol.mony.harmony.data.repo.ChatRepository
import lol.mony.harmony.data.session.AuthState
import lol.mony.harmony.ui.AppState
import lol.mony.harmony.ui.screens.chat.RoomRow
import lol.mony.harmony.ui.screens.chat.RoomScreen
import lol.mony.harmony.ui.screens.chat.RowState
import lol.mony.harmony.ui.theme.VisualThemeSettings
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What a send looks like while it is in flight and after it fails, and what a long draft does.
 *
 * These are the states a person actually sees and no screenshot of a healthy channel shows: a
 * greyed row that says Sending, a row that says it was not sent with a Retry beside it, and a
 * composer that has gone over the instance's own limit. They are drawn from the same RoomScreen
 * the app runs, with the cache primed the way a real send primes it, so a regression in the
 * optimistic path is visible rather than merely asserted.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w320dp-h640dp-xhdpi")
class OptimisticSendRenderTest {
    @get:Rule val compose = createComposeRule()

    private class Fixture(val state: AppState, val channelId: String)

    /** The seeded channel, with one row still sending and one that failed. */
    private fun fixture(): Fixture = runBlocking {
        val state = signedIn()
        val session = state.auth.state.value as AuthState.SignedIn
        val token = requireNotNull(state.auth.freshToken())
        val profile = requireNotNull(session.profile)
        val server = state.servers.servers(session.endpoint, token, profile.id).getOrThrow().first().server
        val channel = state.servers.tree(session.endpoint, token, server).getOrThrow().channels.first { it.type == 0 }
        val key = state.roomKey(channel.id, false, null)
        val now = java.time.Instant.now()
        fun row(id: String, text: String, offset: Long) = Message(
            id = id, channelId = channel.id, userId = profile.id,
            content = listOf(ContentPart.text(text)),
            createdAt = now.plusSeconds(offset).toString(),
        )
        state.rooms.appendPending(key, RoomRow("pending-1",
            row("pending-1", "This one is still going out", 1), RowState.Sending, "pending-1"))
        state.rooms.appendPending(key, RoomRow("failed-1",
            row("failed-1", "This one never left the phone", 2),
            RowState.Failed("You are offline."), "failed-1"))
        Fixture(state, channel.id)
    }

    private fun waitFor(tag: String) = compose.waitUntil(180_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    /**
     * A failed send stays in the list, says so, and offers Retry -- and the text it carried is
     * still readable, so nothing the person typed is lost by a failure.
     */
    @Test fun aFailedSendKeepsItsTextAndOffersRetry() {
        val fixture = fixture()
        compose.setContent {
            RoomScreen(fixture.state, fixture.channelId, direct = false,
                appearance = VisualThemeSettings(), onBack = {})
        }
        waitFor("send-failed")
        waitFor("sending-label")

        compose.capture("send-failed-row")
        assertTrue(compose.onAllNodesWithText("This one never left the phone", substring = true)
            .fetchSemanticsNodes().isNotEmpty())
        assertTrue("a failed row offers Retry",
            compose.onAllNodesWithTag("send-retry").fetchSemanticsNodes().isNotEmpty())
        assertTrue("a sending row says so",
            compose.onAllNodesWithTag("sending-label").fetchSemanticsNodes().isNotEmpty())
    }

    /**
     * Past the instance's limit the counter appears and Send refuses, rather than the message
     * being silently truncated as it is typed or the instance refusing it as a network error.
     */
    @Test fun aDraftPastTheInstanceLimitShowsACounterAndCannotBeSent() {
        val fixture = fixture()
        compose.setContent {
            RoomScreen(fixture.state, fixture.channelId, direct = false,
                appearance = VisualThemeSettings(), onBack = {})
        }
        waitFor("message-composer")
        // Two characters over the instance's default 2000, typed the way a paste arrives.
        compose.onNodeWithTag("message-composer")
            .performTextInput("x".repeat(ChatRepository.DEFAULT_MAX_LENGTH + 2))
        waitFor("message-length")

        compose.capture("composer-over-limit")
        compose.onNodeWithTag("message-length").assertExists()
        compose.onNodeWithTag("message-send").assertIsNotEnabled()
    }
}