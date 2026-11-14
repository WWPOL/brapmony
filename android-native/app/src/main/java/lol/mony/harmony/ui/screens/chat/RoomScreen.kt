@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package lol.mony.harmony.ui.screens.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import lol.mony.harmony.data.api.*
import lol.mony.harmony.data.model.*
import lol.mony.harmony.data.repo.ChatRepository
import lol.mony.harmony.data.session.AuthState
import lol.mony.harmony.ui.AppState
import lol.mony.harmony.ui.LiveStatus
import lol.mony.harmony.ui.components.*
import lol.mony.harmony.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * One channel, DM or thread.
 *
 * Three things here are load-bearing:
 *
 * - The rows come from [AppState.rooms], which is painted before the network is asked, so a
 *   channel a person has already read opens on its history rather than a spinner, and the
 *   refresh merges into that history instead of replacing it.
 * - Sending is optimistic: the row appears greyed with "Sending" the moment Send is tapped,
 *   then becomes the instance's own row. A failed send stays in the list with Retry, and its
 *   text is still in the composer, so nothing is lost.
 * - A horizontal drag on a row is a reply gesture, with the arrow and the row's own offset as
 *   the feedback, matching what a messaging app on Android does.
 */
@Composable
fun RoomScreen(state: AppState, roomId: String, direct: Boolean, appearance: VisualThemeSettings, onBack: () -> Unit) {
    val session = state.auth.state.collectAsState().value as? AuthState.SignedIn ?: return
    val myId = session.profile?.id ?: return
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val list = rememberLazyListState()
    var title by remember(roomId) { mutableStateOf(if (direct) "Conversation" else "Channel") }
    var thread by remember(roomId) { mutableStateOf<Thread?>(null) }
    val cacheKey = state.roomKey(roomId, direct, thread?.id)
    val draftKey = roomId + (thread?.id ?: "")
    val snapshot by state.rooms.rooms.collectAsState()
    val room = snapshot[cacheKey] ?: RoomSnapshot()
    var loading by remember(cacheKey) { mutableStateOf(!room.loaded) }
    var refreshing by remember(cacheKey) { mutableStateOf(false) }
    var draft by remember(draftKey) { mutableStateOf("") }
    var draftReady by remember(draftKey) { mutableStateOf(false) }
    var reply by remember(draftKey) { mutableStateOf<Message?>(null) }
    var failure by remember(cacheKey) { mutableStateOf<String?>(null) }
    var selected by remember(cacheKey) { mutableStateOf<Message?>(null) }
    var edit by remember(cacheKey) { mutableStateOf<Message?>(null) }
    var editText by remember { mutableStateOf("") }
    var delete by remember { mutableStateOf<Message?>(null) }
    var pins by remember { mutableStateOf<List<Message>?>(null) }
    var threads by remember { mutableStateOf<List<Thread>?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searchRows by remember { mutableStateOf<List<Message>?>(null) }
    val people by state.people.collectAsState()
    val bots by state.bots.collectAsState()
    var selectedProfile by remember(cacheKey) { mutableStateOf<String?>(null) }
    var reactors by remember(cacheKey) { mutableStateOf<Pair<Message, String>?>(null) }
    val live by state.live.collectAsState()
    val sizes = LocalSizes.current
    // A notification can name a message far older than the page this room opens on. The id is
    // taken once, the room pages back until it is in hand, and it is highlighted until the
    // person scrolls away from it.
    var focusId by remember(cacheKey) { mutableStateOf(state.focusMessageId.value) }
    var highlighted by remember(cacheKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(cacheKey) {
        val wanted = state.focusMessageId.value ?: return@LaunchedEffect
        state.focusMessageId.value = null
        focusId = wanted
    }

    suspend fun load(older: Boolean = false) {
        val key = state.roomKey(roomId, direct, thread?.id)
        try {
            if (!older) refreshing = true
            val token = state.auth.freshToken() ?: error("Your session expired. Sign in again.")
            // Every surface pages backwards from the newest message, so `before` is the
            // oldest row held. A thread paged the other way round returned its oldest
            // messages and looked as though it had stopped receiving replies.
            val before = if (older) state.rooms.snapshot(key)?.oldest else null
            val activeThread = thread
            val page = when {
                activeThread != null -> state.chats.threadMessages(session.endpoint, token, activeThread.id, before).getOrThrow()
                direct -> state.chats.directMessages(session.endpoint, token, roomId, before).getOrThrow()
                else -> state.chats.channelMessages(session.endpoint, token, roomId, before).getOrThrow()
            }
            state.rooms.update(key) { current ->
                val rows = if (older) (page + current.rows.filter { it.state is RowState.Sent }.map { it.message })
                    .distinctBy { it.id }.sortedBy { it.createdAt }
                    .map { message -> current.rows.firstOrNull { it.message.id == message.id } ?: RoomRow(message.id, message) }
                    .let { merged -> merged + current.rows.filter { it.state !is RowState.Sent } }
                    .sortedWith(compareBy({ it.message.createdAt }, { it.message.id }))
                else RoomCache.reconcile(current.rows, page, replaceTail = true)
                current.copy(
                    rows = rows,
                    oldest = rows.filter { it.state is RowState.Sent }.minOfOrNull { it.message.createdAt } ?: current.oldest,
                    moreOlder = page.size >= ChatRepositoryPage,
                    loaded = true,
                )
            }
            state.resolveAuthors(session.endpoint, token, page)
            val ids = state.rooms.snapshot(key)?.rows.orEmpty().takeLast(50).map { it.message.id }
            runCatching { state.chats.reactionsFor(session.endpoint, token, ids).getOrThrow() }
                .onSuccess { state.rooms.reactions(key, it) }
            if (!older && page.isNotEmpty()) {
                val last = page.last().id
                if (direct) {
                    runCatching { state.chats.markConversationRead(session.endpoint, token, roomId, last) }
                    state.clearConversationUnread(roomId)
                } else {
                    runCatching { state.chats.markChannelRead(session.endpoint, token, roomId, last) }
                    state.clearChannelUnread(roomId)
                }
            }
            failure = null
            state.markLive(LiveStatus.Connected)
        } catch (error: Exception) {
            failure = error.message ?: "Could not load messages."
            state.markLive(LiveStatus.Offline("You are offline. Messages will refresh when the connection returns."))
        } finally { loading = false; refreshing = false }
    }
    suspend fun react(message: Message, emoji: String) {
        val key = state.roomKey(roomId, direct, thread?.id)
        val token = state.auth.freshToken() ?: error("Sign in again.")
        val existing = state.rooms.snapshot(key)?.reactions?.get(message.id).orEmpty().any { it.userId == myId && it.emoji == emoji }
        if (existing) state.chats.removeReaction(session.endpoint, token, message.id, myId, emoji).getOrThrow()
        else state.chats.addReaction(session.endpoint, token, message.id, myId, emoji).getOrThrow()
        runCatching { state.chats.reactionsFor(session.endpoint, token, listOf(message.id)).getOrThrow() }
            .onSuccess { state.rooms.reactions(key, it) }
    }
    fun action(work: suspend () -> Unit) { scope.launch {
        try { work() } catch (error: Exception) { failure = error.message ?: "The action failed." }
    } }

    /**
     * Puts the message in the list and sends it. The row is the person's own until the instance
     * answers; a failure leaves it in place with Retry rather than deleting what they wrote.
     */
    fun send(text: String, parent: String?, nonce: String, retryKey: String?) {
        val key = state.roomKey(roomId, direct, thread?.id)
        val threadId = thread?.id
        val draftMessage = Message(
            id = retryKey ?: nonce, channelId = if (direct) null else roomId,
            conversationId = if (direct) roomId else null, threadId = threadId, userId = myId,
            replyTo = parent, content = listOf(ContentPart.text(text)), createdAt = Instant.now().toString(),
        )
        if (retryKey == null) {
            state.rooms.appendPending(key, RoomRow(nonce, draftMessage, RowState.Sending, nonce))
        } else {
            state.rooms.replace(key, retryKey, RoomRow(retryKey, draftMessage, RowState.Sending, nonce))
        }
        scope.launch {
            try {
                val token = state.auth.freshToken() ?: error("Sign in again.")
                // A retry of a send that actually landed adopts the stored row rather than
                // posting it twice: the instance has no uniqueness rule on the nonce, so the
                // check belongs here, as it is in the web client's outbox.
                val already = if (retryKey == null) null else state.chats.findByNonce(
                    session.endpoint, token, if (direct) null else roomId, if (direct) roomId else null, nonce,
                )
                if (already != null) {
                    state.rooms.update(key) { current ->
                        current.copy(
                            rows = RoomCache.reconcile(
                                current.rows.filterNot { it.key == (retryKey ?: nonce) },
                                listOf(already), replaceTail = false),
                            loaded = true)
                    }
                    state.resolveAuthors(session.endpoint, token, listOf(already))
                    failure = null
                    state.markLive(LiveStatus.Connected)
                    return@launch
                }
                val sent = if (direct)
                    state.chats.sendDirectMessage(session.endpoint, token, roomId, text, parent, threadId, nonce)
                else state.chats.sendChannelMessage(session.endpoint, token, roomId, text, parent, threadId, nonce)
                val acknowledged = sent.getOrThrow()
                state.rooms.update(key) { current ->
                    current.copy(rows = current.rows
                        .filterNot { it.key == (retryKey ?: nonce) }
                        .let { rows -> RoomCache.reconcile(rows, listOf(acknowledged), replaceTail = false) },
                        loaded = true)
                }
                state.resolveAuthors(session.endpoint, token, listOf(acknowledged))
                state.markLive(LiveStatus.Connected)
            } catch (error: Exception) {
                val reason = error.message ?: "Not sent."
                state.rooms.replace(key, retryKey ?: nonce, RoomRow(
                    retryKey ?: nonce, draftMessage, RowState.Failed(reason), nonce,
                ))
                // The text goes back into the composer only if the person has not started
                // writing something else, so a failed send can never overwrite a new draft.
                if (draft.isBlank()) { draft = text; reply = parent?.let { id -> room.rows.firstOrNull { it.message.id == id }?.message } }
                failure = reason
            }
        }
    }

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
    LaunchedEffect(draftKey) {
        draft = state.sessionStore.draft(draftKey); draftReady = true
    }
    LaunchedEffect(cacheKey) {
        // The cached rows paint first; the refresh then merges into them. The scroll goes to
        // the newest row only when the room is opened, never on a later refresh.
        if (room.rows.isNotEmpty() && state.focusMessageId.value == null) list.scrollToItem(room.rows.lastIndex)
        load()
    }
    // Pages back until the target message is on screen, then scrolls to it and marks it. A
    // message the account can no longer see is reported once, without its text.
    LaunchedEffect(cacheKey, focusId, room.rows.size) {
        val wanted = focusId ?: return@LaunchedEffect
        var pages = 0
        while (room.rows.none { it.message.id == wanted } && pages < lol.mony.harmony.notifications.NotificationTargets.MAX_TARGET_PAGES &&
            (room.moreOlder || pages == 0)) {
            pages++
            val before = room.rows.size
            load(older = true)
            if (room.rows.size == before) break
        }
        val index = room.rows.indexOfFirst { it.message.id == wanted }
        if (index >= 0) {
            list.scrollToItem(index)
            highlighted = wanted
            kotlinx.coroutines.delay(2_500)
            highlighted = null
            focusId = null
        } else if (pages >= lol.mony.harmony.notifications.NotificationTargets.MAX_TARGET_PAGES || !room.moreOlder) {
            failure = "That message is no longer available."
            focusId = null
        }
    }
    LaunchedEffect(roomId) {
        try {
            val token = state.auth.freshToken() ?: return@LaunchedEffect
            if (direct) title = state.chats.conversation(session.endpoint, token, roomId, myId, state.profiles)?.title ?: "Conversation"
            else title = state.container.rest.select(session.endpoint, token, "channels", filters = listOf("id" to "eq.$roomId"),
                serializer = ListSerializer(Channel.serializer())).getOrThrow().firstOrNull()?.name ?: "Channel"
        } catch (error: Exception) { failure = error.message }
    }
    LaunchedEffect(draftKey, draft) {
        if (draftReady) { delay(250); state.sessionStore.saveDraft(draftKey, draft) }
    }
    LaunchedEffect(roomId) {
        val token = state.auth.freshToken() ?: return@LaunchedEffect
        Realtime(Http.client).room(session.endpoint, token, roomId, direct).collect { event ->
            when (event) {
                LiveUpdate.Connected -> { state.markLive(LiveStatus.Connected); load() }
                LiveUpdate.Changed -> load()
                is LiveUpdate.Unavailable -> {
                    state.markLive(LiveStatus.Offline(event.reason))
                    failure = null
                }
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
    val rows = room.rows
    val reactions = room.reactions

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(title = { Column {
            Text(thread?.name ?: title, maxLines = 1, style = MaterialTheme.typography.titleMedium)
            Text(if (thread != null) "Thread in " + title else if (direct) "Direct message" else "Text channel",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }, navigationIcon = {
            IconButton(onClick = { if (thread != null) thread = null else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        }, actions = {
            IconButton(onClick = { action { load() } }) { Icon(Icons.Default.Refresh, "Refresh") }
            IconButton(onClick = { searchOpen = true }) { Icon(Icons.Default.Search, "Search") }
            if (!direct) {
                IconButton(onClick = { action { val token = state.auth.freshToken() ?: return@action
                    pins = state.chats.pinned(session.endpoint, token, roomId).getOrThrow() } }) { Icon(Icons.Default.PushPin, "Pinned") }
                IconButton(onClick = { action { val token = state.auth.freshToken() ?: return@action
                    threads = state.chats.threads(session.endpoint, token, roomId).getOrThrow()
                    state.rememberThreads(roomId, threads.orEmpty()) } }) { Icon(Icons.Default.Forum, "Threads") }
            }
        })
        // The connection banner is drawn only while the instance cannot be reached. A healthy
        // connection says nothing: an always-on label is noise that hides a real outage.
        ConnectionBanner(live) { action { load() } }
        if (openFailure != null) ErrorBanner(openFailure) { failure = null }
        if (refreshing && rows.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (loading && rows.isEmpty()) LoadingState("Loading messages")
            else if (rows.isEmpty()) EmptyState("No messages yet", "Send the first message in this " + (if (direct) "conversation" else "channel") + ".")
            else LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("message-list"),
                contentPadding = PaddingValues(vertical = 8.dp)) {
                if (room.moreOlder) item(key = "older") {
                    TextButton(onClick = { action { load(true) } }, modifier = Modifier.testTag("load-older")) { Text("Load older") }
                }
                itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                    val previous = rows.getOrNull(index - 1)?.message
                    MessageRow(
                        row = row,
                        author = row.message.author(people, bots),
                        showHeader = showsAuthorHeader(row.message, previous),
                        baseUrl = session.endpoint.baseUrl,
                        appearance = appearance,
                        reactions = reactions[row.message.id].orEmpty(),
                        myId = myId,
                        onActions = { selected = row.message },
                        onReact = { emoji -> if (!row.isPending) action { react(row.message, emoji) } },
                        onAuthor = { profileId -> selectedProfile = profileId },
                        onShowReactors = { emoji -> reactors = row.message to emoji },
                        onReply = { reply = row.message },
                        onRetry = { if (row.isFailed) send(row.message.plainText, row.message.replyTo, row.nonce ?: row.key, row.key) },
                        highlighted = highlighted == row.message.id,
                    )
                }
            }
        }
        if (openReply != null) Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp).testTag("reply-banner"), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.Reply, null, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Replying to " + openReply.author(people, bots).name + ": " + openReply.plainText.take(60),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { reply = null }) { Icon(Icons.Default.Close, "Cancel reply") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
            // A long message is not truncated as it is typed: pasting one keeps every
            // character, the counter turns red, and Send waits. Swallowing the keystrokes
            // instead would look like a broken keyboard, and the instance would refuse the
            // message in a trigger anyway -- which reads as a network failure.
            OutlinedTextField(draft, { draft = it },
                placeholder = { Text("Message " + (if (direct) title else "#" + title)) },
                modifier = Modifier.weight(1f).testTag("message-composer"), maxLines = 5,
                shape = MaterialTheme.shapes.large,
                supportingText = if (draft.length > ChatRepository.DEFAULT_MAX_LENGTH - 200) ({
                    Text(draft.length.toString() + " / " + ChatRepository.DEFAULT_MAX_LENGTH,
                        Modifier.testTag("message-length"))
                }) else null,
                isError = draft.length > ChatRepository.DEFAULT_MAX_LENGTH)
            // The send button never turns into a spinner: the row in the list is the progress,
            // and keeping the button usable is what lets a second message be written.
            IconButton(enabled = draft.isNotBlank() && draft.length <= ChatRepository.DEFAULT_MAX_LENGTH,
                modifier = Modifier.testTag("message-send"), onClick = {
                val text = draft
                val parent = reply?.id
                draft = ""; reply = null
                scope.launch { state.sessionStore.saveDraft(draftKey, "") }
                send(text, parent, UUID.randomUUID().toString(), null)
                scope.launch { if (rows.isNotEmpty()) list.animateScrollToItem(rows.lastIndex) }
            }) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = MaterialTheme.colorScheme.primary) }
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
            TextButton(onClick = { clipboard.setText(AnnotatedString(message.plainText)); selected = null }, Modifier.fillMaxWidth()) { Text("Copy") }
            if (!direct) {
                TextButton(onClick = { selected = null; action {
                    val token = state.auth.freshToken() ?: error("Sign in again.")
                    thread = state.chats.createThread(session.endpoint, token, roomId, message.id, "Discussion").getOrThrow()
                } }, Modifier.fillMaxWidth()) { Text("Start thread") }
                TextButton(onClick = { selected = null; action {
                    val token = state.auth.freshToken() ?: error("Sign in again.")
                    state.chats.pin(session.endpoint, token, message.id, !message.isPinned).getOrThrow(); load()
                } }, Modifier.fillMaxWidth()) { Text(if (message.isPinned) "Unpin" else "Pin") }
            }
            if (message.userId == myId) {
                if (message.isEditable) {
                    TextButton(onClick = { edit = message; editText = message.plainText; selected = null }, Modifier.fillMaxWidth()) { Text("Edit") }
                }
                TextButton(onClick = { delete = message; selected = null }, Modifier.fillMaxWidth()) { Text("Delete", color = MaterialTheme.colorScheme.error) }
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
        AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete message?") },
            text = { Text("This cannot be undone.") }, confirmButton = { TextButton(onClick = { delete = null; action {
                val token = state.auth.freshToken() ?: error("Sign in again.")
                state.chats.deleteMessage(session.endpoint, token, message.id).getOrThrow()
                val key = state.roomKey(roomId, direct, thread?.id)
                state.rooms.remove(key, message.id)
            } }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } })
    }
    if (openPins != null) {
        val pinned = openPins
        ModalBottomSheet(onDismissRequest = { pins = null }) {
            Text("Pinned", Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.testTag("pinned-list")) {
                if (pinned.isEmpty()) item { Text("No pinned messages", Modifier.padding(16.dp)) }
                items(pinned, key = { it.id }) { row ->
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
        val listed = openThreads
        ModalBottomSheet(onDismissRequest = { threads = null }) {
            Text("Threads", Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.testTag("thread-list")) {
                val active = listed.filter { it.isActive() }
                if (active.isEmpty()) item { Text("No open threads. Long-press a message to start one.", Modifier.padding(16.dp)) }
                items(active, key = { it.id }) { row -> ListItem(headlineContent = { Text(row.name ?: "Thread") },
                    supportingContent = { Text(countLabel(row.messageCount, "message")) },
                    modifier = Modifier.combinedClickable(onClick = { thread = row; threads = null })) }
            }
        }
    }
    // Who reacted: the pill shows a count, this names them. The identities come from the
    // reaction rows the page already loaded, resolved through the same people map the row
    // drew its author from, so a reactor is never a placeholder.
    val openReactors = reactors
    if (openReactors != null) {
        val (reactedMessage, emoji) = openReactors
        val who = reactions[reactedMessage.id].orEmpty().filter { it.emoji == emoji }
        ModalBottomSheet(onDismissRequest = { reactors = null }) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(emoji + " " + who.size, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("reactors-title"))
                Spacer(Modifier.height(8.dp))
                if (who.isEmpty()) Text("No reactions yet.", style = MaterialTheme.typography.bodyMedium)
                for (reaction in who) {
                    val person = reaction.userId?.let { people[it] }
                    val name = person?.shownName ?: reaction.userId?.let { "Account " + it.take(8) } ?: "Someone"
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Avatar(name, person?.avatarUrl, size = 32.dp, baseUrl = session.endpoint.baseUrl)
                        Spacer(Modifier.width(10.dp))
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
    // Tapping an author opens who they are, as the web client's profile card does.
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
                    Text(person.handle(), style = MaterialTheme.typography.bodyMedium,
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
                    Text("This account is unavailable.", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { selectedProfile = null }) { Text("Close") }
            }
        }
    }
    if (openSearchOpen) ModalBottomSheet(onDismissRequest = { searchOpen = false }) {
        Column(Modifier.padding(16.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("Search") }, modifier = Modifier.fillMaxWidth())
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
 * The connection banner: shown only when the instance cannot be reached, with a retry.
 *
 * A connected client draws nothing here. The old always-on "Live updates connected" line took
 * a strip of every screen and made the one state worth noticing -- being offline -- look like
 * every other.
 */
@Composable
internal fun ConnectionBanner(status: LiveStatus, onRetry: () -> Unit) {
    if (status !is LiveStatus.Offline) return
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().testTag("connection-banner")) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CloudOff, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(8.dp))
            Text(status.reason, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onRetry, modifier = Modifier.testTag("connection-retry")) { Text("Retry") }
        }
    }
}

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

/**
 * One message: its author, its body and its reactions, plus the two gestures a messaging app
 * has here -- long-press for actions and a horizontal drag to reply.
 *
 * The drag is deliberately horizontal-only (`detectHorizontalDragGestures`), so the vertical
 * scroll of the list is never stolen from it, and the row slides with the finger while a reply
 * arrow fades in behind it. The same reply is reachable from the actions sheet and from a
 * custom accessibility action, so a person who cannot perform a drag is not locked out of it.
 */
@Composable
private fun MessageRow(
    row: RoomRow, author: MessageAuthor, showHeader: Boolean, baseUrl: String?,
    appearance: VisualThemeSettings, reactions: List<Reaction>, myId: String,
    onActions: () -> Unit, onReact: (String) -> Unit, onAuthor: (String?) -> Unit,
    onShowReactors: (String) -> Unit, onReply: () -> Unit, onRetry: () -> Unit,
    highlighted: Boolean = false,
) {
    val message = row.message
    val sizes = LocalSizes.current
    val name = if (message.isSystem) "System" else author.name
    val haptics = LocalHapticFeedback.current
    val time = runCatching { DateTimeFormatter.ofPattern(if (appearance.use24HourTime) "HH:mm" else "h:mm a")
        .withZone(ZoneId.systemDefault()).format(Instant.parse(message.createdAt)) }.getOrDefault("")
    val replyThreshold = 64.dp
    var drag by remember(row.key) { mutableStateOf(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val offset = animateFloatAsState(drag, label = "reply-offset").value
    val revealed = offset >= with(density) { replyThreshold.toPx() }

    Box(Modifier.fillMaxWidth()
        .then(if (highlighted) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)) else Modifier)
        .testTag(if (highlighted) "message-highlighted" else "message-row-" + row.key)) {
        // The feedback sits under the row and slides in as the row moves aside.
        if (offset > 0f) Box(Modifier.matchParentSize().padding(start = 12.dp), contentAlignment = Alignment.CenterStart) {
            Icon(Icons.AutoMirrored.Filled.Reply, "Reply",
                Modifier.size(20.dp).alpha((offset / with(density) { replyThreshold.toPx() }).coerceIn(0f, 1f)),
                tint = MaterialTheme.colorScheme.primary)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = offset }
                .pointerInput(row.key) {
                    detectHorizontalDragGestures(
                        onDragStart = { drag = 0f },
                        onHorizontalDrag = { _, amount ->
                            // Rightwards only: a leftward drag is not a gesture this client uses.
                            drag = (drag + amount).coerceIn(0f, size.width * 0.5f)
                        },
                        onDragEnd = {
                            if (drag >= with(density) { replyThreshold.toPx() }) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onReply()
                            }
                            drag = 0f
                        },
                        onDragCancel = { drag = 0f },
                    )
                }
                .combinedClickable(onClick = {}, onLongClick = onActions)
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("Reply") { onReply(); true },
                        CustomAccessibilityAction("Message actions") { onActions(); true },
                    )
                }
                .alpha(if (row.isPending) 0.55f else 1f)
                .testTag(if (row.isPending) "message-pending" else "message-" + message.id)
                .padding(horizontal = 12.dp, vertical = sizes.rowVerticalPadding),
        ) {
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
                    Text(name, fontWeight = FontWeight.Medium, fontSize = 16.sp, maxLines = 1,
                        modifier = Modifier.combinedClickable(onClick = { onAuthor(author.profileId) }, onLongClick = onActions))
                    if (author.isBot) BotBadge()
                    if (message.isPinned) Icon(Icons.Default.PushPin, "Pinned", Modifier.size(12.dp))
                    if (appearance.showTimestamps) Text(time, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (message.replyTo != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.Reply, null, Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    Text("Reply", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                MessageContent(message, sizes.messageSp, baseUrl = baseUrl,
                    encryptedNotice = if (message.encrypted) "Encrypted message. Keys are not available in this client." else null)
                if (row.isPending) Text("Sending…", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("sending-label"))
                if (row.state is RowState.Failed) Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.testTag("send-failed")) {
                    Icon(Icons.Default.ErrorOutline, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text(row.state.reason, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = onRetry, modifier = Modifier.testTag("send-retry")) { Text("Retry") }
                }
                if (reactions.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    reactions.groupBy { it.emoji }.forEach { (emoji, group) ->
                        val mine = group.any { it.userId == myId }
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = if (mine) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier
                                .combinedClickable(onClick = { onShowReactors(emoji) }, onLongClick = { onReact(emoji) })
                                .testTag("reaction-" + emoji),
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
