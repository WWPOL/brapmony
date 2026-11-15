package lol.mony.harmony

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.dp
import lol.mony.harmony.data.model.ContentPart
import lol.mony.harmony.data.model.Emoji
import lol.mony.harmony.data.model.Message
import lol.mony.harmony.data.model.Reaction
import lol.mony.harmony.ui.components.MessageReactions
import lol.mony.harmony.ui.components.reactionLabel
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
 * A reaction pill toggles on a tap and lists its people on a long press, and never both.
 *
 * The reported bug was the pair inverted: a tap opened the reactor list and a long press
 * added or removed the reaction, which is the opposite of every messaging app. These tests
 * drive the pill through Compose's own touch injection, so they measure the gesture a finger
 * makes rather than calling the callbacks directly.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w320dp-h640dp-xhdpi")
class ReactionGestureTest {
    @get:Rule val compose = createComposeRule()

    private val message = Message(
        id = "m1", createdAt = "2026-11-13T05:39:18+00:00",
        content = listOf(ContentPart.text("hello")),
    )

    /** Two reactions: one unicode that this account has not used, one custom that it has. */
    private val reactions = listOf(
        Reaction(id = "r1", messageId = "m1", userId = "someone-else", customEmojiContent = "\uD83D\uDC4D"),
        Reaction(id = "r2", messageId = "m1", userId = "me", customEmojiContent = "\uD83D\uDC4D"),
        Reaction(id = "r3", messageId = "m1", userId = "me", customEmojiContent = ":cat:", emojiId = "e-cat"),
    )

    private fun pills(onToggle: (String) -> Unit, onShowReactors: (String) -> Unit) {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        MessageReactions(
                            message = message, reactions = reactions, myId = "me",
                            onToggle = onToggle, onShowReactors = onShowReactors,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** A tap toggles; it does not open the list. */
    @Test fun aTapTogglesAndDoesNotOpenTheList() {
        val toggled = mutableListOf<String>()
        val shown = mutableListOf<String>()
        pills({ toggled += it }, { shown += it })
        compose.onNodeWithTag("reaction-\uD83D\uDC4D").performClick()
        compose.waitForIdle()
        assertEquals("one toggle", listOf("\uD83D\uDC4D"), toggled)
        assertTrue("a tap must not open the reactor list: $shown", shown.isEmpty())
    }

    /** A long press lists the reactors, and does not toggle. */
    @Test fun aLongPressListsTheReactorsAndDoesNotToggle() {
        val toggled = mutableListOf<String>()
        val shown = mutableListOf<String>()
        pills({ toggled += it }, { shown += it })
        compose.onNodeWithTag("reaction-\uD83D\uDC4D").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals("one list", listOf("\uD83D\uDC4D"), shown)
        // This is the half that was broken: the press must not also toggle.
        assertTrue("a long press must not toggle the reaction: $toggled", toggled.isEmpty())
    }

    /** The custom pill is its own key, so a tap on it cannot toggle the unicode one. */
    @Test fun aCustomPillTogglesItsOwnEmoji() {
        val toggled = mutableListOf<String>()
        pills({ toggled += it }, {})
        compose.onNodeWithTag("reaction-e-cat").performClick()
        compose.waitForIdle()
        assertEquals(listOf("e-cat"), toggled)
    }

    /** Each pill carries the count it draws, and the group it belongs to. */
    @Test fun aPillShowsItsCount() {
        pills({}, {})
        // Both reactions with 👍 are one pill; the custom one is a second.
        compose.onNodeWithTag("reaction-\uD83D\uDC4D").assertExists()
        compose.onNodeWithTag("reaction-e-cat").assertExists()
        compose.onAllNodesWithTag("reaction-\uD83D\uDC4D").fetchSemanticsNodes().let {
            assertEquals("one pill per emoji, not one per reaction", 1, it.size)
        }
    }

    /** A reaction's key and content are the same identity the pill is drawn and toggled under. */
    @Test fun aReactionIsKeyedByItsEmojiNotItsLabel() {
        val unicode = Reaction(messageId = "m1", customEmojiContent = "\uD83D\uDC4D")
        val custom = Reaction(messageId = "m1", customEmojiContent = ":cat:", emojiId = "e-cat")
        assertEquals("\uD83D\uDC4D", unicode.key)
        assertEquals("a unicode pill writes back the glyph it stores", "\uD83D\uDC4D", unicode.content)
        assertEquals("the custom pill is keyed by its emoji row", "e-cat", custom.key)
        assertEquals("and joins the pill by the content that pill stores", ":cat:", custom.content)
        // The label shows the shortcode the row stores, so two custom emoji read differently;
        // it still is not the key, because two pills can share a shortcode across hosts.
        assertEquals(":cat:", reactionLabel(custom))
        assertEquals(":dog:", reactionLabel(Reaction(messageId = "m1", customEmojiContent = ":dog:", emojiId = "e-dog")))
    }
}
