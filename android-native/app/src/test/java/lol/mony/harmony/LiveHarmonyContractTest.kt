package lol.mony.harmony

import kotlinx.coroutines.runBlocking
import lol.mony.harmony.data.api.*
import lol.mony.harmony.data.repo.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in tests against e2e/stack.sh, with only a real user's JWT. No admin key enters the app. */
class LiveHarmonyContractTest {
    @Test fun actualNativeRepositoriesRoundTripChannelThreadReactionPinAndDm() = runBlocking {
        val url = System.getenv("NATIVE_CONTRACT_URL")
        assumeTrue("Set NATIVE_CONTRACT_URL/EMAIL/PASSWORD for a real-stack run", !url.isNullOrBlank())
        val endpoint = Endpoint(url!!, System.getenv("NATIVE_CONTRACT_ANON") ?: "local-e2e")
        val api = AuthApi(OkHttpClient())
        val login = api.signInWithPassword(endpoint, System.getenv("NATIVE_CONTRACT_EMAIL"), System.getenv("NATIVE_CONTRACT_PASSWORD"))
        check(login is AuthApi.Result.Ok) { "Real GoTrue sign-in failed: $login" }
        val token = login.value.accessToken
        val rest = RestApi(OkHttpClient())
        val profiles = ProfileRepository(rest)
        val profile = profiles.mine(endpoint, token)!!.profile
        assertNotEquals("Fixture must cover profile/auth IDs differing", login.value.user!!.id, profile.id)
        val serverRepository = ServerRepository(rest)
        val server = serverRepository.servers(endpoint, token, profile.id).getOrThrow().first().server
        val channel = serverRepository.tree(endpoint, token, server).getOrThrow().channels.first { it.type == 0 }
        val chat = ChatRepository(rest)
        val marker = "Native contract ${System.nanoTime()} — quote \" and slash \\"
        val message = chat.sendChannelMessage(endpoint, token, channel.id, marker).getOrThrow()
        try {
            assertEquals(profile.id, message.userId)
            assertEquals(marker, chat.channelMessages(endpoint, token, channel.id).getOrThrow().first { it.id == message.id }.plainText)
            val edited = chat.editMessage(endpoint, token, message.id, "$marker edited").getOrThrow()
            assertTrue(edited.plainText.endsWith("edited"))
            chat.addReaction(endpoint, token, message.id, profile.id, "👍").getOrThrow()
            assertEquals("👍", chat.reactionsFor(endpoint, token, listOf(message.id)).getOrThrow()[message.id]!!.first().emoji)
            chat.removeReaction(endpoint, token, message.id, profile.id, "👍").getOrThrow()
            chat.pin(endpoint, token, message.id, true).getOrThrow()
            assertTrue(chat.pinned(endpoint, token, channel.id).getOrThrow().any { it.id == message.id })
            chat.pin(endpoint, token, message.id, false).getOrThrow()
            val thread = chat.createThread(endpoint, token, channel.id, message.id, "Native contract thread").getOrThrow()
            val response = chat.sendChannelMessage(endpoint, token, channel.id, "Thread reply", threadId = thread.id).getOrThrow()
            assertTrue(chat.threadMessages(endpoint, token, thread.id).getOrThrow().any { it.id == response.id })
            chat.deleteMessage(endpoint, token, response.id).getOrThrow()
            chat.markChannelRead(endpoint, token, channel.id, message.id)
            chat.unreadCounts(endpoint, token)
            val dm = chat.conversations(endpoint, token, profile.id, profiles).getOrThrow().first()
            val direct = chat.sendDirectMessage(endpoint, token, dm.conversation.id, marker).getOrThrow()
            try {
                assertTrue(chat.directMessages(endpoint, token, dm.conversation.id).getOrThrow().any { it.id == direct.id })
                chat.markConversationRead(endpoint, token, dm.conversation.id, direct.id)
            } finally { chat.deleteMessage(endpoint, token, direct.id).getOrThrow() }
        } finally { chat.deleteMessage(endpoint, token, message.id).getOrThrow() }
        println("PASS real GoTrue + differing profile/auth IDs + channel CRUD + reaction + pin + thread + DM + read markers")
    }
}
