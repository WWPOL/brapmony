@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package lol.mony.harmony.ui.screens.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import lol.mony.harmony.data.model.*
import lol.mony.harmony.data.session.AuthState
import lol.mony.harmony.ui.AppState
import lol.mony.harmony.ui.components.*
import lol.mony.harmony.ui.theme.VisualThemeSettings
import lol.mony.harmony.ui.screens.server.CreateOrJoinServerDialog
import lol.mony.harmony.ui.screens.server.ServerManagementScreen

@Composable
fun ChatWorkspace(state: AppState, appearance: VisualThemeSettings) {
    val serverId by state.selectedServerId.collectAsState()
    val showingDms by state.showingDms.collectAsState()
    val channelId by state.openChannelId.collectAsState()
    val conversationId by state.openConversationId.collectAsState()
    val createServer by state.showCreateServer.collectAsState()
    if (createServer) CreateOrJoinServerDialog(state,
        onDismiss = { state.showCreateServer.value = false },
        onJoined = { id ->
            state.showCreateServer.value = false
            state.selectedServerId.value = id
            state.showingDms.value = false
            state.serversRevision.value++
        })
    when {
        channelId != null -> RoomScreen(state, channelId!!, false, appearance,
            onBack = { state.openChannelId.value = null })
        conversationId != null -> RoomScreen(state, conversationId!!, true, appearance,
            onBack = { state.openConversationId.value = null })
        else -> Row(Modifier.fillMaxSize()) {
            ServerRail(state)
            if (showingDms || serverId == null) DirectMessages(state, Modifier.weight(1f))
            else ChannelList(state, serverId!!, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ChannelList(state: AppState, serverId: String, modifier: Modifier) {
    var tree by remember(serverId) { mutableStateOf<ServerTree?>(null) }
    var failure by remember(serverId) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var voice by remember { mutableStateOf<Channel?>(null) }
    var management by remember { mutableStateOf(false) }
    val unread by state.unread.collectAsState()
    if (management) {
        BackHandler { management = false }
        ServerManagementScreen(state, serverId, onBack = { management = false },
            onChanged = { retry++; state.serversRevision.value++ })
        return
    }
    LaunchedEffect(serverId, retry) {
        try {
            failure = null
            val session = state.auth.state.value as? AuthState.SignedIn ?: return@LaunchedEffect
            val token = state.auth.freshToken() ?: error("Your session expired. Sign in again.")
            val server = state.servers.server(session.endpoint, token, serverId) ?: error("This server is no longer available.")
            tree = state.servers.tree(session.endpoint, token, server).getOrThrow()
            state.channelsByServer.value = state.channelsByServer.value + (serverId to tree!!.channels)
        } catch (error: Exception) { failure = error.message }
    }
    Column(modifier.fillMaxHeight()) {
        TopAppBar(title = { Text(tree?.server?.name ?: "Channels", maxLines = 1) },
            actions = {
                IconButton(onClick = { retry++ }) { Icon(Icons.Default.Refresh, "Refresh channels") }
                IconButton(onClick = { management = true }) { Icon(Icons.Default.Settings, "Server settings and members") }
            })
        when {
            failure != null -> ErrorState(failure!!) { retry++ }
            tree == null -> LoadingState("Loading channels")
            else -> {
                val loaded = tree!!
                LazyColumn(Modifier.fillMaxSize().testTag("channel-list"), contentPadding = PaddingValues(bottom = 12.dp)) {
                    if (!loaded.server.description.isNullOrBlank()) item {
                        Text(loaded.server.description!!, Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val groups = listOf<ChannelCategory?>(null) + loaded.categories
                    for (category in groups) {
                        val channels = loaded.channelsIn(category?.id)
                        if (channels.isNotEmpty() && category != null) item(key = category.id) {
                            Text(category.name, Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp),
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(channels, key = { it.id }) { channel ->
                            ListItem(headlineContent = { Text(channel.name, fontWeight = if (unread.channel(channel.id) > 0)
                                FontWeight.SemiBold else FontWeight.Normal) },
                                leadingContent = { Icon(if (channel.channelType.isVoice) Icons.Default.VolumeUp else Icons.Default.Tag, null) },
                                supportingContent = channel.description?.let { description -> ({ Text(description, maxLines = 2) }) },
                                trailingContent = if (unread.channel(channel.id) > 0) ({ Badge { Text("${unread.channel(channel.id)}") } }) else null,
                                modifier = Modifier.testTag("channel-${channel.name}").clickable {
                                    if (channel.channelType.isVoice) voice = channel else state.openChannelId.value = channel.id
                                })
                        }
                    }
                    if (loaded.channels.isEmpty()) item {
                        EmptyState("No channels", "There are no channels you can view in this server.")
                    }
                }
            }
        }
    }
    if (voice != null) AlertDialog(onDismissRequest = { voice = null },
        title = { Text(voice!!.name) },
        text = { Text("Voice and video are not implemented in this native client yet. No call was joined.") },
        confirmButton = { TextButton(onClick = { voice = null }) { Text("Close") } })
}

@Composable
private fun DirectMessages(state: AppState, modifier: Modifier) {
    var rows by remember { mutableStateOf<List<DirectConversation>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var start by remember { mutableStateOf(false) }
    var handle by remember { mutableStateOf("") }
    var starting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val unread by state.unread.collectAsState()
    LaunchedEffect(retry) {
        try {
            failure = null
            val session = state.auth.state.value as? AuthState.SignedIn ?: return@LaunchedEffect
            val token = state.auth.freshToken() ?: error("Sign in again.")
            rows = state.chats.conversations(session.endpoint, token, session.profile!!.id, state.profiles).getOrThrow()
        } catch (error: Exception) { failure = error.message }
    }
    Column(modifier.fillMaxHeight()) {
        TopAppBar(title = { Text("Messages") }, actions = {
            IconButton(onClick = { retry++ }) { Icon(Icons.Default.Refresh, "Refresh conversations") }
            IconButton(onClick = { start = true }) { Icon(Icons.Default.Edit, "New message") }
        })
        val openFailure = failure
        if (openFailure != null) ErrorBanner(openFailure) { failure = null }
        when {
            rows == null && failure == null -> LoadingState("Loading conversations")
            rows.isNullOrEmpty() -> EmptyState("Your conversations", "Start a conversation with someone on your instance.", "New message") { start = true }
            // The list is drawn after this body returns, so it closes over the rows it was
            // given rather than re-reading the nullable state they came from.
            else -> LazyColumn(Modifier.fillMaxSize().testTag("conversation-list")) {
                items(rows.orEmpty(), key = { it.conversation.id }) { row ->
                    ListItem(headlineContent = { Text(row.title) },
                        supportingContent = { Text(if (row.conversation.type == "group") "Group conversation" else "Direct message") },
                        leadingContent = { Avatar(row.title, row.participants.firstOrNull()?.avatarUrl) },
                        trailingContent = if (unread.conversation(row.conversation.id) > 0) ({ Badge { Text("${unread.conversation(row.conversation.id)}") } }) else null,
                        modifier = Modifier.clickable { state.openConversationId.value = row.conversation.id })
                }
            }
        }
    }
    if (start) AlertDialog(onDismissRequest = { if (!starting) start = false }, title = { Text("New message") },
        text = { Column {
            OutlinedTextField(handle, { handle = it }, label = { Text("Username or @handle") }, enabled = !starting,
                singleLine = true, modifier = Modifier.testTag("new-message-handle"))
            val dialogFailure = failure
            if (dialogFailure != null) Text(dialogFailure, color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(enabled = handle.isNotBlank() && !starting, onClick = {
            starting = true
            scope.launch {
                try {
                    val session = state.auth.state.value as? AuthState.SignedIn ?: error("Sign in again.")
                    val token = state.auth.freshToken() ?: error("Sign in again.")
                    val other = state.profiles.byHandle(session.endpoint, token, handle) ?: error("No matching profile was found.")
                    val id = state.chats.openDirectConversation(session.endpoint, token, session.profile!!.id, other.id).getOrThrow()
                    start = false; state.openConversationId.value = id
                } catch (error: Exception) { failure = error.message } finally { starting = false }
            }
        }) { Text(if (starting) "Opening…" else "Open conversation") } },
        dismissButton = { TextButton(onClick = { start = false }, enabled = !starting) { Text("Cancel") } })
}
