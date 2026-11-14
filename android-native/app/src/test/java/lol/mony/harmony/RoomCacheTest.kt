package lol.mony.harmony

import lol.mony.harmony.data.model.Message
import lol.mony.harmony.ui.screens.chat.RoomCache
import lol.mony.harmony.ui.screens.chat.RoomRow
import lol.mony.harmony.ui.screens.chat.RowState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The message-list merge rules: what a refresh may add, replace and drop.
 *
 * Every case here is a way the list used to go wrong -- a message shown twice after a refresh,
 * a pending send vanishing under a refresh that raced it, a deleted message staying on screen,
 * an edit landing as a second copy -- so each one is a regression this pins.
 */
class RoomCacheTest {
    private fun message(id: String, at: String, text: String = id, nonce: String? = null) = Message(
        id = id, channelId = "c1", userId = "u1",
        content = listOf(lol.mony.harmony.data.model.ContentPart.text(text)),
        createdAt = at,
        metadata = nonce?.let { value ->
            kotlinx.serialization.json.buildJsonObject { put("client_nonce", kotlinx.serialization.json.JsonPrimitive(value)) }
        },
    )

    @Test fun aRefreshThatRepeatsAMessageDoesNotDuplicateIt() {
        val existing = listOf(RoomRow("m1", message("m1", "2026-01-01T00:00:01Z")))
        val merged = RoomCache.reconcile(existing, listOf(message("m1", "2026-01-01T00:00:01Z")))
        assertEquals(1, merged.size)
    }

    @Test fun aPendingSendSurvivesARefreshThatDoesNotMentionIt() {
        val pending = RoomRow("local", message("local", "2026-01-01T00:00:09Z"), RowState.Sending, "n1")
        val existing = listOf(RoomRow("m1", message("m1", "2026-01-01T00:00:01Z")), pending)
        val merged = RoomCache.reconcile(existing, listOf(message("m1", "2026-01-01T00:00:01Z")))
        assertTrue(merged.any { it.key == "local" && it.isPending })
    }

    @Test fun anAcknowledgedRowReplacesItsPendingOneByNonce() {
        val pending = RoomRow("local", message("local", "2026-01-01T00:00:09Z"), RowState.Sending, "n1")
        val existing = listOf(RoomRow("m1", message("m1", "2026-01-01T00:00:01Z")), pending)
        val acked = message("server", "2026-01-01T00:00:09Z", nonce = "n1")
        val merged = RoomCache.reconcile(existing, listOf(message("m1", "2026-01-01T00:00:01Z"), acked))
        assertEquals(listOf("m1", "server"), merged.map { it.key })
        assertTrue(merged.none { it.isPending })
    }

    @Test fun anEditLandsInPlaceRatherThanAsASecondRow() {
        val existing = listOf(RoomRow("m1", message("m1", "2026-01-01T00:00:01Z", "before")))
        val merged = RoomCache.reconcile(existing, listOf(message("m1", "2026-01-01T00:00:01Z", "after")))
        assertEquals(1, merged.size)
        assertEquals("after", merged.single().message.plainText)
    }

    @Test fun aMessageMissingFromInsideThePagesWindowWasDeleted() {
        val existing = listOf(
            RoomRow("m1", message("m1", "2026-01-01T00:00:01Z")),
            RoomRow("m2", message("m2", "2026-01-01T00:00:02Z")),
            RoomRow("m3", message("m3", "2026-01-01T00:00:03Z")),
        )
        // The page spans 01..03 and does not contain 02, so 02 was deleted on the instance.
        val merged = RoomCache.reconcile(existing, listOf(
            message("m1", "2026-01-01T00:00:01Z"),
            message("m3", "2026-01-01T00:00:03Z")))
        assertEquals(listOf("m1", "m3"), merged.map { it.key })
    }

    @Test fun aOneRowPageProvesNothingAboutDeletion() {
        val existing = listOf(
            RoomRow("m1", message("m1", "2026-01-01T00:00:01Z")),
            RoomRow("m2", message("m2", "2026-01-01T00:00:02Z")),
        )
        val merged = RoomCache.reconcile(existing, listOf(message("m2", "2026-01-01T00:00:02Z")))
        assertEquals(listOf("m1", "m2"), merged.map { it.key })
    }

    @Test fun rowsStayInTimeOrderWhicheverPageBringsThem() {
        val existing = listOf(RoomRow("m3", message("m3", "2026-01-01T00:00:03Z")))
        // An older page is additive: it never drops what the newest page already proved exists.
        val merged = RoomCache.reconcile(existing, listOf(message("m1", "2026-01-01T00:00:01Z")),
            replaceTail = false)
        assertEquals(listOf("m1", "m3"), merged.map { it.key })
    }

    @Test fun aRowOlderThanThePageIsKeptBecauseThePageSaysNothingAboutIt() {
        val existing = listOf(RoomRow("old", message("old", "2026-01-01T00:00:01Z")))
        val merged = RoomCache.reconcile(existing, listOf(message("new", "2026-01-01T00:00:09Z")))
        assertEquals(listOf("old", "new"), merged.map { it.key })
    }

    @Test fun aFailedSendKeepsItsPlaceAndItsReason() {
        val failed = RoomRow("local", message("local", "2026-01-01T00:00:09Z"), RowState.Failed("Not sent."), "n1")
        val merged = RoomCache.reconcile(listOf(failed), emptyList())
        assertTrue(merged.single().isFailed)
        assertEquals("Not sent.", (merged.single().state as RowState.Failed).reason)
    }

    @Test fun signingOutForgetsEveryRoom() {
        val cache = RoomCache()
        cache.put("a", lol.mony.harmony.ui.screens.chat.RoomSnapshot(rows = listOf(
            RoomRow("m1", message("m1", "2026-01-01T00:00:01Z")))))
        cache.clear()
        assertTrue(cache.rooms.value.isEmpty())
    }
}
