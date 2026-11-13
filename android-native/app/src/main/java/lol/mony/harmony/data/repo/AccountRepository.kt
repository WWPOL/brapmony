package lol.mony.harmony.data.repo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import lol.mony.harmony.data.api.ApiException
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.api.RestApi
import lol.mony.harmony.data.model.AccountAppearance
import lol.mony.harmony.data.model.AccountDmPolicy
import lol.mony.harmony.data.model.AccountNotifications
import lol.mony.harmony.data.model.AccountPrivacy
import lol.mony.harmony.data.model.AccountProfile
import lol.mony.harmony.data.model.AccountProfileEdit
import lol.mony.harmony.data.model.AccountRestrictedPerson
import lol.mony.harmony.data.model.HarmonyJson
import lol.mony.harmony.ui.theme.VisualThemeSettings

/**
 * Account contracts from UserAccountSettings/PrivacySettings/NotificationService and db_schema.
 * The caller supplies its USER JWT and resolved profile id. No auth-id/profile-id assumption.
 * Every entry point moves synchronous RestApi work off Compose's main thread.
 */
class AccountRepository(
    private val rest: RestApi,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun profile(endpoint: Endpoint, token: String, authUserId: String): Result<AccountProfile> = io {
        rest.select(
            endpoint, token, "profiles", PROFILE_COLUMNS,
            listOf("auth_user_id" to "eq.$authUserId"),
            serializer = ListSerializer(AccountProfile.serializer()),
        ).getOrThrow().singleOrNull() ?: error("Your account profile could not be found. Sign in again.")
    }

    suspend fun updateProfile(
        endpoint: Endpoint, token: String, profileId: String,
        original: AccountProfileEdit, edited: AccountProfileEdit,
    ): Result<AccountProfile> = io {
        edited.validate()?.let { error(it) }
        val before = original.patch()
        val patch = JsonObject(edited.patch().filter { (key, value) -> before[key] != value })
        if (patch.isEmpty()) return@io readProfile(endpoint, token, profileId)
        rest.update(
            endpoint, token, "profiles", listOf("id" to "eq.$profileId"), patch.toString(),
            returning = PROFILE_COLUMNS, serializer = ListSerializer(AccountProfile.serializer()),
        ).getOrThrow().singleOrNull() ?: error("Your profile was not saved. Refresh and try again.")
    }

    suspend fun appearance(endpoint: Endpoint, token: String, profileId: String): Result<VisualThemeSettings> = io {
        VisualThemeSettings.fromJson(readProfile(endpoint, token, profileId).appearanceSettings)
    }

    /** Read immediately before writing and merge ONLY edited native keys into that raw object. */
    suspend fun saveAppearance(
        endpoint: Endpoint, token: String, profileId: String,
        original: VisualThemeSettings, edited: VisualThemeSettings,
    ): Result<VisualThemeSettings> = io {
        val current = readProfile(endpoint, token, profileId).appearanceSettings
        require(current == null || current == JsonNull || current is JsonObject) {
            "This account's appearance is not a settings object. It has not been overwritten."
        }
        val merged = AccountAppearance.merge(current as? JsonObject ?: JsonObject(emptyMap()), original, edited)
        val saved = rest.update(
            endpoint, token, "profiles", listOf("id" to "eq.$profileId"),
            buildJsonObject { put("appearance_settings", merged) }.toString(),
            returning = PROFILE_COLUMNS, serializer = ListSerializer(AccountProfile.serializer()),
        ).getOrThrow().singleOrNull() ?: error("Appearance was not saved. Refresh and try again.")
        VisualThemeSettings.fromJson(saved.appearanceSettings)
    }

    suspend fun savePrivacy(
        endpoint: Endpoint, token: String, profileId: String, original: AccountPrivacy, edited: AccountPrivacy,
    ): Result<AccountProfile> = io {
        val patch = buildJsonObject {
            if (original.requireFollowApproval != edited.requireFollowApproval) {
                put("manually_approves_followers", edited.requireFollowApproval)
            }
            if (original.dmPolicy != edited.dmPolicy) put("dm_policy", edited.dmPolicy.wire)
        }
        if (patch.isEmpty()) return@io readProfile(endpoint, token, profileId)
        rest.update(
            endpoint, token, "profiles", listOf("id" to "eq.$profileId"), patch.toString(),
            returning = PROFILE_COLUMNS, serializer = ListSerializer(AccountProfile.serializer()),
        ).getOrThrow().singleOrNull() ?: error("Privacy preferences were not saved.")
    }

    suspend fun notifications(endpoint: Endpoint, token: String, profileId: String): Result<AccountNotifications> = io {
        readNotifications(endpoint, token, profileId) ?: AccountNotifications()
    }

    /** PATCH existing rows instead of the old SettingsRepository's duplicate-key INSERT. */
    suspend fun saveNotifications(
        endpoint: Endpoint, token: String, profileId: String,
        original: AccountNotifications, edited: AccountNotifications,
    ): Result<AccountNotifications> = io {
        edited.validate()?.let { error(it) }
        val existing = readNotifications(endpoint, token, profileId)
        val patch = edited.changesFrom(original)
        val rows = if (existing == null) {
            val values = HarmonyJson.encodeToJsonElement(AccountNotifications.serializer(), edited).jsonObject
            rest.insert(
                endpoint, token, "notification_preferences",
                JsonObject(values + ("user_id" to JsonPrimitive(profileId))).toString(),
                serializer = ListSerializer(AccountNotifications.serializer()),
            ).getOrThrow()
        } else if (patch.isEmpty()) return@io existing else {
            rest.update(
                endpoint, token, "notification_preferences", listOf("user_id" to "eq.$profileId"),
                patch.toString(), serializer = ListSerializer(AccountNotifications.serializer()),
            ).getOrThrow()
        }
        rows.singleOrNull() ?: error("Notification preferences were not saved.")
    }

    suspend fun blockedPeople(endpoint: Endpoint, token: String, profileId: String): Result<List<AccountRestrictedPerson>> = io {
        val rows = rest.select(
            endpoint, token, "user_blocks", "blocked_user_id", listOf("blocker_id" to "eq.$profileId"),
            serializer = ListSerializer(AccountBlockRow.serializer()),
        ).getOrThrow()
        people(endpoint, token, rows.map { it.blockedUserId })
    }

    suspend fun mutedPeople(endpoint: Endpoint, token: String, profileId: String): Result<List<AccountRestrictedPerson>> = io {
        val rows = rest.select(
            endpoint, token, "user_mutes", "muted_user_id", listOf("muter_id" to "eq.$profileId"),
            serializer = ListSerializer(AccountMuteRow.serializer()),
        ).getOrThrow()
        people(endpoint, token, rows.map { it.mutedUserId })
    }

    suspend fun unblock(endpoint: Endpoint, token: String, profileId: String, otherProfileId: String): Result<Unit> = io {
        deleteAndVerify(endpoint, token, "user_blocks", listOf(
            "blocker_id" to "eq.$profileId", "blocked_user_id" to "eq.$otherProfileId",
        ))
    }

    suspend fun unmute(endpoint: Endpoint, token: String, profileId: String, otherProfileId: String): Result<Unit> = io {
        deleteAndVerify(endpoint, token, "user_mutes", listOf(
            "muter_id" to "eq.$profileId", "muted_user_id" to "eq.$otherProfileId",
        ))
    }

    private suspend fun readProfile(endpoint: Endpoint, token: String, profileId: String): AccountProfile = rest.select(
        endpoint, token, "profiles", PROFILE_COLUMNS, listOf("id" to "eq.$profileId"),
        serializer = ListSerializer(AccountProfile.serializer()),
    ).getOrThrow().singleOrNull() ?: error("Your account profile could not be read.")

    private suspend fun readNotifications(endpoint: Endpoint, token: String, profileId: String): AccountNotifications? = rest.select(
        endpoint, token, "notification_preferences", "*", listOf("user_id" to "eq.$profileId"),
        serializer = ListSerializer(AccountNotifications.serializer()),
    ).getOrThrow().singleOrNull()

    private suspend fun people(endpoint: Endpoint, token: String, ids: List<String>): List<AccountRestrictedPerson> {
        if (ids.isEmpty()) return emptyList()
        return ids.distinct().chunked(100).flatMap { chunk ->
            // IDs came from UUID columns, not user-entered filter fragments.
            rest.select(
                endpoint, token, "profiles", "id,username,display_name",
                listOf("id" to "in.(${chunk.joinToString(",")})"),
                serializer = ListSerializer(AccountRestrictedPerson.serializer()),
            ).getOrThrow()
        }
    }

    private suspend fun deleteAndVerify(endpoint: Endpoint, token: String, table: String, filters: List<Pair<String, String>>) {
        val deletion = rest.delete(endpoint, token, table, filters)
        // The foundation incorrectly treats a successful, empty 204 as an error. Only accept
        // that exact HTTP success, and confirm absence; never convert a real failure to success.
        val failure = deletion.exceptionOrNull()
        if (failure != null && (failure !is ApiException || failure.status != 204)) throw failure
        val remaining = rest.select(
            endpoint, token, table, "*", filters, serializer = ListSerializer(JsonObject.serializer()),
        ).getOrThrow()
        check(remaining.isEmpty()) { "The change was not confirmed. Refresh and try again." }
    }

    private suspend fun <T> io(block: suspend () -> T): Result<T> = withContext(ioDispatcher) {
        try { Result.success(block()) } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
    }

    companion object {
        const val PROFILE_COLUMNS = "id,auth_user_id,username,display_name,bio,pronouns,color,avatar_url," +
            "banner_url,profile_fields,appearance_settings,manually_approves_followers,dm_policy,created_at"
        fun privacy(profile: AccountProfile) = AccountPrivacy(
            profile.manuallyApprovesFollowers == true,
            AccountDmPolicy.entries.firstOrNull { it.wire == profile.dmPolicy } ?: AccountDmPolicy.Everyone,
        )
    }
}

@Serializable
private data class AccountBlockRow(@SerialName("blocked_user_id") val blockedUserId: String)
@Serializable
private data class AccountMuteRow(@SerialName("muted_user_id") val mutedUserId: String)
