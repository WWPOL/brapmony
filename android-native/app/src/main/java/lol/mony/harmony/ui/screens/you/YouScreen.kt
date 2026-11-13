package lol.mony.harmony.ui.screens.you

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import lol.mony.harmony.data.model.AccountProfile
import lol.mony.harmony.data.repo.AccountRepository
import lol.mony.harmony.data.session.AuthState
import lol.mony.harmony.ui.AppState
import lol.mony.harmony.ui.components.Avatar
import lol.mony.harmony.ui.theme.VisualThemeSettings

/** Native account/settings area. The callback Boolean requests an additional account write. */
@Composable
fun YouScreen(
    state: AppState,
    appearance: VisualThemeSettings,
    onAppearanceChange: (VisualThemeSettings, Boolean) -> Unit,
    onSignOut: () -> Unit,
) {
    val auth by state.auth.state.collectAsState()
    val signedIn = auth as? AuthState.SignedIn
    val repository = remember(state.container.rest) { AccountRepository(state.container.rest) }
    var profile by remember(signedIn?.user?.id, signedIn?.endpoint?.baseUrl) { mutableStateOf<AccountProfile?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    var drive by rememberSaveable { mutableStateOf(false) }
    if (drive) {
        androidx.activity.compose.BackHandler { drive = false }
        lol.mony.harmony.ui.screens.drive.DriveScreen(state, onBack = { drive = false })
        return
    }
    val tabs = listOf("Profile", "Appearance", "Privacy", "Notifications", "Security")

    LaunchedEffect(signedIn?.user?.id, signedIn?.endpoint?.baseUrl, reload) {
        loading = true
        loadError = null
        try {
            val userId = signedIn?.user?.id ?: error("Sign in to manage your account.")
            profile = accountRequest(state) { endpoint, token -> repository.profile(endpoint, token, userId).getOrThrow() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { loadError = error.message ?: "Your account could not be loaded." }
        finally { loading = false }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(
                    name = profile?.displayName ?: profile?.username ?: signedIn?.profile?.shownName ?: "You",
                    url = profile?.avatarUrl?.let { url -> if (url.startsWith('/')) signedIn?.endpoint?.baseUrl + url else url },
                    size = 48.dp,
                )
                Column(Modifier.weight(1f)) {
                    Text(profile?.displayName ?: signedIn?.profile?.shownName ?: "You", style = MaterialTheme.typography.titleLarge)
                    Text("@${profile?.username ?: signedIn?.profile?.username.orEmpty()}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { drive = true }) { Text("Drive") }
                TextButton(onClick = { confirmSignOut = true }) { Text("Sign out") }
            }
            // LICENSE-ADDITIONAL-TERMS §1: visible, canonical attribution within one tap of the shell.
            TextButton(onClick = { uriHandler.openUri("https://github.com/y4my4my4m/harmony") }) {
                Text("Powered by Harmony", style = MaterialTheme.typography.labelMedium)
            }
        }
        ScrollableTabRow(selectedTabIndex = selected, edgePadding = 8.dp) {
            tabs.forEachIndexed { index, title ->
                Tab(selected = selected == index, onClick = { selected = index }, text = { Text(title) })
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (loading) CircularProgressIndicator()
            AccountStatus(loadError, null)
            if (loadError != null) TextButton(onClick = { reload++ }) { Text("Retry loading account") }
            val loaded = profile
            if (loaded != null) when (selected) {
                0 -> AccountProfilePanel(state, repository, loaded) { saved ->
                    profile = saved
                    signedIn?.profile?.let { old ->
                        state.rememberPeople(listOf(old.copy(
                            displayName = saved.displayName, bio = saved.bio, pronouns = saved.pronouns,
                            avatarUrl = saved.avatarUrl, bannerUrl = saved.bannerUrl,
                        )))
                    }
                }
                1 -> AccountAppearancePanel(state, repository, loaded, appearance, onAppearanceChange)
                2 -> AccountPrivacyPanel(state, repository, loaded) { profile = it }
                3 -> AccountNotificationsPanel(state, repository, loaded.id)
                4 -> AccountSecurityPanel(state)
            }
            HorizontalDivider()
            Text(
                "Brapmony is an independent native Android client based on Harmony. Account settings are shared with your instance; device appearance stays on this device.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (confirmSignOut) AlertDialog(
        onDismissRequest = { confirmSignOut = false }, title = { Text("Sign out of this device?") },
        text = { Text("Other signed-in devices will stay connected. Your saved account settings will not be removed.") },
        confirmButton = { TextButton(onClick = { confirmSignOut = false; onSignOut() }) { Text("Sign out") } },
        dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
    )
}
