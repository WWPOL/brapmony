package lol.mony.harmony.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lol.mony.harmony.ui.theme.VisualThemeSettings

/** Account editing deliberately uses its own projection, not the chat profile's wire shape. */
@Serializable
data class AccountProfile(
    val id: String,
    @SerialName("auth_user_id") val authUserId: String? = null,
    val username: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val bio: String? = null,
    val pronouns: String? = null,
    val color: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("banner_url") val bannerUrl: String? = null,
    @SerialName("profile_fields") val profileFields: List<AccountProfileField>? = null,
    @SerialName("appearance_settings") val appearanceSettings: JsonElement? = null,
    @SerialName("manually_approves_followers") val manuallyApprovesFollowers: Boolean? = false,
    @SerialName("dm_policy") val dmPolicy: String? = "everyone",
    @SerialName("created_at") val createdAt: String? = null,
) {
    fun edit() = AccountProfileEdit(
        displayName.orEmpty(), bio.orEmpty(), pronouns.orEmpty(), color.orEmpty(),
        avatarUrl.orEmpty(), bannerUrl.orEmpty(), profileFields.orEmpty(),
    )
}

@Serializable
data class AccountProfileField(val name: String, val value: String)

data class AccountProfileEdit(
    val displayName: String = "",
    val bio: String = "",
    val pronouns: String = "",
    val color: String = "",
    val avatarUrl: String = "",
    val bannerUrl: String = "",
    val fields: List<AccountProfileField> = emptyList(),
) {
    /** Matches the web editor's limits (the server additionally sanitizes and validates). */
    fun validate(): String? = when {
        displayName.isBlank() -> "Enter a display name."
        displayName.codePointCount(0, displayName.length) > 50 -> "Display names can have at most 50 characters."
        bio.codePointCount(0, bio.length) > 500 -> "Bios can have at most 500 characters."
        pronouns.codePointCount(0, pronouns.length) > 40 -> "Pronouns can have at most 40 characters."
        color.isNotBlank() && !Regex("#[0-9a-fA-F]{6}").matches(color.trim()) -> "Use a six-digit hex display color, such as #0EA5E9."
        fields.size > 4 -> "Profiles can have at most four extra fields."
        fields.any { it.name.isBlank() || it.value.isBlank() } -> "Each profile field needs a name and value."
        fields.any { it.name.length > 255 || it.value.length > 255 } -> "Profile field names and values can have at most 255 characters."
        else -> null
    }

    fun patch(): JsonObject = buildJsonObject {
        put("display_name", displayName.trim())
        put("bio", bio.trim().nullIfBlank())
        put("pronouns", pronouns.trim().nullIfBlank())
        put("color", color.trim().nullIfBlank())
        put("avatar_url", avatarUrl.trim().nullIfBlank())
        put("banner_url", bannerUrl.trim().nullIfBlank())
        put("profile_fields", HarmonyJson.encodeToJsonElement(
            kotlinx.serialization.builtins.ListSerializer(AccountProfileField.serializer()),
            fields.map { it.copy(name = it.name.trim(), value = it.value.trim()) },
        ))
    }
}

private fun String.nullIfBlank(): JsonElement = if (isBlank()) JsonNull else JsonPrimitive(this)

enum class AccountDmPolicy(val wire: String, val label: String, val description: String) {
    Everyone("everyone", "Everyone", "Anyone can start a direct message."),
    Known("known", "People you know", "People you follow or share a server with can start a direct message."),
    Following("following", "People you follow", "Only people you follow can start a direct message."),
}

data class AccountPrivacy(val requireFollowApproval: Boolean, val dmPolicy: AccountDmPolicy)

/** Only these controls are rewritten; web skins, future keys and unedited fields stay intact. */
object AccountAppearance {
    val editableKeys = setOf(
        "theme", "customThemeMode", "customPrimaryColor", "customAccentColor",
        "customBackgroundColor", "customSidebarColor", "customBackgroundLightness",
        "customBackgroundChroma", "fontSize", "fontFamily", "zoomLevel", "showTimestamps",
        "use24HourTime", "compactMode", "highContrast", "reduceMotion", "screenReaderSupport",
    )

    fun merge(latest: JsonObject, original: VisualThemeSettings, edited: VisualThemeSettings): JsonObject {
        val before = VisualThemeSettings.toJsonMap(original)
        val after = VisualThemeSettings.toJsonMap(edited)
        val changed = editableKeys.filter { before[it] != after[it] }.associateWith { after[it] ?: JsonNull }
        return JsonObject(latest + changed)
    }

    /** Reset the native controls, not another client's CSS/skin or unknown preferences. */
    fun reset(current: VisualThemeSettings): VisualThemeSettings = current.copy(
        theme = "dark", customThemeMode = null, customPrimaryColor = null, customAccentColor = null,
        customBackgroundColor = null, customSidebarColor = null, customBackgroundLightness = 0,
        customBackgroundChroma = 0, fontSize = 15, fontFamily = "system", zoomLevel = 100,
        showTimestamps = true, use24HourTime = false, compactMode = false,
        highContrast = false, reduceMotion = false, screenReaderSupport = false,
    )
}

/** A patchable subset of notification_preferences; user_id is a PROFILE id, not an auth id. */
@Serializable
data class AccountNotifications(
    @SerialName("desktop_notifications") val desktop: Boolean = true,
    @SerialName("desktop_mentions") val mentions: Boolean = true,
    @SerialName("desktop_dms") val dms: Boolean = true,
    @SerialName("desktop_replies") val replies: Boolean = true,
    @SerialName("desktop_reactions") val reactions: Boolean = true,
    @SerialName("sound_notifications") val sound: Boolean = true,
    @SerialName("sound_mentions") val soundMentions: Boolean = true,
    @SerialName("sound_dms") val soundDms: Boolean = true,
    @SerialName("sound_replies") val soundReplies: Boolean = true,
    @SerialName("sound_reactions") val soundReactions: Boolean = true,
    @SerialName("sound_voice_activity") val soundVoice: Boolean = true,
    @SerialName("push_notifications") val push: Boolean = true,
    @SerialName("push_offline_only") val pushOfflineOnly: Boolean = true,
    @SerialName("push_mentions") val pushMentions: Boolean = true,
    @SerialName("push_dms") val pushDms: Boolean = true,
    @SerialName("activitypub_notifications") val activityPub: Boolean = true,
    @SerialName("dnd_enabled") val dnd: Boolean = false,
    @SerialName("dnd_start_time") val quietStart: String = "22:00:00",
    @SerialName("dnd_end_time") val quietEnd: String = "08:00:00",
) {
    fun validate(): String? = if (listOf(quietStart, quietEnd).any {
            !Regex("([01][0-9]|2[0-3]):[0-5][0-9](:[0-5][0-9])?").matches(it)
        }) "Enter quiet hours as HH:mm, using a 24-hour clock." else null

    /** Send changed columns only; never replace unknown columns or another device's edits. */
    fun changesFrom(original: AccountNotifications): JsonObject {
        val before = HarmonyJson.encodeToJsonElement(serializer(), original) as JsonObject
        val after = HarmonyJson.encodeToJsonElement(serializer(), this) as JsonObject
        return JsonObject(after.filter { (key, value) -> before[key] != value })
    }
}

@Serializable
data class AccountSession(
    val id: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_active_at") val lastActiveAt: String? = null,
    @SerialName("user_agent") val userAgent: String? = null,
    val ip: String? = null,
    val aal: String? = null,
    @SerialName("is_current") val isCurrent: Boolean = false,
    @SerialName("push_transports") val pushTransports: List<String> = emptyList(),
)

@Serializable
data class AccountRecoveryStatus(
    @SerialName("mfa_enabled") val mfaEnabled: Boolean,
    val total: Int,
    val remaining: Int,
    @SerialName("generated_at") val generatedAt: String? = null,
)

@Serializable
data class AccountAuthIdentity(
    val id: String,
    val email: String? = null,
    @SerialName("app_metadata") val appMetadata: JsonObject = JsonObject(emptyMap()),
    val identities: List<AccountProviderIdentity> = emptyList(),
) {
    val providers: List<String> get() {
        val declared = (appMetadata["providers"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        return (declared + identities.map { it.provider }).distinct()
    }
    val hasPassword: Boolean get() = providers.isEmpty() || "email" in providers
}

@Serializable
data class AccountProviderIdentity(val provider: String)

@Serializable
data class AccountRestrictedPerson(
    val id: String,
    val username: String? = null,
    @SerialName("display_name") val displayName: String? = null,
) {
    val label: String get() = displayName?.takeIf { it.isNotBlank() } ?: username ?: id
}
