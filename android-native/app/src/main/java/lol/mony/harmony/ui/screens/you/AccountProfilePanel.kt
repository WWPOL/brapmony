package lol.mony.harmony.ui.screens.you

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import lol.mony.harmony.data.model.AccountProfile
import lol.mony.harmony.data.model.AccountProfileField
import lol.mony.harmony.data.repo.AccountRepository
import lol.mony.harmony.ui.AppState

@Composable
internal fun AccountProfilePanel(state: AppState, repository: AccountRepository, profile: AccountProfile, onSaved: (AccountProfile) -> Unit) {
    val original = remember(profile) { profile.edit() }
    var edited by remember(profile) { mutableStateOf(original) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AccountSection("Your profile", "Your name, pronouns and bio are shared with your instance and federated profile.") {
        OutlinedTextField(
            edited.displayName, { edited = edited.copy(displayName = it); notice = null },
            label = { Text("Display name") }, supportingText = { Text("${edited.displayName.length}/50") },
            singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(profile.username.orEmpty(), {}, label = { Text("Username") }, readOnly = true, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(edited.pronouns, { edited = edited.copy(pronouns = it) }, label = { Text("Pronouns") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(edited.bio, { edited = edited.copy(bio = it) }, label = { Text("Bio") }, minLines = 3, supportingText = { Text("${edited.bio.length}/500") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(edited.color, { edited = edited.copy(color = it) }, label = { Text("Display color") }, placeholder = { Text("#0EA5E9") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
    }
    AccountSection("Profile pictures", "Set an existing image address, or clear it to remove the picture. File upload and cropping are not available in this editor yet.") {
        OutlinedTextField(edited.avatarUrl, { edited = edited.copy(avatarUrl = it) }, label = { Text("Avatar image address") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(edited.bannerUrl, { edited = edited.copy(bannerUrl = it) }, label = { Text("Banner image address") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
    }
    AccountSection("Profile fields", "Add up to four labeled links or short details.") {
        edited.fields.forEachIndexed { index, field ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(field.name, { value ->
                    edited = edited.copy(fields = edited.fields.mapIndexed { i, item -> if (i == index) item.copy(name = value) else item })
                }, label = { Text("Field ${index + 1} name") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(field.value, { value ->
                    edited = edited.copy(fields = edited.fields.mapIndexed { i, item -> if (i == index) item.copy(value = value) else item })
                }, label = { Text("Field ${index + 1} value") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { edited = edited.copy(fields = edited.fields.filterIndexed { i, _ -> i != index }) }, enabled = !busy) {
                    Text("Remove field ${index + 1}")
                }
            }
        }
        OutlinedButton(onClick = { edited = edited.copy(fields = edited.fields + AccountProfileField("", "")) }, enabled = !busy && edited.fields.size < 4) { Text("Add profile field") }
    }
    AccountStatus(error, notice)
    AccountSaveActions(busy, edited != original, onSave = {
        error = edited.validate()
        if (error == null) scope.launch {
            busy = true; notice = null
            try {
                val saved = accountRequest(state) { endpoint, token -> repository.updateProfile(endpoint, token, profile.id, original, edited).getOrThrow() }
                onSaved(saved)
                notice = "Profile saved."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Your profile was not saved." }
            finally { busy = false }
        }
    }, onReset = { edited = original; error = null; notice = null })
    profile.createdAt?.let { Text("Account created ${it.substringBefore('T')}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}
