package lol.mony.harmony.notifications

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import lol.mony.harmony.data.model.Notification
import lol.mony.harmony.data.model.NotificationPermissionState
import lol.mony.harmony.data.repo.NotificationRepository
import lol.mony.harmony.data.session.AuthState
import lol.mony.harmony.data.session.AuthRepository

/**
 * Keeps the system's notification tray in step with the account's inbox.
 *
 * It is the delivery half of notifications: the inbox screen is where a person reads them, and
 * this is what makes one reach them while the app is not open on that screen. It polls the
 * account's own inbox -- the same `get_user_notifications` RPC the screen uses, with the same
 * row-level security -- and posts each new row to its channel, withdrawing rows that were read
 * or deleted elsewhere so the tray never disagrees with the inbox.
 *
 * What it is not: a push service. Harmony's real push path is a web push subscription handled
 * by the federation backend and the service worker, which this client does not register. Until
 * that exists, an app that has been killed receives nothing, and this class says so rather than
 * implying otherwise.
 */
class NotificationCoordinator(
    private val context: Context,
    private val auth: AuthRepository,
    private val repositoryFor: (lol.mony.harmony.data.api.RestApi) -> NotificationRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running = false
    private var seen = mutableSetOf<String>()

    private val _permission = MutableStateFlow(HarmonyNotifications.permissionState(context))

    /** What the system currently allows, for the settings screen to show and explain. */
    val permission: StateFlow<NotificationPermissionState> = _permission.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Re-reads the system's permission, after the person grants or revokes it in settings. */
    fun refreshPermission() { _permission.value = HarmonyNotifications.permissionState(context) }

    /**
     * Starts watching the signed-in account's inbox.
     *
     * The interval is deliberately modest: this is a stand-in for push, and a tight loop would
     * spend the person's battery to learn about messages they will see anyway. Every poll is
     * against the account's own row-level security, so a signed-out or switched account stops
     * seeing the previous one's rows.
     */
    fun start(intervalMillis: Long = 30_000L) {
        if (running) return
        running = true
        ensureChannels()
        scope.launch {
            while (isActive) {
                poll()
                delay(intervalMillis)
            }
        }
    }

    fun stop() {
        running = false
        seen.clear()
        HarmonyNotifications.cancelAll(context)
    }

    private fun ensureChannels() { HarmonyNotifications.ensureChannels(context) }

    /**
     * One poll: read the newest unread rows, post the ones this process has not posted, and
     * withdraw notifications for rows that are gone.
     *
     * The state check happens before the permission check so a refused permission does not stop
     * the poll -- the inbox is still read, and the moment the person grants access the next
     * poll delivers what arrived meanwhile.
     */
    private suspend fun poll() {
        val session = auth.state.value as? AuthState.SignedIn ?: return
        val profileId = session.profile?.id ?: return
        val token = runCatching { auth.freshToken() }.getOrNull() ?: return
        val inbox = runCatching {
            repositoryFor(lol.mony.harmony.data.api.Http.rest)
                .fetchNotifications(session.endpoint, token, profileId, limit = 20, unreadOnly = true)
        }.getOrElse { error ->
            _lastError.value = error.message
            return
        }
        _lastError.value = null
        refreshPermission()
        val unread = inbox.filter { !it.isRead }
        val present = unread.map { it.id }.toSet()
        // A row that was read or deleted elsewhere must leave the tray too.
        for (id in seen - present) {
            runCatching { HarmonyNotifications.cancel(context, Notification(id = id, userId = profileId, type = "", createdAt = "")) }
        }
        seen = present.toMutableSet()
        for (notification in unread) HarmonyNotifications.post(context, notification)
    }

    /**
     * Posts a single inbox row the app already knows about, which is what the inbox screen does
     * when the app is in front and a new row arrives.
     */
    fun offer(notification: Notification) {
        if (notification.isRead) return
        seen += notification.id
        HarmonyNotifications.post(context, notification)
    }

    /** Withdraws one row's notification, for a read or deleted row. */
    fun withdraw(notification: Notification) = HarmonyNotifications.cancel(context, notification)
}
