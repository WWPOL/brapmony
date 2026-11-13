package lol.mony.harmony.data.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Run blocking OkHttp off the main thread, preserving coroutine cancellation. */
internal suspend fun <T> networkResult(block: () -> T): Result<T> = withContext(Dispatchers.IO) {
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
}

internal data class HttpAnswer(val status: Int, val text: String, val contentRange: String?) {
    val successful: Boolean get() = status in 200..299
}

internal fun OkHttpClient.answer(request: Request): HttpAnswer = newCall(request).execute().use {
    HttpAnswer(it.code, it.body.string(), it.header("Content-Range"))
}
