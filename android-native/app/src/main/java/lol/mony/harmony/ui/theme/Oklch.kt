package lol.mony.harmony.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * OKLCH, the colour space Harmony's themes are built in.
 *
 * The web client derives every surface from one background tone plus the accent's hue
 * (`src/utils/colorUtils.ts`), which is what lets a person pick one colour and get a whole
 * coherent look. The native client has to reach the same colours from the same settings, so
 * this is a port of the maths, not an approximation.
 */
data class Oklch(val l: Double, val c: Double, val h: Double) {
    /** sRGB channels, clamped, as Compose colours (alpha 1). */
    fun toColor(): Color = oklchToColor(this)

    fun withLightness(lightness: Double): Oklch = copy(l = lightness)

    companion object {
        /** Parses `#rrggbb`, `#rrggbbaa` or an `oklch(...)` string, like the CSS tokens allow. */
        fun parse(value: String): Oklch? {
            val text = value.trim()
            if (text.startsWith("#")) {
                val hex = text.removePrefix("#")
                val rgb = when (hex.length) {
                    6, 8 -> hex.substring(0, 6).toIntOrNull(16) ?: return null
                    3 -> hex.map { "$it$it" }.joinToString("").toIntOrNull(16) ?: return null
                    else -> return null
                }
                val r = ((rgb shr 16) and 0xFF) / 255.0
                val g = ((rgb shr 8) and 0xFF) / 255.0
                val b = (rgb and 0xFF) / 255.0
                return rgbToOklch(r, g, b)
            }
            if (text.startsWith("oklch")) {
                val body = text.substringAfter("(").substringBefore(")").trim()
                val parts = body.split("/").first().trim().split(Regex("[\\s,]+"))
                if (parts.size < 3) return null
                val l = parts[0].removeSuffix("%").toDoubleOrNull() ?: return null
                val lightness = if (parts[0].endsWith("%")) l / 100.0 else l
                val chroma = parts[1].toDoubleOrNull() ?: return null
                val hue = parts[2].removeSuffix("deg").toDoubleOrNull() ?: return null
                return Oklch(lightness, chroma, hue)
            }
            return null
        }
    }
}

fun oklchToColor(value: Oklch): Color {
    val (r, g, b) = oklchToRgb(value.l, value.c, value.h)
    return Color(
        red = r.coerceIn(0.0, 1.0).toFloat(),
        green = g.coerceIn(0.0, 1.0).toFloat(),
        blue = b.coerceIn(0.0, 1.0).toFloat(),
        alpha = 1f,
    )
}

fun Color.toOklch(): Oklch = rgbToOklch(red.toDouble(), green.toDouble(), blue.toDouble())

/** `#rrggbb`, the notation Harmony's settings store, for round-tripping a colour. */
fun Color.toHex(): String = String.format(
    "#%02X%02X%02X",
    (red * 255).toInt().coerceIn(0, 255),
    (green * 255).toInt().coerceIn(0, 255),
    (blue * 255).toInt().coerceIn(0, 255),
)

private fun oklchToRgb(lightness: Double, chroma: Double, hueDegrees: Double): Triple<Double, Double, Double> {
    val h = Math.toRadians(hueDegrees)
    val a = chroma * cos(h)
    val b = chroma * sin(h)
    val lPrime = lightness + 0.3963377774 * a + 0.2158037573 * b
    val mPrime = lightness - 0.1055613458 * a - 0.0638541728 * b
    val sPrime = lightness - 0.0894841775 * a - 1.2914855480 * b

    val l = lPrime * lPrime * lPrime
    val m = mPrime * mPrime * mPrime
    val s = sPrime * sPrime * sPrime

    val r = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s
    val g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s
    val bl = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
    return Triple(linearToSrgb(r), linearToSrgb(g), linearToSrgb(bl))
}

private fun rgbToOklch(r: Double, g: Double, b: Double): Oklch {
    val lr = srgbToLinear(r)
    val lg = srgbToLinear(g)
    val lb = srgbToLinear(b)

    val l = 0.4122214708 * lr + 0.5363325363 * lg + 0.0514459929 * lb
    val m = 0.2119034982 * lr + 0.6806995451 * lg + 0.1073969566 * lb
    val s = 0.0883024619 * lr + 0.2817188376 * lg + 0.6299787005 * lb

    val l_ = cbrt(l)
    val m_ = cbrt(m)
    val s_ = cbrt(s)

    val lightness = 0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_
    val a = 1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_
    val bb = 0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_

    val chroma = sqrt(a * a + bb * bb)
    var hue = Math.toDegrees(atan2(bb, a))
    if (hue < 0) hue += 360.0
    return Oklch(lightness, chroma, hue)
}

private fun linearToSrgb(value: Double): Double =
    if (value <= 0.0031308) 12.92 * value else 1.055 * value.pow(1.0 / 2.4) - 0.055

private fun srgbToLinear(value: Double): Double =
    if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)

/** Relative luminance, for choosing readable text on a generated fill (WCAG 2.x). */
fun Color.relativeLuminance(): Double {
    fun channel(value: Float): Double {
        val v = value.toDouble()
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue)
}

/** Contrast ratio against another colour, 1.0 to 21.0. */
fun Color.contrastAgainst(other: Color): Double {
    val a = relativeLuminance()
    val b = other.relativeLuminance()
    val lighter = maxOf(a, b)
    val darker = minOf(a, b)
    return (lighter + 0.05) / (darker + 0.05)
}

/** True when white text reads better than black on this fill. */
fun Color.prefersLightContent(): Boolean = relativeLuminance() < 0.45

/** Blends toward [other] by [amount] (0 = this, 1 = other), in plain sRGB. */
fun Color.mix(other: Color, amount: Double): Color {
    val t = amount.coerceIn(0.0, 1.0).toFloat()
    return Color(
        red = red + (other.red - red) * t,
        green = green + (other.green - green) * t,
        blue = blue + (other.blue - blue) * t,
        alpha = alpha + (other.alpha - alpha) * t,
    )
}

/** Opaque with a new alpha, for overlays and borders. */
fun Color.withAlphaValue(alpha: Double): Color = copy(alpha = alpha.toFloat().coerceIn(0f, 1f))

/** The perceived lightness distance Harmony's contrast checks use. */
fun Color.lightnessDistance(other: Color): Double = abs(toOklch().l - other.toOklch().l)
