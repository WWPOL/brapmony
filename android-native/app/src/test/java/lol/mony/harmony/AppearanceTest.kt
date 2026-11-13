package lol.mony.harmony

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.*
import lol.mony.harmony.ui.theme.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class AppearanceTest {
    @Test fun explicitAccountThemeWinsOverSystem() {
        assertFalse(resolveAppearance(VisualThemeSettings(theme = "dark"), false).light)
        assertTrue(resolveAppearance(VisualThemeSettings(theme = "light"), true).light)
    }
    @Test fun vividBackgroundSwatchSuppliesHueNotRawBrightness() {
        val resolved = resolveAppearance(VisualThemeSettings(theme = "custom", customThemeMode = "dark",
            customBackgroundColor = "#0EA5E9"), true)
        assertEquals(0.17, resolved.background.l, 0.000001)
        assertEquals(0.015, resolved.background.c, 0.000001)
        assertEquals(Oklch.parse("#0EA5E9")!!.h, resolved.background.h, 0.0001)
    }
    @Test fun customSurfaceOffsetsUseHarmonyEquations() {
        val dark = resolveAppearance(VisualThemeSettings(theme = "custom", customBackgroundLightness = 20,
            customBackgroundChroma = 2), true)
        assertEquals(0.26, dark.background.l, 0.000001)
        assertEquals(0.021, dark.background.c, 0.000001)
        val light = resolveAppearance(VisualThemeSettings(theme = "custom", customThemeMode = "light",
            customBackgroundLightness = -10), false)
        assertEquals(0.92, light.background.l, 0.000001)
    }
    @Test fun separatePrimaryAndSidebarColoursAreRespected() {
        val resolved = resolveAppearance(VisualThemeSettings(theme = "custom", customPrimaryColor = "#A855F7",
            customAccentColor = "#0EA5E9", customSidebarColor = "#10B981"), true)
        assertNotEquals(resolved.primary.h, resolved.accent.h)
        assertEquals(Oklch.parse("#10B981")!!.h, resolved.sidebar.h, 0.0001)
    }
    @Test fun buttonContentUsesReadableContrastInBothDefaultModes() {
        for (theme in listOf("dark", "light", "midnight")) {
            val scheme = buildColorScheme(resolveAppearance(VisualThemeSettings(theme = theme, highContrast = true), true))
            assertTrue("$theme onPrimary", scheme.onPrimary.contrastAgainst(scheme.primary) >= 4.5)
            assertTrue("$theme body", scheme.onSurface.contrastAgainst(scheme.surface) >= 4.5)
        }
    }
    @Test fun oklchRoundTripsBrandAndNeutrals() {
        for (hex in listOf("#0EA5E9", "#17181A", "#F2F3F5", "#FFFFFF", "#000000")) {
            val color = Oklch.parse(hex)!!.toColor()
            val roundTrip = color.toOklch().toColor()
            assertTrue(abs(color.red - roundTrip.red) < 0.001)
            assertTrue(abs(color.green - roundTrip.green) < 0.001)
            assertTrue(abs(color.blue - roundTrip.blue) < 0.001)
        }
    }
    @Test fun invalidAndNonFiniteColoursAreRejected() {
        assertNull(Oklch.parse("#nope"))
        assertNull(Oklch.parse("oklch(NaN 0.1 200)"))
        assertNull(Oklch.parse("oklch(500% 0.1 200)"))
    }
    @Test fun unknownAppearanceKeysSurviveRoundTripWithoutNativeMetadata() {
        val settings = VisualThemeSettings.fromJson(Json.parseToJsonElement("""{"theme":"midnight","skinOptions":{"new":{"option":true}},"_savedAt":42}"""))
        val saved = VisualThemeSettings.toJsonMap(settings)
        assertEquals(Json.parseToJsonElement("""{"new":{"option":true}}"""), saved["skinOptions"])
        assertEquals(JsonPrimitive(42), saved["_savedAt"])
        assertFalse(saved.containsKey("unknown"))
    }
}
