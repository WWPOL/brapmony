package lol.mony.harmony.ui.screens.drive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import java.time.Instant
import kotlinx.coroutines.CancellationException
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.model.DriveFile
import lol.mony.harmony.data.model.DriveFileAction
import lol.mony.harmony.data.model.DriveFileUsage
import lol.mony.harmony.data.model.DriveFolder
import lol.mony.harmony.data.model.DriveLinkState
import lol.mony.harmony.data.model.DriveMessageUsage
import lol.mony.harmony.data.model.DrivePostUsage
import lol.mony.harmony.data.model.DriveShare
import lol.mony.harmony.data.model.actions
import lol.mony.harmony.data.model.driveBreadcrumbs
import lol.mony.harmony.data.model.driveMoveTargets
import lol.mony.harmony.data.repo.driveLinkUrl

@Composable
internal fun DriveNameDialog(initial: String, title: String, folder: Boolean, busy: Boolean, error: String?,
    onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(initial, title) { mutableStateOf(initial) }
    val valid = name.trim().isNotEmpty() && (!folder || (name.trim().length <= 120 && '/' !in name && '\\' !in name))
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true,
                enabled = !busy, isError = name.isNotEmpty() && !valid)
            if (folder) Text("1 to 120 characters, without slashes.", style = MaterialTheme.typography.bodySmall)
            error?.let { DriveError(it) }
        }
    }, confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = valid && !busy) { Text(if (busy) "Saving…" else "Save") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } })
}

@Composable
internal fun DriveMoveDialog(folders: List<DriveFolder>, movingFolderId: String?, busy: Boolean, error: String?,
    onDismiss: () -> Unit, onMove: (String?) -> Unit) {
    val targets = remember(folders, movingFolderId) { driveMoveTargets(folders, movingFolderId) }
    var selected by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Move to folder") }, text = {
        Column(Modifier.heightIn(max = androidx.compose.ui.unit.Dp(420f)).verticalScroll(rememberScrollState())) {
            TextButton(onClick = { selected = null }, enabled = !busy) { Text(if (selected == null) "✓ My Drive" else "My Drive") }
            targets.forEach { folder ->
                TextButton(onClick = { selected = folder.id }, enabled = !busy) {
                    Text((if (selected == folder.id) "✓ " else "") + driveBreadcrumbs(folders, folder.id).joinToString(" / ") { it.name })
                }
            }
            error?.let { DriveError(it) }
        }
    }, confirmButton = { TextButton(onClick = { onMove(selected) }, enabled = !busy) { Text(if (busy) "Moving…" else "Move here") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } })
}

@Composable
internal fun DriveConfirmDialog(title: String, description: String, busy: Boolean, error: String?,
    onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(description)
            error?.let { DriveError(it) }
        }
    }, confirmButton = { TextButton(onClick = onConfirm, enabled = !busy) {
        Text(if (busy) "Deleting…" else "Confirm", color = MaterialTheme.colorScheme.error)
    } }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } })
}

@Composable
internal fun DriveDetailsDialog(
    file: DriveFile,
    profileId: String?,
    linkSharing: Boolean,
    loadUsage: suspend () -> DriveFileUsage,
    onDismiss: () -> Unit,
    onOpenMessage: ((DriveMessageUsage) -> Unit)?,
    onOpenPost: ((DrivePostUsage) -> Unit)?,
    onRevokeShare: (DriveShare) -> Unit,
    onAction: (DriveFileAction) -> Unit,
    busy: Boolean,
    error: String?,
) {
    var usage by remember(file.id) { mutableStateOf<DriveFileUsage?>(null) }
    var usageError by remember(file.id) { mutableStateOf<String?>(null) }
    var revision by remember(file.id) { mutableIntStateOf(0) }
    val own = file.ownerId == profileId
    LaunchedEffect(file.id, revision) {
        if (!own) return@LaunchedEffect
        usageError = null
        try { usage = loadUsage() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { usageError = failure.message ?: "File usage could not load." }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(file.displayName) }, text = {
        Column(Modifier.heightIn(max = androidx.compose.ui.unit.Dp(480f)).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(androidx.compose.ui.unit.Dp(8f))) {
            Text("${driveSize(file.sizeBytes)} · ${file.mimeType ?: file.kind}")
            Text(when (file.state) {
                "drive" -> "Kept in Drive"
                "ephemeral" -> "Temporary — expires ${file.expiresAt ?: "on the instance's schedule"}"
                "trashed" -> "In trash — restore or delete permanently"
                "gone" -> "File no longer available: ${file.goneReason ?: "removed"}"
                else -> "Unavailable file state"
            })
            Text("Created ${file.createdAt}", style = MaterialTheme.typography.bodySmall)
            Text(when (file.photoDetails) {
                "removed" -> "Photo details were removed before upload."
                "kept" -> if (file.photoLocation == true) "Photo details kept, including location." else "Photo details kept."
                "none" -> "The upload carried no photo details."
                else -> "Photo details are unknown."
            }, style = MaterialTheme.typography.bodySmall)
            if (!own) Text("Shared with you. Only the owner can change this file.")
            if (own) {
                HorizontalDivider()
                Text("Where used", style = MaterialTheme.typography.titleSmall)
                if (usage == null && usageError == null) CircularProgressIndicator()
                usageError?.let {
                    DriveError(it)
                    TextButton(onClick = { revision++ }) { Text("Try again") }
                }
                usage?.let { details ->
                    if (details.messages.isEmpty() && details.posts.isEmpty()) Text("No current messages or posts use this file.")
                    details.messages.forEach { message ->
                        val label = message.channelName?.let { "#${it}" } ?: message.conversationName ?: "Direct conversation"
                        if (onOpenMessage == null) Text(label) else TextButton(onClick = { onDismiss(); onOpenMessage(message) }) { Text(label) }
                    }
                    details.posts.forEach { post ->
                        val label = "Post (${post.visibility ?: "unknown visibility"})"
                        if (onOpenPost == null) Text(label) else TextButton(onClick = { onDismiss(); onOpenPost(post) }) { Text(label) }
                    }
                    val shares = details.shares.filter { it.scope != "link" }
                    if (shares.isNotEmpty()) Text("Explicit access", style = MaterialTheme.typography.titleSmall)
                    shares.forEach { share ->
                        Text("${share.scope}: ${share.targetName ?: "Shared recipient"}" + (share.expiresAt?.let { " (expires $it)" } ?: ""))
                        TextButton(onClick = { onRevokeShare(share) }, enabled = !busy) { Text("Revoke access") }
                    }
                }
            }
            HorizontalDivider()
            file.actions(profileId, linkSharing).forEach { action ->
                TextButton(onClick = { onAction(action) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(when (action) {
                        DriveFileAction.Preview -> "Preview image"
                        DriveFileAction.Save -> "Save to device"
                        DriveFileAction.Rename -> "Rename"
                        DriveFileAction.Move -> "Move to folder"
                        DriveFileAction.Trash -> "Move to trash"
                        DriveFileAction.Restore -> "Restore"
                        DriveFileAction.DeleteForever -> "Delete permanently"
                        DriveFileAction.Keep -> "Keep in Drive"
                        DriveFileAction.Link -> "Link access"
                    })
                }
            }
            error?.let { DriveError(it) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") } })
}

@Composable
internal fun DrivePreviewDialog(file: DriveFile, load: suspend () -> String, onDismiss: () -> Unit) {
    var url by remember(file.id) { mutableStateOf<String?>(null) }
    var failure by remember(file.id) { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(file.id, revision) {
        url = null
        failure = null
        try { url = load() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure = error.message ?: "The file could not load." }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(androidx.compose.ui.unit.Dp(16f))) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(file.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when {
                        failure != null -> Column {
                            DriveError(failure!!)
                            Button(onClick = { revision++ }) { Text("Try again") }
                        }
                        url == null -> CircularProgressIndicator()
                        else -> AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(url)
                            .diskCachePolicy(CachePolicy.DISABLED).memoryCachePolicy(CachePolicy.DISABLED).build(),
                            contentDescription = file.displayName, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                            onError = { failure = "The image could not be displayed. Refresh its access and try again." })
                    }
                }
            }
        }
    }
}

@Composable
internal fun DriveLinkDialog(
    file: DriveFile,
    endpoint: Endpoint,
    linkSharing: Boolean,
    busy: Boolean,
    error: String?,
    load: suspend () -> DriveLinkState,
    onDismiss: () -> Unit,
    onSave: (Boolean, String?, Boolean) -> Unit,
    onReset: () -> Unit,
) {
    var link by remember(file.id) { mutableStateOf<DriveLinkState?>(null) }
    var public by remember(file.id) { mutableStateOf(false) }
    var showOwner by remember(file.id) { mutableStateOf(false) }
    var loadError by remember(file.id) { mutableStateOf<String?>(null) }
    var expiry by remember(file.id) { mutableIntStateOf(-1) }
    var revision by remember(file.id) { mutableIntStateOf(0) }
    var resetConfirm by remember(file.id) { mutableStateOf(false) }
    var copied by remember(file.id) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(file.id, revision) {
        loadError = null
        try {
            val result = load()
            link = result
            public = result.isPublic
            showOwner = result.showOwner
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { loadError = failure.message ?: "Link access could not load." }
    }
    val choices = listOf(-1 to "Keep current expiry", 0 to "No expiry", 1 to "In one hour", 24 to "In one day", 168 to "In seven days")
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Link access") }, text = {
        Column(Modifier.heightIn(max = androidx.compose.ui.unit.Dp(480f)).verticalScroll(rememberScrollState())) {
            Text(file.displayName, style = MaterialTheme.typography.titleSmall)
            val shownError = loadError
            if (shownError != null) {
                DriveError(shownError)
                TextButton(onClick = { revision++ }) { Text("Try again") }
            } else if (link == null) CircularProgressIndicator()
            else {
                Text(if (public) "Anyone holding the address can open this file, even without an account." else "Restricted. Only accounts with existing access can open this address.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // An instance switch must not prevent restricting an already-public file.
                    val canChangeAccess = !busy && (linkSharing || public)
                    Checkbox(checked = public, onCheckedChange = { public = it }, enabled = canChangeAccess)
                    Text("Anyone with the link", modifier = Modifier.clickable(enabled = canChangeAccess) { public = !public })
                }
                if (!linkSharing) Text("This instance has turned off public link sharing.")
                if (public) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = showOwner, onCheckedChange = { showOwner = it }, enabled = !busy)
                        Text("Show my name", modifier = Modifier.clickable(enabled = !busy) { showOwner = !showOwner })
                    }
                    var expiryOpen by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { expiryOpen = true }, enabled = !busy) { Text(choices.first { it.first == expiry }.second) }
                        DropdownMenu(expiryOpen, onDismissRequest = { expiryOpen = false }) {
                            choices.forEach { (hours, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { expiry = hours; expiryOpen = false }) }
                        }
                    }
                    link?.linkExpiresAt?.let { Text("Current expiry: $it", style = MaterialTheme.typography.bodySmall) }
                }
                val address = link?.let { driveLinkUrl(endpoint, it.token) }
                TextButton(onClick = { if (address != null) { clipboard.setText(AnnotatedString(address)); copied = true } },
                    enabled = !busy && address != null) { Text(if (copied) "Address copied" else "Copy address") }
                Text("Copying the address does not change access.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { resetConfirm = true }, enabled = !busy) { Text("Reset address") }
            }
            error?.let { DriveError(it) }
        }
    }, confirmButton = { TextButton(enabled = link != null && loadError == null && !busy, onClick = {
        val expiresAt = if (!public) null else when (expiry) {
            -1 -> link?.linkExpiresAt
            0 -> null
            else -> Instant.now().plusSeconds(expiry * 3600L).toString()
        }
        onSave(public, expiresAt, showOwner)
    }) { Text(if (busy) "Saving…" else "Save access") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } })
    if (resetConfirm) DriveConfirmDialog("Reset file address?", "The old address will stop working for everyone. Existing direct room access is unchanged.", busy, error,
        onDismiss = { if (!busy) resetConfirm = false }, onConfirm = onReset)
}
