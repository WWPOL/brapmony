package lol.mony.harmony.ui.screens.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
internal fun ServerManagementMembersPage(state: AppState, repository: ServerManagementRepository, overview: ServerManagementOverview,
    revision: Int, onChanged: () -> Unit) {
    var selected by remember { mutableStateOf<ServerManagementMember?>(null) }
    var moderating by remember { mutableStateOf<ServerManagementMember?>(null) }
    var ban by remember { mutableStateOf(false) }
    val actions = rememberServerManagementActions()
    val canRoles = overview.can(ServerManagementPermission.MANAGE_ROLES)
    ServerManagementPagedList(key = "members:${overview.server.id}", revision = revision, emptyText = "No accepted members to show.",
        load = { offset ->
            val access = state.serverManagementAccess(repository)
            repository.members(access.endpoint, access.token, overview.server.id, offset).getOrThrow()
        }, itemKey = { it.membership.id }, header = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Members", style = MaterialTheme.typography.headlineSmall)
                ServerManagementNote("Role changes and removals are checked against the server's role hierarchy. Timeout, server profiles and live voice moderation are not available here yet.")
            }
        }) { member ->
        Column {
            Text(member.shownName, style = MaterialTheme.typography.titleMedium)
            member.person?.let { ServerManagementNote("@${it.username}") }
            val id = member.membership.userId
            if (id == overview.server.owner) ServerManagementNote("Server owner")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { selected = member }) { Text(if (canRoles) "Manage roles" else "View roles") }
                if (id != overview.profileId && id != overview.server.owner) {
                    if (overview.can(ServerManagementPermission.KICK_MEMBERS)) TextButton(onClick = { moderating = member; ban = false }) { Text("Kick") }
                    if (overview.can(ServerManagementPermission.BAN_MEMBERS)) TextButton(onClick = { moderating = member; ban = true }) { Text("Ban", color = MaterialTheme.colorScheme.error) }
                }
            }
            HorizontalDivider()
        }
    }
    selected?.let { member -> ServerManagementMemberRolesDialog(state, repository, overview, member,
        onDismiss = { selected = null }, onChanged = onChanged) }
    moderating?.let { member -> ServerManagementModerationDialog(member, ban, actions, onDismiss = { moderating = null }) { reason ->
        actions.launch {
            val access = state.serverManagementAccess(repository)
            repository.moderate(access.endpoint, access.token, overview.server.id, member.membership.userId, ban, reason).getOrThrow()
            moderating = null; onChanged()
        }
    } }
}

@Composable
private fun ServerManagementMemberRolesDialog(state: AppState, repository: ServerManagementRepository, overview: ServerManagementOverview,
    member: ServerManagementMember, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val actions = rememberServerManagementActions()
    var assigned by remember(member.membership.id) { mutableStateOf<List<ServerManagementUserRole>>(emptyList()) }
    var roles by remember(member.membership.id) { mutableStateOf<List<ServerManagementRole>>(emptyList()) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(member.membership.id) {
        try {
            val access = state.serverManagementAccess(repository)
            assigned = repository.memberRoles(access.endpoint, access.token, overview.server.id, member.membership.userId).getOrThrow()
            val page = repository.roles(access.endpoint, access.token, overview.server.id).getOrThrow()
            roles = page.items; hasMore = page.hasMore
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { loadError = failure.message ?: "Could not load this member's roles." }
        finally { loading = false }
    }
    AlertDialog(onDismissRequest = { if (!actions.busy) onDismiss() }, title = { Text("Roles for ${member.shownName}") },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            loadError?.let { ServerManagementError(it) }
            ServerManagementFeedback(actions)
            roles.forEach { role ->
                val checked = role.isDefault || assigned.any { it.roleId == role.id }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked, enabled = !loading && loadError == null && !actions.busy && overview.canEdit(role) && !role.isDefault,
                        onCheckedChange = { next -> actions.launch {
                            val access = state.serverManagementAccess(repository)
                            if (next) {
                                val assignment = repository.assignRole(access.endpoint, access.token, overview.server.id, member.membership.userId, role.id).getOrThrow()
                                assigned = assigned + assignment
                            } else {
                                repository.removeRole(access.endpoint, access.token, overview.server.id, member.membership.userId, role).getOrThrow()
                                assigned = assigned.filterNot { it.roleId == role.id }
                            }
                            onChanged()
                        } })
                    Text(role.name)
                }
            }
            if (hasMore) TextButton(enabled = !actions.busy, onClick = { actions.launch {
                val access = state.serverManagementAccess(repository)
                val page = repository.roles(access.endpoint, access.token, overview.server.id, roles.size).getOrThrow()
                roles = roles + page.items; hasMore = page.hasMore
            } }) { Text("Load more roles") }
            ServerManagementNote("The everyone role is automatic. Protected or higher-ranked roles cannot be changed here.")
        } }, confirmButton = { TextButton(onClick = onDismiss, enabled = !actions.busy) { Text("Done") } })
}

@Composable
private fun ServerManagementModerationDialog(member: ServerManagementMember, ban: Boolean, actions: ServerManagementActions,
    onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var reason by remember(member.membership.id, ban) { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if (!actions.busy) onDismiss() }, title = { Text("${if (ban) "Ban" else "Kick"} ${member.shownName}?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (ban) "This removes the member and prevents rejoining until the ban is revoked." else "This removes the member. They can rejoin with a valid invite.")
            ServerManagementNote("Their messages will be kept. The database removes voice membership; immediate LiveKit room disconnection is not verified by this native client.")
            OutlinedTextField(value = reason, onValueChange = { reason = it }, label = { Text("Reason (optional)") }, minLines = 2, enabled = !actions.busy)
            ServerManagementFeedback(actions)
        } }, confirmButton = { Button(onClick = { onConfirm(reason) }, enabled = !actions.busy) { Text(if (ban) "Ban member" else "Kick member") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !actions.busy) { Text("Cancel") } })
}

@Composable
internal fun ServerManagementBansPage(state: AppState, repository: ServerManagementRepository, overview: ServerManagementOverview,
    revision: Int, onChanged: () -> Unit) {
    if (!overview.can(ServerManagementPermission.BAN_MEMBERS)) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Bans", style = MaterialTheme.typography.headlineSmall)
            ServerManagementNote("Viewing and revoking bans requires Ban members permission on a locally hosted server.")
        }
        return
    }
    val actions = rememberServerManagementActions()
    var selected by remember { mutableStateOf<ServerManagementBan?>(null) }
    ServerManagementPagedList(key = "bans:${overview.server.id}", revision = revision, emptyText = "No banned members.", load = {
        val access = state.serverManagementAccess(repository)
        ServerManagementPage(repository.bans(access.endpoint, access.token, overview.server.id).getOrThrow(), false)
    }, itemKey = { it.id }, header = { Text("Bans", style = MaterialTheme.typography.headlineSmall) }) { record ->
        Column {
            Text(record.displayName ?: record.username, style = MaterialTheme.typography.titleMedium)
            record.reason?.let { Text(it) }
            TextButton(onClick = { selected = record }, enabled = !actions.busy) { Text("Revoke ban") }
            HorizontalDivider()
        }
    }
    selected?.let { record -> ServerManagementConfirm("Revoke ban for ${record.displayName ?: record.username}?",
        "They will be able to rejoin with a new invite. This does not add them back to the server.", "Revoke ban", actions.busy, actions.error,
        { selected = null }) { actions.launch {
        val access = state.serverManagementAccess(repository)
        repository.unban(access.endpoint, access.token, overview.server.id, record.userId).getOrThrow()
        selected = null; onChanged()
    } } }
}
