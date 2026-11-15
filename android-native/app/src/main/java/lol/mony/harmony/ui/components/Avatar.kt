package lol.mony.harmony.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import lol.mony.harmony.ui.theme.HarmonyColors
import lol.mony.harmony.ui.theme.LocalHarmonyColors
import lol.mony.harmony.ui.theme.toOklch
import lol.mony.harmony.ui.theme.oklchToColor
import kotlin.math.absoluteValue

/**
 * A person's avatar: their picture when they have one, else their initials on a colour the app
 * derives from their id, which is how the web client's `Avatar.vue` fills the gap.
 */
@Composable
fun Avatar(
    name: String,
    url: String?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    shape: androidx.compose.ui.graphics.Shape = CircleShape,
    contentDescription: String? = null,
    /** The instance this avatar belongs to, so a relative path resolves against it. */
    baseUrl: String? = null,
) {
    val fallback = initialsOf(name)
    val background = colorFor(name)
    // The row may hold an absolute URL, a site-relative path or a bare storage path; resolve it
    // here so every caller gets a picture without each one remembering to.
    val image = AvatarUrl.resolve(baseUrl, url)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(background, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(image)
                    .crossfade(true)
                    .build(),
                contentDescription = contentDescription ?: name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size)
                    .clip(shape),
            )
        } else {
            Text(
                text = fallback,
                color = Color.White,
                fontSize = (size.value * 0.38f).sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }
}

/**
 * A server's icon: its uploaded picture, else its initials on a rounded square.
 *
 * `servers.icon` holds the same mix of forms an avatar does -- an absolute URL, a site-relative
 * path, or a bare storage path under the `server_icons` bucket -- so it resolves through
 * [AvatarUrl] against the instance rather than being handed to the image loader raw. That raw
 * path is why a server with an icon still drew its initials.
 */
@Composable
fun ServerAvatar(
    name: String,
    url: String?,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    selected: Boolean = false,
    baseUrl: String? = null,
) {
    val shape = RoundedCornerShape(if (selected) 16.dp else 22.dp)
    val harmony: HarmonyColors = LocalHarmonyColors.current
    val image = AvatarUrl.resolveServerIcon(baseUrl, url)
    if (image != null) {
        AsyncImage(
            model = image,
            contentDescription = name,
            contentScale = ContentScale.Crop,
            // The tag names the branch that drew: the picture is proof the image branch ran,
            // the monogram below is proof it did not. It is applied after the caller's
            // modifier so a caller cannot accidentally replace it.
            modifier = modifier.size(size).clip(shape).testTag("server-icon-image"),
        )
        return
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(colorFor(name), shape)
            .testTag("server-icon-monogram"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text =name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
                .ifBlank { name.take(1).uppercase() },
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * 0.34f).sp,
        )
    }
}

/** A stable, readable colour for a name: OKLCH at a fixed lightness so text stays legible. */
fun colorFor(key: String): Color {
    val hue = (key.hashCode().absoluteValue * 47) % 360
    return oklchToColor(lol.mony.harmony.ui.theme.Oklch(0.55, 0.11, hue.toDouble()))
}

fun initialsOf(name: String): String =
    name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
        .ifBlank { name.take(1).uppercase() }
