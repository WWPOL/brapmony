package lol.mony.harmony.ui.screens.server

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import lol.mony.harmony.data.model.*
import lol.mony.harmony.data.repo.ServerManagementRepository
import lol.mony.harmony.data.session.AuthState
import lol.mony.harmony.ui.AppState

@Composable
internal fun ServerManagementInvitesPage(state: AppState, repository: ServerManagementRepository, overview: ServerManagementOverview,
    revision: Int, onChanged: () -> Unit) {
    if (!overview.server.isLocal) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Invites", style = MaterialTheme.typography.headlineSmall)
            ServerManagementNote("Invites for this server are managed on its home instance. Creating a link to this local copy is not supported.")
        }
        return
    }
    val authState by state.auth.state.collectAsState()
    val actions = rememberServerManagementActions()
    val policy = overview.invitePolicy
    var expiry by remember(overview.server.id, policy) { mutableStateOf((if (policy.maxExpiration > 0) policy.defaultExpiration.coerceIn(1, policy.maxExpiration) else 0).toString()) }
    var maxUses by remember(overview.server.id, policy) { mutableStateOf(policy.maxUses.toString()) }
    var selected by remember { mutableStateOf<ServerManagementInvite?>(null) }
    var copied by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val minutes = expiry.toIntOrNull()
    val uses = maxUses.toIntOrNull()
    val validation = if (minutes == null || uses == null) "Enter whole numbers for expiry and use limit." else
        validateServerManagementInviteOptions(minutes, uses, policy)
    ServerManagementPagedList(key = "invites:${overview.server.id}", revision = revision,
        emptyText = "You have no invite links for this server. Create one to invite someone.", load = { offset ->
            val access = state.serverManagementAccess(repository)
            repository.invites(access.endpoint, access.token, overview.server.id, access.profileId, offset).getOrThrow()
        }, itemKey = { it.id }, header = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Your invite links", style = MaterialTheme.typography.headlineSmall)
                ServerManagementNote("Only links you created are shown. Revoked, expired or exhausted links cannot be used to join.")
                ServerManagementFeedback(actions)
                if (overview.can(ServerManagementPermission.CREATE_INVITE)) {
                    OutlinedTextField(value = expiry, onValueChange = { expiry = it }, label = { Text("Expire after (minutes)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                        enabled = !actions.busy, modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text(if (policy.maxExpiration > 0) "Maximum ${policy.maxExpiration} minutes." else "0 means never expires.") })
                    OutlinedTextField(value = maxUses, onValueChange = { maxUses = it }, label = { Text("Maximum uses") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                        enabled = !actions.busy, modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text(if (policy.maxUses > 0) "Choose 1 to ${policy.maxUses}." else "0 means unlimited uses.") })
                    validation?.let { ServerManagementNote(it) }
                    Button(enabled = !actions.busy && validation == null, onClick = { actions.launch {
                        val access = state.serverManagementAccess(repository)
                        repository.createInvite(access.endpoint, access.token, overview.server.id, access.profileId,
                            requireNotNull(minutes), requireNotNull(uses), policy).getOrThrow()
                        onChanged()
                    } }) { Text("Create invite link") }
                } else ServerManagementNote("Creating an invite requires Create invites permission. Existing links you created can still be viewed and revoked.")
                ServerManagementNote("Temporary memberships and invite-permission settings are not editable in this native client.")
                HorizontalDivider()
            }
        }) { invite ->
        val base = (authState as? AuthState.SignedIn)?.endpoint?.origin?.trimEnd('/')
        val link = base?.let { "$it/invite/${invite.code}" }
        val active = invite.active()
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(invite.code, style = MaterialTheme.typography.titleMedium)
            Text(when {
                active -> "${invite.uses} uses${invite.maxUses?.takeIf { it > 0 }?.let { " of $it" }.orEmpty()}"
                invite.maxUses != null && invite.maxUses > 0 && invite.uses >= invite.maxUses -> "Use limit reached"
                invite.used -> "Revoked"
                else -> "Expired"
            }, style = MaterialTheme.typography.bodyMedium)
            ServerManagementNote(invite.expiresAt?.let { "Expires $it" } ?: "Never expires")
            if (link != null) Text(link, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = active && link != null && !actions.busy, onClick = { actions.launch {
                    clipboard.setText(AnnotatedString(requireNotNull(link))); copied = invite.id
                } }) { Text(if (copied == invite.id) "Copied" else "Copy link") }
                TextButton(enabled = active && link != null && !actions.busy, onClick = { actions.launch {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, requireNotNull(link))
                    }, "Share server invite"))
                } }) { Text("Share") }
                if (active) TextButton(enabled = !actions.busy, onClick = { selected = invite }) { Text("Revoke") }
            }
            HorizontalDivider()
        }
    }
    selected?.let { invite -> ServerManagementConfirm("Revoke ${invite.code}?", "Anyone holding this link will no longer be able to join. People who already joined stay in the server.",
        "Revoke link", actions.busy, actions.error, { selected = null }) { actions.launch {
        val access = state.serverManagementAccess(repository)
        repository.revokeInvite(access.endpoint, access.token, overview.server.id, access.profileId, invite.id).getOrThrow()
        selected = null; onChanged()
    } } }
}
