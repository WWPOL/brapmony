package lol.mony.harmony.ui.screens.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import lol.mony.harmony.data.model.ServerManagementInviteInput
import lol.mony.harmony.data.model.ServerManagementInvitePreview
import lol.mony.harmony.data.model.ServerManagementServer
import lol.mony.harmony.data.model.parseServerManagementInvite
import lol.mony.harmony.data.model.validateServerManagementInfo
import lol.mony.harmony.data.repo.ServerManagementRepository
import lol.mony.harmony.ui.AppState

/** Native entry point for the shell's server rail '+'. No browser or anonymous writes. */
@Composable
fun CreateOrJoinServerDialog(state: AppState, onDismiss: () -> Unit, onJoined: (String) -> Unit) {
    val repository = remember(state.container.rest) { ServerManagementRepository(state.container.rest) }
    val actions = rememberServerManagementActions()
    var creating by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var isPublic by rememberSaveable { mutableStateOf(true) }
    var input by rememberSaveable { mutableStateOf("") }
    var preview by remember { mutableStateOf<ServerManagementInvitePreview?>(null) }
    var previewCode by remember { mutableStateOf<String?>(null) }
    var publicChoice by remember { mutableStateOf<ServerManagementServer?>(null) }
    var acceptedRules by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var publicServers by remember { mutableStateOf<List<ServerManagementServer>?>(null) }
    var hasMore by remember { mutableStateOf(false) }
    val validation = validateServerManagementInfo(name, description, creating = true)

    fun joined(id: String) {
        state.selectedServerId.value = id
        state.showingDms.value = false
        state.showCreateServer.value = false
        state.serverManagementChanged()
        onJoined(id)
    }
    fun loadPublic(append: Boolean) = actions.launch {
        val access = state.serverManagementAccess(repository)
        val page = repository.publicServers(access.endpoint, access.token, search,
            if (append) publicServers.orEmpty().size else 0).getOrThrow()
        publicServers = (if (append) publicServers.orEmpty() else emptyList()) + page.items
        hasMore = page.hasMore
    }

    AlertDialog(
        onDismissRequest = { if (!actions.busy) onDismiss() },
        title = { Text(if (creating) "Create a server" else "Join a server") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { creating = false }, enabled = !actions.busy && creating) { Text("Join") }
                    OutlinedButton(onClick = { creating = true }, enabled = !actions.busy && !creating) { Text("Create") }
                }
                ServerManagementFeedback(actions)
                if (creating) {
                    ServerManagementNote("A place for your community, hosted on your signed-in instance.")
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Server name") },
                        supportingText = { Text("${name.length}/28") }, modifier = Modifier.fillMaxWidth(),
                        singleLine = true, enabled = !actions.busy, isError = name.isNotEmpty() && (name.trim().length !in 2..28))
                    OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description (optional)") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2, enabled = !actions.busy,
                        isError = description.length > 500, supportingText = { Text("${description.length}/500") })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Public server", style = MaterialTheme.typography.titleSmall)
                            ServerManagementNote(if (isPublic) "People on this instance can discover and join it." else "People need an invite to join.")
                        }
                        Switch(checked = isPublic, onCheckedChange = { isPublic = it }, enabled = !actions.busy)
                    }
                    ServerManagementNote("Image uploads and template imports are not available in the native client yet. A new server starts without channels.")
                } else {
                    OutlinedTextField(value = input, onValueChange = {
                        input = it; preview = null; previewCode = null; publicChoice = null; acceptedRules = false
                    }, label = { Text("Invite link or code") }, modifier = Modifier.fillMaxWidth(),
                        singleLine = true, enabled = !actions.busy)
                    OutlinedButton(onClick = {
                        actions.launch {
                            val access = state.serverManagementAccess(repository)
                            val code = when (val parsed = parseServerManagementInvite(input, access.endpoint.baseUrl)) {
                                is ServerManagementInviteInput.Local -> parsed.code
                                ServerManagementInviteInput.RemoteUnsupported -> error("Joining another instance with a federated invite is not supported in the native client yet.")
                                ServerManagementInviteInput.Invalid -> error("Enter an invite code or a link from your signed-in instance.")
                            }
                            preview = repository.previewInvite(access.endpoint, access.token, code).getOrThrow()
                            previewCode = code; publicChoice = null; acceptedRules = false
                        }
                    }, enabled = input.isNotBlank() && !actions.busy) { Text("Preview invite") }
                    val selectedName = preview?.name ?: publicChoice?.name
                    if (selectedName != null) {
                        HorizontalDivider()
                        Text(selectedName, style = MaterialTheme.typography.titleLarge)
                        (preview?.description ?: publicChoice?.description)?.takeIf { it.isNotBlank() }?.let { Text(it) }
                        val memberCount = preview?.memberCount ?: publicChoice?.memberCount ?: 0
                        ServerManagementNote("$memberCount members")
                        val rules = (preview?.rules ?: publicChoice?.rules).orEmpty().filter(String::isNotBlank)
                        if (rules.isNotEmpty()) {
                            Text("Server rules", style = MaterialTheme.typography.titleSmall)
                            rules.forEachIndexed { index, rule -> Text("${index + 1}. $rule") }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = acceptedRules, onCheckedChange = { acceptedRules = it }, enabled = !actions.busy)
                                Text("I agree to these server rules.")
                            }
                        }
                    }
                    HorizontalDivider()
                    Text("Find a public server", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(value = search, onValueChange = { search = it; publicServers = null; hasMore = false },
                        label = { Text("Server name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !actions.busy)
                    TextButton(onClick = { loadPublic(false) }, enabled = !actions.busy) { Text("Search this instance") }
                    if (publicServers?.isEmpty() == true) ServerManagementNote("No public servers found. Try another name or use an invite.")
                    publicServers.orEmpty().forEach { server ->
                        TextButton(onClick = {
                            publicChoice = server; preview = null; previewCode = null; acceptedRules = false
                        }, enabled = !actions.busy, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(server.name, style = MaterialTheme.typography.titleSmall)
                                server.description?.takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 2) }
                            }
                        }
                    }
                    if (hasMore) TextButton(onClick = { loadPublic(true) }, enabled = !actions.busy) { Text("Load more servers") }
                }
            }
        },
        confirmButton = {
            val rules = (preview?.rules ?: publicChoice?.rules).orEmpty().filter(String::isNotBlank)
            Button(enabled = !actions.busy && if (creating) validation == null else
                (preview != null || publicChoice != null) && (rules.isEmpty() || acceptedRules || preview?.isMember == true),
                onClick = {
                    actions.launch {
                        val access = state.serverManagementAccess(repository)
                        val id = if (creating) repository.createServer(access.endpoint, access.token, access.profileId, name, description, isPublic).getOrThrow().id
                        else if (previewCode != null) repository.redeemInvite(access.endpoint, access.token, previewCode!!).getOrThrow().serverId
                        else repository.joinPublicServer(access.endpoint, access.token, requireNotNull(publicChoice).id).getOrThrow().serverId
                        joined(id)
                    }
                }) { Text(if (creating) "Create server" else if (preview?.isMember == true) "Open server" else "Join server") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !actions.busy) { Text("Cancel") } },
    )
}
