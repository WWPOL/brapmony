package lol.mony.harmony.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import lol.mony.harmony.data.model.NotificationDestination
import lol.mony.harmony.ui.screens.chat.ChatWorkspace
import lol.mony.harmony.ui.screens.social.SocialHomeScreen
import lol.mony.harmony.ui.screens.notifications.NotificationsScreen
import lol.mony.harmony.ui.screens.you.YouScreen
import lol.mony.harmony.ui.theme.VisualThemeSettings

@Composable
fun SignedInShell(state: AppState, appearance: VisualThemeSettings,
                  onAppearanceChange: (VisualThemeSettings, Boolean) -> Unit, onSignOut: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(TopDestination.Chats.name) }
    val destination = TopDestination.valueOf(tab)
    // The rail is a drawer on a phone: choosing anything from it puts it away so the choice is
    // visible full width, and the menu button brings it back. The choice survives rotation; an
    // open rail does not, because reopening a drawer nobody asked for is the bug being fixed.
    var railOpen by rememberSaveable { mutableStateOf(true) }
    // One place decides what a destination tap does, so no path can leave the rail open by
    // forgetting to close it. `ShellNavigation` is where that rule is stated and tested.
    fun choose(next: TopDestination) {
        tab = next.name
        railOpen = ShellNavigation.railOpenAfterChoosing()
    }
    // A tapped notification (or a deep link) lands here as a destination. It is resolved once,
    // then cleared, so backing out of the room does not immediately reopen it.
    val pending by state.pendingDestination.collectAsState()
    LaunchedEffect(pending) {
        val wanted = pending ?: return@LaunchedEffect
        state.pendingDestination.value = null
        // A notification or deep link opens something specific; the rail would cover it.
        railOpen = ShellNavigation.railOpenAfterArriving()
        when (wanted) {
            is NotificationDestination.Conversation -> {
                tab = TopDestination.Chats.name
                state.showingDms.value = true
                state.openChannelId.value = null
                state.openConversationId.value = wanted.conversationId
                state.focusMessageId.value = wanted.messageId
            }
            is NotificationDestination.Channel -> {
                tab = TopDestination.Chats.name
                state.showingDms.value = false
                wanted.serverId?.let { state.selectedServerId.value = it }
                state.openConversationId.value = null
                state.openChannelId.value = wanted.channelId
                state.focusMessageId.value = wanted.messageId
            }
            is NotificationDestination.Thread -> {
                tab = TopDestination.Chats.name
                state.showingDms.value = false
                wanted.serverId?.let { state.selectedServerId.value = it }
                state.openConversationId.value = null
                wanted.channelId?.let { state.openChannelId.value = it }
                state.openThreadId.value = wanted.threadId
                state.focusMessageId.value = wanted.messageId
            }
            NotificationDestination.FollowRequests -> tab = TopDestination.Social.name
            NotificationDestination.Security -> tab = TopDestination.You.name
            is NotificationDestination.Post -> tab = TopDestination.Social.name
            is NotificationDestination.Profile -> tab = TopDestination.Chats.name
            NotificationDestination.Chats, is NotificationDestination.Unavailable ->
                tab = TopDestination.Chats.name
        }
    }
    Shell(
        state,
        destination,
        onDestination = { choose(it) },
        railOpen = railOpen,
        onRailOpenChange = { railOpen = it },
    ) {
        when (destination) {
            TopDestination.Chats -> ChatWorkspace(state, appearance)
            TopDestination.Social -> SocialHomeScreen(state)
            TopDestination.Files -> lol.mony.harmony.ui.screens.drive.DriveScreen(state, onBack = { tab = TopDestination.You.name })
            TopDestination.Notifications -> NotificationsScreen(state)
            TopDestination.You -> YouScreen(state, appearance, onAppearanceChange, onSignOut)
        }
    }
}


