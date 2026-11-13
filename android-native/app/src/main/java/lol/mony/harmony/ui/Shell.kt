package lol.mony.harmony.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import lol.mony.harmony.ui.components.ServerRail
import lol.mony.harmony.ui.theme.HarmonyColors
import lol.mony.harmony.ui.theme.LocalHarmonyColors

/** The four top-level places, as Android's bottom bar names them. */
enum class TopDestination(val label: String, val icon: ImageVector) {
    Chats("Chats", Icons.Outlined.ChatBubbleOutline),
    Social("Social", Icons.Outlined.Public),
    Notifications("Alerts", Icons.Outlined.Notifications),
    You("You", Icons.Outlined.Settings),
}

/**
 * The signed-in app: the server rail and Android's own navigation.
 *
 * Phones get the bottom navigation bar and one column at a time; wide windows (tablets,
 * foldables, landscape) get a navigation rail and the rail beside the content, which is how
 * Android's adaptive layouts behave. The rail is always on the left, as in the web client.
 */
@Composable
fun Shell(
    state: AppState,
    destination: TopDestination,
    onDestination: (TopDestination) -> Unit,
    content: @Composable () -> Unit,
) {
    val windowSize = LocalWindowInfo.current.containerSize
    val widthDp = with(LocalDensity.current) { windowSize.width.toDp().value }
    val wide = widthDp >= 840
    val harmony: HarmonyColors = LocalHarmonyColors.current
    val unread by state.unread.collectAsState()
    val mentions = unread.mentions.values.sum()

    if (wide) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail(
                containerColor = harmony.surfaceSidebar,
                modifier = Modifier.fillMaxHeight().width(88.dp),
            ) {
                TopDestination.entries.forEach { item ->
                    NavigationRailItem(
                        selected = destination == item,
                        onClick = { onDestination(item) },
                        icon = { TopIcon(item, if (item == TopDestination.Notifications) mentions else 0) },
                        label = { Text(item.label) },
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) { content() }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { content() }
            NavigationBar(containerColor = harmony.surfaceSidebar) {
                TopDestination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { onDestination(item) },
                        icon = { TopIcon(item, if (item == TopDestination.Notifications) mentions else 0) },
                        label = { Text(item.label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TopIcon(item: TopDestination, badge: Int) {
    if (badge > 0) {
        BadgedBox(badge = { Badge { Text(if (badge > 99) "99+" else "$badge") } }) {
            Icon(item.icon, contentDescription = item.label)
        }
    } else {
        Icon(item.icon, contentDescription = item.label)
    }
}
