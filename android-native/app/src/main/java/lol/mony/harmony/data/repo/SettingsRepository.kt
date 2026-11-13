package lol.mony.harmony.data.repo

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.api.RestApi
import lol.mony.harmony.data.model.Profile
import lol.mony.harmony.ui.theme.VisualThemeSettings

/**
 * The account's settings: appearance, notifications and the per-device scope switch.
 *
 * Appearance lives in `profiles.appearance_settings` as one JSON object, which is what the web
 * client writes (`useVisualTheme.ts`). The app merges into the object it read instead of
 * replacing it, so a setting this client does not model - a skin's options, a future key -
 * survives a save made here.
 */
class SettingsRepository(private val rest: RestApi) {

    suspend fun appearance(endpoint: Endpoint, token: String, profileId: String): VisualThemeSettings? = runCatching {
        val rows = rest.select(
            endpoint, token, "profiles", "id,appearance_settings",
            listOf("id" to "eq.$profileId"),
            serializer = ListSerializer(Profile.serializer()),
        ).getOrThrow()
        rows.firstOrNull()?.appearanceSettings?.let { VisualThemeSettings.fromJson(it) }
    }.getOrNull()

    /** Writes the settings back, keeping every key the app did not touch. */
    suspend fun saveAppearance(
        endpoint: Endpoint,
        token: String,
        profileId: String,
        settings: VisualThemeSettings,
    ): Result<Unit> = runCatching {
        val payload = JsonObject(VisualThemeSettings.toJsonMap(settings))
        rest.update(
            endpoint, token, "profiles",
            filters = listOf("id" to "eq.$profileId"),
            patch = """{"appearance_settings":$payload}""",
            serializer = ListSerializer(Profile.serializer()),
        ).getOrThrow()
        Unit
    }

    /** The person's notification preferences; the app reads the same row the web client does. */
    suspend fun notificationPreferences(endpoint: Endpoint, token: String, userId: String): JsonObject? = runCatching {
        val rows = rest.select(
            endpoint, token, "notification_preferences", "*",
            listOf("user_id" to "eq.$userId"),
            serializer = ListSerializer(JsonObject.serializer()),
        ).getOrThrow()
        rows.firstOrNull()
    }.getOrNull()

    suspend fun saveNotificationPreferences(
        endpoint: Endpoint,
        token: String,
        userId: String,
        patch: Map<String, JsonElement>,
    ): Result<Unit> = runCatching {
        val body = JsonObject(patch.mapValues { (_, value) -> value })
        rest.insert(
            endpoint, token, "notification_preferences",
            rows = JsonObject(buildMap {
                put("user_id", JsonPrimitive(userId))
                putAll(body)
            }).toString(),
            serializer = ListSerializer(JsonObject.serializer()),
        ).getOrThrow()
        Unit
    }
}
