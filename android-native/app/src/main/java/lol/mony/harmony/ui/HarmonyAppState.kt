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
import lol.mony.harmony.data.model.Channel
import lol.mony.harmony.data.model.Profile
import lol.mony.harmony.data.model.UnreadCountRow
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
    val profiles: ProfileRepository = container.profiles,
    val settings: SettingsRepository = container.settings,
    val sessionStore: SessionStore = container.sessionStore,
) {
    /** The signed-in account's profile id, resolved once per session. */
    val profileId = MutableStateFlow<String?>(null)

    /** The server the channel column shows; null means the DM list. */
    val selectedServerId = MutableStateFlow<String?>(null)
    val showingDms = MutableStateFlow(true)

    /** The open conversation: a channel id, or a DM conversation id. */
    val openChannelId = MutableStateFlow<String?>(null)
    val openConversationId = MutableStateFlow<String?>(null)

    /** Bumped when the server list changes shape, so the rail reloads. */
    val serversRevision = MutableStateFlow(0)

    /** Channels per server, filled by the channel column and read by the rail's badges. */
    val channelsByServer = MutableStateFlow<Map<String, List<Channel>>>(emptyMap())

    /** Set by the rail's "+", cleared by the create-server sheet. */
    val showCreateServer = MutableStateFlow(false)

    private val _unread = MutableStateFlow(UnreadBadges())
    val unread: StateFlow<UnreadBadges> = _unread.asStateFlow()

    private val _typing = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val typing: StateFlow<Map<String, List<String>>> = _typing.asStateFlow()

    private val _people = MutableStateFlow<Map<String, Profile>>(emptyMap())
    val people: StateFlow<Map<String, Profile>> = _people.asStateFlow()

    fun rememberPeople(profiles: Collection<Profile>) {
        if (profiles.isEmpty()) return
        _people.update { current ->
            val next = current.toMutableMap()
            for (profile in profiles) {
                next[profile.id] = profile
                profile.authUserId?.let { next[it] = profile }
            }
            next
        }
    }

    fun person(authUserId: String?): Profile? = authUserId?.let { _people.value[it] }

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
        channelsByServer.value = emptyMap()
        selectedServerId.value = null
        openChannelId.value = null
        openConversationId.value = null
        profileId.value = null
    }
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
