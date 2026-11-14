package lol.mony.harmony.ui.screens.drive

import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.model.DriveContextTree
import lol.mony.harmony.data.model.DriveFile
import lol.mony.harmony.data.model.DriveFileAction
import lol.mony.harmony.data.model.DriveFileUsage
import lol.mony.harmony.data.model.DriveFolder
import lol.mony.harmony.data.model.DriveKind
import lol.mony.harmony.data.model.DriveLinkState
import lol.mony.harmony.data.model.DriveListRow
import lol.mony.harmony.data.model.DriveMessageUsage
import lol.mony.harmony.data.model.DrivePage
import lol.mony.harmony.data.model.DrivePostUsage
import lol.mony.harmony.data.model.DriveQuery
import lol.mony.harmony.data.model.DriveSort
import lol.mony.harmony.data.model.DriveUsage
import lol.mony.harmony.data.model.DriveView
import lol.mony.harmony.data.model.actions
import lol.mony.harmony.data.model.driveBreadcrumbs
import lol.mony.harmony.data.model.driveMoveTargets
import lol.mony.harmony.data.repo.DriveRepository
import lol.mony.harmony.data.repo.driveDownloadName
import lol.mony.harmony.data.repo.driveLinkUrl
import lol.mony.harmony.data.session.AuthState
import lol.mony.harmony.ui.AppState

/**
 * Native Drive browser/owner management, not a placeholder for uploads or complete media parity.
 * The parent shell only needs to call DriveScreen(state, onBack); it owns navigation.
 * Optional usage callbacks are omitted as buttons when the shell cannot navigate that target.
 */
@Composable
fun DriveScreen(
    state: AppState,
    onBack: () -> Unit,
    onOpenMessage: ((DriveMessageUsage) -> Unit)? = null,
    onOpenPost: ((DrivePostUsage) -> Unit)? = null,
) {
    val auth by state.auth.state.collectAsState()
    val signedIn = auth as? AuthState.SignedIn
    if (signedIn == null) {
        Column(Modifier.padding(24.dp)) {
            Text("Sign in to open Drive.", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onBack) { Text("Back") }
        }
        return
    }
    // Neither in-flight results nor private file names survive an account/instance change.
    key(signedIn.endpoint.baseUrl, signedIn.user.id) {
        DriveBrowser(state, signedIn, onBack, onOpenMessage, onOpenPost)
    }
}

private data class DriveSnapshot(
    val usage: DriveUsage,
    val folders: List<DriveFolder>,
    val contexts: DriveContextTree,
    val page: DrivePage,
)

private sealed interface DriveDialog {
    data class Details(val file: DriveFile) : DriveDialog
    data class Preview(val file: DriveFile) : DriveDialog
    data class Name(val file: DriveFile? = null, val folder: DriveFolder? = null) : DriveDialog
    data class Move(val file: DriveFile? = null, val folder: DriveFolder? = null) : DriveDialog
    data class Remove(val file: DriveFile? = null, val folder: DriveFolder? = null) : DriveDialog
    data class Link(val file: DriveFile) : DriveDialog
    data object EmptyTrash : DriveDialog
}

@Composable
private fun DriveBrowser(
    state: AppState,
    session: AuthState.SignedIn,
    onBack: () -> Unit,
    onOpenMessage: ((DriveMessageUsage) -> Unit)?,
    onOpenPost: ((DrivePostUsage) -> Unit)?,
) {
    val repository = remember(state) { DriveRepository(state.container.rest) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val snackbar = remember { SnackbarHostState() }
    val profileId = session.profile?.id
    var query by remember { mutableStateOf(DriveQuery()) }
    var search by remember { mutableStateOf("") }
    var contextName by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf<DriveSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<DriveDialog?>(null) }
    var mutationError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<DriveFile?>(null) }

    suspend fun <T> request(call: suspend (Endpoint, String) -> Result<T>): T {
        fun sameAccount(): Boolean {
            val current = state.auth.state.value as? AuthState.SignedIn ?: return false
            return current.user.id == session.user.id && current.endpoint == session.endpoint
        }
        check(sameAccount()) { "Sign in again to open Drive." }
        val token = state.auth.freshToken() ?: error("Your session expired. Sign in again.")
        val result = call(session.endpoint, token)
        if (!sameAccount()) throw CancellationException("The signed-in account changed.")
        return result.getOrThrow()
    }

    fun mutate(operation: suspend () -> String) {
        if (busy) return
        busy = true
        mutationError = null
        scope.launch {
            try {
                val message = operation()
                dialog = null
                revision++
                snackbar.showSnackbar(message)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutationError = failure.message ?: "Drive could not save this change. Try again."
            } finally {
                busy = false
            }
        }
    }

    val saveDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val file = pendingSave
        pendingSave = null
        if (uri != null && file != null && !saving) {
            saving = true
            scope.launch {
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                            request { endpoint, token -> repository.saveTo(endpoint, token, file, output) }
                        } ?: error("The selected location cannot be written.")
                    }
                    snackbar.showSnackbar("Saved ${file.displayName} (${driveSize(bytes)}).")
                } catch (cancelled: CancellationException) {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                    }
                    throw cancelled
                } catch (failure: Exception) {
                    val removed = withContext(Dispatchers.IO) {
                        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
                    }
                    snackbar.showSnackbar((failure.message ?: "The file could not be saved.") +
                        if (removed) "" else " Remove the incomplete document from the selected location.")
                } finally {
                    saving = false
                }
            }
        }
    }

    LaunchedEffect(search) {
        delay(300)
        query = query.copy(search = search, offset = 0)
    }
    LaunchedEffect(query, revision) {
        loading = true
        error = null
        snapshot = null
        try {
            val usage = request(repository::usage)
            if (!usage.enabled) {
                snapshot = DriveSnapshot(usage, emptyList(), DriveContextTree(), DrivePage(emptyList(), 0))
                return@LaunchedEffect
            }
            val folders = request(repository::folders)
            val contexts = request(repository::contexts)
            // Official Drive searches all folders when searching from the folder browser.
            val actual = if (query.view == DriveView.Folder && query.search.isNotBlank()) {
                query.copy(view = DriveView.All, folderId = null)
            } else query
            val page = request { endpoint, token -> repository.files(endpoint, token, actual) }
            snapshot = DriveSnapshot(usage, folders, contexts, page)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Drive could not load. Check your connection and try again."
        } finally {
            loading = false
        }
    }

    fun open(next: DriveDialog) {
        mutationError = null
        dialog = next
    }
    fun go(view: DriveView, folderId: String? = null, contextId: String? = null, name: String? = null) {
        search = ""
        contextName = name
        dialog = null
        query = DriveQuery(view = view, folderId = folderId, contextId = contextId, kind = query.kind, sort = query.sort)
    }
    fun back() {
        if (query.view == DriveView.Folder && query.folderId != null) {
            go(DriveView.Folder, snapshot?.folders?.find { it.id == query.folderId }?.parentId)
        } else if (query.view != DriveView.Folder || search.isNotEmpty()) go(DriveView.Folder)
        else onBack()
    }
    BackHandler(onBack = ::back)

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    Text("Drive", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    Text("Files you keep and share in Harmony", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { revision++ }, enabled = !loading && !busy) { Icon(Icons.Outlined.Refresh, "Refresh Drive") }
            }
            val data = snapshot
            if (data != null && !data.usage.enabled) {
                DriveNotice("Drive is turned off on this instance.", "Your existing attachments remain governed by the instance's upload and room rules.")
                return@Column
            }
            if (data != null) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    val used = data.usage.usedBytes
                    val quota = data.usage.quotaBytes
                    Text("${driveSize(used)} of ${driveSize(quota)} used", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { if (quota <= 0) 0f else (used.toDouble() / quota).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                DriveLocationPicker(query, contextName, data?.contexts ?: DriveContextTree(), ::go)
                Spacer(Modifier.weight(1f))
                if (data != null && query.view == DriveView.Folder && search.isBlank()) {
                    IconButton(onClick = { open(DriveDialog.Name()) }, enabled = !busy) {
                        Icon(Icons.Outlined.CreateNewFolder, "Create folder")
                    }
                }
                if (data != null && query.view == DriveView.Trash && data.page.entries.isNotEmpty()) {
                    TextButton(onClick = { open(DriveDialog.EmptyTrash) }, enabled = !busy) { Text("Empty trash") }
                }
                var sortOpen by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { sortOpen = true }) { Text(query.sort.label) }
                    DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                        DriveSort.entries.forEach { sort ->
                            DropdownMenuItem(text = { Text(sort.label) }, onClick = { sortOpen = false; query = query.copy(sort = sort) })
                        }
                    }
                }
            }
            if (data != null && query.view == DriveView.Folder && search.isBlank() && query.folderId != null) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { go(DriveView.Folder) }) { Text("My Drive") }
                    driveBreadcrumbs(data.folders, query.folderId).forEach { folder ->
                        Text("/")
                        TextButton(onClick = { go(DriveView.Folder, folder.id) }) { Text(folder.name) }
                    }
                }
            }
            OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text("Search files") }, singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); query = query.copy(search = search) }))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = query.kind == null, onClick = { query = query.copy(kind = null) }, label = { Text("All types") })
                DriveKind.entries.forEach { kind ->
                    FilterChip(selected = query.kind == kind, onClick = { query = query.copy(kind = kind) }, label = { Text(kind.label) })
                }
            }
            if (query.view == DriveView.Temporary) Text("Temporary files expire after ${data?.usage?.ephemeralDays ?: "the instance's configured"} days. Deleting one is permanent.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            if (query.view == DriveView.Trash) Text("Files in trash are deleted after 30 days. Deleting now cannot be undone.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            if (saving) Text("Saving file… Keep Drive open until the save finishes.", modifier = Modifier.padding(20.dp).semantics { liveRegion = LiveRegionMode.Polite })
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                error != null -> Column(Modifier.padding(20.dp)) {
                    DriveError(error!!)
                    Button(onClick = { revision++ }) { Text("Try again") }
                }
                data != null -> {
                    val folders = if (query.view == DriveView.Folder && search.isBlank()) data.folders.filter { it.parentId == query.folderId } else emptyList()
                    if (data.page.entries.isEmpty() && folders.isEmpty()) {
                        DriveNotice(if (search.isNotBlank()) "No matching files" else "No files here", if (search.isNotBlank()) "Try a different name or file type." else "Files uploaded in Harmony appear here. This native browser does not upload files yet.")
                    } else LazyColumn(Modifier.weight(1f)) {
                        items(folders, key = { "folder:${it.id}" }) { folder ->
                            var menu by remember(folder.id) { mutableStateOf(false) }
                            ListItem(headlineContent = { Text(folder.name) }, leadingContent = { Icon(Icons.Outlined.Folder, null) },
                                modifier = Modifier.clickable { go(DriveView.Folder, folder.id) },
                                trailingContent = { Box {
                                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Actions for ${folder.name}") }
                                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                        listOf("Rename" to DriveDialog.Name(folder = folder), "Move" to DriveDialog.Move(folder = folder), "Delete folder" to DriveDialog.Remove(folder = folder)).forEach { (label, action) ->
                                            DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; open(action) })
                                        }
                                    }
                                } })
                        }
                        items(data.page.entries, key = { "file:${it.file.id}" }) { entry ->
                            DriveFileRow(entry, onClick = { open(DriveDialog.Details(entry.file)) })
                        }
                        if (data.page.hasMore) item {
                            TextButton(enabled = !loading, modifier = Modifier.fillMaxWidth(), onClick = {
                                val expectedQuery = query
                                val expectedRevision = revision
                                loading = true
                                scope.launch {
                                    try {
                                        val actual = if (expectedQuery.view == DriveView.Folder && search.isNotBlank()) expectedQuery.copy(view = DriveView.All, folderId = null) else expectedQuery
                                        val next = request { endpoint, token -> repository.files(endpoint, token, actual.copy(offset = data.page.entries.size)) }
                                        if (query == expectedQuery && revision == expectedRevision) {
                                            val entries = (data.page.entries + next.entries).distinctBy { it.file.id }
                                            snapshot = data.copy(page = DrivePage(entries, if (next.entries.isEmpty()) entries.size.toLong() else next.total))
                                        }
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (failure: Exception) { snackbar.showSnackbar(failure.message ?: "More files could not load. Try again.") }
                                    finally { if (query == expectedQuery && revision == expectedRevision) loading = false }
                                }
                            }) { Text("Load more files") }
                        }
                    }
                }
            }
        }
    }

    val dismiss = { if (!busy) { dialog = null; mutationError = null } }
    when (val current = dialog) {
        is DriveDialog.Details -> DriveDetailsDialog(current.file, profileId, snapshot?.usage?.linkSharing == true,
            loadUsage = { request { endpoint, token -> repository.fileUsage(endpoint, token, current.file.id) } },
            onDismiss = dismiss, onOpenMessage = onOpenMessage, onOpenPost = onOpenPost,
            onRevokeShare = { share -> mutate {
                request { endpoint, token -> repository.unshare(endpoint, token, share.id) }
                "Access revoked. Existing message access is unchanged."
            } },
            onAction = { action -> when (action) {
                DriveFileAction.Preview -> open(DriveDialog.Preview(current.file))
                DriveFileAction.Save -> { pendingSave = current.file; saveDocument.launch(driveDownloadName(current.file.name)) }
                DriveFileAction.Rename -> open(DriveDialog.Name(file = current.file))
                DriveFileAction.Move -> open(DriveDialog.Move(file = current.file))
                DriveFileAction.Trash, DriveFileAction.DeleteForever -> open(DriveDialog.Remove(file = current.file))
                DriveFileAction.Link -> open(DriveDialog.Link(current.file))
                DriveFileAction.Keep, DriveFileAction.Restore -> mutate {
                    val changed = request { endpoint, token -> if (action == DriveFileAction.Keep) repository.keep(endpoint, token, listOf(current.file.id)) else repository.restore(endpoint, token, listOf(current.file.id)) }
                    check(changed == 1) { if (action == DriveFileAction.Keep) "The file was not kept. Check your storage quota and refresh Drive." else "The file changed or is no longer in trash. Refresh Drive." }
                    if (action == DriveFileAction.Keep) "File kept in Drive." else "File restored."
                }
            } }, busy = busy || saving, error = mutationError)
        is DriveDialog.Preview -> DrivePreviewDialog(current.file,
            load = { request { endpoint, token -> repository.viewUrl(endpoint, token, current.file) } }, onDismiss = dismiss)
        is DriveDialog.Name -> DriveNameDialog(
            initial = current.file?.name ?: current.folder?.name ?: "",
            title = if (current.file != null) "Rename file" else if (current.folder != null) "Rename folder" else "New folder",
            folder = current.file == null, busy = busy, error = mutationError, onDismiss = dismiss,
            onSave = { name -> mutate {
                request { endpoint, token -> when {
                    current.file != null -> repository.renameFile(endpoint, token, current.file.id, name)
                    current.folder != null -> repository.renameFolder(endpoint, token, current.folder.id, name)
                    else -> repository.createFolder(endpoint, token, name, query.folderId)
                } }
                if (current.file != null || current.folder != null) "Name saved." else "Folder created."
            } })
        is DriveDialog.Move -> DriveMoveDialog(snapshot?.folders.orEmpty(), current.folder?.id, busy, mutationError, dismiss) { destination ->
            mutate {
                request { endpoint, token ->
                    val file = current.file
                    if (file != null) {
                        repository.moveFiles(endpoint, token, listOf(file.id), destination).map { count -> check(count == 1) { "The file was not moved. Refresh Drive." } }
                    } else {
                        val folder = current.folder
                        if (folder == null) Result.success(0)
                        else repository.moveFolder(endpoint, token, folder.id, destination).map { 0 }
                    }
                }
                "Moved to folder."
            }
        }
        is DriveDialog.Remove -> {
            val permanent = current.file?.state != "drive" && current.file != null
            val title = if (current.folder != null) "Delete folder?" else if (permanent) "Delete file permanently?" else "Move file to trash?"
            val description = when {
                current.folder != null -> "Delete ${current.folder.name} and its subfolders. Kept files move to trash. Temporary files are deleted permanently. Existing messages can lose access to these files."
                permanent -> "Delete ${current.file?.displayName ?: "this file"} wherever it is used. This cannot be undone."
                else -> "Move ${current.file?.displayName ?: "this file"} to trash. Messages using this file can no longer open it. Trash is deleted after 30 days."
            }
            DriveConfirmDialog(title, description, busy, mutationError, dismiss) {
                mutate {
                    val file = current.file
                    val count = request { endpoint, token -> when {
                        current.folder != null -> repository.deleteFolder(endpoint, token, current.folder.id)
                        file != null && permanent -> repository.deleteForever(endpoint, token, listOf(file.id))
                        file != null -> repository.trash(endpoint, token, listOf(file.id))
                        else -> Result.success(0)
                    } }
                    if (current.folder == null) check(count == 1) { "The file changed or is no longer yours. Refresh Drive." }
                    if (current.folder != null) "Folder deleted; $count files affected." else if (permanent) "File deleted permanently." else "File moved to trash."
                }
            }
        }
        DriveDialog.EmptyTrash -> DriveConfirmDialog("Empty trash?", "Permanently delete all files in your trash. Messages using them will no longer open them. This cannot be undone.", busy, mutationError, dismiss) {
            mutate { val count = request(repository::emptyTrash); "$count files deleted permanently." }
        }
        is DriveDialog.Link -> DriveLinkDialog(current.file, session.endpoint, snapshot?.usage?.linkSharing == true, busy, mutationError,
            load = { request { endpoint, token -> repository.card(endpoint, token, current.file.id) }.let { card ->
                check(card.access && card.own) { "This file is no longer available to share." }
                card.linkState() ?: error("The instance did not return a file address.")
            } },
            onDismiss = dismiss,
            onSave = { public, expiresAt, showOwner -> mutate {
                request { endpoint, token -> repository.setLinkAccess(endpoint, token, current.file.id, public, expiresAt, showOwner) }
                "Link access saved."
            } },
            onReset = { mutate { request { endpoint, token -> repository.resetLink(endpoint, token, current.file.id) }; "File address reset. The old address no longer works." } })
        null -> Unit
    }
}

@Composable
private fun DriveLocationPicker(query: DriveQuery, contextName: String?, tree: DriveContextTree,
    onSelect: (DriveView, String?, String?, String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(contextName ?: query.view.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.heightIn(min = 24.dp))
            Icon(Icons.Outlined.ArrowDropDown, null)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 420.dp)) {
            DriveView.entries.filterNot { it.needsContext }.forEach { view ->
                DropdownMenuItem(text = { Text(view.label) }, onClick = { expanded = false; onSelect(view, null, null, null) })
            }
            tree.servers.forEach { server ->
                DropdownMenuItem(text = { Text("Server: ${server.name}") }, onClick = { expanded = false; onSelect(DriveView.Server, null, server.id, server.name) })
                server.channels.orEmpty().forEach { channel ->
                    DropdownMenuItem(text = { Text("${server.name} / #${channel.name}") }, onClick = { expanded = false; onSelect(DriveView.Channel, null, channel.id, "#${channel.name}") })
                }
            }
            tree.conversations.forEach { conversation ->
                val label = conversation.name ?: "Direct conversation"
                DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; onSelect(DriveView.Conversation, null, conversation.id, label) })
            }
        }
    }
}

@Composable
private fun DriveFileRow(entry: DriveListRow, onClick: () -> Unit) {
    val file = entry.file
    val icon = when (file.kind) {
        "image" -> Icons.Outlined.Image
        "video" -> Icons.Outlined.VideoFile
        "audio" -> Icons.Outlined.AudioFile
        else -> Icons.Outlined.Description
    }
    ListItem(headlineContent = { Text(file.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Column {
            Text("${driveSize(file.sizeBytes)} · ${file.kind}" + if (file.state == "ephemeral") " · Temporary" else "")
            entry.owner?.let { Text("Shared by ${it.displayName ?: it.username}") }
        } }, leadingContent = { Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable(onClick = onClick))
}

@Composable
private fun DriveNotice(title: String, detail: String) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun DriveError(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
}

internal fun driveSize(bytes: Long): String {
    if (bytes < 1024) return "${bytes.coerceAtLeast(0)} B"
    val (scale, unit) = when {
        bytes >= 1024L * 1024 * 1024 -> (1024.0 * 1024 * 1024) to "GiB"
        bytes >= 1024L * 1024 -> (1024.0 * 1024) to "MiB"
        else -> 1024.0 to "KiB"
    }
    return String.format(Locale.getDefault(), "%.1f %s", bytes / scale, unit)
}
