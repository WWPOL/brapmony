package lol.mony.harmony.ui.screens.notifications

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.model.Notification
import lol.mony.harmony.data.model.NotificationInboxFilter
import lol.mony.harmony.data.repo.NotificationRepository

internal data class NotificationInboxState(
    val items: List<Notification> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val nextOffset: Int = 0,
    val loadError: String? = null,
    val actionError: String? = null,
    val busy: Boolean = false,
    val notice: String? = null,
)

/** Composition-scoped inbox. Filter changes create a new instance, so late pages cannot mix tabs. */
internal class NotificationInbox(
    private val repository: NotificationRepository,
    private val endpoint: Endpoint,
    private val profileId: String,
    private val token: suspend () -> String,
    private val filter: NotificationInboxFilter,
    private val unreadOnly: Boolean,
) {
    private val mutableState = MutableStateFlow(NotificationInboxState())
    val state = mutableState.asStateFlow()
    private var loading = false
    private var failedReset = true
    private var failedAction: (suspend () -> Unit)? = null

    suspend fun load(reset: Boolean = true) {
        if (loading || mutableState.value.busy) return
        if (!reset && !mutableState.value.hasMore) return
        loading = true
        failedReset = reset
        val offset = if (reset) 0 else mutableState.value.nextOffset
        mutableState.update { it.copy(loading = reset, loadingMore = !reset, loadError = null) }
        try {
            val page = repository.fetchNotifications(endpoint, token(), profileId, PAGE_SIZE, offset, unreadOnly, filter.types)
            mutableState.update { current ->
                current.copy(
                    items = (if (reset) page else current.items + page).distinctBy { it.id },
                    nextOffset = offset + page.size,
                    hasMore = page.size == PAGE_SIZE,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableState.update { it.copy(loadError = error.message ?: "Couldn't load notifications. Check your connection and retry.") }
        } finally {
            loading = false
            mutableState.update { it.copy(loading = false, loadingMore = false) }
        }
    }

    suspend fun retryLoad() = load(failedReset)
    suspend fun retryAction() { failedAction?.invoke() }
    fun clearNotice() { mutableState.update { it.copy(notice = null) } }

    suspend fun setRead(notification: Notification) = perform("Couldn't update this notification") {
        val updated = if (notification.isRead) {
            repository.markAsUnread(endpoint, token(), profileId, notification.id)
        } else {
            repository.markAsRead(endpoint, token(), profileId, notification.id)
        }
        replace(updated)
    }

    suspend fun open(notification: Notification, onOpened: (Notification) -> Unit) = perform("Couldn't open this notification") {
        val updated = repository.markAsClicked(endpoint, token(), profileId, notification.id)
        replace(updated)
        onOpened(updated)
    }

    suspend fun markAllRead() = perform("Couldn't mark all notifications as read") {
        repository.markAllAsRead(endpoint, token(), profileId)
        mutableState.update { current ->
            if (unreadOnly) current.copy(items = emptyList(), hasMore = false, nextOffset = 0, notice = "All notifications marked as read")
            else current.copy(items = current.items.map { it.copy(isRead = true) }, notice = "All notifications marked as read")
        }
    }

    suspend fun delete(notification: Notification) = perform("Couldn't delete this notification") {
        repository.deleteNotification(endpoint, token(), profileId, notification.id)
        remove { it.id == notification.id }
        mutableState.update { it.copy(notice = "Notification deleted") }
    }

    suspend fun deleteAll() = perform("Couldn't delete notifications") {
        repository.deleteAllNotifications(endpoint, token(), profileId)
        mutableState.update { it.copy(items = emptyList(), hasMore = false, nextOffset = 0, notice = "All notifications deleted") }
    }

    suspend fun respond(notification: Notification, accept: Boolean) = perform("Couldn't respond to the follow request") {
        val followerId = notification.followerId ?: error("This notification doesn't identify a follower. Refresh the inbox.")
        repository.respondToFollowRequest(endpoint, token(), profileId, followerId, accept)
        // The server's clear_follow_request_notification trigger deletes every matching request.
        remove { it.isFollowRequest && it.followerId == followerId }
        mutableState.update { it.copy(notice = if (accept) "Follow request accepted" else "Follow request declined") }
    }

    private fun replace(notification: Notification) {
        if (unreadOnly && notification.isRead) remove { it.id == notification.id }
        else mutableState.update { current -> current.copy(items = current.items.map { if (it.id == notification.id) notification else it }) }
    }

    /** Offset follows server membership changes, not the de-duplicated display list size. */
    private fun remove(predicate: (Notification) -> Boolean) {
        mutableState.update { current ->
            val remaining = current.items.filterNot(predicate)
            current.copy(items = remaining, nextOffset = (current.nextOffset - (current.items.size - remaining.size)).coerceAtLeast(0))
        }
    }

    private suspend fun perform(label: String, action: suspend () -> Unit) {
        if (mutableState.value.busy || loading) return
        failedAction = { perform(label, action) }
        mutableState.update { it.copy(busy = true, actionError = null) }
        try {
            action()
            failedAction = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableState.update { it.copy(actionError = "$label. ${error.message ?: "Check your connection and retry."}") }
        } finally {
            mutableState.update { it.copy(busy = false) }
        }
    }

    private companion object { const val PAGE_SIZE = 20 }
}
