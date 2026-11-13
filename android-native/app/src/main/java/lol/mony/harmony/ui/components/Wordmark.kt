package lol.mony.harmony.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Harmony mark: two uprights joined by a bar, the same idea as the app icon drawn in the
 * accent the person's appearance settings give. It is drawn, not an image, so it takes the
 * theme's colour and stays crisp at every size.
 */
@Composable
fun HarmonyMark(modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val accent = MaterialTheme.colorScheme.primary
    val light = MaterialTheme.colorScheme.tertiary
    Canvas(modifier = modifier.size(size)) {
        drawHarmonyMark(accent, light)
    }
}

private fun DrawScope.drawHarmonyMark(accent: androidx.compose.ui.graphics.Color, light: androidx.compose.ui.graphics.Color) {
    val w = size.width
    val bar = w * 0.2f
    val top = w * 0.16f
    val height = w * 0.68f
    drawRoundRect(
        color = accent,
        topLeft = Offset(w * 0.12f, top),
        size = Size(bar, height),
        cornerRadius = CornerRadius(bar / 2),
    )
    drawRoundRect(
        color = accent,
        topLeft = Offset(w - bar - w * 0.12f, top),
        size = Size(bar, height),
        cornerRadius = CornerRadius(bar / 2),
    )
    drawRoundRect(
        color = light,
        topLeft = Offset(w * 0.32f, w * 0.36f),
        size = Size(w * 0.36f, w * 0.28f),
        cornerRadius = CornerRadius(w * 0.04f),
    )
}

/** Mark plus wordmark, as the sign-in screen and the top bar show the product. */
@Composable
fun HarmonyWordmark(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        HarmonyMark(size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = "Harmony",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
