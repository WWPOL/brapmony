package lol.mony.harmony.domain

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import lol.mony.harmony.data.model.AccountAppearance
import lol.mony.harmony.data.model.AccountAuthIdentity
import lol.mony.harmony.data.model.AccountNotifications
import lol.mony.harmony.data.model.AccountProfileEdit
import lol.mony.harmony.data.model.AccountProfileField
import lol.mony.harmony.data.model.AccountQuietHours
import lol.mony.harmony.data.model.HarmonyJson
import lol.mony.harmony.ui.theme.VisualThemeSettings
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AccountSettingsTest {
    @Test fun appearanceOnlyTouchesChangedNativeControls() {
        val raw = json("""{"theme":"dark","fontSize":15,"futureSkin":{"x":[1,2]},"animateEmoji":false,"fontFamily":"web-font"}""")
        val original = VisualThemeSettings.fromJson(raw)
        val merged = AccountAppearance.merge(raw, original, original.copy(theme = "light"))
        assertEquals(raw + ("theme" to JsonPrimitive("light")), merged)
        assertFalse(merged.containsKey("unknown"))
        assertFalse(merged.containsKey("compactMode"))
    }

    @Test fun appearanceKeepsConcurrentUnknownAndUneditedKnownKeys() {
        val originalRaw = json("""{"theme":"dark","future":"old"}""")
        val latest = json("""{"theme":"dark","future":"new","zoomLevel":150,"customCssOverrides":{"--x":"red"}}""")
        val original = VisualThemeSettings.fromJson(originalRaw)
        val merged = AccountAppearance.merge(latest, original, original.copy(showTimestamps = false))
        assertEquals(JsonPrimitive("new"), merged["future"])
        assertEquals(JsonPrimitive(150), merged["zoomLevel"])
        assertEquals(latest["customCssOverrides"], merged["customCssOverrides"])
        assertEquals(JsonPrimitive(false), merged["showTimestamps"])
    }

    @Test fun appearanceCanExplicitlyClearCustomColors() {
        val raw = json("""{"theme":"custom","customAccentColor":"#112233","future":true}""")
        val original = VisualThemeSettings.fromJson(raw)
        val merged = AccountAppearance.merge(raw, original, original.copy(customAccentColor = null))
        assertEquals(JsonNull, merged["customAccentColor"])
        assertEquals(JsonPrimitive(true), merged["future"])
    }

    @Test fun appearanceResetKeepsWebOnlySettingsAndUnknownKeys() {
        val original = VisualThemeSettings(theme = "midnight", fontSize = 24, animateEmoji = false,
            customSkinCss = "body{}", unknown = mapOf("future" to JsonPrimitive("retained")))
        val reset = AccountAppearance.reset(original)
        assertEquals("dark", reset.theme)
        assertEquals(15, reset.fontSize)
        assertEquals(false, reset.animateEmoji)
        assertEquals(original.customSkinCss, reset.customSkinCss)
        assertEquals(original.unknown, reset.unknown)
    }

    @Test fun unchangedAppearanceDoesNotInventDefaults() {
        val raw = json("""{"future":{"nested":true}}""")
        val original = VisualThemeSettings.fromJson(raw)
        assertEquals(raw, AccountAppearance.merge(raw, original, original))
    }

    @Test fun profileValidationMatchesWebEditorLimits() {
        assertNotNull(AccountProfileEdit(displayName = " ").validate())
        assertNull(AccountProfileEdit(displayName = "A", pronouns = "x".repeat(40), bio = "b".repeat(500)).validate())
        assertNotNull(AccountProfileEdit(displayName = "A", pronouns = "x".repeat(41)).validate())
        assertNotNull(AccountProfileEdit(displayName = "A", bio = "b".repeat(501)).validate())
        assertNotNull(AccountProfileEdit(displayName = "A", fields = listOf(AccountProfileField("", "x"))).validate())
        assertNotNull(AccountProfileEdit(displayName = "A", fields = List(5) { AccountProfileField("a", "b") }).validate())
    }

    @Test fun profilePatchUsesNullableColumnsNotEmptyStrings() {
        val patch = AccountProfileEdit(displayName = "  Person  ", bio = " ", pronouns = " they/them ").patch()
        assertEquals(JsonPrimitive("Person"), patch["display_name"])
        assertEquals(JsonNull, patch["bio"])
        assertEquals(JsonPrimitive("they/them"), patch["pronouns"])
        assertEquals(JsonNull, patch["avatar_url"])
    }

    @Test fun notificationPatchDoesNotResetOtherColumns() {
        val before = AccountNotifications()
        val after = before.copy(push = false)
        assertEquals(json("""{"push_notifications":false}"""), after.changesFrom(before))
        assertNull(after.validate())
    }

    @Test fun quietHoursAreValidatedAndConvertedLikeWeb() {
        assertNotNull(AccountNotifications(quietStart = "25:10").validate())
        assertNotNull(AccountNotifications(quietEnd = "08:99").validate())
        assertNull(AccountNotifications(quietStart = "22:30").validate())
        val day = LocalDate.of(2025, 7, 1)
        val zone = ZoneId.of("America/New_York")
        assertEquals("18:00", AccountQuietHours.local("22:00:00", zone, day))
        assertEquals("02:00:00", AccountQuietHours.utc("22:00", zone, day))
        assertEquals("18:00", AccountQuietHours.local(AccountQuietHours.utc("18:00", zone, day), zone, day))
    }

    @Test fun identityUnderstandsStructuredGoTrueProviderMetadata() {
        val identity = HarmonyJson.decodeFromString(AccountAuthIdentity.serializer(),
            """{"id":"auth-id","app_metadata":{"providers":["custom:oidc"],"provider":"custom:oidc"}}""")
        assertFalse(identity.hasPassword)
        assertEquals(listOf("custom:oidc"), identity.providers)
    }

    private fun json(text: String) = HarmonyJson.parseToJsonElement(text) as JsonObject
}
