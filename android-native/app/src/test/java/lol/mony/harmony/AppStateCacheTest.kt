package lol.mony.harmony

import lol.mony.harmony.data.model.Channel
import lol.mony.harmony.data.model.Thread
import lol.mony.harmony.ui.AppState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The state the channel list and the DM list are drawn from: which threads belong to which
 * channel, and what a cache is allowed to keep.
 *
 * Two bugs sit here. An open thread did not appear in the side channel list at all, because
 * nothing remembered it per channel; and every navigation re-read a list that had not
 * changed, because nothing was kept. A cache must also never outlive the account that filled
 * it, which is the leak these tests pin.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AppStateCacheTest {
    private fun state(): AppState =
        AppState(lol.mony.harmony.AppContainer(org.robolectric.RuntimeEnvironment.getApplication()))

    private fun thread(id: String, channel: String, name: String = id) =
        Thread(id = id, channelId = channel, name = name, messageCount = 3)

    // ---- threads belong to their channel ---------------------------------------------------

    @Test fun anOpenThreadIsListedUnderItsOwnChannel() {
        val state = state()
        state.rememberThreads("c1", listOf(thread("t1", "c1", "Release planning")))
        state.rememberThreads("c2", listOf(thread("t2", "c2")))

        val byChannel = state.threadsByChannel.value
        assertEquals(listOf("t1"), byChannel.getValue("c1").map { it.id })
        assertEquals(listOf("t2"), byChannel.getValue("c2").map { it.id })
    }

    @Test fun aNewThreadAppearsWithoutWaitingForAReload() {
        val state = state()
        state.rememberThreads("c1", listOf(thread("old", "c1")))
        state.rememberThread(thread("fresh", "c1"))

        assertEquals(listOf("fresh", "old"), state.threadsByChannel.value.getValue("c1").map { it.id })
    }

    @Test fun rememberingTheSameThreadTwiceDoesNotDuplicateIt() {
        val state = state()
        state.rememberThread(thread("t1", "c1"))
        state.rememberThread(thread("t1", "c1"))
        assertEquals(1, state.threadsByChannel.value.getValue("c1").size)
    }

    // ---- caches ----------------------------------------------------------------------------

    @Test fun aChannelTreeIsKeptSoNavigationDoesNotRefetchIt() {
        val state = state()
        val tree = lol.mony.harmony.data.model.ServerTree(
            server = lol.mony.harmony.data.model.Server(id = "s1", name = "Server"),
            categories = emptyList(),
            channels = listOf(Channel(id = "c1", serverId = "s1", name = "general")),
        )
        state.serverTrees.value = state.serverTrees.value + ("s1" to tree)
        assertEquals("general", state.serverTrees.value.getValue("s1").channels.single().name)
    }

    @Test fun signingOutDropsEveryCachedList() {
        val state = state()
        state.rememberThreads("c1", listOf(thread("t1", "c1")))
        state.channelsByServer.value = mapOf("s1" to listOf(Channel(id = "c1", serverId = "s1", name = "general")))
        state.conversations.value = emptyList()

        state.forgetCachedLists()

        assertTrue(state.threadsByChannel.value.isEmpty())
        assertTrue(state.channelsByServer.value.isEmpty())
        assertTrue(state.serverTrees.value.isEmpty())
        assertNull(state.conversations.value)
    }
}
