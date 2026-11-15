package lol.mony.harmony

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import lol.mony.harmony.ui.Shell
import lol.mony.harmony.ui.ShellNavigation
import lol.mony.harmony.ui.TopDestination
import lol.mony.harmony.ui.theme.HarmonyTheme
import lol.mony.harmony.ui.theme.VisualThemeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The rail stays inside the system bars, and choosing from it puts it away.
 *
 * Both are reports from a phone. The app draws edge to edge, so the rail began at y=0 and its
 * first destination sat under the status bar and the camera cutout; and a rail that never
 * closed left the thing it had just selected squeezed into the remaining width.
 *
 * The insets are passed in rather than read from the window, so the arithmetic can be asserted
 * exactly: a synthetic status bar of 40dp and a cutout of 30dp on the start side, on a
 * 320x640dp screen. The production default is the real safe drawing area.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w320dp-h640dp-xhdpi")
class ShellRailTest {
    @get:Rule val compose = createComposeRule()

    /** A status bar and a start-side cutout, the shape a notched phone reports. */
    private val notched = WindowInsets(
        left = 30.dp, top = 40.dp, right = 0.dp, bottom = 24.dp,
    )

    private fun shell(
        railOpen: Boolean,
        onOpenChange: (Boolean) -> Unit = {},
        onDestination: (TopDestination) -> Unit = {},
        content: @androidx.compose.runtime.Composable () -> Unit = { Text("content") },
    ) = @androidx.compose.runtime.Composable {
        HarmonyTheme(VisualThemeSettings()) {
            Shell(
                state = RenderHarness.fresh(),
                destination = TopDestination.Chats,
                onDestination = onDestination,
                railOpen = railOpen,
                onRailOpenChange = onOpenChange,
                safeArea = notched,
            ) { content() }
        }
    }

    /**
     * Every destination is below the status bar and the cutout, and inside the screen.
     *
     * This is the reported bug stated as a measurement: with the insets ignored, `Chats` starts
     * at y=0 and its centre is under the clock. The assertion is on the node's real bounds, not
     * on the presence of an inset modifier, so removing the padding fails it.
     */
    @Test fun theFirstDestinationIsBelowTheStatusBarAndCutout() {
        compose.setContent(shell(railOpen = true))
        compose.waitForIdle()
        for (label in listOf("Chats", "Social", "Files", "Alerts", "You")) {
            val node = compose.onNodeWithTag("nav-" + label).fetchSemanticsNode()
            val top = node.boundsInRoot.top
            val left = node.boundsInRoot.left
            assertTrue(
                "$label starts at y=$top, under a 40dp status bar (" + with(compose.density) { 40.dp.toPx() } + "px)",
                top >= with(compose.density) { 40.dp.toPx() },
            )
            assertTrue(
                "$label starts at x=$left, under a 30dp cutout",
                left >= with(compose.density) { 30.dp.toPx() },
            )
            // And it is on the screen, not pushed off the bottom by the padding.
            assertTrue(
                "$label ends at y=${node.boundsInRoot.bottom}, past the 640dp screen",
                node.boundsInRoot.bottom <= with(compose.density) { 640.dp.toPx() },
            )
        }
    }

    /**
     * The inset narrows what the rail's controls may use, not what its background covers.
     *
     * The reported symptom was controls under the system bars; the fix must not trade that for
     * controls clipped by their own container. So the assertion is relational: every
     * destination sits inside the rail's own bounds, and the rail is inside the screen.
     */
    @Test fun theRailStillCoversItsControls() {
        compose.setContent(shell(railOpen = true))
        compose.waitForIdle()
        val rail = compose.onNodeWithTag("rail").fetchSemanticsNode()
        assertTrue("the rail is inside the screen",
            rail.boundsInRoot.right <= with(compose.density) { 320.dp.toPx() } + 1f)
        for (label in listOf("Chats", "Social", "Files", "Alerts", "You")) {
            val node = compose.onNodeWithTag("nav-" + label).fetchSemanticsNode()
            assertTrue(
                "$label (${node.boundsInRoot}) is not inside the rail (${rail.boundsInRoot})",
                node.boundsInRoot.left >= rail.boundsInRoot.left - 1f &&
                    node.boundsInRoot.right <= rail.boundsInRoot.right + 1f,
            )
        }
    }

    /**
     * Choosing a destination collapses the rail, and the menu button brings it back.
     *
     * The open/closed state belongs to the caller, so this drives the same two states the
     * caller sets: a tap reports the destination and the shell asks to close, then the closed
     * shell offers only the reopen handle. A Compose rule takes one `setContent`, so both
     * states are drawn by one composition whose state the test changes.
     */
    @Test fun choosingADestinationCollapsesTheRail() {
        var open by mutableStateOf(true)
        var chosen: TopDestination? = null
        // The state is read inside the composition, the way SignedInShell reads it: passing
        // `open` to the helper would capture its first value and never recompose.
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Shell(
                    state = RenderHarness.fresh(),
                    destination = TopDestination.Chats,
                    // The caller does what SignedInShell does: take the destination, close.
                    onDestination = { chosen = it; open = false },
                    railOpen = open,
                    onRailOpenChange = { open = it },
                    safeArea = notched,
                ) { Text("the destination") }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("nav-Social").assertIsDisplayed()
        compose.onNodeWithTag("nav-Social").performClick()
        compose.waitForIdle()
        assertEquals("the tap reaches the caller", TopDestination.Social, chosen)
        assertEquals("and the shell asks to close", false, open)
        // Closed: the destinations go away and the way back is offered. The rail slides out,
        // so the node exists until the animation ends; waiting for its absence is what a
        // person sees, and asserting at once would only measure the first frame.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("nav-Chats").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("rail-open").assertIsDisplayed()
        // And the handle reopens it.
        compose.onNodeWithTag("rail-open").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("nav-Chats").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(true, open)
        compose.onNodeWithTag("nav-Chats").assertIsDisplayed()
    }

    /**
     * The rule itself, independent of any composition.
     *
     * `ShellRailTest`'s other tests drive a shell whose caller closes the rail, which would keep
     * passing if `SignedInShell` stopped closing it. This pins the rule the shell calls, so a
     * rail that stayed open after a tap fails here as well as on screen.
     */
    @Test fun choosingOrArrivingAlwaysClosesTheRail() {
        assertEquals("a chosen destination is shown, not covered", false, ShellNavigation.railOpenAfterChoosing())
        assertEquals("a notification's target is shown, not covered", false, ShellNavigation.railOpenAfterArriving())
    }

    /** The reopen handle is itself inside the status bar inset, so it is reachable too. */
    @Test fun theReopenHandleIsInsideTheInset() {
        compose.setContent(shell(railOpen = false))
        compose.waitForIdle()
        val handle = compose.onNodeWithTag("rail-open").fetchSemanticsNode()
        assertTrue(
            "the handle starts at y=${handle.boundsInRoot.top}, under the status bar",
            handle.boundsInRoot.top >= with(compose.density) { 40.dp.toPx() },
        )
        assertTrue(
            "the handle starts at x=${handle.boundsInRoot.left}, under the cutout",
            handle.boundsInRoot.left >= with(compose.density) { 30.dp.toPx() },
        )
    }
}
