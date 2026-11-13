package lol.mony.harmony.data

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.api.RestApi
import lol.mony.harmony.data.model.DriveFile
import lol.mony.harmony.data.model.DriveQuery
import lol.mony.harmony.data.model.DriveView
import lol.mony.harmony.data.model.HarmonyJson
import lol.mony.harmony.data.repo.DriveRepository
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in, real GoTrue + PostgREST + storage gateway contract (no emulator or federation worker proof).
 * HARMONY_DRIVE_CONTRACT_URL=http://localhost:54480 enables it; no fixture credentials are read.
 * Creates two disposable ordinary users and their profiles like NewProfile.vue. Their rows
 * remain in the local test instance; files/folders and sessions are cleaned up. Never use prod.
 */
class DriveLocalContractTest {
    @Test fun `real ordinary users enforce Drive ownership file lifecycle and link redaction`() = runBlocking {
        val url = System.getenv("HARMONY_DRIVE_CONTRACT_URL")
        assumeTrue("Set HARMONY_DRIVE_CONTRACT_URL to opt in to the disposable local backend test.", !url.isNullOrBlank())
        val parsed = java.net.URI(url!!)
        require(parsed.host in setOf("localhost", "127.0.0.1", "10.0.2.2")) { "This contract only runs against a local disposable server." }
        val endpoint = Endpoint(url.trimEnd('/'), "local-e2e")
        val client = OkHttpClient.Builder().followRedirects(false).build()
        val rest = RestApi(client)
        val repo = DriveRepository(rest, client)
        val owner = signup(client, endpoint)
        val outsider = signup(client, endpoint)
        var root: String? = null
        val files = mutableListOf<String>()
        try {
            assertTrue(repo.usage(endpoint, owner.token).getOrThrow().enabled)
            val folder = repo.createFolder(endpoint, owner.token, "Native Drive contract ${UUID.randomUUID()}").getOrThrow()
            root = folder.id
            val nested = repo.createFolder(endpoint, owner.token, "Nested", folder.id).getOrThrow()
            assertTrue(repo.createFolder(endpoint, owner.token, "Nested", folder.id).isFailure)
            assertTrue(repo.moveFolder(endpoint, owner.token, folder.id, nested.id).isFailure)
            assertFalse(repo.folders(endpoint, outsider.token).getOrThrow().any { it.id == folder.id })
            assertTrue(repo.renameFolder(endpoint, outsider.token, folder.id, "Not allowed").isFailure)
            val renamed = repo.renameFolder(endpoint, owner.token, nested.id, "Renamed nested").getOrThrow()
            assertEquals("Renamed nested", renamed.name)

            val bytes = "Harmony native Drive contract bytes\n".toByteArray()
            val name = "native-drive-${UUID.randomUUID()}.txt"
            val path = upload(client, endpoint, owner, name, bytes, folder.id, temporary = false)
            var file = repo.files(endpoint, owner.token, DriveQuery(folderId = folder.id, search = name)).getOrThrow().entries.single().file
            files.add(file.id)
            assertEquals(path, file.path)
            assertEquals("drive", file.state)
            assertEquals(owner.profileId, file.ownerId)
            assertFalse(repo.files(endpoint, outsider.token, DriveQuery(view = DriveView.All, search = name)).getOrThrow().entries.any { it.file.id == file.id })
            assertFalse(repo.card(endpoint, outsider.token, file.id).getOrThrow().access)
            assertTrue(repo.renameFile(endpoint, outsider.token, file.id, "Not allowed").isFailure)
            assertEquals(0, repo.trash(endpoint, outsider.token, listOf(file.id)).getOrThrow())
            assertTrue(repo.viewUrl(endpoint, outsider.token, file).isFailure)

            file = repo.renameFile(endpoint, owner.token, file.id, "native & # +.txt").getOrThrow()
            assertEquals("native & # +.txt", file.name)
            assertEquals(1, repo.moveFiles(endpoint, owner.token, listOf(file.id), nested.id).getOrThrow())
            assertTrue(repo.files(endpoint, owner.token, DriveQuery(folderId = nested.id)).getOrThrow().entries.any { it.file.id == file.id })
            val output = ByteArrayOutputStream()
            assertEquals(bytes.size.toLong(), repo.saveTo(endpoint, owner.token, file, output).getOrThrow())
            assertArrayEquals(bytes, output.toByteArray())

            val ownerCard = repo.card(endpoint, owner.token, file.id).getOrThrow()
            assertTrue(ownerCard.access && ownerCard.own)
            val originalToken = ownerCard.token!!
            assertFalse(repo.cardByToken(endpoint, outsider.token, originalToken).getOrThrow().access)
            val public = repo.setLinkAccess(endpoint, owner.token, file.id, true, Instant.now().plusSeconds(3600).toString(), false).getOrThrow()
            assertTrue(public.isPublic)
            val publicCard = repo.cardByToken(endpoint, outsider.token, public.token).getOrThrow()
            assertEquals("link", publicCard.via)
            assertTrue(publicCard.access)
            assertNull(publicCard.id)
            assertNull(publicCard.path)
            assertNull(publicCard.owner)
            assertTrue(repo.setLinkAccess(endpoint, outsider.token, file.id, true).isFailure)
            assertTrue(repo.resetLink(endpoint, outsider.token, file.id).isFailure)
            assertTrue(repo.setLinkAccess(endpoint, owner.token, file.id, true, "2000-01-01T00:00:00Z").isFailure)

            val reset = repo.resetLink(endpoint, owner.token, file.id).getOrThrow()
            assertNotEquals(public.token, reset.token)
            assertFalse(repo.cardByToken(endpoint, outsider.token, public.token).getOrThrow().access)
            assertTrue(repo.cardByToken(endpoint, outsider.token, reset.token).getOrThrow().access)
            repo.setLinkAccess(endpoint, owner.token, file.id, false).getOrThrow()
            assertFalse(repo.cardByToken(endpoint, outsider.token, reset.token).getOrThrow().access)

            assertEquals(1, repo.trash(endpoint, owner.token, listOf(file.id)).getOrThrow())
            assertTrue(repo.files(endpoint, owner.token, DriveQuery(view = DriveView.Trash)).getOrThrow().entries.any { it.file.id == file.id })
            assertEquals(1, repo.restore(endpoint, owner.token, listOf(file.id)).getOrThrow())
            assertEquals(1, repo.deleteForever(endpoint, owner.token, listOf(file.id)).getOrThrow())
            assertFalse(repo.cardByToken(endpoint, outsider.token, reset.token).getOrThrow().access)

            val temporaryName = "temporary-${UUID.randomUUID()}.txt"
            upload(client, endpoint, owner, temporaryName, bytes, folder.id, temporary = true)
            val temporary = repo.files(endpoint, owner.token, DriveQuery(view = DriveView.Temporary, search = temporaryName)).getOrThrow().entries.single().file
            files.add(temporary.id)
            assertEquals("ephemeral", temporary.state)
            assertEquals(1, repo.keep(endpoint, owner.token, listOf(temporary.id)).getOrThrow())
            assertEquals("drive", repo.card(endpoint, owner.token, temporary.id).getOrThrow().state)
            val removed = repo.deleteFolder(endpoint, owner.token, folder.id).getOrThrow()
            assertEquals(1, removed)
            assertFalse(repo.folders(endpoint, owner.token).getOrThrow().any { it.id == folder.id || it.id == nested.id })
            root = null
            println("Real Drive contract: folder ownership/cycle/duplicate; tracked upload; private signing and exact bytes; restricted/public/reset link redaction; trash/restore/delete; temporary keep; recursive deletion verified.")
        } finally {
            if (files.isNotEmpty()) repo.deleteForever(endpoint, owner.token, files)
            root?.let { repo.deleteFolder(endpoint, owner.token, it) }
            logout(client, endpoint, owner)
            logout(client, endpoint, outsider)
        }
    }

    // Deliberately different profile/auth ids catch accidental auth.uid/profile-id joins.
    private data class Account(val id: String, val token: String, val profileId: String = UUID.randomUUID().toString())

    private suspend fun signup(client: OkHttpClient, endpoint: Endpoint): Account {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
        val username = "native_drive_$suffix"
        val body = buildJsonObject {
            put("email", "$username@example.local")
            put("password", UUID.randomUUID().toString() + "aA1!")
        }.toString()
        val request = Request.Builder().url(endpoint.authUrl + "/signup").header("apikey", endpoint.anonKey)
            .post(body.toRequestBody("application/json".toMediaType())).build()
        val account = client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Local contract signup failed (${response.code}); no credentials are printed." }
            val answer = HarmonyJson.parseToJsonElement(response.body.string()).jsonObject
            Account(answer.getValue("user").jsonObject.getValue("id").jsonPrimitive.content,
                answer["access_token"]?.jsonPrimitive?.content ?: error("Local signup did not grant a session; email verification is required."))
        }
        val domainRows = RestApi(client).select(endpoint, account.token, "instance_config", "config_value", listOf("config_key" to "eq.domain"),
            serializer = ListSerializer(JsonObject.serializer())).getOrThrow()
        val domain = domainRows.single().getValue("config_value").jsonPrimitive.content
        val actor = "https://$domain/users/$username"
        val profile = buildJsonObject {
            put("id", account.profileId)
            put("auth_user_id", account.id)
            put("username", username)
            put("display_name", "Native Drive contract")
            put("color", "#0EA5E9")
            put("is_local", true)
            put("domain", domain)
            put("federated_id", actor)
            put("inbox_url", "$actor/inbox")
            put("outbox_url", "$actor/outbox")
            put("followers_url", "$actor/followers")
            put("following_url", "$actor/following")
        }
        RestApi(client).insert(endpoint, account.token, "profiles", profile.toString(), serializer = ListSerializer(JsonObject.serializer())).getOrThrow()
        return account
    }

    private fun upload(client: OkHttpClient, endpoint: Endpoint, account: Account, name: String, bytes: ByteArray, folder: String, temporary: Boolean): String {
        val path = "u/${account.id}/${UUID.randomUUID()}.txt"
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val metadata = buildJsonObject { put("name", name); put("sha256", hash); put("folder", folder); put("ephemeral", temporary) }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("cacheControl", "3600")
            .addFormDataPart("metadata", metadata.toString())
            .addFormDataPart("", name, bytes.toRequestBody("text/plain".toMediaType())).build()
        val request = Request.Builder().url(endpoint.baseUrl + "/storage/v1/object/message_media/" + path)
            .header("apikey", endpoint.anonKey).header("Authorization", "Bearer ${account.token}")
            .header("x-upsert", "false").post(body).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Local contract storage upload failed (${response.code}); no credentials are printed." }
        }
        return path
    }

    private fun logout(client: OkHttpClient, endpoint: Endpoint, account: Account) {
        val request = Request.Builder().url(endpoint.authUrl + "/logout").header("apikey", endpoint.anonKey)
            .header("Authorization", "Bearer ${account.token}").post("".toRequestBody()).build()
        runCatching { client.newCall(request).execute().close() }
    }
}
