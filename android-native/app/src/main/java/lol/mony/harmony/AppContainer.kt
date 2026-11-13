package lol.mony.harmony

import android.content.Context
import lol.mony.harmony.data.api.AuthApi
import lol.mony.harmony.data.api.Http
import lol.mony.harmony.data.api.RestApi
import lol.mony.harmony.data.repo.ChatRepository
import lol.mony.harmony.data.repo.ProfileRepository
import lol.mony.harmony.data.repo.ServerRepository
import lol.mony.harmony.data.repo.SettingsRepository
import lol.mony.harmony.data.session.AuthRepository
import lol.mony.harmony.data.session.SessionStore

/**
 * The app's object graph, built once.
 *
 * A single container rather than a DI framework: the graph is small and explicit, and the tests
 * build the same one with a MockWebServer endpoint by hand.
 */
class AppContainer(context: Context) {
    val sessionStore: SessionStore = SessionStore(context.applicationContext)
    val rest: RestApi = RestApi(Http.client)
    val authApi: AuthApi = AuthApi(Http.client)

    val profiles: ProfileRepository = ProfileRepository(rest)
    val servers: ServerRepository = ServerRepository(rest)
    val chats: ChatRepository = ChatRepository(rest)
    val settings: SettingsRepository = SettingsRepository(rest)

    val auth: AuthRepository = AuthRepository(sessionStore, authApi, profiles)
}
