package lol.mony.harmony.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Menu
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

/** The width of the rail when it is shown. */
private val RailWidth = 76.dp

/**
 * When the rail is shown, as one rule instead of a boolean scattered through the shell.
 *
 * The rule is the reported behaviour: picking a destination or a server puts the rail away so
 * the thing picked is visible, and arriving from a notification does the same. Nothing in this
 * app opens the rail except the person tapping its handle, so a screen that appears never
 * covers itself.
 */
object ShellNavigation {
    /** After a destination or server is chosen: the choice is what should be on screen. */
    fun railOpenAfterChoosing(): Boolean = false

    /** After a notification or deep link opens a specific thing. */
    fun railOpenAfterArriving(): Boolean = false
}

/**
 * The signed-in app: one left rail carrying both the destinations and the servers.
 *
 * The destinations live at the top of the server rail rather than in a bottom navigation bar,
 * so the bottom of a phone screen stays free for the composer and the keyboard.
 *
 * Two things about the rail are load-bearing, and both were reported from a phone:
 *
 * - **It is inside the system bars, not under them.** The app draws edge to edge, so a
 *   `fillMaxHeight` column starts at y=0, underneath the status bar and any camera cutout. The
 *   first destination, `Chats`, was therefore behind the clock and the cutout and could not be
 *   tapped. The rail pads itself by the safe drawing area -- status bar, cutout and navigation
 *   bar -- so every one of its controls is visible and reachable.
 * - **Choosing something puts it away.** A permanent 76dp rail is right on a tablet and wrong
 *   on a phone: the content it just selected is squeezed into what is left. A rail tap
 *   collapses the rail to a single menu button and shows the destination or server full width;
 *   the button brings it back.
 */
@Composable
fun Shell(
    state: AppState,
    destination: TopDestination,
    onDestination: (TopDestination) -> Unit,
    /** Whether the rail is currently shown; the shell owns this so a tap can collapse it. */
    railOpen: Boolean,
    onRailOpenChange: (Boolean) -> Unit,
    /**
     * The area the system bars and a display cutout occupy, already excluding the app's own
     * space. Defaults to the real safe drawing area; a test passes a synthetic one so the
     * layout can be asserted at a known size without a device that has a notch.
     */
    safeArea: WindowInsets = WindowInsets.safeDrawing,
    content: @Composable () -> Unit,
) {
    val harmony: HarmonyColors = LocalHarmonyColors.current
    val unread by state.unread.collectAsState()
    val mentions = unread.mentions.values.sum()
    val chats = unread.conversations.values.sum() + unread.channels.values.sum()

    // A back press with the rail open puts it away before it leaves the screen, which is what
    // Android's own drawers do.
    BackHandler(enabled = railOpen) { onRailOpenChange(false) }

    Row(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = railOpen,
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut(),
        ) {
            Column(
                Modifier
                    .width(RailWidth)
                    .fillMaxHeight()
                    .background(harmony.surfaceSidebar)
                    // The status bar, the cutout and the navigation bar: the rail's own controls
                    // must sit below the clock and inside the safe area on every device.
                    .windowInsetsPadding(
                        safeArea.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical),
                    )
                    .testTag("rail"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                DestinationRail(destination, onDestination, chats, mentions)
                Box(Modifier.fillMaxWidth(0.6f).height(1.dp).background(harmony.border))
                // The servers continue below the destinations, scrolling on their own.
                Box(Modifier.weight(1f)) {
                    ServerRail(state, onSelected = { onRailOpenChange(false) })
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            // The content is inset from the cutout on its own side; each screen draws its own
            // app bar and already pads for the status bar.
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        safeArea.only(WindowInsetsSides.End + WindowInsetsSides.Horizontal),
                    ),
            ) { content() }
            if (!railOpen) RailHandle(safeArea, onOpen = { onRailOpenChange(true) })
        }
    }
}

/**
 * The button that brings the rail back once it has collapsed.
 *
 * It sits in the top-start corner *inside* the status bar and cutout inset, so it is reachable
 * with a thumb and never under the clock; it is drawn over the content, which is why it carries
 * its own background.
 */
@Composable
private fun RailHandle(safeArea: WindowInsets, onOpen: () -> Unit) {
    Box(
        Modifier
            .padding(start = 6.dp, top = 6.dp)
            .windowInsetsPadding(safeArea.only(WindowInsetsSides.Start + WindowInsetsSides.Top))
            .size(40.dp)
            .background(LocalHarmonyColors.current.surfaceRaised, MaterialTheme.shapes.small)
            .clickable(onClick = onOpen)
            .testTag("rail-open")
            .semantics { contentDescription = "Show the navigation rail" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Menu, contentDescription = null, Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
