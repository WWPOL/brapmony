@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package lol.mony.harmony.ui.screens.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
    val bots by state.bots.collectAsState()
    var selectedProfile by remember(roomKey) { mutableStateOf<String?>(null) }
    var reactors by remember(roomKey) { mutableStateOf<Pair<Message, String>?>(null) }
    val harmony = LocalHarmonyColors.current
    val sizes = LocalSizes.current

    suspend fun load(older: Boolean = false) {
        try {
            val token = state.auth.freshToken() ?: error("Your session expired. Sign in again.")
            // Every surface pages backwards from the newest message, so `before` is the
            // oldest row currently on screen. A thread paged the other way round returned its
            // oldest messages and looked as though it had stopped receiving replies.
            val cursor = if (older) messages.firstOrNull()?.createdAt else null
            val page = when {
                thread != null -> state.chats.threadMessages(session.endpoint, token, thread!!.id, cursor).getOrThrow()
                direct -> state.chats.directMessages(session.endpoint, token, roomId, cursor).getOrThrow()
                else -> state.chats.channelMessages(session.endpoint, token, roomId, cursor).getOrThrow()
            }
            messages = if (older) (page + messages).distinctBy { it.id }
                else if (messages.size > ChatRepositoryPage) {
                    (messages.filter { old -> page.none { it.id == old.id } } + page).sortedBy { it.createdAt }
                } else page
            olderAvailable = page.size >= ChatRepositoryPage
            state.resolveAuthors(session.endpoint, token, messages)
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

    // A thread opened from the channel list is picked up here, then cleared so returning to
    // the channel later opens the channel itself.
    LaunchedEffect(roomId) {
        val wanted = state.openThreadId.value ?: return@LaunchedEffect
        state.openThreadId.value = null
        runCatching {
            val token = state.auth.freshToken() ?: return@LaunchedEffect
            state.chats.thread(session.endpoint, token, wanted).getOrThrow()
        }.getOrNull()?.let { thread = it }
    }
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

    // Deferred content -- a bottom sheet's body, a lazy list's items, a dialog's buttons -- runs
    // after this composable body has returned, so each one closes over an immutable snapshot of
    // the state it draws. Reading the mutable `var` there is how opening a thread from the sheet
    // crashed: the row's click set `threads = null`, and the list's next recomposition then
    // dereferenced it.
    val openFailure = failure
    val openReply = reply
    val openSelected = selected
    val openEdit = edit
    val openDelete = delete
    val openPins = pins
    val openThreads = threads
    val openSearchRows = searchRows
    val openSearchOpen = searchOpen

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
                    threads = state.chats.threads(session.endpoint, token, roomId).getOrThrow()
                    state.rememberThreads(roomId, threads.orEmpty()) } }) { Icon(Icons.Default.Forum, "Threads") }
            }
        })
        Text(live, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (openFailure != null) ErrorBanner(openFailure) { failure = null }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (loading && messages.isEmpty()) LoadingState("Loading messages")
            else if (messages.isEmpty()) EmptyState("Start the conversation", "Messages sent here will appear in this ${if (direct) "conversation" else "channel"}.")
            else LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("message-list"),
                contentPadding = PaddingValues(vertical = 8.dp)) {
                if (olderAvailable) item { TextButton(onClick = { action { load(true) } }) { Text("Load older messages") } }
                itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                    val previous = messages.getOrNull(index - 1)
                    MessageRow(
                        message = message,
                        author = message.author(people, bots),
                        showHeader = showsAuthorHeader(message, previous),
                        baseUrl = session.endpoint.baseUrl,
                        appearance = appearance,
                        reactions = reactions[message.id].orEmpty(),
                        myId = myId,
                        onActions = { selected = message },
                        onReact = { emoji -> action { react(message, emoji) } },
                        onAuthor = { profileId -> selectedProfile = profileId },
                        onShowReactors = { emoji -> reactors = message to emoji },
                    )
                }
            }
        }
        if (openReply != null) Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Replying to ${openReply.author(people, bots).name}: ${openReply.plainText.take(60)}",
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
    // The message-actions sheet. `message` is captured once, so a click that closes the sheet
    // cannot leave the body reading a cleared state.
    if (openSelected != null) {
        val message = openSelected
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
            if (message.userId == myId) {
                if (message.isEditable) {
                    TextButton(onClick = { edit = message; editText = message.plainText; selected = null }, Modifier.fillMaxWidth()) { Text("Edit message") }
                }
                TextButton(onClick = { delete = message; selected = null }, Modifier.fillMaxWidth()) { Text("Delete message", color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (openEdit != null) {
        val message = openEdit
        AlertDialog(onDismissRequest = { edit = null }, title = { Text("Edit message") },
            text = { OutlinedTextField(editText, { editText = it }, modifier = Modifier.testTag("message-edit-text")) },
            confirmButton = { TextButton(enabled = editText.isNotBlank(), onClick = { edit = null; action {
                val token = state.auth.freshToken() ?: error("Sign in again.")
                state.chats.editMessage(session.endpoint, token, message.id, editText).getOrThrow(); load()
            } }) { Text("Save") } }, dismissButton = { TextButton(onClick = { edit = null }) { Text("Cancel") } })
    }
    if (openDelete != null) {
        val message = openDelete
        AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete this message?") },
            text = { Text("This cannot be undone.") }, confirmButton = { TextButton(onClick = { delete = null; action {
                val token = state.auth.freshToken() ?: error("Sign in again.")
                state.chats.deleteMessage(session.endpoint, token, message.id).getOrThrow(); load()
            } }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } })
    }
    if (openPins != null) {
        val rows = openPins
        ModalBottomSheet(onDismissRequest = { pins = null }) {
            Text("Pinned messages", Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.testTag("pinned-list")) {
                if (rows.isEmpty()) item { Text("No pinned messages", Modifier.padding(16.dp)) }
                items(rows, key = { it.id }) { row ->
                    Column(Modifier.padding(16.dp)) {
                        Text(row.author(people, bots).name, style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold)
                        MessageContent(row, sizes.messageSp)
                    }
                }
            }
        }
    }
    if (openThreads != null) {
        val rows = openThreads
        ModalBottomSheet(onDismissRequest = { threads = null }) {
            Text("Threads", Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.testTag("thread-list")) {
                if (rows.isEmpty()) item { Text("No threads yet. Open a message's actions to start one.", Modifier.padding(16.dp)) }
                items(rows, key = { it.id }) { row -> ListItem(headlineContent = { Text(row.name ?: "Thread") },
                    supportingContent = { Text(countLabel(row.messageCount, "message")) },
                    modifier = Modifier.combinedClickable(onClick = { thread = row; threads = null })) }
            }
        }
    }
    // Who reacted: the pill shows a count, this names them. The identities come from the    // reaction rows the page already loaded, resolved through the same people map the row    // drew its author from, so a reactor is never a placeholder.    val openReactors = reactors    if (openReactors != null) {        val (reactedMessage, emoji) = openReactors        val who = reactions[reactedMessage.id].orEmpty().filter { it.emoji == emoji }        ModalBottomSheet(onDismissRequest = { reactors = null }) {            Column(Modifier.fillMaxWidth().padding(16.dp)) {                Text(emoji + @Q@  @Q@ + who.size, style = MaterialTheme.typography.titleMedium,                    modifier = Modifier.testTag(@Q@reactors-title@Q@))                Spacer(Modifier.height(8.dp))                if (who.isEmpty()) Text(@Q@No one has reacted yet.@Q@, style = MaterialTheme.typography.bodyMedium)                for (reaction in who) {                    val person = reaction.userId?.let { people[it] }                    val name = person?.shownName ?: reaction.userId?.let { @Q@Account @Q@ + it.take(8) } ?: @Q@Someone@Q@                    Row(verticalAlignment = Alignment.CenterVertically,                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {                        Avatar(name, person?.avatarUrl, size = 32.dp, baseUrl = session.endpoint.baseUrl)                        Spacer(Modifier.width(10.dp))                        Text(name, style = MaterialTheme.typography.bodyLarge)                    }                }            }        }    }
    // Tapping an author opens who they are, as the web client's profile card does. This is the
    // only place a message row offers the account's own details, so it reads the same resolved
    // profile the row drew from rather than fetching a second copy.
    val openProfileId = selectedProfile
    if (openProfileId != null) {
        val person = people[openProfileId]
        ModalBottomSheet(onDismissRequest = { selectedProfile = null }) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (person != null) {
                    Avatar(person.shownName, person.avatarUrl, size = 88.dp, baseUrl = session.endpoint.baseUrl,
                        modifier = Modifier.testTag("author-profile-avatar"))
                    Spacer(Modifier.height(12.dp))
                    Text(person.shownName, style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.testTag("author-profile-name"))
                    val handle = person.handle()
                    Text(handle, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!person.pronouns.isNullOrBlank()) Text(person.pronouns!!,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!person.bio.isNullOrBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(person.bio!!, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (person.authUserId != null && person.authUserId != myId) {
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = {
                            val other = person.id
                            selectedProfile = null
                            action {
                                val token = state.auth.freshToken() ?: error("Sign in again.")
                                val conversation = state.chats.openDirectConversation(session.endpoint, token, myId, other).getOrThrow()
                                state.openConversationId.value = conversation
                                state.showingDms.value = true
                            }
                        }) { Text("Message") }
                    }
                } else {
                    Text("This account could not be read.", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { selectedProfile = null }) { Text("Close") }
            }
        }
    }
    if (openSearchOpen) ModalBottomSheet(onDismissRequest = { searchOpen = false }) {
        Column(Modifier.padding(16.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("Search messages") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { action { val token = state.auth.freshToken() ?: return@action
                searchRows = state.chats.search(session.endpoint, token, query, channelId = if (direct) null else roomId).getOrThrow() } },
                enabled = query.isNotBlank()) { Text("Search") }
            LazyColumn { items(openSearchRows.orEmpty(), key = { it.id }) { row ->
                Column(Modifier.padding(vertical = 12.dp)) {
                    Text(row.author(people, bots).name, style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold)
                    MessageContent(row, sizes.messageSp)
                }
            }
                if (openSearchRows?.isEmpty() == true) item { Text("No results") } }
        }
    }
}

private const val ChatRepositoryPage = 50

/** `1 message`, `2 messages`: a count and its noun, as the web client writes it. */
internal fun countLabel(count: Int, noun: String): String =
    count.toString() + " " + noun + (if (count == 1) "" else "s")

/**
 * Whether a row repeats the author's header, as the web client's `shouldShowHeader` decides.
 *
 * One author's consecutive messages group under a single header until five minutes pass, the
 * author changes, the message answers something, or a system event interrupts. Grouping is what
 * makes a busy channel readable; drawing the header on every row wastes the width a phone has.
 */
internal fun showsAuthorHeader(message: Message, previous: Message?): Boolean {
    if (previous == null) return true
    if (message.isSystem || previous.isSystem) return true
    if (message.authorKey() != previous.authorKey()) return true
    if (message.replyTo != null) return true
    val gap = runCatching {
        Instant.parse(message.createdAt).toEpochMilli() - Instant.parse(previous.createdAt).toEpochMilli()
    }.getOrDefault(Long.MAX_VALUE)
    return gap > 5 * 60 * 1000
}

@Composable
private fun MessageRow(message: Message, author: MessageAuthor, showHeader: Boolean, baseUrl: String?,
                       appearance: VisualThemeSettings, reactions: List<Reaction>, myId: String,
                       onActions: () -> Unit, onReact: (String) -> Unit, onAuthor: (String?) -> Unit,
                       onShowReactors: (String) -> Unit) {
    val sizes = LocalSizes.current
    val name = if (message.isSystem) "System" else author.name
    val time = runCatching { DateTimeFormatter.ofPattern(if (appearance.use24HourTime) "HH:mm" else "h:mm a")
        .withZone(ZoneId.systemDefault()).format(Instant.parse(message.createdAt)) }.getOrDefault("")
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = onActions)
        .padding(horizontal = 12.dp, vertical = sizes.rowVerticalPadding)) {
        if (!appearance.compactMode) {
            if (showHeader) {
                Avatar(name, author.avatarPath, size = 40.dp, baseUrl = baseUrl,
                    modifier = Modifier.combinedClickable(onClick = { onAuthor(author.profileId) }, onLongClick = onActions))
            } else {
                // A grouped row keeps the avatar's width so the text column stays aligned.
                Spacer(Modifier.size(40.dp))
            }
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            if (showHeader) Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, fontWeight = FontWeight.Medium, fontSize = 16.sp,
                    modifier = Modifier.combinedClickable(onClick = { onAuthor(author.profileId) }, onLongClick = onActions))
                if (author.isBot) BotBadge()
                if (message.isPinned) Icon(Icons.Default.PushPin, "Pinned", Modifier.size(12.dp))
            }
            if (message.replyTo != null) Text("↳ Reply", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            MessageContent(message, sizes.messageSp, baseUrl = baseUrl,
                encryptedNotice = if (message.encrypted) "Encrypted message — keys are not available in this client" else null)
            if (message.hasAttachmentParts) Text("This message also carries an attachment or poll this client does not draw yet.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (reactions.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                reactions.groupBy { it.emoji }.forEach { (emoji, group) ->
                    val mine = group.any { it.userId == myId }
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (mine) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .combinedClickable(onClick = { onShowReactors(emoji) }, onLongClick = { onReact(emoji) })                            .testTag("reaction-" + emoji),
                    ) {
                        Text(
                            reactionLabel(group.first()) + " " + group.size,
                            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The badge the web client puts after a bot's name: a small primary-coloured tag.
 *
 * It is what tells a person the account is a bot before they read the message, so it is
 * drawn with the same weight and colour as the reference rather than as plain text.
 */
@Composable
private fun BotBadge() {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape(3.dp),
    ) {
        Text(
            "BOT",
            Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}
/** What a reaction pill shows for its emoji: the custom emoji's name, else the glyph. */
private fun reactionLabel(reaction: Reaction): String =
    if (reaction.emojiId != null) ":custom:" else reaction.emoji
