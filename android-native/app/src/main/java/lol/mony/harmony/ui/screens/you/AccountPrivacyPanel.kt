package lol.mony.harmony.ui.screens.you

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import lol.mony.harmony.data.model.AccountDmPolicy
import lol.mony.harmony.data.model.AccountProfile
import lol.mony.harmony.data.model.AccountRestrictedPerson
import lol.mony.harmony.data.repo.AccountRepository
import lol.mony.harmony.ui.AppState

@Composable
internal fun AccountPrivacyPanel(state: AppState, repository: AccountRepository, profile: AccountProfile, onSaved: (AccountProfile) -> Unit) {
    val original = remember(profile) { AccountRepository.privacy(profile) }
    var edited by remember(profile) { mutableStateOf(original) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var blocked by remember(profile.id) { mutableStateOf<List<AccountRestrictedPerson>?>(null) }
    var muted by remember(profile.id) { mutableStateOf<List<AccountRestrictedPerson>?>(null) }
    var blockError by remember { mutableStateOf<String?>(null) }
    var muteError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var removal by remember { mutableStateOf<Pair<AccountRestrictedPerson, Boolean>?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(profile.id, reload) {
        blockError = null; muteError = null
        try { blocked = accountRequest(state) { endpoint, token -> repository.blockedPeople(endpoint, token, profile.id).getOrThrow() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { blockError = failure.message ?: "Blocked users could not be loaded." }
        try { muted = accountRequest(state) { endpoint, token -> repository.mutedPeople(endpoint, token, profile.id).getOrThrow() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { muteError = failure.message ?: "Muted users could not be loaded." }
    }
    AccountSection("Who can reach you") {
        AccountToggle(
            "Approve new followers", "Require approval before a new person follows your federated profile.",
            edited.requireFollowApproval, !busy,
        ) { edited = edited.copy(requireFollowApproval = it) }
        Text("Who can start a direct message", style = MaterialTheme.typography.titleSmall)
        AccountDmPolicy.entries.forEach { policy ->
            Row(
                Modifier.fillMaxWidth().selectable(edited.dmPolicy == policy, enabled = !busy, role = Role.RadioButton) { edited = edited.copy(dmPolicy = policy) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RadioButton(edited.dmPolicy == policy, onClick = null, enabled = !busy)
                Column { Text(policy.label); Text(policy.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Text("Existing conversations remain available. Turning follower approval off also accepts pending follow requests.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AccountStatus(error, notice)
        AccountSaveActions(busy, edited != original, onSave = {
            scope.launch {
                busy = true; error = null; notice = null
                try {
                    val saved = accountRequest(state) { endpoint, token -> repository.savePrivacy(endpoint, token, profile.id, original, edited).getOrThrow() }
                    onSaved(saved); notice = "Privacy preferences saved."
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: "Privacy preferences were not saved." }
                finally { busy = false }
            }
        }, onReset = { edited = original; error = null; notice = null })
    }
    AccountSection("Blocked users", "Blocking prevents new interactions with these accounts.") {
        AccountStatus(blockError, null)
        when {
            blocked == null && blockError == null -> Text("Loading blocked users…")
            blocked?.isEmpty() == true && blockError == null -> Text("No blocked users.")
            else -> blocked.orEmpty().forEach { person -> RestrictedPersonRow(person, "Unblock", !busy) { removal = person to true } }
        }
        if (blockError != null) TextButton(onClick = { reload++ }) { Text("Retry blocked users") }
    }
    AccountSection("Muted users", "Muted accounts are hidden from your feed without notifying them.") {
        AccountStatus(muteError, null)
        when {
            muted == null && muteError == null -> Text("Loading muted users…")
            muted?.isEmpty() == true && muteError == null -> Text("No muted users.")
            else -> muted.orEmpty().forEach { person -> RestrictedPersonRow(person, "Unmute", !busy) { removal = person to false } }
        }
        if (muteError != null) TextButton(onClick = { reload++ }) { Text("Retry muted users") }
    }
    val pending = removal
    if (pending != null) AlertDialog(
        onDismissRequest = { removal = null },
        title = { Text("${if (pending.second) "Unblock" else "Unmute"} ${pending.first.label}?") },
        text = { Text(if (pending.second) "This account will be able to interact with you again." else "Posts from this account may appear in your feed again.") },
        confirmButton = { TextButton(onClick = {
            removal = null
            scope.launch {
                busy = true; error = null; notice = null
                try {
                    accountRequest(state) { endpoint, token ->
                        (if (pending.second) repository.unblock(endpoint, token, profile.id, pending.first.id)
                        else repository.unmute(endpoint, token, profile.id, pending.first.id)).getOrThrow()
                    }
                    notice = if (pending.second) "User unblocked." else "User unmuted."
                    reload++
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: "The user preference was not changed." }
                finally { busy = false }
            }
        }) { Text(if (pending.second) "Unblock" else "Unmute") } },
        dismissButton = { TextButton(onClick = { removal = null }) { Text("Cancel") } },
    )
}

@Composable
private fun RestrictedPersonRow(person: AccountRestrictedPerson, action: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(person.label)
            person.username?.let { Text("@$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        TextButton(onClick = onClick, enabled = enabled) { Text(action) }
    }
}
