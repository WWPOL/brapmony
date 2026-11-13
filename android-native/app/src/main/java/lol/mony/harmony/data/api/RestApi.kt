package lol.mony.harmony.data.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import lol.mony.harmony.data.model.HarmonyJson
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * PostgREST, the instance's table and function API, under `/rest/v1`.
 *
 * Filters are the query parameters PostgREST itself defines (`eq.`, `in.`, `is.null`, `order`,
 * `select`, `limit`, `offset`), passed through as the web client's Supabase calls produce them,
 * so an endpoint behaves the same for both clients.
 */
class RestApi(
    private val client: OkHttpClient,
    private val json: Json = HarmonyJson,
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** Read a list of rows. */
    suspend fun <T> select(
        endpoint: Endpoint,
        token: String,
        table: String,
        columns: String = "*",
        filters: List<Pair<String, String>> = emptyList(),
        order: String? = null,
        limit: Int? = null,
        offset: Int? = null,
        serializer: KSerializer<List<T>>,
    ): Result<List<T>> {
        val url = buildUrl(endpoint, table, columns, filters, order, limit, offset)
        val request = authorized(endpoint, token, url).get().build()
        return execute(request) { json.decodeFromString(serializer, it) }
    }

    /** Read at most one row; PostgREST's `Accept: application/vnd.pgrst.object+json`. */
    suspend fun <T> selectSingle(
        endpoint: Endpoint,
        token: String,
        table: String,
        columns: String = "*",
        filters: List<Pair<String, String>> = emptyList(),
        serializer: KSerializer<T>,
    ): Result<T?> {
        val url = buildUrl(endpoint, table, columns, filters, order = null, limit = 1, offset = null)
        val request = authorized(endpoint, token, url)
            .header("Accept", "application/vnd.pgrst.object+json")
            .get()
            .build()
        return executeOptional(request) { json.decodeFromString(serializer, it) }
    }

    /** Insert one or more rows and read what the server stored. */
    suspend fun <T> insert(
        endpoint: Endpoint,
        token: String,
        table: String,
        rows: String,
        returning: String = "*",
        serializer: KSerializer<List<T>>,
    ): Result<List<T>> {
        val url = buildUrl(endpoint, table, returning, emptyList(), order = null, limit = null, offset = null)
        val request = authorized(endpoint, token, url)
            .header("Prefer", "return=representation")
            .post(rows.toRequestBody(jsonMedia))
            .build()
        return execute(request) { json.decodeFromString(serializer, it) }
    }

    /** Patch rows matching `filters` and read what they became. */
    suspend fun <T> update(
        endpoint: Endpoint,
        token: String,
        table: String,
        filters: List<Pair<String, String>>,
        patch: String,
        returning: String = "*",
        serializer: KSerializer<List<T>>,
    ): Result<List<T>> {
        val url = buildUrl(endpoint, table, returning, filters, order = null, limit = null, offset = null)
        val request = authorized(endpoint, token, url)
            .header("Prefer", "return=representation")
            .patch(patch.toRequestBody(jsonMedia))
            .build()
        return execute(request) { json.decodeFromString(serializer, it) }
    }

    /** Delete rows matching `filters`. */
    suspend fun delete(
        endpoint: Endpoint,
        token: String,
        table: String,
        filters: List<Pair<String, String>>,
    ): Result<Unit> {
        val url = buildUrl(endpoint, table, "*", filters, order = null, limit = null, offset = null)
        val request = authorized(endpoint, token, url).delete().build()
        return execute(request) { }
    }

    /** Call a Postgres function. A table-valued function is read as a list of rows. */
    suspend fun <T> rpc(
        endpoint: Endpoint,
        token: String,
        function: String,
        args: String = "{}",
        serializer: KSerializer<List<T>>,
    ): Result<List<T>> {
        val url = (endpoint.restUrl + "/rpc/" + function).toHttpUrl()
        val request = authorized(endpoint, token, url)
            .header("Prefer", "return=representation")
            .post(args.toRequestBody(jsonMedia))
            .build()
        return execute(request) { json.decodeFromString(serializer, it) }
    }

    /** Call a function whose answer this app ignores beyond success or failure. */
    suspend fun rpcUnit(
        endpoint: Endpoint,
        token: String,
        function: String,
        args: String = "{}",
    ): Result<Unit> {
        val url = (endpoint.restUrl + "/rpc/" + function).toHttpUrl()
        val request = authorized(endpoint, token, url)
            .post(args.toRequestBody(jsonMedia))
            .build()
        return execute(request) { }
    }

    /** Count rows matching `filters`, via PostgREST's `Prefer: count=exact` header. */
    suspend fun count(
        endpoint: Endpoint,
        token: String,
        table: String,
        filters: List<Pair<String, String>> = emptyList(),
    ): Result<Int> {
        val url = buildUrl(endpoint, table, "id", filters, order = null, limit = 1, offset = null)
        val request = authorized(endpoint, token, url)
            .header("Prefer", "count=exact")
            .header("Range-Unit", "items")
            .header("Range", "0-0")
            .get()
            .build()
        return networkResult {
            val answer = client.answer(request)
            if (!answer.successful) throw ApiException(explain(answer.text, answer.status), answer.status)
            answer.contentRange?.substringAfter('/')?.toIntOrNull()
                ?: throw ApiException("The instance did not return an exact count.")
        }
    }

    private fun buildUrl(
        endpoint: Endpoint,
        table: String,
        columns: String,
        filters: List<Pair<String, String>>,
        order: String?,
        limit: Int?,
        offset: Int?,
    ): HttpUrl {
        val builder = (endpoint.restUrl + "/" + table).toHttpUrl().newBuilder()
        builder.addQueryParameter("select", columns)
        for ((key, value) in filters) builder.addQueryParameter(key, value)
        if (order != null) builder.addQueryParameter("order", order)
        if (limit != null) builder.addQueryParameter("limit", limit.toString())
        if (offset != null) builder.addQueryParameter("offset", offset.toString())
        return builder.build()
    }

    private fun authorized(endpoint: Endpoint, token: String, url: HttpUrl): Request.Builder =
        Request.Builder()
            .url(url)
            .header("apikey", endpoint.anonKey)
            .header("Authorization", "Bearer $token")

    private suspend fun <T> execute(request: Request, parse: (String) -> T): Result<T> = networkResult {
        val answer = client.answer(request)
        if (!answer.successful) throw ApiException(explain(answer.text, answer.status), answer.status)
        // DELETE and void RPCs legitimately answer 204 with an empty body.
        parse(answer.text)
    }

    private suspend fun <T> executeOptional(request: Request, parse: (String) -> T): Result<T?> = networkResult {
        val answer = client.answer(request)
        if (answer.status == 406 && answer.text.contains("PGRST116") &&
            answer.text.contains("0 rows")) return@networkResult null
        if (!answer.successful) throw ApiException(explain(answer.text, answer.status), answer.status)
        parse(answer.text)
    }

    /** Scalar or object-valued RPC, such as create_thread's UUID response. */
    suspend fun <T> rpcValue(endpoint: Endpoint, token: String, function: String,
                             args: String = "{}", serializer: KSerializer<T>): Result<T> {
        val request = authorized(endpoint, token, (endpoint.restUrl + "/rpc/" + function).toHttpUrl())
            .post(args.toRequestBody(jsonMedia)).build()
        return execute(request) { json.decodeFromString(serializer, it) }
    }

    /** PostgREST errors carry `message`, `details` and `hint`; show the most specific sentence. */
    private fun explain(text: String, status: Int): String {
        val body = runCatching { json.decodeFromString(JsonObject.serializer(), text) }.getOrNull()
        val message = body?.get("message")?.toString()?.trim('"')
        val details = body?.get("details")?.toString()?.trim('"')
        return when {
            message.isNullOrBlank() -> "The instance answered $status."
            details.isNullOrBlank() || details == "null" -> message
            else -> "$message ($details)"
        }
    }

    /** Parses an arbitrary JSON answer for callers that only need one field. */
    fun jsonOf(text: String): JsonElement = json.parseToJsonElement(text)

    fun objectOf(text: String): JsonObject = json.parseToJsonElement(text).jsonObject
}

class ApiException(message: String, val status: Int = 0) : Exception(message)
