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
    fun <T> select(
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
    fun <T> selectSingle(
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
    fun <T> insert(
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
    fun <T> update(
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
    fun delete(
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
    fun <T> rpc(
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
    fun rpcUnit(
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
    fun count(
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
        return runCatching {
            client.newCall(request).execute().use { response ->
                val range = response.header("Content-Range")
                if (!response.isSuccessful) return@use Result.failure(
                    ApiException("$table count failed: ${response.code}", response.code),
                )
                val total = range?.substringAfter('/')?.toIntOrNull() ?: 0
                Result.success(total)
            }
        }.getOrElse { Result.failure(it) }
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

    private fun <T> execute(request: Request, parse: (String) -> T): Result<T> = runCatching {
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw ApiException(explain(text, response.code), response.code)
            if (text.isBlank()) throw ApiException("The instance returned an empty answer.", response.code)
            parse(text)
        }
    }.recoverCatching { throw it }

    private fun <T> executeOptional(request: Request, parse: (String) -> T): Result<T?> = runCatching {
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            // PostgREST answers 406 when a `single` read matched no row; that is "none", not an error.
            if (response.code == 406 || text.isBlank()) return@use null
            if (!response.isSuccessful) throw ApiException(explain(text, response.code), response.code)
            parse(text)
        }
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
