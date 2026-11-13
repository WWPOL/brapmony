package lol.mony.harmony.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Harmony's look, expressed as Material 3.
 *
 * The app never invents a palette: [buildColorScheme] derives every Material role from the
 * person's own appearance settings, in OKLCH, the way the web client derives its CSS variables
 * (`generateThemePalette` in `src/utils/colorUtils.ts`). That is what keeps a theme chosen on
 * the web recognisable here, and Android's Material 3 components - ripples, elevation tints,
 * the app bar - keep working because every role is filled.
 */

/** Harmony's non-Material tokens, the ones Material has no slot for. */
@Immutable
data class HarmonyColors(
    /** The message list's surface, one step up from the app background as on the web. */
    val surfaceChat: Color,
    /** The server rail and channel drawer. */
    val surfaceSidebar: Color,
    /** Cards, sheets and the composer. */
    val surfaceRaised: Color,
    /** Hairlines between regions. */
    val border: Color,
    val borderStrong: Color,
    /** Online, away, busy, offline - Harmony's presence colours. */
    val presenceOnline: Color = Color(0xFF43B581),
    val presenceAway: Color = Color(0xFFFAA61A),
    val presenceBusy: Color = Color(0xFFF04747),
    val presenceOffline: Color = Color(0xFF747F8D),
    /** Mention and unread badges. */
    val badge: Color,
    val onBadge: Color,
    /** Greentext and link colours the web client's message rendering uses. */
    val textLink: Color,
    val textSuccess: Color,
    val textDanger: Color,
    val greentext: Color = Color(0xFF789922),
    /** The accent, for the pieces Material calls "primary" but Harmony treats as brand. */
    val brand: Color,
    val onBrand: Color,
    /** True when the theme is a light one; components that draw their own surfaces read it. */
    val isLight: Boolean,
)

val LocalHarmonyColors = staticCompositionLocalOf { buildHarmonyColors(HarmonyPresets.dark, isLight = false) }

/** Motion policy: Harmony's "reduce motion" and the system animation scale, in one place. */
@Immutable
data class MotionPreferences(val reduceMotion: Boolean) {
    fun <T> animation(enabled: T, disabled: T): T = if (reduceMotion) disabled else enabled
}

val LocalMotion = staticCompositionLocalOf { MotionPreferences(false) }

/** Size policy from `fontSize`, `zoomLevel` and `compactMode`. */
@Immutable
data class SizePreferences(
    val messageFontSize: Int,
    val zoomLevel: Int,
    val compact: Boolean,
) {
    /** Message text: the person's own px, scaled by zoom, in sp. */
    val messageSp: Double get() = messageFontSize * (zoomLevel / 100.0)
    /** Row spacing: Harmony's compact mode trims it the way the web client's rows do. */
    val rowVerticalPadding: androidx.compose.ui.unit.Dp get() = if (compact) 2.dp else 6.dp
}

val LocalSizes = staticCompositionLocalOf { SizePreferences(15, 100, compact = false) }

/** Accessibility policy: contrast and screen-reader support. */
@Immutable
data class AccessibilityPreferences(
    val highContrast: Boolean,
    val screenReaderSupport: Boolean,
)

val LocalAccessibility = staticCompositionLocalOf { AccessibilityPreferences(false, false) }

@Composable
fun HarmonyTheme(
    settings: VisualThemeSettings,
    systemInDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val resolved = remember(settings, systemInDarkTheme) { resolveAppearance(settings, systemInDarkTheme) }
    val colorScheme = remember(resolved) { buildColorScheme(resolved) }
    val harmony = remember(resolved) { buildHarmonyColors(resolved.preset, resolved.light) }
    CompositionLocalProvider(
        LocalHarmonyColors provides harmony,
        LocalMotion provides MotionPreferences(resolved.reduceMotion),
        LocalSizes provides SizePreferences(resolved.fontSize, resolved.zoomLevel, resolved.compact),
        LocalAccessibility provides AccessibilityPreferences(resolved.highContrast, resolved.screenReaderSupport),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = harmonyTypography(resolved.fontFamily),
            shapes = harmonyShapes,
            content = content,
        )
    }
}

/** The settings resolved into what the theme needs. */
data class ResolvedAppearance(
    val preset: ThemePreset,
    val light: Boolean,
    val accent: Oklch,
    val background: Oklch,
    val sidebar: Oklch,
    val chart: Double,
    val lightnessShift: Double,
    val fontSize: Int,
    val zoomLevel: Int,
    val compact: Boolean,
    val highContrast: Boolean,
    val reduceMotion: Boolean,
    val screenReaderSupport: Boolean,
    val fontFamily: String,
)

/**
 * Settings plus the system's dark-mode switch into one resolution.
 *
 * The system only decides anything when the person has not picked one of Harmony's own looks -
 * a theme chosen in Harmony is never overridden by the phone's wallpaper, which is the rule the
 * web client applies to its own `system` option.
 */
fun resolveAppearance(settings: VisualThemeSettings, systemInDarkTheme: Boolean): ResolvedAppearance {
    val preset = when (settings.theme) {
        "light" -> HarmonyPresets.light
        "midnight" -> HarmonyPresets.midnight
        "custom" -> {
            val light = settings.customThemeMode == "light"
            ThemePreset(
                id = "custom",
                label = "Custom",
                background = settings.background ?: if (light) HarmonyPresets.light.background else HarmonyPresets.dark.background,
                sidebar = settings.sidebar ?: settings.background
                    ?: if (light) HarmonyPresets.light.sidebar else HarmonyPresets.dark.sidebar,
                accent = settings.accent,
                light = light,
            )
        }
        // `dark`, or a value this app does not know: Harmony's dark is the default look.
        else -> if (settings.theme == "system" && !systemInDarkTheme) HarmonyPresets.light else HarmonyPresets.dark
    }
    return ResolvedAppearance(
        preset = preset,
        light = preset.light,
        accent = if (settings.theme == "custom") settings.accent else preset.accent,
        background = preset.background,
        sidebar = preset.sidebar,
        chart = (settings.customBackgroundChroma / 100.0).coerceIn(-0.3, 0.3),
        lightnessShift = (settings.customBackgroundLightness / 100.0).coerceIn(-0.5, 0.5),
        fontSize = settings.fontSize.coerceIn(10, 32),
        zoomLevel = settings.zoomLevel.coerceIn(50, 200),
        compact = settings.compactMode,
        highContrast = settings.highContrast,
        reduceMotion = settings.reduceMotion,
        screenReaderSupport = settings.screenReaderSupport,
        fontFamily = settings.fontFamily,
    )
}

/**
 * Material's roles from Harmony's palette.
 *
 * Surfaces keep Harmony's relationship between tiers (chat above background, sidebar below it,
 * as the web's `--background-primary/secondary/tertiary` are), and the accent seeds primary,
 * secondary and tertiary so a custom accent colours the whole app instead of one button.
 */
fun buildColorScheme(appearance: ResolvedAppearance): ColorScheme {
    val light = appearance.light
    val accent = appearance.accent
    val background = appearance.background.shift(appearance.lightnessShift).shiftChroma(appearance.chart)
    val sidebar = appearance.sidebar.shift(appearance.lightnessShift)

    val step = if (light) 0.025 else 0.022
    val onBackground = if (light) Oklch(0.24, 0.012, accent.h) else Oklch(0.96, 0.004, accent.h)
    val onSurfaceVariant = if (light) Oklch(0.46, 0.014, accent.h) else Oklch(0.72, 0.012, accent.h)
    val outline = if (light) Oklch(0.78, 0.01, accent.h) else Oklch(0.38, 0.012, accent.h)

    val primary = if (light) accent.shift(-0.12) else accent
    val onPrimary = if (primary.toColor().prefersLightContent()) Color.White else Color(0xFF10161C)
    val primaryContainer = if (light) accent.shift(0.36).shiftChroma(-0.04) else accent.shift(-0.36).shiftChroma(-0.02)
    val onPrimaryContainer = if (light) accent.shift(-0.3) else accent.shift(0.32)

    val surface = background
    val surfaceChat = background.shift(step)
    val surfaceRaised = background.shift(step * 2)
    val surfaceSidebar = sidebar.shift(-step / 2)
    val surfaceHigh = background.shift(step * 3)

    val error = if (light) Oklch(0.55, 0.19, 25.0) else Oklch(0.65, 0.2, 25.0)
    val container = if (light) Oklch(0.92, 0.06, 25.0) else Oklch(0.32, 0.09, 25.0)

    val base = if (light) {
        lightColorScheme(
            primary = primary.toColor(),
            onPrimary = onPrimary,
            primaryContainer = primaryContainer.toColor(),
            onPrimaryContainer = onPrimaryContainer.toColor(),
            inversePrimary = accent.shift(0.2).toColor(),
            secondary = accent.shift(-0.04).shiftChroma(-0.06).toColor(),
            onSecondary = onPrimary,
            secondaryContainer = (if (light) accent.shift(0.42) else accent.shift(-0.3)).toColor(),
            onSecondaryContainer = onPrimaryContainer.toColor(),
            tertiary = accent.shift(0.28).shiftHue(60.0).toColor(),
            onTertiary = onPrimary,
            tertiaryContainer = accent.shift(0.4).shiftHue(40.0).toColor(),
            onTertiaryContainer = onPrimaryContainer.toColor(),
            error = error.toColor(),
            onError = Color.White,
            errorContainer = container.toColor(),
            onErrorContainer = error.shift(-0.2).toColor(),
            background = surface.toColor(),
            onBackground = onBackground.toColor(),
            surface = surfaceChat.toColor(),
            onSurface = onBackground.toColor(),
            surfaceVariant = surfaceRaised.toColor(),
            onSurfaceVariant = onSurfaceVariant.toColor(),
            surfaceTint = primary.toColor(),
            outline = outline.toColor(),
            outlineVariant = outline.shift(0.08).toColor(),
            scrim = Color.Black,
            inverseSurface = Oklch(0.25, 0.01, accent.h).toColor(),
            inverseOnSurface = Oklch(0.95, 0.004, accent.h).toColor(),
            surfaceBright = surfaceHigh.toColor(),
            surfaceDim = surfaceSidebar.toColor(),
            surfaceContainerLowest = surfaceSidebar.toColor(),
            surfaceContainerLow = surface.toColor(),
            surfaceContainer = surfaceChat.toColor(),
            surfaceContainerHigh = surfaceRaised.toColor(),
            surfaceContainerHighest = surfaceHigh.toColor(),
        )
    } else {
        darkColorScheme(
            primary = primary.toColor(),
            onPrimary = onPrimary,
            primaryContainer = primaryContainer.toColor(),
            onPrimaryContainer = onPrimaryContainer.toColor(),
            inversePrimary = accent.shift(0.25).toColor(),
            secondary = accent.shift(0.05).shiftChroma(-0.03).toColor(),
            onSecondary = onPrimary,
            secondaryContainer = (if (light) accent.shift(0.42) else accent.shift(-0.3)).toColor(),
            onSecondaryContainer = onPrimaryContainer.toColor(),
            tertiary = accent.shift(0.2).shiftHue(60.0).toColor(),
            onTertiary = Color(0xFF10161C),
            tertiaryContainer = accent.shift(-0.34).shiftHue(40.0).toColor(),
            onTertiaryContainer = accent.shift(0.34).toColor(),
            error = error.toColor(),
            onError = Color(0xFF10161C),
            errorContainer = container.toColor(),
            onErrorContainer = error.shift(0.22).toColor(),
            background = surface.toColor(),
            onBackground = onBackground.toColor(),
            surface = surfaceChat.toColor(),
            onSurface = onBackground.toColor(),
            surfaceVariant = surfaceRaised.toColor(),
            onSurfaceVariant = onSurfaceVariant.toColor(),
            surfaceTint = primary.toColor(),
            outline = outline.toColor(),
            outlineVariant = outline.shift(0.08).toColor(),
            scrim = Color.Black,
            inverseSurface = Oklch(0.93, 0.004, accent.h).toColor(),
            inverseOnSurface = Oklch(0.25, 0.01, accent.h).toColor(),
            surfaceBright = surfaceHigh.toColor(),
            surfaceDim = surfaceSidebar.toColor(),
            surfaceContainerLowest = surfaceSidebar.toColor(),
            surfaceContainerLow = surface.toColor(),
            surfaceContainer = surfaceChat.toColor(),
            surfaceContainerHigh = surfaceRaised.toColor(),
            surfaceContainerHighest = surfaceHigh.toColor(),
        )
    }
    // High contrast lifts the text and outline roles, as the web client's High contrast does.
    return if (!appearance.highContrast) {
        base
    } else {
        val lifted = if (light) Oklch(0.12, 0.01, accent.h) else Oklch(1.0, 0.0, 0.0)
        base.copy(
            onSurface = lifted.toColor(),
            onBackground = lifted.toColor(),
            onSurfaceVariant = (if (light) Oklch(0.3, 0.012, accent.h) else Oklch(0.86, 0.008, accent.h)).toColor(),
            outline = (if (light) Oklch(0.55, 0.012, accent.h) else Oklch(0.62, 0.012, accent.h)).toColor(),
            primary = (if (light) accent.shift(-0.18) else accent.shift(0.05)).toColor(),
        )
    }
}

fun buildHarmonyColors(preset: ThemePreset, isLight: Boolean): HarmonyColors {
    val light = isLight
    val accent = preset.accent
    val step = if (light) 0.025 else 0.022
    val background = preset.background.shift(if (light) 0.0 else 0.0)
    val border = if (light) Oklch(0.86, 0.008, accent.h) else Oklch(0.3, 0.01, accent.h)
    val brand = accent.toColor()
    return HarmonyColors(
        surfaceChat = background.shift(step).toColor(),
        surfaceSidebar = preset.sidebar.shift(-step / 2).toColor(),
        surfaceRaised = background.shift(step * 2).toColor(),
        border = border.toColor().withAlphaValue(if (light) 1.0 else 1.0),
        borderStrong = border.shift(if (light) -0.1 else 0.1).toColor(),
        badge = Color(0xFFED4245),
        onBadge = Color.White,
        textLink = (if (light) accent.shift(-0.18) else accent).toColor(),
        textSuccess = Color(0xFF23A55A),
        textDanger = Color(0xFFF05A5D),
        brand = brand,
        onBrand = if (brand.prefersLightContent()) Color.White else Color(0xFF10161C),
        isLight = light,
    )
}

private fun Oklch.shift(delta: Double): Oklch = copy(l = (l + delta).coerceIn(0.03, 0.99))

private fun Oklch.shiftChroma(deltaC: Double): Oklch = copy(c = (c + deltaC).coerceIn(0.0, 0.37))

private fun Oklch.shiftHue(delta: Double): Oklch = copy(h = ((h + delta) % 360.0 + 360.0) % 360.0)

/** Roboto (Android's own face) or the pixel face the web client offers, at the person's sizes. */
fun harmonyTypography(fontFamily: String): Typography {
    val family = when (fontFamily) {
        "pixel" -> FontFamily.Monospace
        else -> FontFamily.Default
    }
    fun style(size: Int, weight: FontWeight = FontWeight.Normal, lineHeight: Int = (size * 1.4).toInt()) =
        TextStyle(fontFamily = family, fontSize = size.sp, fontWeight = weight, lineHeight = lineHeight.sp)
    return Typography(
        displayLarge = style(57, FontWeight.Medium),
        displayMedium = style(45, FontWeight.Medium),
        displaySmall = style(36, FontWeight.Medium),
        headlineLarge = style(32, FontWeight.SemiBold),
        headlineMedium = style(28, FontWeight.SemiBold),
        headlineSmall = style(24, FontWeight.SemiBold),
        titleLarge = style(22, FontWeight.SemiBold),
        titleMedium = style(16, FontWeight.SemiBold, 24),
        titleSmall = style(14, FontWeight.SemiBold, 20),
        bodyLarge = style(16),
        bodyMedium = style(14),
        bodySmall = style(12),
        labelLarge = style(14, FontWeight.Medium, 20),
        labelMedium = style(12, FontWeight.Medium, 16),
        labelSmall = style(11, FontWeight.Medium, 16),
    )
}

/** Android-shaped: generous corners, a 48dp touch target floor. */
val harmonyShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

/** True when the platform blurs behind surfaces well enough to use Harmony's glass effects. */
fun supportsBlur(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
