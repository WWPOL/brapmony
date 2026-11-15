package lol.mony.harmony.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import lol.mony.harmony.data.model.Message
import lol.mony.harmony.data.model.Reaction

/**
 * The reaction pills under a message.
 *
 * A tap toggles this account's own reaction with that emoji, which is what a messaging app
 * does and what the web client's pill does: the same gesture that added it takes it away. A
 * long press opens the list of who reacted with it. The two must not both fire, so the long
 * press is consumed: `combinedClickable` reports `onClick` only for a press that was not held
 * past the long-press timeout, and the pill keeps its own haptic so the press is felt before
 * the sheet appears.
 *
 * The list is drawn with `FlowRow` rather than a `Row` so a message with twenty pills wraps
 * instead of running off the screen edge.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MessageReactions(
    message: Message,
    reactions: List<Reaction>,
    myId: String,
    onToggle: (String) -> Unit,
    onShowReactors: (String) -> Unit,
) {
    FlowRow(modifier = Modifier.testTag("reactions-" + message.id), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Grouped by the emoji's own identity: a custom emoji's name, else the glyph. Grouping
        // by the display label would merge a custom `:cat:` with someone typing the word.
        reactions.groupBy { reactionKey(it) }.forEach { (key, group) ->
            val mine = group.any { it.userId == myId }
            val haptics = LocalHapticFeedback.current
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (mine) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .combinedClickable(
                        // A tap is the toggle, and only a tap: `combinedClickable` does not
                        // call this after a long press, so the sheet is not dismissed by the
                        // finger coming up.
                        onClick = { onToggle(key) },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onShowReactors(key)
                        },
                    )
                    .testTag("reaction-" + key)
                    .semantics {
                        contentDescription = reactionSpoken(group, mine)
                        // The same two actions are reachable without a gesture, so a person
                        // using a screen reader is not required to hold a finger still.
                        customActions = listOf(
                            CustomAccessibilityAction(if (mine) "Remove your reaction" else "Add your reaction") {
                                onToggle(key); true
                            },
                            CustomAccessibilityAction("See who reacted") { onShowReactors(key); true },
                        )
                    },
            ) {
                Text(
                    reactionLabel(group.first()) + " " + group.size,
                    Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/**
 * What one reaction is keyed by: the custom emoji's row id when there is one, else the content
 * the row stores. Two people reacting with the same custom emoji are one pill; two people
 * typing the same unicode glyph are one pill too.
 */
private fun reactionKey(reaction: Reaction): String = reaction.key

/** What a pill says to a screen reader: the emoji, the count, and whether it is this account's. */
private fun reactionSpoken(group: List<Reaction>, mine: Boolean): String {
    val first = group.first()
    val emoji = if (first.emojiId != null) ":" + (first.customEmojiContent ?: first.emojiId) + ":" else first.emoji
    val count = group.size
    return emoji + ", " + (if (count == 1) "1 reaction" else "$count reactions") +
        (if (mine) ", including yours" else "")
}

/**
 * What a reaction pill shows for its emoji: the custom emoji's own shortcode, else the glyph.
 *
 * This used to read `:custom:` for every custom emoji, which made two different server emoji
 * indistinguishable on the row -- and it is what let the old tap handler remove the wrong
 * pill, because it compared this label. The stored content already carries the shortcode
 * (`:cat:`, `:cat@host:`, `discord:cat:id`), so that is what is drawn; the reference draws the
 * image, which needs the `emojis` row this client does not fetch for reactions yet.
 */
internal fun reactionLabel(reaction: Reaction): String =
    if (reaction.emojiId != null) reaction.content else reaction.emoji
