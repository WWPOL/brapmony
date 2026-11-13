package lol.mony.harmony.data.repo

import java.io.OutputStream
import java.net.ProtocolException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import lol.mony.harmony.data.api.ApiException
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.api.Http
import lol.mony.harmony.data.api.RestApi
import lol.mony.harmony.data.model.DriveContextTree
import lol.mony.harmony.data.model.DriveFile
import lol.mony.harmony.data.model.DriveFileCard
import lol.mony.harmony.data.model.DriveFileUsage
import lol.mony.harmony.data.model.DriveFolder
import lol.mony.harmony.data.model.DriveLinkState
import lol.mony.harmony.data.model.DriveListRow
import lol.mony.harmony.data.model.DrivePage
import lol.mony.harmony.data.model.DriveQuery
import lol.mony.harmony.data.model.DriveShare
import lol.mony.harmony.data.model.DriveUsage
import lol.mony.harmony.data.model.HarmonyJson
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor

/**
 * Drive's actual PostgREST contracts, not inferred table writes.
 *
 * Source of truth: src/services/drive/driveService.ts; migrations
 * 20261015100002_drive_api, 20261027100001_drive_link_access and 20261106500001_drive_switch.
 * All IO (including the foundation's blocking REST implementation) runs off Main.
 * Tokens/endpoints come from the caller's shared AppState session, never a second login.
 */
class DriveRepository(
    private val rest: RestApi,
    client: OkHttpClient = Http.client,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    // Do not follow a storage redirect with an account's Authorization/apikey headers.
    private val mediaClient = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .apply {
            // BASIC HTTP logging includes signed-URL bearer tokens in the request line.
            interceptors().removeAll { it is HttpLoggingInterceptor }
            networkInterceptors().removeAll { it is HttpLoggingInterceptor }
        }.build()

    suspend fun usage(endpoint: Endpoint, token: String): Result<DriveUsage> =
        value(endpoint, token, "get_my_drive_usage", serializer = DriveUsage.serializer())

    suspend fun files(endpoint: Endpoint, token: String, query: DriveQuery): Result<DrivePage> = ioResult {
        val args = buildJsonObject {
            put("p_view", query.view.wire)
            putNullable("p_folder", query.folderId)
            putNullable("p_context", query.contextId)
            putNullable("p_query", query.search.trim().takeIf { it.isNotEmpty() })
            putNullable("p_kind", query.kind?.wire)
            put("p_sort", query.sort.wire)
            put("p_limit", query.limit)
            put("p_offset", query.offset)
        }
        val rows = rest.rpc(endpoint, token, "drive_list_files", args.toString(), ListSerializer(DriveListRow.serializer())).getOrThrow()
        DrivePage(rows, rows.firstOrNull()?.totalCount ?: 0, query.offset)
    }

    suspend fun folders(endpoint: Endpoint, token: String): Result<List<DriveFolder>> = ioResult {
        rest.rpc(endpoint, token, "drive_list_folders", serializer = ListSerializer(DriveFolder.serializer())).getOrThrow()
    }

    suspend fun contexts(endpoint: Endpoint, token: String): Result<DriveContextTree> =
        value(endpoint, token, "drive_context_tree", serializer = DriveContextTree.serializer())

    suspend fun fileUsage(endpoint: Endpoint, token: String, fileId: String): Result<DriveFileUsage> =
        value(endpoint, token, "drive_file_usage", fileArgs(fileId), DriveFileUsage.serializer())

    suspend fun createFolder(endpoint: Endpoint, token: String, name: String, parentId: String? = null): Result<DriveFolder> =
        value(endpoint, token, "drive_create_folder", buildJsonObject {
            put("p_name", name.trim())
            putNullable("p_parent", parentId)
        }, DriveFolder.serializer())

    suspend fun renameFolder(endpoint: Endpoint, token: String, folderId: String, name: String): Result<DriveFolder> =
        value(endpoint, token, "drive_rename_folder", buildJsonObject {
            put("p_folder", folderId)
            put("p_name", name.trim())
        }, DriveFolder.serializer())

    suspend fun moveFolder(endpoint: Endpoint, token: String, folderId: String, parentId: String?): Result<DriveFolder> =
        value(endpoint, token, "drive_move_folder", buildJsonObject {
            put("p_folder", folderId)
            putNullable("p_parent", parentId)
        }, DriveFolder.serializer())

    /** Deletes the folder tree; kept files go to trash, temporary files are permanently removed. */
    suspend fun deleteFolder(endpoint: Endpoint, token: String, folderId: String): Result<Int> =
        value(endpoint, token, "drive_delete_folder", buildJsonObject { put("p_folder", folderId) }, Int.serializer())

    suspend fun renameFile(endpoint: Endpoint, token: String, fileId: String, name: String): Result<DriveFile> =
        value(endpoint, token, "drive_rename_file", buildJsonObject {
            put("p_file", fileId)
            put("p_name", name.trim())
        }, DriveFile.serializer())

    suspend fun moveFiles(endpoint: Endpoint, token: String, ids: List<String>, folderId: String?): Result<Int> =
        value(endpoint, token, "drive_move_files", buildJsonObject {
            putIds("p_files", ids)
            putNullable("p_folder", folderId)
        }, Int.serializer())

    suspend fun trash(endpoint: Endpoint, token: String, ids: List<String>): Result<Int> =
        count(endpoint, token, "drive_trash_files", ids)

    suspend fun restore(endpoint: Endpoint, token: String, ids: List<String>): Result<Int> =
        count(endpoint, token, "drive_restore_files", ids)

    suspend fun deleteForever(endpoint: Endpoint, token: String, ids: List<String>): Result<Int> =
        count(endpoint, token, "drive_delete_files", ids)

    /** Returns how many actually fit. Zero is not a successful 'kept' toast. */
    suspend fun keep(endpoint: Endpoint, token: String, ids: List<String>): Result<Int> =
        count(endpoint, token, "drive_keep_files", ids)

    suspend fun emptyTrash(endpoint: Endpoint, token: String): Result<Int> =
        value(endpoint, token, "drive_empty_trash", serializer = Int.serializer())

    suspend fun card(endpoint: Endpoint, token: String, fileId: String): Result<DriveFileCard> =
        value(endpoint, token, "drive_file_card", buildJsonObject {
            put("p_file", fileId)
            put("p_token", JsonNull)
        }, DriveFileCard.serializer())

    suspend fun cardByToken(endpoint: Endpoint, token: String, linkToken: String): Result<DriveFileCard> =
        value(endpoint, token, "drive_file_card", buildJsonObject {
            put("p_file", JsonNull)
            put("p_token", linkToken)
        }, DriveFileCard.serializer())

    suspend fun setLinkAccess(
        endpoint: Endpoint,
        token: String,
        fileId: String,
        public: Boolean,
        expiresAt: String? = null,
        showOwner: Boolean = false,
    ): Result<DriveLinkState> = value(endpoint, token, "drive_link_set", buildJsonObject {
        put("p_file", fileId)
        put("p_public", public)
        putNullable("p_expires_at", expiresAt)
        put("p_show_owner", showOwner)
    }, DriveLinkState.serializer())

    suspend fun resetLink(endpoint: Endpoint, token: String, fileId: String): Result<DriveLinkState> =
        value(endpoint, token, "drive_link_reset", fileArgs(fileId), DriveLinkState.serializer())

    /** Existing non-link grants are visible/removable in the owner details; creation needs a proper target picker. */
    suspend fun unshare(endpoint: Endpoint, token: String, shareId: String): Result<Unit> = ioResult {
        rest.rpcUnit(endpoint, token, "drive_unshare", buildJsonObject { put("p_share", shareId) }.toString()).getOrThrow()
    }

    /** Signs fresh on every open, with storage RLS. No long-lived private URLs are stored in account settings. */
    suspend fun viewUrl(endpoint: Endpoint, token: String, file: DriveFile): Result<String> = ioResult {
        require(file.isActive) { "This file is no longer available." }
        storageUrl(endpoint, token, file, 3600).toString()
    }

    /**
     * Streams into a caller-owned SAF output, rather than buffering the whole file or requesting
     * broad storage permission. The caller closes/deletes an incomplete document on failure.
     * Foreground only: persistent/resumable background transfers are explicitly not implemented.
     */
    suspend fun saveTo(endpoint: Endpoint, token: String, file: DriveFile, output: OutputStream): Result<Long> = ioResult {
        require(file.isActive) { "This file is no longer available." }
        val url = storageUrl(endpoint, token, file, 6 * 60 * 60)
        val call = mediaClient.newCall(Request.Builder().url(url).get().build())
        call.execute().use { response ->
            if (!response.isSuccessful) throw ApiException("The file could not be saved (${response.code}).", response.code)
            val body = response.body
            val expected = body.contentLength()
            var copied = 0L
            body.byteStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    copied += count
                }
            }
            if (expected >= 0 && copied != expected) throw ProtocolException("The file download was interrupted.")
            output.flush()
            copied
        }
    }

    private suspend fun count(endpoint: Endpoint, token: String, function: String, ids: List<String>): Result<Int> =
        value(endpoint, token, function, buildJsonObject { putIds("p_files", ids) }, Int.serializer())

    private suspend fun <T> value(
        endpoint: Endpoint,
        token: String,
        function: String,
        args: JsonObject = buildJsonObject {},
        serializer: KSerializer<T>,
    ): Result<T> = ioResult {
        rest.rpcValue(endpoint, token, function, args.toString(), serializer).getOrThrow()
    }

    private suspend fun <T> ioResult(block: suspend () -> T): Result<T> = withContext(dispatcher) {
        try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun storageUrl(endpoint: Endpoint, token: String, file: DriveFile, lifetime: Int): HttpUrl {
        require(file.bucket == "message_media" || file.bucket == "user_media") { "Unsupported storage bucket." }
        val segments = file.path.split('/')
        require(segments.isNotEmpty() && segments.none { it.isBlank() || it == "." || it == ".." || '\\' in it }) {
            "Invalid storage object name."
        }
        val base = (endpoint.baseUrl.trimEnd('/') + "/storage/v1/").toHttpUrl()
        fun objectUrl(kind: String): HttpUrl = base.newBuilder()
            .addPathSegments("object/$kind")
            .addPathSegment(file.bucket)
            .apply { segments.forEach { addPathSegment(it) } }
            .build()
        if (file.bucket == "user_media") return objectUrl("public")
        val request = Request.Builder()
            .url(objectUrl("sign"))
            .header("apikey", endpoint.anonKey)
            .header("Authorization", "Bearer $token")
            .post("""{"expiresIn":$lifetime}""".toRequestBody("application/json".toMediaType()))
            .build()
        return mediaClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException("The file is not available to this account (${response.code}).", response.code)
            val parsed = HarmonyJson.parseToJsonElement(response.body.string()).jsonObject
            val path = parsed["signedURL"]?.jsonPrimitive?.content
                ?: parsed["signedUrl"]?.jsonPrimitive?.content
                ?: throw ProtocolException("The instance returned no file URL.")
            // storage-js resolves /object/sign/... against /storage/v1, not the gateway origin.
            val resolved = base.resolve(if (path.startsWith("/object/")) path.drop(1) else path)
                ?: throw ProtocolException("The instance returned an invalid file URL.")
            require(resolved.scheme == base.scheme && resolved.host == base.host && resolved.port == base.port &&
                resolved.username.isEmpty() && resolved.password.isEmpty() &&
                resolved.encodedPath == objectUrl("sign").encodedPath) { "The instance returned an unsafe file URL." }
            resolved
        }
    }
}

private fun fileArgs(fileId: String): JsonObject = buildJsonObject { put("p_file", fileId) }

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(name: String, value: String?) {
    if (value == null) put(name, JsonNull) else put(name, value)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putIds(name: String, ids: List<String>) {
    put(name, buildJsonArray { ids.distinct().forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
}

/** The stable /d address never exposes an object's private storage name. */
fun driveLinkUrl(endpoint: Endpoint, token: String): String = endpoint.origin.trimEnd('/').toHttpUrl().newBuilder()
    .addPathSegment("d").addPathSegment(token).build().toString()

/** Safe suggested filename for Android's document provider. */
fun driveDownloadName(name: String?): String = name.orEmpty()
    .map { if (it == '/' || it == '\\' || it.isISOControl()) '_' else it }
    .joinToString("").trim().trimEnd('.', ' ').take(200).ifEmpty { "download" }
