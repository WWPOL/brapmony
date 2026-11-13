package lol.mony.harmony

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import lol.mony.harmony.data.api.*
import lol.mony.harmony.data.model.*
import lol.mony.harmony.data.repo.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class CoreTransportTest {
    @Test fun voidDeleteAccepts204AndKeepsAuthScope() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            val endpoint = Endpoint(server.url("/").toString(), "public-anon")
            RestApi(OkHttpClient()).delete(endpoint, "user-jwt", "messages", listOf("id" to "eq.message-id")).getOrThrow()
            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("Bearer user-jwt", request.getHeader("Authorization"))
            assertEquals("public-anon", request.getHeader("apikey"))
            assertTrue(request.path!!.contains("id=eq.message-id"))
        }
    }

    @Test fun deniedSelectIsNotAnEmptySuccess() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"code":"42501","message":"Permission denied"}"""))
            val result = RestApi(OkHttpClient()).select(Endpoint(server.url("/").toString(), "anon"), "jwt", "messages",
                serializer = ListSerializer(Message.serializer()))
            assertTrue(result.isFailure)
            assertEquals(403, (result.exceptionOrNull() as ApiException).status)
        }
    }

    @Test fun authenticationAcceptsNestedGoTrueMetadata() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"access_token":"access","refresh_token":"refresh","user":{"id":"id","app_metadata":{"provider":"email","providers":["email"]},"user_metadata":{"nested":{"enabled":true}}}}"""))
            val result = AuthApi(OkHttpClient()).signInWithPassword(Endpoint(server.url("/").toString(), "anon"), "a@example.test", "not-a-real-password")
            assertTrue(result is AuthApi.Result.Ok)
            assertEquals("access", (result as AuthApi.Result.Ok).value.accessToken)
        }
    }

    @Test fun numericAuthErrorStillShowsServerSentence() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"code":400,"msg":"Invalid login credentials"}"""))
            val result = AuthApi(OkHttpClient()).signInWithPassword(Endpoint(server.url("/").toString(), "anon"), "a@example.test", "wrong")
            assertEquals("Invalid login credentials", (result as AuthApi.Result.Failed).message)
        }
    }

    @Test fun encryptedChannelFailsBeforeAnyInsert() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"messages_encrypted":true}]"""))
            val result = ChatRepository(RestApi(OkHttpClient())).sendChannelMessage(
                Endpoint(server.url("/").toString(), "anon"), "jwt", "channel", "must not be plaintext")
            assertTrue(result.isFailure)
            assertEquals(1, server.requestCount)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("encryption"))
        }
    }

    @Test fun unsupportedStructuredEditCannotStripAttachments() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"id":"m","user_id":"p","created_at":"2026-01-01T00:00:00Z","content":[{"type":"text","text":"caption"},{"type":"file","url":"https://example.test/photo.jpg"}]}]"""))
            val result = ChatRepository(RestApi(OkHttpClient())).editMessage(
                Endpoint(server.url("/").toString(), "anon"), "jwt", "m", "new caption")
            assertTrue(result.isFailure)
            assertEquals(1, server.requestCount)
            assertEquals("GET", server.takeRequest().method)
        }
    }

    @Test fun schemaModelsDoNotInventReactionOrThreadColumns() {
        val reaction = HarmonyJson.decodeFromString(Reaction.serializer(),
            """{"id":"r","message_id":"m","user_id":"p","custom_emoji_content":"👍","emoji_id":null}""")
        assertEquals("👍", reaction.emoji)
        val thread = HarmonyJson.decodeFromString(lol.mony.harmony.data.model.Thread.serializer(),
            """{"id":"t","channel_id":"c","parent_message_id":"m","name":"Talk","last_message_at":"2026-01-01T00:00:00Z"}""")
        assertEquals("2026-01-01T00:00:00Z", thread.lastActivityAt)
    }
}
