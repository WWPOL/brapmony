package lol.mony.harmony.data.repo

import kotlinx.serialization.builtins.ListSerializer
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.api.RestApi
import lol.mony.harmony.data.model.Profile

/** The caller's own profile, and other people's. */
class ProfileRepository(private val rest: RestApi) {

    /** The signed-in account's profile, keyed by its auth user id. */
    suspend fun mine(endpoint: Endpoint, token: String): Loaded? {
        val q = rest.select(
            endpoint = endpoint,
            token = token,
            table = "profiles",
            columns = PROFILE_COLUMNS,
            filters = listOf("auth_user_id" to "eq.${authUserIdOf(token)}"),
            serializer = ListSerializer(Profile.serializer()),
        )
        val profile = q.getOrThrow().firstOrNull() ?: return null
        return Loaded(profile, authUserIdOf(token))
    }

    suspend fun byId(endpoint: Endpoint, token: String, id: String): Profile? = rest.select(
        endpoint, token, "profiles", PROFILE_COLUMNS, listOf("id" to "eq.$id"),
        serializer = ListSerializer(Profile.serializer()),
    ).getOrThrow().firstOrNull()

    /**
     * Profiles for a set of ids; missing ids are simply absent.
     *
     * Read one row at a time so a single row this build cannot decode costs that one author
     * rather than the page. PostgREST answers a mixed-type column without complaint; it is the
     * client's own decoding that used to fail the whole list.
     */
    suspend fun byIds(endpoint: Endpoint, token: String, ids: Collection<String>): Map<String, Profile> {
        val unique = ids.distinct().filter { it.isNotBlank() }
        if (unique.isEmpty()) return emptyMap()
        return unique.chunked(CHUNK).flatMap { chunk ->
            runCatching {
                rest.select(
                    endpoint, token, "profiles", PROFILE_COLUMNS,
                    listOf("id" to "in.(${chunk.joinToString(",")})"),
                    serializer = ListSerializer(Profile.serializer()),
                ).getOrThrow()
            }.getOrElse { error ->
                // The page was refused as a whole; fall back to reading each row on its own so
                // one bad row does not hide the rest.
                chunk.mapNotNull { id ->
                    runCatching { byId(endpoint, token, id) }.getOrNull()
                }
            }
        }.associateBy { it.id }
    }

    suspend fun byAuthUserIds(
        endpoint: Endpoint,
        token: String,
        authIds: Collection<String>,
    ): Map<String, Profile> {
        if (authIds.isEmpty()) return emptyMap()
        val unique = authIds.distinct()
        val found = rest.select(
            endpoint, token, "profiles", PROFILE_COLUMNS,
            listOf("auth_user_id" to "in.(${unique.joinToString(",")})"),
            serializer = ListSerializer(Profile.serializer()),
        ).getOrThrow()
        return found.mapNotNull { profile -> profile.authUserId?.let { it to profile } }.toMap()
    }

    /** The instance's own domain, from `instance_config`, as the web client reads it. */
    suspend fun instanceDomain(endpoint: Endpoint, token: String): String? = runCatching {
        val rows = rest.select(
            endpoint, token, "instance_config", "config_key,config_value",
            listOf("config_key" to "eq.domain"),
            serializer = ListSerializer(lol.mony.harmony.data.model.InstanceConfigRow.serializer()),
        ).getOrNull()
        rows?.firstOrNull()?.configValue?.toString()?.trim('"')
    }.getOrNull()

    /** Saves the appearance settings the person picked; the JSON is the web client's own shape. */
    suspend fun saveAppearance(
        endpoint: Endpoint,
        token: String,
        profileId: String,
        settingsJson: String,
    ): Result<Unit> = runCatching {
        val result = rest.update(
            endpoint, token, "profiles",
            filters = listOf("id" to "eq.$profileId"),
            patch = """{"appearance_settings":$settingsJson}""",
            serializer = ListSerializer(Profile.serializer()),
        )
        result.getOrThrow()
        Unit
    }

    /** Updates editable profile fields (display name, bio, pronouns, avatar). */
    suspend fun updateProfile(
        endpoint: Endpoint,
        token: String,
        profileId: String,
        fields: Map<String, String?>,
    ): Result<Profile> = runCatching {
        val patch = fields.entries.joinToString(",", "{", "}") { (key, value) ->
            val encoded = if (value == null) "null" else kotlinx.serialization.json.JsonPrimitive(value).toString()
            "\"$key\":$encoded"
        }
        rest.update(
            endpoint, token, "profiles",
            filters = listOf("id" to "eq.$profileId"),
            patch = patch,
            serializer = ListSerializer(Profile.serializer()),
        ).getOrThrow().first()
    }

    /** Reads another person's profile page data by handle, the way `/@name` resolves it. */
    suspend fun byHandle(endpoint: Endpoint, token: String, handle: String): Profile? {
        val bare = handle.trim().removePrefix("@")
        val (name, domain) = if (bare.contains("@")) {
            bare.substringBeforeLast("@") to bare.substringAfterLast("@")
        } else {
            bare to null
        }
        val filters = buildList {
            add("username" to "eq.$name")
            if (domain != null) add("domain" to "eq.$domain")
        }
        return rest.select(
            endpoint, token, "profiles", PROFILE_COLUMNS, filters,
            serializer = ListSerializer(Profile.serializer()),
        ).getOrThrow().firstOrNull()
    }

    suspend fun createSelf(endpoint: Endpoint, token: String, username: String, displayName: String): Profile {
        require(username.matches(Regex("[a-z0-9_]{3,24}"))) { "Use 3–24 lowercase letters, numbers or underscores." }
        val id = authUserIdOf(token).takeIf { it.isNotBlank() } ?: error("Sign in again.")
        val domain = instanceDomain(endpoint, token) ?: error("Could not read the instance domain.")
        val row = kotlinx.serialization.json.buildJsonObject {
            put("id", kotlinx.serialization.json.JsonPrimitive(id))
            put("auth_user_id", kotlinx.serialization.json.JsonPrimitive(id))
            put("username", kotlinx.serialization.json.JsonPrimitive(username))
            put("display_name", kotlinx.serialization.json.JsonPrimitive(displayName.ifBlank { username }))
            put("domain", kotlinx.serialization.json.JsonPrimitive(domain))
            put("is_local", kotlinx.serialization.json.JsonPrimitive(true))
        }
        return rest.insert(endpoint, token, "profiles", row.toString(),
            serializer = ListSerializer(Profile.serializer())).getOrThrow().first()
    }

    data class Loaded(val profile: Profile, val authUserId: String)

    private fun authUserIdOf(token: String): String = Jwt.subject(token) ?: ""

    companion object {
        /**
         * What a person's row draws, and only that.
         *
         * `status` and `custom_status` are the two columns whose *types* once broke every
         * lookup: they are `smallint` and `jsonb` in the schema, and declaring them as strings
         * made the decoder throw on the first profile, which emptied the whole page and left
         * every message reading "Unknown account". They are asked for as `JsonElement` now,
         * and a row this build cannot read no longer costs the rest of the page.
         */
        const val PROFILE_COLUMNS =
            "id,auth_user_id,username,display_name,avatar_url,banner_url,bio,pronouns,domain," +
                "is_local,appearance_settings,created_at,status,custom_status"

        /** PostgREST's `in.(...)` list is a URL parameter; keep it well inside a sane length. */
        const val CHUNK = 40
    }
}

/** The few JWT claims this client reads. No signature check: the server checks it, not us. */
object Jwt {
    fun payload(token: String): Map<String, Any?> {
        val part = token.split(".").getOrNull(1) ?: return emptyMap()
        val padded = part.replace('-', '+').replace('_', '/').padEnd((part.length + 3) / 4 * 4, '=')
        return runCatching {
            val text = String(java.util.Base64.getDecoder().decode(padded), Charsets.UTF_8)
            val element = kotlinx.serialization.json.Json.parseToJsonElement(text)
            (element as? kotlinx.serialization.json.JsonObject)?.mapValues { it.value.toString().trim('"') }
                ?: emptyMap()
        }.getOrDefault(emptyMap())
    }

    fun subject(token: String): String? = payload(token)["sub"] as? String

    fun expiresAtMillis(token: String): Long? = (payload(token)["exp"] as? String)?.toLongOrNull()?.times(1000)
}
