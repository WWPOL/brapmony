package lol.mony.harmony.ui.screens.notifications

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import lol.mony.harmony.data.model.Notification
import lol.mony.harmony.data.model.NotificationFormatter
import lol.mony.harmony.data.model.NotificationInboxFilter
import lol.mony.harmony.data.model.NotificationTarget
import lol.mony.harmony.data.repo.NotificationRepository
import lol.mony.harmony.data.session.AuthState
import lol.mony.harmony.ui.AppState
import lol.mony.harmony.ui.components.Avatar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Standalone parent integration entrypoint. Callbacks route real post/profile ids when supplied;
 * without shell routing the screen loads its own real, RLS-protected target previews.
 */
@Composable
fun NotificationsScreen(
    state: AppState,
    onOpenPost: ((String) -> Unit)? = null,
    onOpenProfile: ((String) -> Unit)? = null,
) {
    val authState by state.auth.state.collectAsStateWithLifecycle()
    val currentProfileId by state.profileId.collectAsStateWithLifecycle()
    val signedIn = authState as? AuthState.SignedIn
    val profileId = currentProfileId ?: signedIn?.profile?.id
    if (signedIn == null || profileId == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(if (signedIn == null) "Sign in to view notifications." else "Your profile is not available yet. Finish signing in to view notifications.")
        }
        return
    }

    val repository = remember(state.container.rest) { NotificationRepository(state.container.rest) }
    var filter by remember(profileId, signedIn.endpoint) { mutableStateOf(NotificationInboxFilter.All) }
    var unreadOnly by remember(profileId, signedIn.endpoint) { mutableStateOf(false) }
    val token: suspend () -> String = remember(state, profileId, signedIn.endpoint) {
        {
            val active = state.auth.state.value as? AuthState.SignedIn ?: error("Your session ended. Sign in again.")
            check(active.endpoint == signedIn.endpoint && (state.profileId.value ?: active.profile?.id) == profileId) {
                "The signed-in account changed. Reopen notifications."
            }
            state.auth.freshToken() ?: error("Your session expired. Sign in again.")
        }
    }
    val inbox = remember(repository, profileId, signedIn.endpoint, filter, unreadOnly) {
        NotificationInbox(repository, signedIn.endpoint, profileId, token, filter, unreadOnly)
    }
    val ui by inbox.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var deleteRequest by remember(inbox) { mutableStateOf<NotificationDeleteRequest?>(null) }
    var previewTarget by remember(profileId, signedIn.endpoint) { mutableStateOf<NotificationTarget?>(null) }
    var detail by remember(profileId, signedIn.endpoint) { mutableStateOf<Notification?>(null) }
    val working = ui.loading || ui.loadingMore || ui.busy

    LaunchedEffect(inbox) { inbox.load() }
    LaunchedEffect(inbox, ui.notice) {
        ui.notice?.let {
            snackbar.showSnackbar(it)
            inbox.clearNotice()
        }
    }

    fun open(notification: Notification) {
        scope.launch {
            inbox.open(notification) { updated ->
                when (val target = updated.target) {
                    is NotificationTarget.Post -> if (onOpenPost != null) onOpenPost(target.id) else previewTarget = target
                    is NotificationTarget.Profile -> if (onOpenProfile != null) onOpenProfile(target.id) else previewTarget = target
                    null -> detail = updated
                }
            }
        }
    }

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Notifications", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { scope.launch { inbox.load() } }, enabled = !working) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh notifications")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }, enabled = !working) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "Notification actions")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Mark all as read") },
                                leadingIcon = { Icon(Icons.Outlined.DoneAll, contentDescription = null) },
                                onClick = { menuOpen = false; scope.launch { inbox.markAllRead() } },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete all notifications", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { menuOpen = false; deleteRequest = NotificationDeleteRequest.All },
                            )
                        }
                    }
                }
                TabRow(selectedTabIndex = filter.ordinal) {
                    NotificationInboxFilter.entries.forEach { item ->
                        Tab(selected = filter == item, onClick = { filter = item }, enabled = !ui.busy, text = { Text(item.label) })
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = unreadOnly, onClick = { unreadOnly = !unreadOnly }, enabled = !ui.busy, label = { Text("Unread only") })
                    Spacer(Modifier.weight(1f))
                    if (ui.items.isNotEmpty()) Text("${ui.items.size} loaded", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (working && ui.items.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
                ui.actionError?.let { error -> NotificationError(error, enabled = !working, onRetry = { scope.launch { inbox.retryAction() } }) }
                if (ui.loadError != null) {
                    NotificationError(ui.loadError!!, enabled = !working, onRetry = { scope.launch { inbox.retryLoad() } })
                }
                when {
                    ui.loading && ui.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator()
                            Text("Loading notifications", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    ui.items.isEmpty() && ui.loadError == null -> Box(Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.NotificationsNone, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
                            Text(if (unreadOnly) "No unread notifications" else "No notifications in this filter", style = MaterialTheme.typography.titleMedium)
                            Text("Refresh to check for new activity.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = { scope.launch { inbox.load() } }, enabled = !working) { Text("Refresh") }
                        }
                    }
                    else -> LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val groups = ui.items.groupBy { notificationDay(it.createdAt) }
                        groups.forEach { (day, rows) ->
                            item(key = "day:$day") {
                                Text(day, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                            }
                            items(rows, key = { it.id }) { notification ->
                                NotificationRow(
                                    notification = notification,
                                    enabled = !working,
                                    avatarBaseUrl = signedIn.endpoint.baseUrl,
                                    onOpen = { open(notification) },
                                    onRead = { scope.launch { inbox.setRead(notification) } },
                                    onDelete = { deleteRequest = NotificationDeleteRequest.One(notification) },
                                    onRespond = { accept -> scope.launch { inbox.respond(notification, accept) } },
                                )
                            }
                        }
                        item(key = "pagination") {
                            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                when {
                                    ui.loadingMore -> CircularProgressIndicator(Modifier.size(28.dp))
                                    ui.hasMore -> OutlinedButton(onClick = { scope.launch { inbox.load(reset = false) } }, enabled = !working && ui.loadError == null) { Text("Load more") }
                                    ui.items.isNotEmpty() && ui.loadError == null -> Text("You're up to date", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))
        }
    }

    deleteRequest?.let { request ->
        AlertDialog(
            onDismissRequest = { deleteRequest = null },
            title = { Text(if (request is NotificationDeleteRequest.All) "Delete all notifications?" else "Delete notification?") },
            text = { Text(if (request is NotificationDeleteRequest.All) "This deletes every notification for your account, including those outside this filter. This cannot be undone." else "This removes the notification from your inbox. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteRequest = null
                    scope.launch { when (request) { is NotificationDeleteRequest.All -> inbox.deleteAll(); is NotificationDeleteRequest.One -> inbox.delete(request.notification) } }
                }, enabled = !working) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteRequest = null }) { Text("Cancel") } },
        )
    }
    previewTarget?.let { target ->
        NotificationTargetPreview(repository, signedIn.endpoint, token, target, onDismiss = { previewTarget = null })
    }
    detail?.let { notification -> NotificationDetail(notification, onDismiss = { detail = null }) }
}

private sealed interface NotificationDeleteRequest {
    data object All : NotificationDeleteRequest
    data class One(val notification: Notification) : NotificationDeleteRequest
}

@Composable
private fun NotificationRow(
    notification: Notification,
    enabled: Boolean,
    avatarBaseUrl: String,
    onOpen: () -> Unit,
    onRead: () -> Unit,
    onDelete: () -> Unit,
    onRespond: (Boolean) -> Unit,
) {
    val copy = remember(notification) { NotificationFormatter.format(notification) }
    val actor = notification.actor
    var menu by remember(notification.id) { mutableStateOf(false) }
    Surface(
        onClick = onOpen,
        enabled = enabled,
        color = if (notification.isRead) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.padding(horizontal = 8.dp).fillMaxWidth().semantics {
            stateDescription = if (notification.isRead) "Read" else "Unread"
        },
    ) {
        Row(Modifier.padding(start = 12.dp, top = 12.dp, end = 4.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (actor != null) Avatar(actor.name, notificationAvatarUrl(avatarBaseUrl, actor.avatarUrl), size = 40.dp)
                else Icon(Icons.Outlined.NotificationsNone, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp).padding(8.dp))
                if (!notification.isRead) Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(copy.title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (notification.isRead) FontWeight.Normal else FontWeight.SemiBold)
                actor?.handle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                copy.preview?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                copy.context?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(
                    listOfNotNull(notificationTime(notification.createdAt), if (notification.isClicked) "Opened" else null).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (notification.isFollowRequest) {
                    if (notification.followerId != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onRespond(true) }, enabled = enabled) { Text("Accept") }
                            OutlinedButton(onClick = { onRespond(false) }, enabled = enabled) { Text("Decline") }
                        }
                    } else Text("The follower is missing from this notification. Refresh the inbox.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            Box {
                IconButton(onClick = { menu = true }, enabled = enabled) { Icon(Icons.Outlined.MoreVert, contentDescription = "Actions for ${copy.title}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (notification.isRead) "Mark as unread" else "Mark as read") },
                        leadingIcon = { Icon(if (notification.isRead) Icons.Outlined.MarkEmailUnread else Icons.Outlined.MarkEmailRead, contentDescription = null) },
                        onClick = { menu = false; onRead() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
internal fun NotificationError(message: String, enabled: Boolean, onRetry: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onRetry, enabled = enabled) { Text("Retry") }
        }
    }
}

@Composable
private fun NotificationDetail(notification: Notification, onDismiss: () -> Unit) {
    val copy = NotificationFormatter.format(notification)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(copy.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                copy.preview?.let { Text(it) }
                copy.context?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                HorizontalDivider()
                Text(notificationTime(notification.createdAt), style = MaterialTheme.typography.labelMedium)
                Text(if (notification.isClicked) "Read and opened" else if (notification.isRead) "Read" else "Unread", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

internal fun notificationAvatarUrl(baseUrl: String, path: String?): String? = path?.takeIf { it.isNotBlank() }?.let {
    baseUrl.toHttpUrlOrNull()?.resolve(it)?.toString()
}

private fun notificationDay(iso: String): String = runCatching {
    val date = Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = java.time.LocalDate.now()
    when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }
}.getOrDefault("Earlier")

internal fun notificationTime(iso: String): String = runCatching {
    Instant.parse(iso).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
}.getOrDefault(iso)
