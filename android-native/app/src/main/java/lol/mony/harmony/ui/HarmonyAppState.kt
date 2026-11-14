package lol.mony.harmony.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import lol.mony.harmony.data.model.Bot
import lol.mony.harmony.data.model.DirectConversation
import lol.mony.harmony.data.model.ServerTree
import lol.mony.harmony.data.model.Thread
import lol.mony.harmony.data.model.Channel
import lol.mony.harmony.data.model.Profile
import lol.mony.harmony.data.model.UnreadCountRow
import lol.mony.harmony.data.repo.BotRepository
import lol.mony.harmony.data.repo.ChatRepository
import lol.mony.harmony.data.repo.ProfileRepository
import lol.mony.harmony.data.repo.ServerRepository
import lol.mony.harmony.data.repo.SettingsRepository
import lol.mony.harmony.data.session.AuthRepository
import lol.mony.harmony.data.session.AuthState
import lol.mony.harmony.data.session.SessionStore
import lol.mony.harmony.ui.theme.VisualThemeSettings

/**
 * Process-wide state the screens share: who is signed in, what is selected, unread badges,
 * typing and the people already resolved.
 *
 * It is deliberately plain state rather than an event bus: the app has one window, and every
 * screen already reads through the same repositories.
 */
class AppState(
    val container: lol.mony.harmony.AppContainer,
    val auth: AuthRepository = container.auth,
    val servers: ServerRepository = container.servers,
    val chats: ChatRepository = container.chats,
    val botRepo: BotRepository = container.bots,
    val profiles: ProfileRepository = container.profiles,
    val settings: SettingsRepository = container.settings,
    val sessionStore: SessionStore = container.sessionStore,
) {
    /** The signed-in account's profile id, resolved once per session. */
    val profileId = MutableStateFlow<String?>(null)

    /** The server the channel column shows; null means the DM list. */
    val selectedServerId = MutableStateFlow<String?>(null)
    val showingDms = MutableStateFlow(true)

    /** A thread the channel list opened, which the room reads and then clears. */
    val openThreadId = MutableStateFlow<String?>(null)

    /**
     * A message a notification asked for, which the room scrolls to and highlights once, then
     * clears. It survives the room not being open yet: the room reads it when it opens.
     */
    val focusMessageId = MutableStateFlow<String?>(null)

    /** The destination a tapped notification named, for the shell to open. */
    val pendingDestination = MutableStateFlow<lol.mony.harmony.data.model.NotificationDestination?>(null)

    /** The open conversation: a channel id, or a DM conversation id. */
    val openChannelId = MutableStateFlow<String?>(null)
    val openConversationId = MutableStateFlow<String?>(null)

    /** Bumped when the server list changes shape, so the rail reloads. */
    val serversRevision = MutableStateFlow(0)

    /** Channels per server, filled by the channel column and read by the rail's badges. */
    val channelsByServer = MutableStateFlow<Map<String, List<Channel>>>(emptyMap())

    /**
     * The server trees read so far, so moving between channels does not re-read a server@AP@s
     * channels and categories on every visit. Cleared on sign-out and by a pull to refresh.
     */
    val serverTrees = MutableStateFlow<Map<String, ServerTree>>(emptyMap())

    /** The DM list read so far; null until it has been read once. */
    val conversations = MutableStateFlow<List<DirectConversation>?>(null)

    /** Set by the rail's "+", cleared by the create-server sheet. */
    val showCreateServer = MutableStateFlow(false)

    private val _unread = MutableStateFlow(UnreadBadges())
    val unread: StateFlow<UnreadBadges> = _unread.asStateFlow()

    private val _typing = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val typing: StateFlow<Map<String, List<String>>> = _typing.asStateFlow()

    private val _people = MutableStateFlow<Map<String, Profile>>(emptyMap())

    /**
     * The profiles resolved so far, keyed by both `profiles.id` and `profiles.auth_user_id`.
     *
     * A message names its author by `profiles.id`, but the session, membership rows and the JWT
     * speak `auth_user_id`; for an account made before its profile they differ, so both keys
     * point at the same row and either spelling finds the person.
     */
    val people: StateFlow<Map<String, Profile>> = _people.asStateFlow()

    private val _bots = MutableStateFlow<Map<String, Bot>>(emptyMap())

    /** The bots resolved so far, keyed by `bots.id`, which is what `messages.bot_id` points at. */
    val bots: StateFlow<Map<String, Bot>> = _bots.asStateFlow()

    fun rememberPeople(profiles: Collection<Profile>) {
        if (profiles.isEmpty()) return
        _people.update { current ->
            val next = current.toMutableMap()
            for (profile in profiles) {
                next[profile.id] = profile
                profile.authUserId?.takeIf { it.isNotBlank() }?.let { next[it] = profile }
            }
            next
        }
    }

    fun rememberBots(bots: Collection<Bot>) {
        if (bots.isEmpty()) return
        _bots.update { current -> current + bots.associateBy { it.id } }
    }

    fun person(authUserId: String?): Profile? = authUserId?.let { _people.value[it] }

    /**
     * Resolves every author a page of messages names, so no row draws a name it has not looked
     * up. A failed lookup is swallowed on purpose: the row then draws what the message itself
     * carries, which is better than emptying the page over one unreadable profile.
     */
    suspend fun resolveAuthors(
        endpoint: lol.mony.harmony.data.api.Endpoint,
        token: String,
        messages: Collection<lol.mony.harmony.data.model.Message>,
    ) {
        val known = _people.value
        val userIds = messages.mapNotNull { it.userId }.filter { it !in known }.distinct()
        val botIds = messages.mapNotNull { it.botId }.filter { it !in _bots.value }.distinct()
        if (userIds.isNotEmpty()) runCatching { rememberPeople(container.profiles.byIds(endpoint, token, userIds).values) }
        if (botIds.isNotEmpty()) runCatching { rememberBots(botRepo.byIds(endpoint, token, botIds).values) }
    }

    private val _threadsByChannel = MutableStateFlow<Map<String, List<Thread>>>(emptyMap())
    val threadsByChannel: StateFlow<Map<String, List<Thread>>> = _threadsByChannel.asStateFlow()

    fun rememberThread(thread: Thread) {
        _threadsByChannel.update { current ->
            val existing = current[thread.channelId].orEmpty()
            current + (thread.channelId to (listOf(thread) + existing.filter { it.id != thread.id }))
        }
    }

    /** Forgets every cached list, so nothing from one account can show under another. */
    fun forgetCachedLists() {
        _threadsByChannel.value = emptyMap()
        channelsByServer.value = emptyMap()
        serverTrees.value = emptyMap()
        conversations.value = null
        rooms.clear()
    }

    // ---- open rooms -----------------------------------------------------------------------

    /**
     * The messages already read, per room, so a channel paints its history at once instead of
     * showing a spinner every time it is opened.
     *
     * A room's key carries the account and instance it was read under, so two accounts on one
     * device can never share a row. The cache is dropped on sign-out and on an account change.
     */
    val rooms = lol.mony.harmony.ui.screens.chat.RoomCache()

    /** The room key a cache entry uses: account, instance and room, in that order. */
    fun roomKey(roomId: String, direct: Boolean, threadId: String? = null): String {
        val session = auth.state.value as? AuthState.SignedIn
        val account = session?.profile?.id ?: profileId.value ?: "anonymous"
        val instance = session?.endpoint?.baseUrl ?: ""
        return listOf(account, instance, if (direct) "dm" else "channel", roomId, threadId ?: "").joinToString("|")
    }

    /**
     * Only the *open* threads of a channel, by the reference's rule: archived threads and
     * threads whose auto-archive clock has run out are not in the sidebar.
     *
     * The channel list used to nest every thread the instance returned, which put closed
     * conversations back in front of the reader on every visit.
     */
    fun rememberThreads(channelId: String, threads: List<Thread>) {
        val active = threads.filter { it.isActive() }
            .sortedWith(compareByDescending<Thread> { it.lastActivityAt ?: it.createdAt ?: "" })
        _threadsByChannel.update { it + (channelId to active) }
    }

    // ---- live connection ------------------------------------------------------------------

    /**
     * Whether the instance is currently reachable, and why not when it is not.
     *
     * The app shows a banner for [LiveStatus.Offline] and nothing at all while it is connected:
     * an always-visible "connected" label is noise that makes a real outage harder to notice.
     */
    private val _live = MutableStateFlow<LiveStatus>(LiveStatus.Connecting)
    val live: StateFlow<LiveStatus> = _live.asStateFlow()

    fun markLive(status: LiveStatus) { _live.value = status }

    /** True while something has failed and a retry is worth offering. */
    val isOffline: Boolean get() = _live.value is LiveStatus.Offline

    fun setTyping(channelId: String, names: List<String>) {
        _typing.update { it + (channelId to names) }
    }

    fun clearTyping(channelId: String) {
        _typing.update { it - channelId }
    }

    fun applyUnread(rows: List<UnreadCountRow>) {
        val channels = rows.mapNotNull { row -> row.channelId?.takeIf { row.unreadCount > 0 }?.let { it to row.unreadCount } }.toMap()
        val mentions = rows.mapNotNull { row -> row.channelId?.takeIf { row.unreadMentions > 0 }?.let { it to row.unreadMentions } }.toMap()
        val conversations = rows.mapNotNull { row ->
            row.conversationId?.takeIf { row.unreadCount > 0 }?.let { it to row.unreadCount }
        }.toMap()
        _unread.value = UnreadBadges(channels, conversations, mentions)
    }

    fun clearChannelUnread(channelId: String) {
        _unread.update { current ->
            current.copy(channels = current.channels - channelId, mentions = current.mentions - channelId)
        }
    }

    fun clearConversationUnread(conversationId: String) {
        _unread.update { current -> current.copy(conversations = current.conversations - conversationId) }
    }

    /** Refreshes the badges from the database's own counts. */
    suspend fun refreshUnread() {
        val session = auth.state.value as? AuthState.SignedIn ?: return
        val token = auth.freshToken() ?: return
        applyUnread(chats.unreadCounts(session.endpoint, token))
    }

    /** Sets up the signed-in session: profile, people, badges. */
    suspend fun onSignedIn(session: AuthState.SignedIn) {
        val token = auth.freshToken() ?: return
        val profile = session.profile ?: return
        // A different account must start from an empty cache, never the previous one's lists.
        if (profileId.value != null && profileId.value != profile.id) forgetCachedLists()
        profileId.value = profile.id
        rememberPeople(listOf(profile))
        refreshUnread()
    }

    // ---- appearance ---------------------------------------------------------------------

    /**
     * The device's own copy of the look, kept in the same JSON shape the account stores, so a
     * device that keeps its own look - or a signed-out app - has something to wear.
     */
    private val _deviceAppearance = MutableStateFlow<VisualThemeSettings?>(null)
    val deviceAppearance: StateFlow<VisualThemeSettings?> = _deviceAppearance.asStateFlow()

    fun rememberDeviceAppearance(settings: VisualThemeSettings) {
        _deviceAppearance.value = settings
    }

    suspend fun saveDeviceAppearance(settings: VisualThemeSettings) {
        rememberDeviceAppearance(settings)
        val id = profileId.value ?: return
        sessionStore.saveAppearance(id, JsonObject(VisualThemeSettings.toJsonMap(settings)))
    }

    /** Writes the look to the account, so it follows to other devices and to the web client. */
    suspend fun persistAccountAppearance(settings: VisualThemeSettings) {
        val session = auth.state.value as? AuthState.SignedIn ?: return
        val token = auth.freshToken() ?: return
        val profileId = profileId.value ?: session.profile?.id ?: return
        this.settings.saveAppearance(session.endpoint, token, profileId, settings)
    }

    /** True while this device keeps its own look (the web client's `appearance-sync`). */
    suspend fun appearanceIsDeviceOnly(): Boolean =
        runCatching { sessionStore.appearanceScopeFlow.first() }.getOrNull() == "device"

    suspend fun setAppearanceDeviceOnly(deviceOnly: Boolean) {
        sessionStore.setAppearanceScope(deviceOnly)
    }

    suspend fun signOut() {
        auth.signOut()
        _unread.value = UnreadBadges()
        _people.value = emptyMap()
        _bots.value = emptyMap()
        // Every cached list goes with the session, so the next account can never be shown the
        // previous one's channels, conversations or threads.
        forgetCachedLists()
        selectedServerId.value = null
        openChannelId.value = null
        openConversationId.value = null
        openThreadId.value = null
        profileId.value = null
    }
}

/** What the live connection is doing, as the banner reports it. */
sealed interface LiveStatus {
    /** Before the first connection attempt has answered; no banner. */
    data object Connecting : LiveStatus
    /** Realtime is attached and the room is being kept current; no banner. */
    data object Connected : LiveStatus
    /**
     * The instance cannot be reached, or realtime was refused. The banner names what is wrong
     * in a way a person can act on, and offers a retry.
     */
    data class Offline(val reason: String) : LiveStatus
}

data class UnreadBadges(
    val channels: Map<String, Int> = emptyMap(),
    val conversations: Map<String, Int> = emptyMap(),
    val mentions: Map<String, Int> = emptyMap(),
) {
    fun channel(channelId: String): Int = channels[channelId] ?: 0
    fun conversation(conversationId: String): Int = conversations[conversationId] ?: 0
    fun serverTotal(channelIds: Collection<String>): Int = channelIds.sumOf { channels[it] ?: 0 }
    fun mentionsIn(channelIds: Collection<String>): Int = channelIds.sumOf { mentions[it] ?: 0 }
}

val LocalAppState = staticCompositionLocalOf<AppState> { error("AppState is provided by HarmonyRoot") }

@Composable
fun ProvideAppState(state: AppState, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppState provides state, content = content)
}
