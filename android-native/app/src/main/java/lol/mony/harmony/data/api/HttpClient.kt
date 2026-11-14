package lol.mony.harmony.data.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * The one OkHttp client the app uses.
 *
 * Requests go to two roots: GoTrue (`/auth/v1`) and PostgREST (`/rest/v1`). Both authenticate
 * with the anon key in `apikey` and, once signed in, the user's access token in
 * `Authorization`, exactly as `@supabase/supabase-js` does.
 */
object Http {
    /** The instance API every repository shares, for code that cannot be handed one. */
    val rest: RestApi by lazy { RestApi(client) }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .apply {
                if (lol.mony.harmony.BuildConfig.DEBUG) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply {
                            level = HttpLoggingInterceptor.Level.BASIC
                        },
                    )
                }
            }
            .build()
    }
}
