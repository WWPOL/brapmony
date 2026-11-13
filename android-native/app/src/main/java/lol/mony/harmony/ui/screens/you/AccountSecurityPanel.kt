package lol.mony.harmony.ui.screens.you

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import lol.mony.harmony.data.model.AccountAuthIdentity
import lol.mony.harmony.data.model.AccountRecoveryStatus
import lol.mony.harmony.data.model.AccountSession
import lol.mony.harmony.data.repo.AccountSecurityRepository
import lol.mony.harmony.ui.AppState
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun AccountSecurityPanel(state: AppState) {
    val repository = remember(state.container.rest) { AccountSecurityRepository(state.container.rest) }
    var identity by remember { mutableStateOf<AccountAuthIdentity?>(null) }
    var recovery by remember { mutableStateOf<AccountRecoveryStatus?>(null) }
    var sessions by remember { mutableStateOf<List<AccountSession>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var recoveryError by remember { mutableStateOf<String?>(null) }
    var sessionsError by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var revision by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<AccountSession?>(null) }
    var confirmOthers by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(revision) {
        loading = true; loadError = null; recoveryError = null; sessionsError = null
        try { identity = accountRequest(state) { endpoint, token -> repository.identity(endpoint, token).getOrThrow() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { loadError = failure.message ?: "Account security could not be loaded." }
        try { recovery = accountRequest(state) { endpoint, token -> repository.recoveryStatus(endpoint, token).getOrThrow() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { recoveryError = failure.message ?: "Two-factor status could not be loaded." }
        try { sessions = accountRequest(state) { endpoint, token -> repository.sessions(endpoint, token).getOrThrow() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { sessionsError = failure.message ?: "Sessions could not be loaded." }
        finally { loading = false }
    }
    if (loading) CircularProgressIndicator()
    AccountSection("Password", "Security changes are checked by your instance. Passwords are not saved on this device.") {
        AccountStatus(loadError, null)
        if (identity != null) {
            val hasPassword = identity?.hasPassword == true
            if (hasPassword) OutlinedTextField(
                currentPassword, { currentPassword = it }, label = { Text("Current password") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = !busy, modifier = Modifier.fillMaxWidth(),
            ) else Text("Signed in with ${identity?.providers?.joinToString().orEmpty()}. Your provider may manage your password.")
            OutlinedTextField(newPassword, { newPassword = it }, label = { Text("New password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(confirmPassword, { confirmPassword = it }, label = { Text("Confirm new password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = !busy, modifier = Modifier.fillMaxWidth())
            Text("Use at least eight characters. Two-factor and provider-managed account restrictions still apply.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(enabled = !busy && newPassword.isNotEmpty() && confirmPassword.isNotEmpty() && (!hasPassword || currentPassword.isNotEmpty()), onClick = {
                actionError = when {
                    newPassword.length < 8 -> "Use at least eight characters for the new password."
                    newPassword != confirmPassword -> "The new passwords do not match."
                    else -> null
                }
                if (actionError == null) scope.launch {
                    busy = true; notice = null
                    try {
                        accountRequest(state) { endpoint, token -> repository.changePassword(endpoint, token, currentPassword, newPassword).getOrThrow() }
                        currentPassword = ""; newPassword = ""; confirmPassword = ""
                        notice = "Password changed."; revision++
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { actionError = failure.message ?: "Your password was not changed." }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Working…" else if (hasPassword) "Change password" else "Set password") }
        }
        AccountStatus(actionError, notice)
    }
    AccountSection("Two-factor authentication") {
        AccountStatus(recoveryError, null)
        recovery?.let { status ->
            Text(if (status.mfaEnabled) "Two-factor authentication is enabled." else "Two-factor authentication is not enabled.")
            if (status.mfaEnabled) {
                Text("${status.remaining} of ${status.total} recovery codes remain.", style = MaterialTheme.typography.bodyMedium)
                status.generatedAt?.let { Text("Codes generated ${securityTime(it)}", style = MaterialTheme.typography.bodySmall) }
            }
        }
        Text("Authenticator enrollment, removal and recovery-code generation are not available in this native screen yet. Existing two-factor requirements are never bypassed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    AccountSection("Signed-in devices", "Sign out a device you no longer recognize. Revoking a session also removes its push subscription.") {
        AccountStatus(sessionsError, null)
        val devices = sessions
        if (devices?.isEmpty() == true && sessionsError == null) Text("No active sessions were returned by your instance.")
        devices.orEmpty().forEach { session ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                Text(if (session.isCurrent) "This device" else session.userAgent?.takeIf { it.isNotBlank() } ?: "Other device", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (session.isCurrent) session.userAgent?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                session.ip?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("Last active ${securityTime(session.lastActiveAt ?: session.createdAt)}", style = MaterialTheme.typography.bodySmall)
                Text("Signed in ${securityTime(session.createdAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (session.pushTransports.isNotEmpty()) Text("Push: ${session.pushTransports.joinToString()}", style = MaterialTheme.typography.bodySmall)
                if (session.aal == "aal1" && recovery?.mfaEnabled == true) Text("Two-factor verification is incomplete on this device.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (!session.isCurrent) TextButton(onClick = { pending = session }, enabled = !busy) { Text("Sign out this device") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { revision++ }, enabled = !busy && !loading) { Text("Refresh devices") }
            if (devices.orEmpty().any { !it.isCurrent }) TextButton(onClick = { confirmOthers = true }, enabled = !busy) { Text("Sign out other devices") }
        }
    }
    val revoke = pending
    if (revoke != null || confirmOthers) AlertDialog(
        onDismissRequest = { pending = null; confirmOthers = false },
        title = { Text(if (confirmOthers) "Sign out all other devices?" else "Sign out this device?") },
        text = { Text("That device will need to sign in again. This device stays signed in.") },
        confirmButton = { TextButton(onClick = {
            val all = confirmOthers
            pending = null; confirmOthers = false
            scope.launch {
                busy = true; actionError = null; notice = null
                try {
                    notice = accountRequest(state) { endpoint, token ->
                        if (all) {
                            val count = repository.signOutOtherSessions(endpoint, token).getOrThrow()
                            "Signed out $count other ${if (count == 1) "device" else "devices"}."
                        } else {
                            val changed = repository.revokeSession(endpoint, token, requireNotNull(revoke).id).getOrThrow()
                            if (changed) "Device signed out." else "That session was already signed out."
                        }
                    }
                    revision++
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { actionError = failure.message ?: "The device could not be signed out." }
                finally { busy = false }
            }
        }) { Text("Sign out") } },
        dismissButton = { TextButton(onClick = { pending = null; confirmOthers = false }) { Text("Cancel") } },
    )
}

private fun securityTime(iso: String): String = runCatching {
    OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
}.getOrDefault(iso)
