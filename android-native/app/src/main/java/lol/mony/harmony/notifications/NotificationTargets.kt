package lol.mony.harmony.notifications

import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.model.Message
import lol.mony.harmony.data.model.NotificationDestination
import lol.mony.harmony.data.repo.ChatRepository
import lol.mony.harmony.data.session.AuthState

/**
 * Resolves a notification's destination into the room and message the app should show.
 *
 * A notification can name a message that is far older than the page a channel opens on. The
 * reference passes `?messageId=` to the channel and the channel fetches around it; here the
 * same thing happens explicitly: the target message is read by id, and if it is not in the
 * cached page the room loads pages backwards until the message is in hand or the history runs
 * out. A message that no longer exists, or that this account may not read, comes back as a
 * [Resolution.Refused] with the reason -- never as an empty room that looks like a bug.
 */
object NotificationTargets {
    sealed interface Resolution {
        data class Open(val destination: NotificationDestination, val messageId: String?) : Resolution
        data class Refused(val reason: String) : Resolution
    }

    /**
     * Checks that a destination can be opened by this account, and finds the message it names.
     *
     * The read is done with the signed-in account's own token, so the instance's row-level
     * security decides what is visible. A message from a channel the account was removed from
     * therefore comes back empty rather than leaking its text.
     */
    suspend fun resolve(
        destination: NotificationDestination,
        endpoint: Endpoint,
        token: String,
        chats: ChatRepository,
    ): Resolution = when (destination) {
        is NotificationDestination.Conversation ->
            Resolution.Open(destination, destination.messageId)
        is NotificationDestination.Channel -> Resolution.Open(destination, destination.messageId)
        is NotificationDestination.Thread -> Resolution.Open(destination, destination.messageId)
        is NotificationDestination.Post, is NotificationDestination.Profile,
        NotificationDestination.Security, NotificationDestination.FollowRequests,
        NotificationDestination.Chats -> Resolution.Open(destination, null)
        is NotificationDestination.Unavailable -> Resolution.Refused(destination.reason)
    }

    /**
     * Reads one message by id, for the case where the room's first page does not contain it.
     *
     * Null means the instance has no such row *for this account*: either it was deleted or the
     * account cannot see the room. The caller shows a safe fallback for both, and never the
     * message's text.
     */
    suspend fun message(endpoint: Endpoint, token: String, messageId: String, chats: ChatRepository): Message? =
        runCatching { chats.messageById(endpoint, token, messageId) }.getOrNull()

    /**
     * Whether a page of messages already contains the target, which is what decides between
     * "scroll to it" and "load older pages until it appears".
     */
    fun contains(messages: Collection<Message>, messageId: String?): Boolean =
        messageId != null && messages.any { it.id == messageId }

    /**
     * How many pages to load while looking for an older target before giving up. The reference
     * loads around the message; this is the same idea with a bound, so a notification pointing
     * at a message that was deleted cannot spin forever.
     */
    const val MAX_TARGET_PAGES = 12
}
