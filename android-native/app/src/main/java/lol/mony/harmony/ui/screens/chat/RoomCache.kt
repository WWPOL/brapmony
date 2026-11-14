package lol.mony.harmony.ui.screens.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import lol.mony.harmony.data.model.Message
import lol.mony.harmony.data.model.Reaction

/** Where one row is in its life: confirmed by the instance, still sending, or failed. */
sealed interface RowState {
    data object Sent : RowState
    data object Sending : RowState
    data class Failed(val reason: String) : RowState
}

/**
 * One row as the message list draws it.
 *
 * [key] is the instance's id once the message is confirmed, and a local key while it is still
 * being sent. [nonce] is what ties a pending row to the row the instance answers with, so the
 * acknowledged message replaces the pending one instead of appearing twice.
 */
data class RoomRow(
    val key: String,
    val message: Message,
    val state: RowState = RowState.Sent,
    val nonce: String? = null,
) {
    val isPending: Boolean get() = state is RowState.Sending
    val isFailed: Boolean get() = state is RowState.Failed
}

/**
 * What one open room holds: the rows on screen, the reactions already read, and how far back the
 * instance has been asked.
 *
 * Keeping it is what makes a revisit instant: the screen paints [rows] before it asks the
 * network anything, then reconciles the answer into the same list instead of blanking it.
 */
data class RoomSnapshot(
    val rows: List<RoomRow> = emptyList(),
    val reactions: Map<String, List<Reaction>> = emptyMap(),
    /** The oldest confirmed row's `created_at`; null until a page has been read. */
    val oldest: String? = null,
    /** False once a page came back short, which is the only proof there is nothing older. */
    val moreOlder: Boolean = true,
    /** True once a page has been read, so an empty list means empty rather than unread. */
    val loaded: Boolean = false,
)

/**
 * The message lists this app has read, one per room and account.
 *
 * Everything that changes a room goes through [reconcile], which merges rather than replaces.
 * That is what stops a refresh from duplicating a message, losing a pending one, or throwing
 * away the history already loaded.
 */
class RoomCache {
    private val _rooms = MutableStateFlow<Map<String, RoomSnapshot>>(emptyMap())
    val rooms: StateFlow<Map<String, RoomSnapshot>> = _rooms.asStateFlow()

    fun snapshot(key: String): RoomSnapshot? = _rooms.value[key]

    fun put(key: String, snapshot: RoomSnapshot) {
        _rooms.update { it + (key to snapshot) }
    }

    fun update(key: String, change: (RoomSnapshot) -> RoomSnapshot) {
        _rooms.update { current -> current + (key to change(current[key] ?: RoomSnapshot())) }
    }

    /** Adds a row this device is sending, at the end of the list. */
    fun appendPending(key: String, row: RoomRow) = update(key) { it.copy(rows = it.rows + row) }

    /** Drops a row by key, which is what a cancelled or discarded send does. */
    fun remove(key: String, rowKey: String) =
        update(key) { it.copy(rows = it.rows.filterNot { row -> row.key == rowKey }) }

    /** Replaces one row in place, keeping the list's order. */
    fun replace(key: String, rowKey: String, row: RoomRow) = update(key) { snapshot ->
        snapshot.copy(rows = snapshot.rows.map { if (it.key == rowKey) row else it })
    }

    fun reactions(key: String, value: Map<String, List<Reaction>>) =
        update(key) { it.copy(reactions = it.reactions + value) }

    /** Forgets every room, which is what signing out or changing account must do. */
    fun clear() { _rooms.value = emptyMap() }

    companion object {
        /**
         * Merges [page], the instance's answer for a room, into the rows already held.
         *
         * A row the list already holds keeps its place and takes the instance's version, so an
         * edit lands without the list jumping. A pending send whose nonce the page carries
         * becomes the acknowledged row. A pending or failed send the page does not mention stays
         * put: the person's own message must not vanish because a refresh raced it. When
         * [replaceTail] is set the page describes the newest end of the room, so a confirmed row
         * inside the page's window that the page no longer contains was deleted on the instance.
         */
        fun reconcile(existing: List<RoomRow>, page: List<Message>, replaceTail: Boolean = true): List<RoomRow> {
            val byId = page.associateBy { it.id }
            val nonces = page.mapNotNull { it.clientNonce }.toSet()
            val oldestIncoming = page.minOfOrNull { it.createdAt }
            val kept = mutableListOf<RoomRow>()
            for (row in existing) {
                when {
                    row.nonce != null && row.nonce in nonces -> Unit
                    row.message.id in byId -> kept += row.copy(
                        message = byId.getValue(row.message.id), state = RowState.Sent, nonce = null,
                    )
                    row.state !is RowState.Sent -> kept += row
                    replaceTail && oldestIncoming != null && row.message.createdAt >= oldestIncoming -> Unit
                    else -> kept += row
                }
            }
            val known = kept.map { it.message.id }.toSet()
            for (message in page) if (message.id !in known) kept += RoomRow(message.id, message)
            return kept.distinctBy { it.key }
                .sortedWith(compareBy({ it.message.createdAt }, { it.message.id }))
        }
    }
}
