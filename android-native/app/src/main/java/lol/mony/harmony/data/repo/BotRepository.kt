package lol.mony.harmony.data.repo

import kotlinx.serialization.builtins.ListSerializer
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.api.RestApi
import lol.mony.harmony.data.model.Bot

/**
 * The `bots` table: the accounts that post through `messages.bot_id`.
 *
 * A bot message carries no `user_id`, so reading only profiles leaves every bot row nameless.
 * The web client looks a bot up by id the first time it draws one (`useMessageAuthorPresentation`
 * -> `ensureBot`) and keeps it; this does the same for a whole page at once.
 */
class BotRepository(private val rest: RestApi) {

    suspend fun byIds(endpoint: Endpoint, token: String, ids: Collection<String>): Map<String, Bot> {
        val unique = ids.filter { it.isNotBlank() }.distinct()
        if (unique.isEmpty()) return emptyMap()
        return rest.select(
            endpoint, token, "bots", COLUMNS,
            listOf("id" to "in.(${unique.joinToString(",")})"),
            serializer = ListSerializer(Bot.serializer()),
        ).getOrThrow().associateBy { it.id }
    }

    suspend fun byId(endpoint: Endpoint, token: String, id: String): Bot? = rest.select(
        endpoint, token, "bots", COLUMNS, listOf("id" to "eq.$id"),
        serializer = ListSerializer(Bot.serializer()),
    ).getOrThrow().firstOrNull()

    private companion object {
        // Only what a message row draws. `bots` also holds settings, stats and tags this
        // client never shows, and a wide select is a wider surface to break on.
        const val COLUMNS = "id,username,display_name,avatar_url,owner_id,is_verified,bot_type"
    }
}
