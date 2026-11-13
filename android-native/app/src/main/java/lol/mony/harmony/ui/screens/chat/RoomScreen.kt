@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package lol.mony.harmony.ui.screens.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import lol.mony.harmony.data.api.*
import lol.mony.harmony.data.model.*
import lol.mony.harmony.data.session.AuthState
import lol.mony.harmony.ui.AppState
import lol.mony.harmony.ui.components.*
import lol.mony.harmony.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun RoomScreen(state: AppState, roomId: String, direct: Boolean, appearance: VisualThemeSettings, onBack: () -> Unit) {
    val session = state.auth.state.collectAsState().value as? AuthState.SignedIn ?: return
    val myId = session.profile?.id ?: return
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val list = rememberLazyListState()
    var title by remember(roomId) { mutableStateOf(if (direct) "Conversation" else "Channel") }
    var thread by remember(roomId) { mutableStateOf<Thread?>(null) }
    val roomKey = roomId + (thread?.id ?: "")
    var messages by remember(roomKey) { mutableStateOf<List<Message>>(emptyList()) }
    var reactions by remember(roomKey) { mutableStateOf<Map<String, List<Reaction>>>(emptyMap()) }
    var loading by remember(roomKey) { mutableStateOf(true) }
    var olderAvailable by remember(roomKey) { mutableStateOf(true) }
    var draft by remember(roomKey) { mutableStateOf("") }
    var draftReady by remember(roomKey) { mutableStateOf(false) }
    var reply by remember(roomKey) { mutableStateOf<Message?>(null) }
    var sending by remember(roomKey) { mutableStateOf(false) }
    var failure by remember(roomKey) { mutableStateOf<String?>(null) }
    var selected by remember(roomKey) { mutableStateOf<Message?>(null) }
    var edit by remember(roomKey) { mutableStateOf<Message?>(null) }
    var editText by remember { mutableStateOf("") }
    var delete by remember { mutableStateOf<Message?>(null) }
    var pins by remember { mutableStateOf<List<Message>?>(null) }
    var threads by remember { mutableStateOf<List<Thread>?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searchRows by remember { mutableStateOf<List<Message>?>(null) }
    var live by remember(roomId) { mutableStateOf("Connecting to live updates…") }
    val people by state.people.collectAsState()
    val harmony = LocalHarmonyColors.current
    val sizes = LocalSizes.current

    suspend fun load(older: Boolean = false) {
        try {
            val token = state.auth.freshToken() ?: error("Your session expired. Sign in again.")
            val page = when {
                thread != null -> state.chats.threadMessages(session.endpoint, token, thread!!.id).getOrThrow()
                direct -> state.chats.directMessages(session.endpoint, token, roomId, if (older) messages.firstOrNull()?.createdAt else null).getOrThrow()
                else -> state.chats.channelMessages(session.endpoint, token, roomId, if (older) messages.firstOrNull()?.createdAt else null).getOrThrow()
            }
            messages = if (older) (page + messages).distinctBy { it.id } else if (messages.size > ChatRepositoryPage) {
                (messages.filter { old -> page.none { it.id == old.id } } + page).sortedBy { it.createdAt }
            } else page
            olderAvailable = page.size >= ChatRepositoryPage && thread == null
            state.rememberPeople(state.profiles.byIds(session.endpoint, token, messages.mapNotNull { it.userId }.distinct()).values)
            reactions = state.chats.reactionsFor(session.endpoint, token, messages.takeLast(50).map { it.id }).getOrThrow()
            if (!older) {
                val last = page.lastOrNull()?.id
                if (direct) {
                    state.chats.markConversationRead(session.endpoint, token, roomId, last)
                    state.clearConversationUnread(roomId)
                } else {
                    state.chats.markChannelRead(session.endpoint, token, roomId, last)
                    state.clearChannelUnread(roomId)
                }
            }
        } catch (error: Exception) { failure = error.message ?: "Could not load messages." }
        finally { loading = false }
    }
    suspend fun react(message: Message, emoji: String) {
        val token = state.auth.freshToken() ?: error("Sign in again.")
        val existing = reactions[message.id].orEmpty().any { it.userId == myId && it.emoji == emoji }
        if (existing) state.chats.removeReaction(session.endpoint, token, message.id, myId, emoji).getOrThrow()
        else state.chats.addReaction(session.endpoint, token, message.id, myId, emoji).getOrThrow()
        load()
    }
    fun action(work: suspend () -> Unit) { scope.launch {
        try { work() } catch (error: Exception) { failure = error.message ?: "The action failed." }
    } }

    LaunchedEffect(roomKey) {
        draft = state.sessionStore.draft(roomKey); draftReady = true
        load()
        if (messages.isNotEmpty()) list.scrollToItem(messages.lastIndex)
    }
    LaunchedEffect(roomId) {
        try {
            val token = state.auth.freshToken() ?: return@LaunchedEffect
            if (direct) title = state.chats.conversation(session.endpoint, token, roomId, myId, state.profiles)?.title ?: "Conversation"
            else title = state.container.rest.select(session.endpoint, token, "channels", filters = listOf("id" to "eq.$roomId"),
                serializer = ListSerializer(Channel.serializer())).getOrThrow().firstOrNull()?.name ?: "Channel"
        } catch (error: Exception) { failure = error.message }
    }
    LaunchedEffect(roomKey, draft) {
        if (draftReady) { delay(250); state.sessionStore.saveDraft(roomKey, draft) }
    }
    LaunchedEffect(roomId) {
        val token = state.auth.freshToken() ?: return@LaunchedEffect
        Realtime(Http.client).room(session.endpoint, token, roomId, direct).collect { event ->
            when (event) {
                LiveUpdate.Connected -> { live = "Live updates connected"; load() }
                LiveUpdate.Changed -> load()
                is LiveUpdate.Unavailable -> live = event.reason
            }
        }
    }
    BackHandler { if (thread != null) thread = null else onBack() }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(title = { Column {
            Text(thread?.name ?: title, maxLines = 1, style = MaterialTheme.typography.titleMedium)
            Text(if (thread != null) "Thread in $title" else if (direct) "Direct conversation" else "Text channel",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }, navigationIcon = {
            IconButton(onClick = { if (thread != null) thread = null else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        }, actions = {
            IconButton(onClick = { action { load() } }) { Icon(Icons.Default.Refresh, "Refresh messages") }
            IconButton(onClick = { searchOpen = true }) { Icon(Icons.Default.Search, "Search messages") }
            if (!direct) {
                IconButton(onClick = { action { val token = state.auth.freshToken() ?: return@action
                    pins = state.chats.pinned(session.endpoint, token, roomId).getOrThrow() } }) { Icon(Icons.Default.PushPin, "Pinned messages") }
                IconButton(onClick = { action { val token = state.auth.freshToken() ?: return@action
                    threads = state.chats.threads(session.endpoint, token, roomId).getOrThrow() } }) { Icon(Icons.Default.Forum, "Threads") }
            }
        })
        Text(live, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (failure != null) ErrorBanner(failure!!) { failure = null }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (loading && messages.isEmpty()) LoadingState("Loading messages")
            else if (messages.isEmpty()) EmptyState("Start the conversation", "Messages sent here will appear in this ${if (direct) "conversation" else "channel"}.")
            else LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("message-list"),
                contentPadding = PaddingValues(vertical = 8.dp)) {
                if (olderAvailable) item { TextButton(onClick = { action { load(true) } }) { Text("Load older messages") } }
                items(messages, key = { it.id }) { message ->
                    MessageRow(message, people[message.userId], appearance, reactions[message.id].orEmpty(), myId,
                        onActions = { selected = message }, onReact = { emoji -> action { react(message, emoji) } })
                }
            }
        }
        if (reply != null) Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Replying to ${people[reply!!.userId]?.shownName ?: "message"}: ${reply!!.plainText.take(60)}",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = { reply = null }) { Icon(Icons.Default.Close, "Cancel reply") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(draft, { draft = it }, placeholder = { Text("Message ${if (direct) title else "#$title"}") },
                modifier = Modifier.weight(1f).testTag("message-composer"), maxLines = 5, enabled = !sending,
                shape = MaterialTheme.shapes.large)
            IconButton(enabled = !sending && draft.isNotBlank(), modifier = Modifier.testTag("message-send"), onClick = {
                sending = true
                val text = draft; val parent = reply?.id; val threadId = thread?.id
                scope.launch {
                    try {
                        val token = state.auth.freshToken() ?: error("Sign in again.")
                        val result = if (direct) state.chats.sendDirectMessage(session.endpoint, token, roomId, text, parent, threadId)
                            else state.chats.sendChannelMessage(session.endpoint, token, roomId, text, parent, threadId)
                        result.getOrThrow()
                        draft = ""; reply = null; state.sessionStore.saveDraft(roomKey, "")
                        load(); if (messages.isNotEmpty()) list.animateScrollToItem(messages.lastIndex)
                    } catch (error: Exception) { failure = error.message ?: "Message could not be sent. Your draft is kept." }
                    finally { sending = false }
                }
            }) {
                if (sending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.AutoMirrored.Filled.Send, "Send message", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
    if (selected != null) {
        val message = selected!!
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("👍", "❤️", "😂", "🎉", "👀").forEach { emoji ->
                    TextButton(onClick = { selected = null; action { react(message, emoji) } }) { Text(emoji, fontSize = 24.sp) }
                }
            }
            TextButton(onClick = { reply = message; selected = null }, Modifier.fillMaxWidth()) { Text("Reply") }
            TextButton(onClick = { clipboard.setText(AnnotatedString(message.plainText)); selected = null }, Modifier.fillMaxWidth()) { Text("Copy text") }
            if (!direct) {
                TextButton(onClick = { selected = null; action {
                    val token = state.auth.freshToken() ?: error("Sign in again.")
                    thread = state.chats.createThread(session.endpoint, token, roomId, message.id, "Discussion").getOrThrow()
                } }, Modifier.fillMaxWidth()) { Text("Create thread") }
                TextButton(onClick = { selected = null; action {
                    val token = state.auth.freshToken() ?: error("Sign in again.")
                    state.chats.pin(session.endpoint, token, message.id, !message.isPinned).getOrThrow(); load()
                } }, Modifier.fillMaxWidth()) { Text(if (message.isPinned) "Unpin message" else "Pin message") }
            }
            if (message.userId == myId && !message.encrypted) {
                TextButton(onClick = { edit = message; editText = message.plainText; selected = null }, Modifier.fillMaxWidth()) { Text("Edit message") }
                TextButton(onClick = { delete = message; selected = null }, Modifier.fillMaxWidth()) { Text("Delete message", color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (edit != null) AlertDialog(onDismissRequest = { edit = null }, title = { Text("Edit message") },
        text = { OutlinedTextField(editText, { editText = it }, modifier = Modifier.testTag("message-edit-text")) },
        confirmButton = { TextButton(enabled = editText.isNotBlank(), onClick = { val id = edit!!.id; edit = null; action {
            val token = state.auth.freshToken() ?: error("Sign in again.")
            state.chats.editMessage(session.endpoint, token, id, editText).getOrThrow(); load()
        } }) { Text("Save") } }, dismissButton = { TextButton(onClick = { edit = null }) { Text("Cancel") } })
    if (delete != null) AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete this message?") },
        text = { Text("This cannot be undone.") }, confirmButton = { TextButton(onClick = { val id = delete!!.id; delete = null; action {
            val token = state.auth.freshToken() ?: error("Sign in again.")
            state.chats.deleteMessage(session.endpoint, token, id).getOrThrow(); load()
        } }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } })
    if (pins != null) ModalBottomSheet(onDismissRequest = { pins = null }) {
        Text("Pinned messages", Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
        LazyColumn { if (pins!!.isEmpty()) item { Text("No pinned messages", Modifier.padding(16.dp)) }
            items(pins!!, key = { it.id }) { Text(it.plainText, Modifier.padding(16.dp)) } }
    }
    if (threads != null) ModalBottomSheet(onDismissRequest = { threads = null }) {
        Text("Threads", Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
        LazyColumn { if (threads!!.isEmpty()) item { Text("No threads yet. Open a message's actions to start one.", Modifier.padding(16.dp)) }
            items(threads!!, key = { it.id }) { row -> ListItem(headlineContent = { Text(row.name ?: "Thread") },
                supportingContent = { Text("${row.messageCount} messages") }, modifier = Modifier.combinedClickable(onClick = { thread = row; threads = null })) } }
    }
    if (searchOpen) ModalBottomSheet(onDismissRequest = { searchOpen = false }) {
        Column(Modifier.padding(16.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("Search messages") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { action { val token = state.auth.freshToken() ?: return@action
                searchRows = state.chats.search(session.endpoint, token, query, channelId = if (direct) null else roomId).getOrThrow() } },
                enabled = query.isNotBlank()) { Text("Search") }
            LazyColumn { items(searchRows.orEmpty(), key = { it.id }) { Text(it.plainText, Modifier.padding(vertical = 12.dp)) }
                if (searchRows?.isEmpty() == true) item { Text("No results") } }
        }
    }
}

private const val ChatRepositoryPage = 50

@Composable
private fun MessageRow(message: Message, profile: Profile?, appearance: VisualThemeSettings, reactions: List<Reaction>,
                       myId: String, onActions: () -> Unit, onReact: (String) -> Unit) {
    val sizes = LocalSizes.current
    val name = profile?.shownName ?: if (message.isSystem) "System" else "Unknown account"
    val time = runCatching { DateTimeFormatter.ofPattern(if (appearance.use24HourTime) "HH:mm" else "h:mm a")
        .withZone(ZoneId.systemDefault()).format(Instant.parse(message.createdAt)) }.getOrDefault("")
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = onActions)
        .padding(horizontal = 12.dp, vertical = sizes.rowVerticalPadding)) {
        if (!appearance.compactMode) { Avatar(name, profile?.avatarUrl, size = 36.dp); Spacer(Modifier.width(10.dp)) }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                if (appearance.showTimestamps) Text(time, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (message.isPinned) Icon(Icons.Default.PushPin, "Pinned", Modifier.padding(start = 6.dp).size(12.dp))
            }
            if (message.replyTo != null) Text("↳ Reply", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer {
                Text(if (message.encrypted) "Encrypted message — keys are not available in this client" else message.plainText,
                    fontSize = sizes.messageSp.sp, modifier = Modifier.testTag("message-${message.id}"),
                    color = if (message.isSystem) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            }
            if (message.content.any { it.type != "text" }) Text("This message contains media or other content not rendered here.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                reactions.groupBy { it.emoji }.forEach { (emoji, group) ->
                    FilterChip(selected = group.any { it.userId == myId }, onClick = { onReact(emoji) },
                        label = { Text("${if (group.first().emojiId != null) "Custom emoji" else emoji} ${group.size}") })
                }
            }
        }
        IconButton(onClick = onActions, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.MoreHoriz, "Message actions", Modifier.size(18.dp)) }
    }
}
