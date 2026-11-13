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
import androidx.compose.ui.unit.dp
import lol.mony.harmony.data.model.*
import lol.mony.harmony.data.repo.ServerManagementRepository
import lol.mony.harmony.ui.AppState

@Composable
internal fun ServerManagementRolesPage(state: AppState, repository: ServerManagementRepository, overview: ServerManagementOverview,
    revision: Int, onChanged: () -> Unit) {
    val actions = rememberServerManagementActions()
    var editing by remember { mutableStateOf<ServerManagementRole?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ServerManagementRole?>(null) }
    ServerManagementPagedList(key = "roles:${overview.server.id}", revision = revision, emptyText = "No roles to show.",
        load = { offset ->
            val access = state.serverManagementAccess(repository)
            repository.roles(access.endpoint, access.token, overview.server.id, offset).getOrThrow()
        }, itemKey = { it.id }, header = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Roles", style = MaterialTheme.typography.headlineSmall)
                ServerManagementNote("Roles are listed from highest to lowest. Members always receive the everyone role. Assign roles from the Members section.")
                if (overview.can(ServerManagementPermission.MANAGE_ROLES)) Button(onClick = { editing = null; showEditor = true },
                    enabled = overview.isOwner || overview.highestRolePosition > 1) { Text("New role") }
                else ServerManagementNote("Editing requires Manage roles permission on a locally hosted server.")
                ServerManagementNote("Role colors, icons, drag ordering and channel overrides are not editable here; existing values are kept.")
            }
        }) { role ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(role.name, style = MaterialTheme.typography.titleMedium)
            ServerManagementNote("Position ${role.position}${if (role.isDefault || role.isAdmin) " • Protected role" else ""}")
            val permissions = ServerManagementPermission.entries.filter { it.isIn(role.permissions) }
            Text(if (permissions.isEmpty()) "No added permissions" else permissions.joinToString { it.label }, style = MaterialTheme.typography.bodySmall)
            if (overview.canEdit(role)) Row {
                TextButton(onClick = { editing = role; showEditor = true }) { Text("Edit role") }
                if (!role.isDefault && !role.isAdmin) TextButton(onClick = { deleting = role }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
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
    deleting?.let { role -> ServerManagementConfirm("Delete ${role.name}?", "This removes the role from every member who has it. This cannot be undone.",
        "Delete role", actions.busy, actions.error, { deleting = null }) { actions.launch {
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
    AlertDialog(onDismissRequest = { if (!actions.busy) onDismiss() }, title = { Text(if (role == null) "New role" else "Edit ${role.name}") },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ServerManagementFeedback(actions)
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Role name") }, singleLine = true,
                enabled = !actions.busy && role?.isDefault != true)
            if (role == null) OutlinedTextField(value = position, onValueChange = { position = it }, label = { Text("Position") }, singleLine = true,
                enabled = !actions.busy, supportingText = { Text("Use a positive number below your highest role. Higher numbers rank above lower ones.") },
                isError = rank == null || rank < 1 || (!overview.isOwner && rank >= overview.highestRolePosition))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Show separately in member list", Modifier.weight(1f))
                Switch(checked = hoist, onCheckedChange = { hoist = it }, enabled = !actions.busy && role?.isDefault != true)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Allow anyone to mention role", Modifier.weight(1f))
                Switch(checked = mentionable, onCheckedChange = { mentionable = it }, enabled = !actions.busy && role?.isDefault != true)
            }
            HorizontalDivider()
            Text("Permissions", style = MaterialTheme.typography.titleMedium)
            if (ServerManagementPermission.ADMINISTRATOR.isIn(permissions)) ServerManagementError("Administrator gives every permission and bypasses channel access restrictions. Grant it only to people you trust.")
            ServerManagementPermission.entries.forEach { permission ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = permission.isIn(permissions), onCheckedChange = { permissions = permission.setIn(permissions, it) },
                        enabled = !actions.busy && permission != ServerManagementPermission.CREATE_PRIVATE_THREADS &&
                            (overview.isOwner || overview.permissions.allows(permission) || permission.isIn(role?.permissions ?: 0)))
                    Text(permission.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
            ServerManagementNote("You cannot grant permissions you do not hold. Private threads are not implemented by Harmony. The instance makes the final permission and role-rank checks.")
        } }, confirmButton = { Button(onClick = { onSave(name, permissions, requireNotNull(rank), hoist, mentionable) },
            enabled = !actions.busy && name.isNotBlank() && name.length <= 100 && rank != null &&
                (role != null || (rank > 0 && (overview.isOwner || rank < overview.highestRolePosition)))) { Text("Save role") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !actions.busy) { Text("Cancel") } })
}
