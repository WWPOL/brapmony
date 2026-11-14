package lol.mony.harmony.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
    Shell(state, destination, { tab = it.name }) {
        when (destination) {
            TopDestination.Chats -> ChatWorkspace(state, appearance)
            TopDestination.Social -> SocialHomeScreen(state)
            TopDestination.Files -> lol.mony.harmony.ui.screens.drive.DriveScreen(state, onBack = { tab = TopDestination.You.name })
            TopDestination.Notifications -> NotificationsScreen(state)
            TopDestination.You -> YouScreen(state, appearance, onAppearanceChange, onSignOut)
        }
    }
}
