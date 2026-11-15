package lol.mony.harmony

import kotlinx.serialization.builtins.ListSerializer
import lol.mony.harmony.data.model.HarmonyJson
import lol.mony.harmony.data.model.UnreadCountRow
import lol.mony.harmony.ui.AppState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The unread badges come from `get_unread_counts`, and the field it counts is `unread_messages`.
 *
 * The reported symptom was simpler than a badge drawing wrongly: no badge ever drew, at any
 * count, on any channel, conversation or server. The row decoded the count from `unread_count`,
 * which is not a column of the function's `RETURNS TABLE`
 * (`db_schema/migrations/20261011100001_unread_badge_fixes.sql`) nor of the `unread_counts`
 * table it reads. `HarmonyJson` sets `ignoreUnknownKeys`, so the field the instance did send was
 * dropped in silence and the default 0 stood in for every count.
 *
 * These tests decode the exact JSON the function returns -- the columns of that migration's
 * `RETURNS TABLE`, in its order -- rather than constructing a row in Kotlin, because a row built
 * by hand in Kotlin would use whichever name the class declares and pass either way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class UnreadBadgeTest {
    private fun state(): AppState =
        AppState(lol.mony.harmony.AppContainer(org.robolectric.RuntimeEnvironment.getApplication()))

    /**
     * One row as PostgREST writes it: every column of the function's return type, snake_case.
     *
     * `unread_messages` is the count of unread messages and `unread_mentions` the subset that
     * mention the caller. `muted` is an active mute, in which case the count is the one frozen
     * when the mute began.
     */
    private fun row(
        server: String? = null,
        channel: String? = null,
        conversation: String? = null,
        messages: Int = 0,
        mentions: Int = 0,
        muted: Boolean = false,
    ): String = buildString {
        append("{\"id\":\"00000000-0000-4000-8000-000000000001\",")
        append("\"user_id\":\"11111111-1111-4111-8111-111111111111\",")
        append("\"server_id\":").append(server?.let { "\"$it\"" } ?: "null").append(',')
        append("\"channel_id\":").append(channel?.let { "\"$it\"" } ?: "null").append(',')
        append("\"conversation_id\":").append(conversation?.let { "\"$it\"" } ?: "null").append(',')
        append("\"unread_messages\":").append(messages).append(',')
        append("\"unread_mentions\":").append(mentions).append(',')
        append("\"last_read_message_id\":null,")
        append("\"last_read_at\":\"2026-11-14T20:00:00+00:00\",")
        append("\"last_message_at\":\"2026-11-14T21:00:00+00:00\",")
        append("\"muted\":").append(muted).append('}')
    }

    private fun decode(vararg rows: String): List<UnreadCountRow> =
        HarmonyJson.decodeFromString(ListSerializer(UnreadCountRow.serializer()),
            rows.joinToString(",", prefix = "[", postfix = "]"))

    // ---- the field the function actually returns -------------------------------------------

    @Test fun aCountIsReadFromUnreadMessages() {
        val row = decode(row(server = "s1", channel = "c1", messages = 3)).single()
        assertEquals(3, row.unreadMessages)
    }

    /**
     * The same JSON with the old field name decodes to nothing, which is what the bug did.
     *
     * This is not a second test of the fix -- it is the proof that the assertion above can fail.
     * If `unread_messages` were renamed back to `unread_count`, the row above would decode to 0
     * and this one would decode to 3, so the pair pins the name rather than merely exercising a
     * path that would pass either way.
     */
    @Test fun theOldUnreadCountSpellingIsNotWhatTheFunctionSends() {
        val wrong = "{\"channel_id\":\"c1\",\"unread_count\":3}"
        val decoded = decode(wrong).single()
        assertEquals(0, decoded.unreadMessages)
    }

    // ---- what the badge state is built from -------------------------------------------------

    @Test fun aChannelCountBecomesAChannelBadge() {
        val state = state()
        state.applyUnread(decode(row(server = "s1", channel = "c1", messages = 3)))
        assertEquals(3, state.unread.value.channel("c1"))
        assertEquals(3, state.unread.value.serverTotal(listOf("c1", "c2")))
    }

    @Test fun aConversationCountBecomesAConversationBadge() {
        val state = state()
        state.applyUnread(decode(row(conversation = "d1", messages = 7)))
        assertEquals(7, state.unread.value.conversation("d1"))
        assertEquals(0, state.unread.value.channel("d1"))
    }

    @Test fun aMentionIsABadgeOfItsOwnAndDoesNotChangeTheCount() {
        val state = state()
        state.applyUnread(decode(row(server = "s1", channel = "c1", messages = 5, mentions = 2)))
        assertEquals(5, state.unread.value.channel("c1"))
        assertEquals(2, state.unread.value.mentionsIn(listOf("c1")))
    }

    @Test fun aChannelWithNoUnreadMessagesHasNoBadge() {
        val state = state()
        state.applyUnread(decode(row(server = "s1", channel = "c1", messages = 0, mentions = 0)))
        assertTrue(state.unread.value.channels.isEmpty())
    }

    /** The function returns a row per channel, so a server's rail badge is their sum. */
    @Test fun rowsForAWholeServerAreSummedForItsRailBadge() {
        val state = state()
        state.applyUnread(decode(
            row(server = "s1", channel = "c1", messages = 3),
            row(server = "s1", channel = "c2", messages = 4, mentions = 1),
            row(server = "s2", channel = "c3", messages = 9),
        ))
        assertEquals(7, state.unread.value.serverTotal(listOf("c1", "c2")))
        assertEquals(9, state.unread.value.serverTotal(listOf("c3")))
        assertEquals(1, state.unread.value.mentionsIn(listOf("c1", "c2")))
    }
}
