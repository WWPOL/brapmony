package lol.mony.harmony.ui.screens.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import lol.mony.harmony.data.model.*
import lol.mony.harmony.data.repo.ServerManagementRepository
import lol.mony.harmony.ui.AppState
import lol.mony.harmony.ui.theme.LocalHarmonyColors

/** Independently embeddable native server area; the parent shell owns navigation and container. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerManagementScreen(state: AppState, serverId: String, onBack: () -> Unit = {}, onChanged: () -> Unit = {}) {
    val repository = remember(state.container.rest) { ServerManagementRepository(state.container.rest) }
    var overview by remember(state, serverId) { mutableStateOf<ServerManagementOverview?>(null) }
    var error by remember(state, serverId) { mutableStateOf<String?>(null) }
    var loading by remember(state, serverId) { mutableStateOf(true) }
    var revision by remember(serverId) { mutableIntStateOf(0) }
    var section by rememberSaveable(serverId) { mutableIntStateOf(0) }
    LaunchedEffect(state, serverId, revision) {
        loading = true; error = null
        try {
            val access = state.serverManagementAccess(repository)
            overview = repository.overview(access.endpoint, access.token, access.profileId, serverId).getOrThrow()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Could not load this server." }
        finally { loading = false }
    }
    fun changed() { revision += 1; state.serverManagementChanged(); onChanged() }

    Surface(color = LocalHarmonyColors.current.surfaceChat, modifier = Modifier.fillMaxSize()) {
        Column {
            TopAppBar(title = { Column {
                Text(overview?.server?.name ?: "Server", maxLines = 1)
                Text("Server management", style = MaterialTheme.typography.labelMedium)
            } }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { revision += 1 }, enabled = !loading) { Icon(Icons.Default.Refresh, "Refresh server") } })
            val data = overview
            if (data == null) {
                Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                    if (loading) CircularProgressIndicator()
                    error?.let { ServerManagementError(it, retry = { revision += 1 }) }
                }
            } else {
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Box(Modifier.padding(16.dp)) { ServerManagementError(it) } }
                val sections = listOf("Overview", "Channels", "Members", "Roles", "Invites", "Bans")
                ScrollableTabRow(selectedTabIndex = section, edgePadding = 8.dp) {
                    sections.forEachIndexed { index, label -> Tab(selected = section == index,
                        onClick = { section = index }, text = { Text(label) }) }
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.fillMaxWidth().widthIn(max = 840.dp)) {
                        when (section) {
                            0 -> ServerManagementOverviewPage(state, repository, data, revision, ::changed, onBack)
                            1 -> ServerManagementChannelsPage(state, repository, data, revision, ::changed)
                            2 -> ServerManagementMembersPage(state, repository, data, revision, ::changed)
                            3 -> ServerManagementRolesPage(state, repository, data, revision, ::changed)
                            4 -> ServerManagementInvitesPage(state, repository, data, revision, ::changed)
                            5 -> ServerManagementBansPage(state, repository, data, revision, ::changed)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerManagementOverviewPage(state: AppState, repository: ServerManagementRepository, overview: ServerManagementOverview,
    revision: Int, onChanged: () -> Unit, onBack: () -> Unit) {
    val server = overview.server
    val actions = rememberServerManagementActions()
    var name by rememberSaveable(server.id, revision) { mutableStateOf(server.name) }
    var description by rememberSaveable(server.id, revision) { mutableStateOf(server.description.orEmpty()) }
    var isPublic by rememberSaveable(server.id, revision) { mutableStateOf(server.public) }
    var rules by rememberSaveable(server.id, revision) { mutableStateOf(server.rules.joinToString("\n")) }
    var confirmLeave by remember { mutableStateOf(false) }
    val ruleLines = rules.lines().map(String::trim).filter(String::isNotEmpty)
    val dirty = name != server.name || description != server.description.orEmpty() || isPublic != server.public || ruleLines != server.rules
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("About this server", style = MaterialTheme.typography.headlineSmall)
        ServerManagementNote("${server.memberCount} members${if (overview.isOwner) " • You own this server" else ""}")
        if (!server.isLocal) ServerManagementNote("Hosted on ${server.hostDomain ?: "another instance"}. Server settings, channels, roles and moderation must be managed on its home instance. Federated leaving is not available here yet.")
        if (server.suspendedAt != null) ServerManagementError("This server is suspended. Management actions are unavailable.")
        if (server.isLocal && !overview.canManage) ServerManagementNote("You can view this server. Changing its details requires Manage server permission.")
        ServerManagementFeedback(actions)
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Server name") },
            enabled = overview.canManage && !actions.busy, modifier = Modifier.fillMaxWidth(), singleLine = true,
            isError = name.isBlank() || name.length > 100)
        OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") },
            enabled = overview.canManage && !actions.busy, modifier = Modifier.fillMaxWidth(), minLines = 2,
            isError = description.length > 500, supportingText = { Text("${description.length}/500") })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Public server", style = MaterialTheme.typography.titleMedium)
                ServerManagementNote("Allow people on this instance to discover and join.") }
            Switch(checked = isPublic, onCheckedChange = { isPublic = it }, enabled = overview.canManage && !actions.busy)
        }
        OutlinedTextField(value = rules, onValueChange = { rules = it }, label = { Text("Server rules") },
            enabled = overview.canManage && !actions.busy, modifier = Modifier.fillMaxWidth(), minLines = 3,
            isError = ruleLines.size > 25, supportingText = { Text("One rule per line, up to 25 rules.") })
        if (overview.canManage) Button(onClick = { actions.launch {
            val access = state.serverManagementAccess(repository)
            repository.saveInfo(access.endpoint, access.token, server.id, name, description, isPublic, ruleLines).getOrThrow()
            onChanged()
        } }, enabled = dirty && !actions.busy && validateServerManagementInfo(name, description, false) == null && ruleLines.size <= 25) { Text("Save changes") }
        HorizontalDivider()
        Text("Not yet available here", style = MaterialTheme.typography.titleMedium)
        ServerManagementNote("Server image uploads, welcome screens, emoji, stickers, soundboard, encryption, federation controls, AutoMod, reports and audit logs are not implemented in the native client. Existing values are kept unchanged.")
        OutlinedButton(onClick = {}, enabled = false) { Text("Advanced server settings") }
        HorizontalDivider()
        if (!overview.isOwner) OutlinedButton(onClick = { confirmLeave = true },
            enabled = server.isLocal && overview.membership?.status == "accepted" && !actions.busy) { Text("Leave server") }
        else ServerManagementNote("Ownership transfer and server deletion are not available in the native client. The owner cannot leave a server.")
    }
    if (confirmLeave) ServerManagementConfirm("Leave ${server.name}?", "You will lose access to its channels. You will need a public listing or a new invite to return.",
        "Leave server", actions.busy, actions.error, { confirmLeave = false }) { actions.launch {
        val access = state.serverManagementAccess(repository)
        repository.leaveServer(access.endpoint, access.token, server.id, access.profileId).getOrThrow()
        if (state.selectedServerId.value == server.id) {
            state.selectedServerId.value = null; state.showingDms.value = true
            state.openChannelId.value = null
        }
        state.channelsByServer.value = state.channelsByServer.value - server.id
        state.serverManagementChanged(); onBack()
    } }
}

/** Shared paged list: bounded requests, stable ordering, real error states and an explicit next page. */
@Composable
internal fun <T> ServerManagementPagedList(key: String, revision: Int, emptyText: String,
    load: suspend (Int) -> ServerManagementPage<T>, itemKey: (T) -> String,
    header: @Composable () -> Unit = {}, row: @Composable (T) -> Unit) {
    var rows by remember(key, revision) { mutableStateOf<List<T>>(emptyList()) }
    var more by remember(key, revision) { mutableStateOf(false) }
    var loading by remember(key, revision) { mutableStateOf(true) }
    var error by remember(key, revision) { mutableStateOf<String?>(null) }
    val currentLoad by rememberUpdatedState(load)
    val scope = rememberCoroutineScope()
    suspend fun fetch(append: Boolean) {
        loading = true; error = null
        try {
            val result = currentLoad(if (append) rows.size else 0)
            rows = (if (append) rows else emptyList()) + result.items
            more = result.hasMore
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Could not load this list." }
        finally { loading = false }
    }
    LaunchedEffect(key, revision) { fetch(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { header() }
        if (error != null) item { ServerManagementError(error.orEmpty()) { if (!loading) scope.launch { fetch(rows.isNotEmpty()) } } }
        if (rows.isEmpty() && !loading && error == null) item { ServerManagementNote(emptyText) }
        items(rows, key = itemKey) { row(it) }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (more && !loading) item { TextButton(onClick = { scope.launch { fetch(true) } }) { Text("Load more") } }
    }
}
