package lol.mony.harmony

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.runBlocking
import lol.mony.harmony.RenderHarness.capture
import lol.mony.harmony.RenderHarness.signedIn
import lol.mony.harmony.data.session.AuthState
import lol.mony.harmony.ui.AppState
import lol.mony.harmony.ui.screens.chat.RoomScreen
import lol.mony.harmony.ui.theme.VisualThemeSettings
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Opening a thread from the room's threads sheet.
 *
 * This is the regression for a crash reported from a real phone: `RoomScreen` guarded the sheet
 * with `if (threads != null)` and then read `threads!!` *inside* the sheet's `LazyColumn`. The
 * row's own click sets `threads = null` (and `thread = row`), so the list's next recomposition
 * dereferenced null and the app died with a `NullPointerException` from the lazy-list content.
 *
 * The test drives the same route a thumb does -- the Threads button, the row, then back out -- and
 * requires the thread's own messages to be on screen afterwards, so a sheet that merely stopped
 * crashing without opening the thread would not pass either.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w320dp-h640dp-xhdpi")
class ThreadSheetJourneyTest {
    @get:Rule val compose = createComposeRule()

    private fun waitFor(tag: String? = null, text: String? = null) = compose.waitUntil(180_000) {
        val byTag = tag == null || compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        val byText = text == null || compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        byTag && byText
    }

    /**
     * Waits until the channel's message list has rows.
     *
     * The header draws before the history does, so tapping a row after only "Text channel"
     * appeared raced the repository's answer and failed on an empty tree.
     */
    private fun waitForActions() = compose.waitUntil(180_000) {
        compose.onAllNodesWithContentDescription("Message actions").fetchSemanticsNodes().isNotEmpty()
    }

    /** The seeded channel, with one fresh message that carries a thread of its own. */
    private class Fixture(val state: AppState, val channelId: String, val threadName: String)

    private fun fixture(): Fixture = runBlocking {
        val state = signedIn()
        val session = state.auth.state.value as AuthState.SignedIn
        val token = requireNotNull(state.auth.freshToken())
        val profile = requireNotNull(session.profile)
        val server = state.servers.servers(session.endpoint, token, profile.id).getOrThrow().first().server
        val channel = state.servers.tree(session.endpoint, token, server).getOrThrow().channels.first { it.type == 0 }
        val marker = "thread parent " + System.nanoTime()
        val parent = state.chats.sendChannelMessage(session.endpoint, token, channel.id, marker).getOrThrow()
        val name = "Native thread " + System.nanoTime()
        val thread = state.chats.createThread(session.endpoint, token, channel.id, parent.id, name).getOrThrow()
        state.chats.sendChannelMessage(session.endpoint, token, channel.id, "thread reply body", threadId = thread.id).getOrThrow()
        Fixture(state, channel.id, name)
    }

    /**
     * The reported crash: open Threads, tap the thread, and let the sheet's list recompose after
     * the row cleared it.
     */
    @Test fun openingAThreadFromTheSheetShowsTheThreadInsteadOfCrashing() {
        val fixture = fixture()
        compose.setContent {
            RoomScreen(fixture.state, fixture.channelId, direct = false,
                appearance = VisualThemeSettings(), onBack = {})
        }
        waitFor(text = "Text channel")

        compose.onNodeWithContentDescription("Threads").performClick()
        waitFor(tag = "thread-list", text = fixture.threadName)
        compose.capture("thread-sheet-open")

        compose.onNodeWithText(fixture.threadName).performClick()
        // Before the fix this recomposition threw NullPointerException from the LazyColumn.
        waitFor(text = "Thread in")
        compose.capture("thread-opened")

        compose.onNodeWithText("thread reply body").assertExists()
        assertTrue("the room now shows the thread's own header",
            compose.onAllNodesWithText("Thread in", substring = true).fetchSemanticsNodes().isNotEmpty())
    }
    /**
     * The pinned sheet had the same shape (`pins!!` inside its list), and it is the other sheet
     * this screen pins a nullable to. Opening it, closing it and opening it again must be safe.
     */
    @Test fun thePinnedSheetSurvivesItsOwnDismissal() {
        val fixture = fixture()
        compose.setContent {
            RoomScreen(fixture.state, fixture.channelId, direct = false,
                appearance = VisualThemeSettings(), onBack = {})
        }
        waitFor(text = "Text channel")

        compose.onNodeWithContentDescription("Pinned messages").performClick()
        waitFor(tag = "pinned-list")
        compose.capture("pinned-sheet")
        compose.onAllNodes(androidx.compose.ui.test.isRoot())[1]
            .performTouchInput { click(androidx.compose.ui.geometry.Offset(20f, 20f)) }
        compose.waitUntil(180_000) { compose.onAllNodesWithTag("pinned-list").fetchSemanticsNodes().isEmpty() }
        waitFor(text = "Text channel")

        compose.onNodeWithContentDescription("Pinned messages").performClick()
        waitFor(tag = "pinned-list")
        compose.capture("pinned-sheet-again")
    }

    /**
     * The message-actions sheet's own buttons close it while its deferred content is still
     * composed, which is the same nullable-read shape.
     */
    @Test fun theActionsSheetClosesCleanly() {
        val fixture = fixture()
        compose.setContent {
            RoomScreen(fixture.state, fixture.channelId, direct = false,
                appearance = VisualThemeSettings(), onBack = {})
        }
        waitFor(text = "Text channel")

        waitForActions()
        compose.onAllNodesWithContentDescription("Message actions")[0].performClick()
        waitFor(text = "Copy text")
        compose.capture("actions-sheet")
        compose.onNodeWithText("Reply").performClick()
        waitFor(text = "Replying to")
        compose.capture("actions-sheet-reply")
    }


    /** Dismissing the sheet without choosing leaves the channel as it was, and reopening works. */
    @Test fun dismissingAndReopeningTheSheetIsSafe() {
        val fixture = fixture()
        compose.setContent {
            RoomScreen(fixture.state, fixture.channelId, direct = false,
                appearance = VisualThemeSettings(), onBack = {})
        }
        waitFor(text = "Text channel")

        compose.onNodeWithContentDescription("Threads").performClick()
        waitFor(tag = "thread-list")
        compose.capture("thread-sheet-dismiss-before")

        // A tap on the scrim above the sheet is how a person dismisses it. The sheet is its
        // own window, so the touch goes to the second root rather than the room's.
        compose.onAllNodes(androidx.compose.ui.test.isRoot())[1]
            .performTouchInput { click(androidx.compose.ui.geometry.Offset(20f, 20f)) }
        compose.waitUntil(180_000) { compose.onAllNodesWithTag("thread-list").fetchSemanticsNodes().isEmpty() }
        waitFor(text = "Text channel")
        compose.capture("thread-sheet-dismissed")

        compose.onNodeWithContentDescription("Threads").performClick()
        waitFor(tag = "thread-list", text = fixture.threadName)
        compose.onNodeWithText(fixture.threadName).performClick()
        waitFor(text = "Thread in")
        compose.capture("thread-reopened")
    }
}
