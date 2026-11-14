package lol.mony.harmony.ui.screens.server

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import lol.mony.harmony.data.model.*
import lol.mony.harmony.data.repo.ServerManagementRepository
import lol.mony.harmony.ui.AppState

/**
 * The server's roles: searchable, paged as the reader scrolls, and with the picked role kept
 * while pages and searches change underneath it.
 *
 * The search runs against the instance's whole role table, so a role on the last page is found
 * from the first, and the selection is held by role id rather than by position, so arriving
 * pages cannot move it. Nothing here offers a page control: the next page loads itself when the
 * end of the list comes into view.
 */
@Composable
internal fun ServerManagementRolesPage(state: AppState, repository: ServerManagementRepository, overview: ServerManagementOverview,
    revision: Int, onChanged: () -> Unit) {
    val actions = rememberServerManagementActions()
    var editing by remember { mutableStateOf<ServerManagementRole?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ServerManagementRole?>(null) }
    var picked by remember(overview.server.id) { mutableStateOf<String?>(null) }
    PagedSearchList(
        listKey = "roles:${overview.server.id}",
        revision = revision,
        searchHint = "Search roles",
        emptyText = "No roles yet.",
        noMatchText = "No role matches that search.",
        itemKey = { it.id },
        selectedKey = picked,
        load = { offset, query ->
            val access = state.serverManagementAccess(repository)
            repository.roles(access.endpoint, access.token, overview.server.id, offset, query).getOrThrow()
        },
        header = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Roles", style = MaterialTheme.typography.headlineSmall)
                ServerManagementNote("Highest first. Everyone has the everyone role.")
                if (overview.can(ServerManagementPermission.MANAGE_ROLES)) Button(onClick = { editing = null; showEditor = true },
                    enabled = overview.isOwner || overview.highestRolePosition > 1) { Text("New role") }
                else ServerManagementNote("You need Manage roles to change this.")
            }
        },
    ) { role, selected ->
        Column(
            Modifier.fillMaxWidth()
                .then(if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp)) else Modifier)
                .padding(if (selected) 8.dp else 0.dp)
                .testTag("role-" + role.id),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(role.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { picked = if (picked == role.id) null else role.id }) {
                    Text(if (selected) "Chosen" else "Choose")
                }
            }
            ServerManagementNote("Position ${role.position}" + (if (role.isDefault || role.isAdmin) " · Protected" else ""))
            val permissions = ServerManagementPermission.entries.filter { it.isIn(role.permissions) }
            Text(if (permissions.isEmpty()) "No extra permissions" else permissions.joinToString { it.label },
                style = MaterialTheme.typography.bodySmall)
            if (overview.canEdit(role)) Row {
                TextButton(onClick = { editing = role; showEditor = true }) { Text("Edit") }
                if (!role.isDefault && !role.isAdmin) TextButton(onClick = { deleting = role }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
            HorizontalDivider()
        }
    }
    if (showEditor) ServerManagementRoleEditor(editing, overview, actions, onDismiss = { showEditor = false }) { name, permissions, position, hoist, mentionable ->
        actions.launch {
            val access = state.serverManagementAccess(repository)
            repository.saveRole(access.endpoint, access.token, overview.server.id, editing?.id, name, permissions, position, hoist, mentionable).getOrThrow()
            showEditor = false; onChanged()
        }
    }
    deleting?.let { role -> ServerManagementConfirm("Delete ${role.name}?", "It is removed from every member who has it. This cannot be undone.",
        "Delete", actions.busy, actions.error, { deleting = null }) { actions.launch {
        val access = state.serverManagementAccess(repository)
        repository.deleteRole(access.endpoint, access.token, overview.server.id, role).getOrThrow()
        deleting = null; onChanged()
    } } }
}

@Composable
private fun ServerManagementRoleEditor(role: ServerManagementRole?, overview: ServerManagementOverview, actions: ServerManagementActions,
    onDismiss: () -> Unit, onSave: (String, Long, Int, Boolean, Boolean) -> Unit) {
    var name by remember(role?.id) { mutableStateOf(role?.name.orEmpty()) }
    var permissions by remember(role?.id) { mutableStateOf(role?.permissions ?: 0L) }
    var position by remember(role?.id) { mutableStateOf((role?.position ?: 1).toString()) }
    var hoist by remember(role?.id) { mutableStateOf(role?.hoist ?: false) }
    var mentionable by remember(role?.id) { mutableStateOf(role?.mentionable ?: false) }
    val rank = position.toIntOrNull()
    AlertDialog(onDismissRequest = { if (!actions.busy) onDismiss() }, title = { Text(if (role == null) "New role" else "Edit role") },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ServerManagementFeedback(actions)
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Role name") }, singleLine = true,
                enabled = !actions.busy && role?.isDefault != true)
            if (role == null) OutlinedTextField(value = position, onValueChange = { position = it }, label = { Text("Position") }, singleLine = true,
                enabled = !actions.busy, supportingText = { Text("Below your highest role.") },
                isError = rank == null || rank < 1 || (!overview.isOwner && rank >= overview.highestRolePosition))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Show separately in member list", Modifier.weight(1f))
                Switch(checked = hoist, onCheckedChange = { hoist = it }, enabled = !actions.busy && role?.isDefault != true)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Anyone can mention this role", Modifier.weight(1f))
                Switch(checked = mentionable, onCheckedChange = { mentionable = it }, enabled = !actions.busy && role?.isDefault != true)
            }
            HorizontalDivider()
            Text("Permissions", style = MaterialTheme.typography.titleMedium)
            if (ServerManagementPermission.ADMINISTRATOR.isIn(permissions)) ServerManagementError("Administrator grants every permission and bypasses channel restrictions. Give it only to people you trust.")
            ServerManagementPermission.entries.forEach { permission ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = permission.isIn(permissions), onCheckedChange = { permissions = permission.setIn(permissions, it) },
                        enabled = !actions.busy && permission != ServerManagementPermission.CREATE_PRIVATE_THREADS &&
                            (overview.isOwner || overview.permissions.allows(permission) || permission.isIn(role?.permissions ?: 0)))
                    Text(permission.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
            ServerManagementNote("You cannot grant a permission you do not hold. The instance checks roles again when it saves.")
        } }, confirmButton = { Button(onClick = { onSave(name, permissions, requireNotNull(rank), hoist, mentionable) },
            enabled = !actions.busy && name.isNotBlank() && name.length <= 100 && rank != null &&
                (role != null || (rank > 0 && (overview.isOwner || rank < overview.highestRolePosition)))) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !actions.busy) { Text("Cancel") } })
}
