package lol.mony.harmony.ui.screens.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.repo.ServerManagementRepository
import lol.mony.harmony.data.session.AuthState
import lol.mony.harmony.ui.AppState

internal data class ServerManagementAccess(val endpoint: Endpoint, val token: String, val profileId: String)

/** Borrow fresh credentials for each load/action, never the anonymous key as a bearer token. */
internal suspend fun AppState.serverManagementAccess(repository: ServerManagementRepository): ServerManagementAccess {
    val session = auth.state.value as? AuthState.SignedIn ?: error("Sign in to manage servers.")
    val token = withContext(Dispatchers.IO) { auth.freshToken() } ?: error("Your session expired. Sign in again.")
    val current = auth.state.value as? AuthState.SignedIn
    check(current != null && current.endpoint == session.endpoint && current.user.id == session.user.id) { "Your signed-in account changed. Open this screen again." }
    val profile = session.profile?.id ?: repository.profileId(session.endpoint, token, session.user.id).getOrThrow()
    return ServerManagementAccess(session.endpoint, token, profile)
}

internal class ServerManagementActions(private val scope: CoroutineScope) {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun launch(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "The instance could not complete this action. Try again." }
            finally { busy = false }
        }
    }
}

@Composable
internal fun rememberServerManagementActions(): ServerManagementActions {
    val scope = rememberCoroutineScope()
    return remember(scope) { ServerManagementActions(scope) }
}

@Composable
internal fun ServerManagementFeedback(actions: ServerManagementActions) {
    if (actions.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    actions.error?.let { ServerManagementError(it) }
}

@Composable
internal fun ServerManagementError(message: String, retry: (() -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
            if (retry != null) TextButton(onClick = retry) { Text("Retry") }
        }
    }
}

@Composable
internal fun ServerManagementNote(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

@Composable
internal fun ServerManagementConfirm(title: String, description: String, action: String, busy: Boolean, error: String?, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(description)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error != null) ServerManagementError(error)
            }
        },
        confirmButton = { Button(onClick = onConfirm, enabled = !busy) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

internal fun AppState.serverManagementChanged() {
    serversRevision.value += 1
}
