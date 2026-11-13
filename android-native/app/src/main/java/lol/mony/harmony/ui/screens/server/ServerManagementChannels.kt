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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import lol.mony.harmony.data.model.*
import lol.mony.harmony.data.repo.ServerManagementRepository
import lol.mony.harmony.ui.AppState

@Composable
internal fun ServerManagementChannelsPage(state: AppState, repository: ServerManagementRepository, overview: ServerManagementOverview,
    revision: Int, onChanged: () -> Unit) {
    val serverId = overview.server.id
    val actions = rememberServerManagementActions()
    var categories by remember(serverId, revision) { mutableStateOf<List<ServerManagementCategory>>(emptyList()) }
    var categoryError by remember(serverId, revision) { mutableStateOf<String?>(null) }
    var categoriesLoaded by remember(serverId, revision) { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ServerManagementChannel?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ServerManagementChannel?>(null) }
    var newCategory by remember { mutableStateOf(false) }
    var categoryName by remember { mutableStateOf("") }
    LaunchedEffect(serverId, revision) {
        try {
            val access = state.serverManagementAccess(repository)
            val result = repository.categories(access.endpoint, access.token, serverId).getOrThrow()
            check(!result.hasMore) { "This server has more categories than the native editor can display." }
            categories = result.items; categoriesLoaded = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { categoryError = failure.message ?: "Could not load channel categories." }
    }
    fun changed() { state.channelsByServer.value = state.channelsByServer.value - serverId; onChanged() }
    val canManage = overview.can(ServerManagementPermission.MANAGE_CHANNELS)
    ServerManagementPagedList(key = "channels:$serverId", revision = revision,
        emptyText = "No channels yet. A server manager can create the first one.", load = { offset ->
            val access = state.serverManagementAccess(repository)
            repository.channels(access.endpoint, access.token, serverId, offset).getOrThrow()
        }, itemKey = { it.id }, header = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Channels", style = MaterialTheme.typography.headlineSmall)
                ServerManagementNote("Text and voice channel details are supported. Private-channel access, encryption, channel ordering and category editing are not available here; existing values are kept.")
                categoryError?.let { ServerManagementError(it) }
                if (canManage) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { editing = null; showEditor = true }, enabled = !actions.busy && categoriesLoaded) { Text("New channel") }
                    OutlinedButton(onClick = { categoryName = ""; newCategory = true }, enabled = !actions.busy && categoriesLoaded) { Text("New category") }
                } else ServerManagementNote("Creating or editing channels requires Manage channels permission on a locally hosted server.")
            }
        }) { channel ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${if (channel.type == 1) "Voice" else "#"} ${channel.name}", style = MaterialTheme.typography.titleMedium)
            channel.category?.let { id -> categories.find { it.id == id }?.let { ServerManagementNote(it.name) } }
            channel.description?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (channel.slowmodeSeconds > 0) ServerManagementNote("Slowmode: ${channel.slowmodeSeconds} seconds")
            if (canManage) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { editing = channel; showEditor = true }, enabled = channel.type in 0..1 && !actions.busy && categoriesLoaded) { Text("Edit") }
                TextButton(onClick = { deleting = channel }, enabled = !actions.busy) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
            HorizontalDivider()
        }
    }
    if (showEditor) ServerManagementChannelEditor(editing, categories, actions, onDismiss = { showEditor = false }) { name, description, type, category, slowmode ->
        actions.launch {
            val access = state.serverManagementAccess(repository)
            repository.saveChannel(access.endpoint, access.token, serverId, editing?.id, name, description, type, category, slowmode).getOrThrow()
            showEditor = false; changed()
        }
    }
    deleting?.let { channel -> ServerManagementConfirm("Delete ${channel.name}?", "This permanently deletes this channel and its messages. This cannot be undone.",
        "Delete channel", actions.busy, actions.error, { deleting = null }) { actions.launch {
        val access = state.serverManagementAccess(repository)
        repository.deleteChannel(access.endpoint, access.token, serverId, channel.id).getOrThrow()
        if (state.openChannelId.value == channel.id) state.openChannelId.value = null
        deleting = null; changed()
    } } }
    if (newCategory) AlertDialog(onDismissRequest = { if (!actions.busy) newCategory = false },
        title = { Text("New category") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = categoryName, onValueChange = { categoryName = it }, label = { Text("Category name") }, singleLine = true, enabled = !actions.busy)
            ServerManagementFeedback(actions)
        } }, confirmButton = { Button(enabled = !actions.busy && categoryName.isNotBlank() && categoryName.length <= 100, onClick = { actions.launch {
            val access = state.serverManagementAccess(repository)
            repository.createCategory(access.endpoint, access.token, serverId, categoryName).getOrThrow()
            newCategory = false; changed()
        } }) { Text("Create category") } }, dismissButton = { TextButton(onClick = { newCategory = false }, enabled = !actions.busy) { Text("Cancel") } })
}

@Composable
private fun ServerManagementChannelEditor(channel: ServerManagementChannel?, categories: List<ServerManagementCategory>, actions: ServerManagementActions,
    onDismiss: () -> Unit, onSave: (String, String, Int, String?, Int) -> Unit) {
    var name by remember(channel?.id) { mutableStateOf(channel?.name.orEmpty()) }
    var description by remember(channel?.id) { mutableStateOf(channel?.description.orEmpty()) }
    var type by remember(channel?.id) { mutableStateOf(channel?.type ?: 0) }
    var category by remember(channel?.id) { mutableStateOf(channel?.category) }
    var slowmode by remember(channel?.id) { mutableStateOf((channel?.slowmodeSeconds ?: 0).toString()) }
    var menu by remember { mutableStateOf(false) }
    val seconds = slowmode.toIntOrNull()
    AlertDialog(onDismissRequest = { if (!actions.busy) onDismiss() },
        title = { Text(if (channel == null) "New channel" else "Edit channel") },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ServerManagementFeedback(actions)
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Channel name") }, singleLine = true, enabled = !actions.busy)
            ServerManagementNote("The instance normalizes text channel names. Voice names keep their spacing and case.")
            if (channel == null) Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = type == 0, onClick = { type = 0 }, enabled = !actions.busy); Text("Text")
                RadioButton(selected = type == 1, onClick = { type = 1 }, enabled = !actions.busy); Text("Voice")
            }
            OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") }, enabled = !actions.busy, minLines = 2)
            Column {
                OutlinedButton(onClick = { menu = true }, enabled = !actions.busy) { Text("Category: ${categories.find { it.id == category }?.name ?: if (category == null) "None" else "Current category"}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("None") }, onClick = { category = null; menu = false })
                    categories.forEach { choice -> DropdownMenuItem(text = { Text(choice.name) }, onClick = { category = choice.id; menu = false }) }
                }
            }
            OutlinedTextField(value = slowmode, onValueChange = { slowmode = it }, label = { Text("Slowmode (seconds)") },
                isError = seconds == null || seconds !in 0..21600, supportingText = { Text("0 disables slowmode; maximum 21600.") }, enabled = !actions.busy, singleLine = true)
            if (channel == null) ServerManagementNote("The channel inherits its category's access. Without a category, server permissions apply. Private access controls are not available here yet.")
        } }, confirmButton = { Button(onClick = { onSave(name, description, type, category, requireNotNull(seconds)) },
            enabled = !actions.busy && name.isNotBlank() && name.length <= 100 && seconds != null && seconds in 0..21600) { Text(if (channel == null) "Create channel" else "Save channel") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !actions.busy) { Text("Cancel") } })
}
