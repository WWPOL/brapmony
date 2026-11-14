package lol.mony.harmony.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lol.mony.harmony.ui.components.ServerRail
import lol.mony.harmony.ui.theme.HarmonyColors
import lol.mony.harmony.ui.theme.LocalHarmonyColors

/** The destinations a signed-in person moves between. */
enum class TopDestination(val label: String, val icon: ImageVector) {
    Chats("Chats", Icons.Outlined.ChatBubbleOutline),
    Social("Social", Icons.Outlined.Public),
    Files("Files", Icons.Outlined.FolderOpen),
    Notifications("Alerts", Icons.Outlined.Notifications),
    You("You", Icons.Outlined.Settings),
}

/**
 * The signed-in app: one left rail carrying both the destinations and the servers.
 *
 * The destinations live at the top of the server rail rather than in a bottom navigation bar,
 * so the bottom of a phone screen stays free for the composer and the keyboard. The layout is
 * the same at every width; only the labels appear or hide. Nothing here reserves space at the
 * bottom, which is what made the app look like it had a dead strip when the keyboard opened.
 */
@Composable
fun Shell(
    state: AppState,
    destination: TopDestination,
    onDestination: (TopDestination) -> Unit,
    content: @Composable () -> Unit,
) {
    val harmony: HarmonyColors = LocalHarmonyColors.current
    val unread by state.unread.collectAsState()
    val mentions = unread.mentions.values.sum()
    val chats = unread.conversations.values.sum() + unread.channels.values.sum()

    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.width(76.dp).fillMaxHeight().background(harmony.surfaceSidebar).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DestinationRail(destination, onDestination, chats, mentions)
            Box(Modifier.fillMaxWidth(0.6f).height(1.dp).background(harmony.border))
            // The servers continue below the destinations, scrolling on their own.
            Box(Modifier.weight(1f)) { ServerRail(state) }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) { content() }
    }
}

@Composable
private fun DestinationRail(
    destination: TopDestination,
    onDestination: (TopDestination) -> Unit,
    chats: Int,
    mentions: Int,
) {
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        TopDestination.entries.forEach { item ->
            val badge = when (item) {
                TopDestination.Chats -> chats
                TopDestination.Notifications -> mentions
                else -> 0
            }
            val selected = destination == item
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("nav-" + item.label)
                    .semantics { contentDescription = item.label }
                    .clickable { onDestination(item) }
                    .padding(vertical = 4.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (badge > 0) {
                        BadgedBox(badge = { Badge { Text(if (badge > 99) "99+" else "$badge") } }) {
                            Icon(item.icon, contentDescription = null, Modifier.size(22.dp),
                                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        Icon(item.icon, contentDescription = null, Modifier.size(22.dp),
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    item.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
