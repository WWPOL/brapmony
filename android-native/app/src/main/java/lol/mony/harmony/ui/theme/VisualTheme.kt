package lol.mony.harmony.ui.theme

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import lol.mony.harmony.data.model.HarmonyJson

/**
 * Appearance settings, as Harmony stores them.
 *
 * This is the web client's `VisualThemeSettings` (`src/composables/useVisualTheme.types.ts`),
 * field for field and name for name, because it is the same JSON: `profiles.appearance_settings`.
 * A person who picks a theme on the web finds it here, and saving in the app changes it there.
 *
 * Unknown keys in the stored JSON are dropped on write by [HarmonyJson]'s encoder only if the
 * app rewrites the whole object, so the app always merges into the object it read and never
 * invents the fields it does not understand.
 */
@Serializable
data class VisualThemeSettings(
    val theme: String = "dark",
    val customThemeMode: String? = null,
    val customPrimaryColor: String? = null,
    val customAccentColor: String? = null,
    val customBackgroundColor: String? = null,
    val customSidebarColor: String? = null,
    val customBackgroundLightness: Int = 0,
    val customBackgroundChroma: Int = 0,
    val customCssOverrides: Map<String, String>? = null,
    val fontSize: Int = 15,
    val zoomLevel: Int = 100,
    val showTimestamps: Boolean = true,
    val use24HourTime: Boolean = false,
    val compactMode: Boolean = false,
    val highContrast: Boolean = false,
    val reduceMotion: Boolean = false,
    val screenReaderSupport: Boolean = false,
    val showCustomEmojisInDisplayNames: Boolean = true,
    val greentextEnabled: Boolean = true,
    val convertSentEmoticons: Boolean = true,
    val renderEmoticonsAsEmoji: Boolean = true,
    val animateEmoji: Boolean = true,
    val fontFamily: String = "system",
    val glassEffectsEnabled: Boolean = true,
    val activeSkinId: String? = null,
    val customSkinCss: String? = null,
    val bridgeSourceBadge: String = "icon",
    val inviteBannerBackground: Boolean = true,
    val videoPlayer: String = "harmony",
    /** Every key the app did not model, so a save does not delete another client's settings. */
    val unknown: Map<String, JsonElement> = emptyMap(),
) {
    val isLight: Boolean
        get() = when (theme) {
            "light" -> true
            "custom" -> customThemeMode == "light"
            else -> false
        }

    /** The accent: what the person picked, else Harmony's teal. */
    val accent: Oklch
        get() = Oklch.parse(customAccentColor ?: customPrimaryColor ?: HARMONY_TEAL) ?: Oklch(0.68, 0.15, 230.0)

    val primary: Oklch
        get() = Oklch.parse(customPrimaryColor ?: customAccentColor ?: HARMONY_TEAL) ?: Oklch(0.68, 0.15, 230.0)

    val background: Oklch?
        get() = customBackgroundColor?.let { Oklch.parse(it) }

    val sidebar: Oklch?
        get() = customSidebarColor?.let { Oklch.parse(it) }

    companion object {
        const val HARMONY_TEAL = "#0EA5E9"

        /** Parses a stored `appearance_settings` object, keeping every key it does not know. */
        fun fromJson(element: JsonElement?): VisualThemeSettings {
            if (element == null) return VisualThemeSettings()
            val known = runCatching {
                HarmonyJson.decodeFromString(serializer(), element.toString())
            }.getOrNull() ?: return VisualThemeSettings()
            val schemaNames = setOf(
                "theme", "customThemeMode", "customPrimaryColor", "customAccentColor",
                "customBackgroundColor", "customSidebarColor", "customBackgroundLightness",
                "customBackgroundChroma", "customCssOverrides", "fontSize", "zoomLevel",
                "showTimestamps", "use24HourTime", "compactMode", "highContrast", "reduceMotion",
                "screenReaderSupport", "showCustomEmojisInDisplayNames", "greentextEnabled",
                "convertSentEmoticons", "renderEmoticonsAsEmoji", "animateEmoji", "fontFamily",
                "glassEffectsEnabled", "activeSkinId", "customSkinCss", "bridgeSourceBadge",
                "inviteBannerBackground", "videoPlayer", "unknown",
            )
            val extra = (element as? kotlinx.serialization.json.JsonObject)?.filterKeys { it !in schemaNames }
                ?: emptyMap()
            return known.copy(unknown = extra)
        }

        /** The object to store: this app's keys plus whatever else the account already had. */
        fun toJsonMap(settings: VisualThemeSettings): Map<String, JsonElement> {
            val body = HarmonyJson.encodeToJsonElement(serializer(), settings.copy(unknown = emptyMap()))
                as kotlinx.serialization.json.JsonObject
            return body + settings.unknown
        }
    }
}

/** The three built-in presets Harmony ships, by name, with their token values. */
object HarmonyPresets {
    /** `theme: 'dark'` - Harmony's default look. */
    val dark = ThemePreset(
        id = "dark",
        label = "Dark",
        background = Oklch(0.19, 0.006, 260.0),
        sidebar = Oklch(0.165, 0.008, 260.0),
        accent = Oklch.parse("#0EA5E9")!!,
        light = false,
    )

    /** `theme: 'light'`. */
    val light = ThemePreset(
        id = "light",
        label = "Light",
        background = Oklch(0.98, 0.004, 250.0),
        sidebar = Oklch(0.955, 0.006, 250.0),
        accent = Oklch.parse("#0284C7")!!,
        light = true,
    )

    /** `theme: 'midnight'` - the neutral near-black. */
    val midnight = ThemePreset(
        id = "midnight",
        label = "Midnight",
        background = Oklch(0.145, 0.004, 260.0),
        sidebar = Oklch(0.12, 0.005, 260.0),
        accent = Oklch.parse("#0EA5E9")!!,
        light = false,
    )

    val all = listOf(dark, light, midnight)

    fun byId(id: String?): ThemePreset? = all.firstOrNull { it.id == id }
}

data class ThemePreset(
    val id: String,
    val label: String,
    val background: Oklch,
    val sidebar: Oklch,
    val accent: Oklch,
    val light: Boolean,
)
